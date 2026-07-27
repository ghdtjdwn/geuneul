package com.geuneul.domain.report;

import com.geuneul.domain.auth.JwtService;
import com.geuneul.domain.auth.Role;
import com.geuneul.domain.auth.TrustScoreService;
import com.geuneul.domain.photo.PhotoService;
import com.geuneul.domain.photo.PhotoUploadService;
import com.geuneul.domain.place.PlaceRepository;
import com.geuneul.domain.report.dto.ReportCreateRequest;
import com.geuneul.domain.report.dto.ReportResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * DB 없는 순수 오케스트레이션 단위테스트 — Docker 없이 로컬에서 항상 돈다(colima 이슈 무관, TS-009).
 * 저장·만료 필터링의 실 SQL 동작은 ReportFlowIT(Testcontainers)가 검증한다.
 * 이 테스트의 초점: 로그인 여부에 따른 userId/익명 플래그 분기 + trust_score 재계산 트리거(P2, WORKLOG 2026-07-09).
 */
class ReportServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-07-09T12:00:00Z"), ZoneOffset.UTC);

    private ReportRepository reportRepository;
    private PlaceRepository placeRepository;
    private TrustScoreService trustScoreService;
    private ReportDerivedCacheService derivedCacheService;
    private PhotoUploadService photoUploadService;
    private ReportService reportService;

    @BeforeEach
    void setUp() {
        reportRepository = mock(ReportRepository.class);
        placeRepository = mock(PlaceRepository.class);
        trustScoreService = mock(TrustScoreService.class);
        derivedCacheService = mock(ReportDerivedCacheService.class);
        photoUploadService = mock(PhotoUploadService.class);
        // PhotoService는 버킷 미설정이라 presignGet이 저장 photoUrl을 그대로 통과시킨다(N1 passthrough 분기).
        PhotoService photoService = new PhotoService(mock(S3Presigner.class), "", "ap-northeast-2", CLOCK, photoUploadService);
        reportService = new ReportService(reportRepository, placeRepository, trustScoreService, photoService,
                derivedCacheService, photoUploadService, CLOCK);

        when(placeRepository.existsByIdAndDeletedAtIsNull(1L)).thenReturn(true);
        when(reportRepository.save(any(Report.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private static ReportCreateRequest request(Boolean anonymous) {
        return new ReportCreateRequest(1L, ReportType.COOL, "에어컨 좋아요", null, anonymous, null, null);
    }

    @Test
    @DisplayName("비로그인(principal null) 제보는 userId 없이 저장되고 trust_score 재계산도 안 일어난다")
    void anonymousPrincipalMeansNoUserId() {
        ReportResponse response = reportService.create(null, request(null), "x:test");

        assertThat(response.anonymous()).isTrue();
        verify(trustScoreService, never()).recalculate(anyLong());
        // 익명 제보라도 장소 상태가 바뀌었으니 AI 요약 캐시는 무효화된다(다음 조회 때 최신 반영).
        verify(derivedCacheService).evictAfterCommit(1L);
    }

    @Test
    @DisplayName("로그인 유저가 익명을 선택하지 않으면 userId가 기록되고 trust_score가 재계산된다")
    void loggedInUserAttachesUserIdAndRecalculatesTrust() {
        JwtService.AuthPrincipal principal = new JwtService.AuthPrincipal(10L, Role.USER);

        ReportResponse response = reportService.create(principal, request(false), "x:test");

        assertThat(response.anonymous()).isFalse();
        verify(trustScoreService).recalculate(10L);
    }

    @Test
    @DisplayName("로그인 유저가 '익명으로 표시'를 선택해도 userId는 기록돼 trust_score가 반영된다 (docs/SPEC.md §6)")
    void loggedInUserChoosingAnonymousDisplayStillTracksTrust() {
        JwtService.AuthPrincipal principal = new JwtService.AuthPrincipal(10L, Role.USER);

        ReportResponse response = reportService.create(principal, request(true), "x:test");

        assertThat(response.anonymous()).isTrue(); // 화면 표시는 익명
        verify(trustScoreService).recalculate(10L); // 그러나 신뢰도 가중 대상에서는 빠지지 않음
    }

    @Test
    @DisplayName("없는 장소면 404 — 유령 장소에 제보가 쌓이지 않고 trust_score도 건드리지 않는다")
    void unknownPlaceIs404() {
        when(placeRepository.existsByIdAndDeletedAtIsNull(999L)).thenReturn(false);
        JwtService.AuthPrincipal principal = new JwtService.AuthPrincipal(10L, Role.USER);

        assertThatThrownBy(() -> reportService.create(principal,
                new ReportCreateRequest(999L, ReportType.COOL, null, null, null, null, null), "x:test"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("404");

        verify(reportRepository, never()).save(any());
        verify(trustScoreService, never()).recalculate(anyLong());
    }

    @Test
    @DisplayName("GPS 좌표가 하나만 오면 400")
    void partialReporterLocationIs400() {
        assertThatThrownBy(() -> reportService.create(null,
                new ReportCreateRequest(1L, ReportType.COOL, null, null, null, 37.5, null), "x:test"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("400");

        verify(reportRepository, never()).save(any());
    }

    @Test
    @DisplayName("GPS 좌표가 NaN이면 400")
    void nanReporterLocationIs400() {
        assertThatThrownBy(() -> reportService.create(null,
                new ReportCreateRequest(1L, ReportType.COOL, null, null, null, Double.NaN, 127.0), "x:test"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("400");

        verify(reportRepository, never()).save(any());
    }
}
