package com.geuneul.domain.report;

import com.geuneul.domain.auth.JwtService;
import com.geuneul.domain.auth.TrustScoreService;
import com.geuneul.domain.photo.PhotoService;
import com.geuneul.domain.photo.PhotoUploadService;
import com.geuneul.domain.place.PlaceRepository;
import com.geuneul.domain.report.dto.PopularTimesSlot;
import com.geuneul.domain.report.dto.ReportCreateRequest;
import com.geuneul.domain.report.dto.ReportResponse;
import com.geuneul.global.web.ApiRequests;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.NOT_FOUND;

@Service
@Transactional(readOnly = true)
public class ReportService {

    /** GPS 방문 인증 반경(ADR-0005 §④) — 제보자 좌표가 장소 이 거리 이내면 verified. */
    static final double VISIT_VERIFY_METERS = 100;

    private final ReportRepository reportRepository;
    private final PlaceRepository placeRepository;
    private final TrustScoreService trustScoreService;
    private final PhotoService photoService;
    private final ReportDerivedCacheService derivedCacheService;
    private final PhotoUploadService photoUploadService;
    private final Clock clock;

    public ReportService(ReportRepository reportRepository, PlaceRepository placeRepository,
                         TrustScoreService trustScoreService, PhotoService photoService,
                         ReportDerivedCacheService derivedCacheService, PhotoUploadService photoUploadService, Clock clock) {
        this.reportRepository = reportRepository;
        this.placeRepository = placeRepository;
        this.trustScoreService = trustScoreService;
        this.photoService = photoService;
        this.derivedCacheService = derivedCacheService;
        this.photoUploadService = photoUploadService;
        this.clock = clock;
    }

    /**
     * @param principal 로그인 시 JWT에서 뽑은 주체(비로그인이면 null — POST /reports는 permitAll이라
     *                  SecurityConfig가 강제하지 않는다, ReviewService.create의 "요청 바디로 안 받는다"와 동일 원칙).
     */
    @Transactional
    public ReportResponse create(JwtService.AuthPrincipal principal, ReportCreateRequest request, String clientKey) {
        requirePlace(request.placeId());
        requireReporterLocation(request);
        photoUploadService.validateReport(request.photoUrl(), principal, clientKey);
        Long userId = principal == null ? null : principal.userId();
        // 비로그인이면 무조건 익명(신원 자체가 없음). 로그인 유저는 "익명으로 표시" 선택과 무관하게
        // userId는 기록해 trust_score 가중을 유지한다(docs/SPEC.md §6, Report.of 주석 참고).
        boolean anonymousFlag = userId == null || request.anonymousOrDefault();
        // GPS 방문 인증(ADR-0005 §④): 제보자 좌표가 왔고 장소 100m 이내면 verified — 허위제보 억제,
        // V10 뷰에서 신뢰도 가중. 좌표 미제공이면 false(기존 동작과 동일, 스코어 불변).
        boolean verified = request.hasReporterLocation()
                && placeRepository.isWithinMeters(request.placeId(), request.lat(), request.lng(), VISIT_VERIFY_METERS)
                        .orElse(false);
        // expires_at = 타입별 TTL(ReportType 주석 참고) — 만료된 제보는 조회·스코어에서 빠진다.
        OffsetDateTime expiresAt = OffsetDateTime.now(clock).plus(request.reportType().ttl());
        Report saved = reportRepository.save(Report.of(
                userId, request.placeId(), request.reportType(), normalize(request.comment()),
                request.photoUrl(), anonymousFlag, verified, expiresAt));
        if (userId != null) {
            trustScoreService.recalculate(userId);
        }
        // 새 제보가 장소 상태를 바꿨으니 AI 한줄요약 캐시를 버린다 — 다음 상세 조회 때 최신 반영 요약을 새로 생성.
        derivedCacheService.evictAfterCommit(request.placeId());
        // 비공개 버킷이라 저장 URL은 그대로 못 본다 → 조회 시점 presigned GET으로 변환(N1, 제보 사진도 리뷰와 공유 수정).
        return ReportResponse.of(saved, photoService.presignGet(saved.getPhotoUrl()));
    }

    /** 장소의 유효(미만료) 제보 최신순 — 상세 화면 "최근 제보". */
    public List<ReportResponse> recentByPlace(long placeId) {
        requirePlace(placeId);
        return reportRepository
                .findTop20ByPlaceIdAndExpiresAtAfterAndHiddenFalseOrderByCreatedAtDesc(placeId, OffsetDateTime.now(clock))
                .stream()
                // 첨부 사진은 presignGet으로 임시 서명해 내려준다(N1 — 비공개 S3 403 해소).
                .map(r -> ReportResponse.of(r, photoService.presignGet(r.getPhotoUrl())))
                .toList();
    }

    /**
     * 장소의 시간대별 혼잡 파생(자체 popular-times, ADR-0005 §④) — 제보 이력을 KST 요일×시간으로 집계.
     * 제보가 있는 슬롯만 반환한다(빈 슬롯은 클라이언트가 그리드에서 채움). 외부 API 0 — 우리 UGC만으로 유도.
     *
     * <p>느리게 변하는 과거 이력 집계라 장소별로 1시간 Redis 캐시(P4, RedisCacheConfig.POPULAR_TIMES_CACHE)
     * — 상세 조회마다 group-by를 반복하지 않는다. Redis 장애 시 CacheErrorHandler가 우회해 원본 쿼리로 폴백.
     */
    public List<PopularTimesSlot> popularTimes(long placeId) {
        return derivedCacheService.cached("popularTimes", placeId,
                () -> loadPopularTimes(placeId), ignored -> true);
    }

    private List<PopularTimesSlot> loadPopularTimes(long placeId) {
        requirePlace(placeId);
        return reportRepository.congestionByPlace(placeId).stream()
                .map(PopularTimesSlot::of)
                .toList();
    }

    private void requirePlace(long placeId) {
        if (!placeRepository.existsByIdAndDeletedAtIsNull(placeId)) {
            throw new ResponseStatusException(NOT_FOUND, "place not found: " + placeId);
        }
    }

    private static void requireReporterLocation(ReportCreateRequest request) {
        if (request.lat() == null && request.lng() == null) {
            return;
        }
        if (request.lat() == null || request.lng() == null) {
            throw new ResponseStatusException(BAD_REQUEST, "GPS 방문 인증 좌표는 lat/lng를 함께 보내야 합니다");
        }
        ApiRequests.requireValidLatLng(request.lat(), request.lng());
    }

    private static String normalize(String comment) {
        if (comment == null) return null;
        String trimmed = comment.strip();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
