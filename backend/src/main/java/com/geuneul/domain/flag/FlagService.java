package com.geuneul.domain.flag;

import com.geuneul.domain.flag.dto.FlagCreateRequest;
import com.geuneul.domain.flag.dto.FlagPendingItemResponse;
import com.geuneul.domain.flag.dto.FlagPendingListResponse;
import com.geuneul.domain.flag.dto.FlagResolveRequest;
import com.geuneul.domain.flag.dto.FlagResponse;
import com.geuneul.domain.report.Report;
import com.geuneul.domain.report.ReportRepository;
import com.geuneul.domain.report.ReportDerivedCacheService;
import com.geuneul.domain.review.Review;
import com.geuneul.domain.review.ReviewRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import static org.springframework.http.HttpStatus.CONFLICT;
import static org.springframework.http.HttpStatus.NOT_FOUND;

/**
 * 신고/모더레이션 큐 오케스트레이션 (docs/SPEC.md §0-7, §9 POST /flags·GET /admin/flags/pending).
 * target은 REPORT|REVIEW 다형 참조라 존재 검증·요약 조립에 ReportRepository/ReviewRepository를
 * 함께 참조한다(ReviewService가 PlaceRepository/UserRepository를 참조하는 것과 같은 크로스 도메인
 * 의존 패턴 — 모더레이션은 원래 여러 도메인을 가로지르는 관심사).
 */
@Service
@Transactional(readOnly = true)
public class FlagService {

    private final FlagRepository flagRepository;
    private final ReportRepository reportRepository;
    private final ReviewRepository reviewRepository;
    private final Clock clock;
    private final ReportDerivedCacheService reportDerivedCacheService;

    public FlagService(FlagRepository flagRepository, ReportRepository reportRepository,
                       ReviewRepository reviewRepository, ReportDerivedCacheService reportDerivedCacheService,
                       Clock clock) {
        this.flagRepository = flagRepository;
        this.reportRepository = reportRepository;
        this.reviewRepository = reviewRepository;
        this.clock = clock;
        this.reportDerivedCacheService = reportDerivedCacheService;
    }

    /**
     * 신고 접수. 같은 유저가 같은 대상을 다시 신고하면 409(스팸 신고 억제, docs/SPEC.md 작업 지시).
     * 대상(report/review)이 존재하지 않으면 404 — 유령 대상에 큐가 쌓이지 않게.
     */
    @Transactional
    public FlagResponse create(long reporterId, FlagCreateRequest request) {
        requireTargetExists(request.targetType(), request.targetId());

        if (flagRepository.existsByTargetTypeAndTargetIdAndReporterId(
                request.targetType(), request.targetId(), reporterId)) {
            throw new ResponseStatusException(CONFLICT, "이미 신고한 대상입니다");
        }

        try {
            Flag saved = flagRepository.save(Flag.create(
                    request.targetType(), request.targetId(), reporterId, request.reason(), normalize(request.detail())));
            return FlagResponse.of(saved);
        } catch (DataIntegrityViolationException e) {
            // 동시 이중 제출 레이스(사전 체크와 save 사이) — DB 유니크 제약(V7)이 최종 방어선.
            throw new ResponseStatusException(CONFLICT, "이미 신고한 대상입니다");
        }
    }

    /** 관리자 검수 큐 — 대기중(PENDING) 신고를 오래된 순으로, 대상 요약을 함께 조립. */
    public FlagPendingListResponse pending(int page, int size) {
        return byStatus(FlagStatus.PENDING, page, size);
    }

    /**
     * 관리자 신고 목록 — 상태별(PENDING/RESOLVED/DISMISSED) 오래된 순 + 대상 요약. 처리 이력 조회에 쓴다.
     * PENDING은 검수 큐(FIFO), RESOLVED/DISMISSED는 처리 이력.
     */
    public FlagPendingListResponse byStatus(FlagStatus status, int page, int size) {
        Pageable pageable = PageRequest.of(page, size);
        Page<Flag> found = flagRepository.findByStatusOrderByCreatedAtAsc(status, pageable);
        TargetSummaries summaries = loadTargetSummaries(found.getContent());
        Page<FlagPendingItemResponse> mapped = found.map(flag -> withTargetSummary(flag, summaries));
        return FlagPendingListResponse.of(mapped);
    }

    /**
     * 관리자 처리 — PENDING에서만 RESOLVED/DISMISSED로 전이. 이미 처리된 신고 재처리는 409.
     * <b>RESOLVED(신고 타당)면 대상 콘텐츠를 숨긴다</b>(V12 hidden) — 모더레이션에 실효를 준다.
     * DISMISSED(오신고)면 콘텐츠는 그대로 둔다. 숨김은 멱등이라 같은 대상에 여러 신고가 RESOLVED돼도 안전.
     */
    @Transactional
    public FlagResponse resolve(long flagId, FlagResolveRequest request) {
        Flag flag = flagRepository.findById(flagId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "flag not found: " + flagId));
        if (flag.getStatus() != FlagStatus.PENDING) {
            throw new ResponseStatusException(CONFLICT, "이미 처리된 신고입니다: " + flag.getStatus());
        }
        if (request.status() == FlagStatus.PENDING) {
            throw new ResponseStatusException(CONFLICT, "PENDING으로는 되돌릴 수 없습니다");
        }
        flag.resolve(request.status(), OffsetDateTime.now(clock));
        if (request.status() == FlagStatus.RESOLVED) {
            hideTarget(flag.getTargetType(), flag.getTargetId());
        }
        return FlagResponse.of(flagRepository.save(flag));
    }

    /** 신고 타당 처리 시 대상(제보/후기)을 숨긴다. 대상이 이미 지워졌으면 no-op(멱등). */
    private void hideTarget(FlagTargetType targetType, long targetId) {
        switch (targetType) {
            case REPORT -> reportRepository.findById(targetId).ifPresent(report -> {
                report.hide();
                reportDerivedCacheService.evictAfterCommit(report.getPlaceId());
            });
            case REVIEW -> reviewRepository.findById(targetId).ifPresent(Review::hide);
        }
    }

    private void requireTargetExists(FlagTargetType targetType, long targetId) {
        boolean exists = switch (targetType) {
            case REPORT -> reportRepository.existsById(targetId);
            case REVIEW -> reviewRepository.existsById(targetId);
        };
        if (!exists) {
            throw new ResponseStatusException(NOT_FOUND, targetType + " not found: " + targetId);
        }
    }

    private TargetSummaries loadTargetSummaries(Collection<Flag> flags) {
        Set<Long> reportIds = new LinkedHashSet<>();
        Set<Long> reviewIds = new LinkedHashSet<>();
        for (Flag flag : flags) {
            switch (flag.getTargetType()) {
                case REPORT -> reportIds.add(flag.getTargetId());
                case REVIEW -> reviewIds.add(flag.getTargetId());
            }
        }
        Map<Long, String> reports = new HashMap<>();
        if (!reportIds.isEmpty()) {
            reportRepository.findAllById(reportIds).forEach(r -> reports.put(r.getId(), summarize(r)));
        }
        Map<Long, String> reviews = new HashMap<>();
        if (!reviewIds.isEmpty()) {
            reviewRepository.findAllById(reviewIds).forEach(r -> reviews.put(r.getId(), summarize(r)));
        }
        return new TargetSummaries(reports, reviews);
    }

    private FlagPendingItemResponse withTargetSummary(Flag flag, TargetSummaries summaries) {
        String summary = switch (flag.getTargetType()) {
            case REPORT -> summaries.reports().get(flag.getTargetId());
            case REVIEW -> summaries.reviews().get(flag.getTargetId());
        };
        return FlagPendingItemResponse.of(flag, summary != null, summary);
    }

    private String summarize(Report r) {
        String comment = r.getComment() == null ? "" : " · " + r.getComment();
        return "[제보] placeId=" + r.getPlaceId() + " · " + r.getReportType().label() + comment;
    }

    private String summarize(Review r) {
        String comment = r.getComment() == null ? "" : " · " + r.getComment();
        return "[후기] placeId=" + r.getPlaceId() + " · ★" + r.getRating() + comment;
    }

    private static String normalize(String detail) {
        if (detail == null) return null;
        String trimmed = detail.strip();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private record TargetSummaries(Map<Long, String> reports, Map<Long, String> reviews) {
    }
}
