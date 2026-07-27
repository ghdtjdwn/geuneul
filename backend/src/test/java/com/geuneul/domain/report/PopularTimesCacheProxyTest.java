package com.geuneul.domain.report;

import com.geuneul.domain.auth.TrustScoreService;
import com.geuneul.domain.photo.PhotoService;
import com.geuneul.domain.photo.PhotoUploadService;
import com.geuneul.domain.place.PlaceRepository;
import com.geuneul.domain.report.dto.PopularTimesSlot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import org.springframework.cache.CacheManager;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * popular-times generation-fenced cache regression test. The second read must avoid the group-by query;
 * RedisCacheConfigTest covers serialization.
 */
class PopularTimesCacheProxyTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-07-10T12:00:00Z"), ZoneOffset.UTC);

    @Test
    @DisplayName("popularTimes 2회 호출 시 집계 쿼리는 1회만 — 2회차는 캐시 히트")
    void secondCallHitsCache() {
        ReportRepository reportRepository = mock(ReportRepository.class);
        PlaceRepository placeRepository = mock(PlaceRepository.class);
        TrustScoreService trustScoreService = mock(TrustScoreService.class);

        PlaceCongestionSlotView slot = mock(PlaceCongestionSlotView.class);
        when(slot.getDow()).thenReturn(6);
        when(slot.getHour()).thenReturn(14);
        when(slot.getSampleCount()).thenReturn(4L);
        when(slot.getCrowdedCount()).thenReturn(3L);
        when(slot.getSeatOkCount()).thenReturn(1L);
        when(placeRepository.existsByIdAndDeletedAtIsNull(1L)).thenReturn(true);
        when(reportRepository.congestionByPlace(1L)).thenReturn(List.of(slot));

        PhotoUploadService photoUploadService = mock(PhotoUploadService.class);
        PhotoService photoService = new PhotoService(mock(S3Presigner.class), "", "ap-northeast-2", CLOCK,
                photoUploadService);
        ReportDerivedCacheGenerationStore generations = mock(ReportDerivedCacheGenerationStore.class);
        ReportDerivedCacheService cache = new ReportDerivedCacheService(event -> {},
                new ConcurrentMapCacheManager("popularTimes"), generations);
        ReportService service = new ReportService(reportRepository, placeRepository, trustScoreService, photoService,
                cache, photoUploadService, CLOCK);
        List<PopularTimesSlot> first = service.popularTimes(1L);
        List<PopularTimesSlot> second = service.popularTimes(1L);

        assertThat(first).hasSize(1);
        assertThat(first.get(0).level()).isEqualTo("BUSY");
        assertThat(second).isEqualTo(first);
        verify(reportRepository, times(1)).congestionByPlace(eq(1L));
        verify(placeRepository, times(1)).existsByIdAndDeletedAtIsNull(anyLong());
    }
}
