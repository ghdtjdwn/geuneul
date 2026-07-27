package com.geuneul.domain.flag;

import com.geuneul.domain.flag.dto.FlagCreateRequest;
import com.geuneul.domain.flag.dto.FlagPendingListResponse;
import com.geuneul.domain.flag.dto.FlagResolveRequest;
import com.geuneul.domain.flag.dto.FlagResponse;
import com.geuneul.domain.report.Report;
import com.geuneul.domain.report.ReportRepository;
import com.geuneul.domain.report.ReportDerivedCacheService;
import com.geuneul.domain.report.ReportType;
import com.geuneul.domain.review.Review;
import com.geuneul.domain.review.ReviewRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.server.ResponseStatusException;

import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * DB 없는 순수 오케스트레이션 단위테스트(ReviewServiceTest와 동일 패턴, TS-009 무관 — 항상 로컬에서 돈다).
 * 실 유니크 제약·페이지네이션 SQL은 FlagFlowIT(Testcontainers)가 검증한다.
 */
class FlagServiceTest {

    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2026-07-09T12:00:00Z"), ZoneOffset.UTC);

    private FlagRepository flagRepository;
    private ReportRepository reportRepository;
    private ReviewRepository reviewRepository;
    private FlagService flagService;
    private ReportDerivedCacheService reportDerivedCacheService;

    @BeforeEach
    void setUp() {
        flagRepository = mock(FlagRepository.class);
        reportRepository = mock(ReportRepository.class);
        reviewRepository = mock(ReviewRepository.class);
        reportDerivedCacheService = mock(ReportDerivedCacheService.class);
        flagService = new FlagService(flagRepository, reportRepository, reviewRepository,
                reportDerivedCacheService, FIXED_CLOCK);
    }

    @Test
    @DisplayName("정상 신고는 저장되고 PENDING 상태로 응답한다")
    void createsFlag() {
        when(reportRepository.existsById(1L)).thenReturn(true);
        when(flagRepository.existsByTargetTypeAndTargetIdAndReporterId(FlagTargetType.REPORT, 1L, 10L))
                .thenReturn(false);
        when(flagRepository.save(any(Flag.class))).thenAnswer(inv -> inv.getArgument(0));

        FlagResponse response = flagService.create(10L,
                new FlagCreateRequest(FlagTargetType.REPORT, 1L, FlagReason.FALSE_INFO, "가짜 제보 같아요"));

        assertThat(response.status()).isEqualTo("PENDING");
        assertThat(response.targetType()).isEqualTo("REPORT");
        assertThat(response.reason()).isEqualTo("FALSE_INFO");
        assertThat(response.detail()).isEqualTo("가짜 제보 같아요");
        verify(flagRepository).save(any(Flag.class));
    }

    @Test
    @DisplayName("존재하지 않는 대상(REPORT)이면 404 — 유령 대상에 신고가 쌓이지 않는다")
    void unknownReportTargetIs404() {
        when(reportRepository.existsById(999L)).thenReturn(false);

        assertThatThrownBy(() -> flagService.create(10L,
                new FlagCreateRequest(FlagTargetType.REPORT, 999L, FlagReason.SPAM, null)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("404");
        verify(flagRepository, never()).save(any());
    }

    @Test
    @DisplayName("존재하지 않는 대상(REVIEW)이면 404")
    void unknownReviewTargetIs404() {
        when(reviewRepository.existsById(999L)).thenReturn(false);

        assertThatThrownBy(() -> flagService.create(10L,
                new FlagCreateRequest(FlagTargetType.REVIEW, 999L, FlagReason.OFFENSIVE, null)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("404");
    }

    @Test
    @DisplayName("이미 같은 대상을 신고했으면(사전 체크) 409 — 서비스까지 저장 시도하지 않는다")
    void duplicateFlagIs409ByPreCheck() {
        when(reportRepository.existsById(1L)).thenReturn(true);
        when(flagRepository.existsByTargetTypeAndTargetIdAndReporterId(FlagTargetType.REPORT, 1L, 10L))
                .thenReturn(true);

        assertThatThrownBy(() -> flagService.create(10L,
                new FlagCreateRequest(FlagTargetType.REPORT, 1L, FlagReason.SPAM, null)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("409");
        verify(flagRepository, never()).save(any());
    }

    @Test
    @DisplayName("사전 체크를 통과했지만 동시 이중 제출로 유니크 제약이 걸리면(레이스) 409로 변환된다")
    void duplicateFlagRaceIs409ByConstraint() {
        when(reportRepository.existsById(1L)).thenReturn(true);
        when(flagRepository.existsByTargetTypeAndTargetIdAndReporterId(FlagTargetType.REPORT, 1L, 10L))
                .thenReturn(false);
        when(flagRepository.save(any(Flag.class))).thenThrow(new DataIntegrityViolationException("uq_flags_target_reporter"));

        assertThatThrownBy(() -> flagService.create(10L,
                new FlagCreateRequest(FlagTargetType.REPORT, 1L, FlagReason.SPAM, null)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("409");
    }

    @Test
    @DisplayName("빈 detail은 null로 정규화된다")
    void blankDetailNormalizesToNull() {
        when(reportRepository.existsById(1L)).thenReturn(true);
        when(flagRepository.existsByTargetTypeAndTargetIdAndReporterId(any(), eq(1L), eq(10L))).thenReturn(false);
        when(flagRepository.save(any(Flag.class))).thenAnswer(inv -> inv.getArgument(0));

        FlagResponse response = flagService.create(10L,
                new FlagCreateRequest(FlagTargetType.REPORT, 1L, FlagReason.OTHER, "   "));

        assertThat(response.detail()).isNull();
    }

    @Test
    @DisplayName("대기 큐 조회는 PENDING만, 대상 요약(REPORT)을 함께 조립한다")
    void pendingListIncludesReportSummary() {
        Report report = Report.anonymous(5L, ReportType.COOL, "에어컨 빵빵", true, null);
        setId(report, 1L);
        when(reportRepository.findAllById(any())).thenReturn(List.of(report));
        Flag flag = Flag.create(FlagTargetType.REPORT, 1L, 10L, FlagReason.FALSE_INFO, "가짜같아요");
        Page<Flag> page = new PageImpl<>(List.of(flag), PageRequest.of(0, 20), 1);
        when(flagRepository.findByStatusOrderByCreatedAtAsc(eq(FlagStatus.PENDING), any())).thenReturn(page);

        FlagPendingListResponse result = flagService.pending(0, 20);

        assertThat(result.flags()).hasSize(1);
        assertThat(result.flags().get(0).targetExists()).isTrue();
        assertThat(result.flags().get(0).targetSummary()).contains("placeId=5").contains("시원해요");
        verify(reportRepository, never()).findById(1L);
    }

    @Test
    @DisplayName("대상이 이미 삭제됐으면 targetExists=false·targetSummary=null")
    void pendingListHandlesMissingTarget() {
        when(reviewRepository.findAllById(any())).thenReturn(List.of());
        Flag flag = Flag.create(FlagTargetType.REVIEW, 2L, 10L, FlagReason.OFFENSIVE, null);
        Page<Flag> page = new PageImpl<>(List.of(flag), PageRequest.of(0, 20), 1);
        when(flagRepository.findByStatusOrderByCreatedAtAsc(eq(FlagStatus.PENDING), any())).thenReturn(page);

        FlagPendingListResponse result = flagService.pending(0, 20);

        assertThat(result.flags().get(0).targetExists()).isFalse();
        assertThat(result.flags().get(0).targetSummary()).isNull();
    }

    @Test
    @DisplayName("대기 큐 대상 요약은 타입별 findAllById로 배치 조회한다(N+1 방지)")
    void pendingListBatchLoadsTargets() {
        Report report = Report.anonymous(5L, ReportType.COOL, null, true, null);
        setId(report, 1L);
        Review review = Review.of(10L, 5L, (short) 4, "좋아요", null);
        setId(review, 2L);
        when(reportRepository.findAllById(any())).thenReturn(List.of(report));
        when(reviewRepository.findAllById(any())).thenReturn(List.of(review));
        Flag reportFlag = Flag.create(FlagTargetType.REPORT, 1L, 10L, FlagReason.FALSE_INFO, null);
        Flag reviewFlag = Flag.create(FlagTargetType.REVIEW, 2L, 11L, FlagReason.OFFENSIVE, null);
        Page<Flag> page = new PageImpl<>(List.of(reportFlag, reviewFlag), PageRequest.of(0, 20), 2);
        when(flagRepository.findByStatusOrderByCreatedAtAsc(eq(FlagStatus.PENDING), any())).thenReturn(page);

        FlagPendingListResponse result = flagService.pending(0, 20);

        assertThat(result.flags()).hasSize(2);
        assertThat(result.flags().get(0).targetSummary()).contains("[제보]");
        assertThat(result.flags().get(1).targetSummary()).contains("[후기]");
        verify(reportRepository, times(1)).findAllById(any());
        verify(reviewRepository, times(1)).findAllById(any());
        verify(reportRepository, never()).findById(1L);
        verify(reviewRepository, never()).findById(2L);
    }

    @Test
    @DisplayName("PENDING 신고를 RESOLVED로 처리하면 resolvedAt이 채워진다")
    void resolvesFlag() {
        Flag flag = Flag.create(FlagTargetType.REPORT, 1L, 10L, FlagReason.SPAM, null);
        Report report = Report.anonymous(5L, ReportType.COOL, null, true, null);
        when(flagRepository.findById(1L)).thenReturn(Optional.of(flag));
        when(reportRepository.findById(1L)).thenReturn(Optional.of(report));
        when(flagRepository.save(any(Flag.class))).thenAnswer(inv -> inv.getArgument(0));

        FlagResponse response = flagService.resolve(1L, new FlagResolveRequest(FlagStatus.RESOLVED));

        assertThat(response.status()).isEqualTo("RESOLVED");
        assertThat(response.resolvedAt()).isEqualTo(OffsetDateTime.now(FIXED_CLOCK));
        assertThat(report.isHidden()).isTrue();
        verify(reportDerivedCacheService).evictAfterCommit(5L);
    }

    @Test
    @DisplayName("이미 처리된 신고를 다시 처리하려 하면 409")
    void resolveAlreadyResolvedIs409() {
        Flag flag = Flag.create(FlagTargetType.REPORT, 1L, 10L, FlagReason.SPAM, null);
        flag.resolve(FlagStatus.DISMISSED, OffsetDateTime.now(FIXED_CLOCK));
        when(flagRepository.findById(1L)).thenReturn(Optional.of(flag));

        assertThatThrownBy(() -> flagService.resolve(1L, new FlagResolveRequest(FlagStatus.RESOLVED)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("409");
        verify(flagRepository, never()).save(any());
    }

    @Test
    @DisplayName("status=PENDING으로 되돌리려 하면 409")
    void resolveToPendingIs409() {
        Flag flag = Flag.create(FlagTargetType.REPORT, 1L, 10L, FlagReason.SPAM, null);
        when(flagRepository.findById(1L)).thenReturn(Optional.of(flag));

        assertThatThrownBy(() -> flagService.resolve(1L, new FlagResolveRequest(FlagStatus.PENDING)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("409");
    }

    @Test
    @DisplayName("없는 신고를 처리하려 하면 404")
    void resolveUnknownFlagIs404() {
        when(flagRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> flagService.resolve(999L, new FlagResolveRequest(FlagStatus.RESOLVED)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("404");
    }

    private static void setId(Object target, long id) {
        try {
            Field f = target.getClass().getDeclaredField("id");
            f.setAccessible(true);
            f.set(target, id);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }
}
