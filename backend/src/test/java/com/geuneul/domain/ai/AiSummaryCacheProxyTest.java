package com.geuneul.domain.ai;

import com.geuneul.domain.report.Report;
import com.geuneul.domain.report.ReportDerivedCacheGenerationStore;
import com.geuneul.domain.report.ReportDerivedCacheService;
import com.geuneul.domain.report.ReportRepository;
import com.geuneul.domain.report.ReportType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cache.CacheManager;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Generation-fenced cache boundary regression test. Present results retain the String-only Redis value contract,
 * while empty results remain uncached.
 */
class AiSummaryCacheProxyTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-07-10T12:00:00Z"), ZoneOffset.UTC);

    @Test
    @DisplayName("present 결과는 SpEL 오류 없이 캐시되고, 2회차는 리포지토리/클라이언트 재호출 없이 캐시 히트")
    void presentResultCachedWithoutSpelError() {
        ReportRepository reportRepository = mock(ReportRepository.class);
        ChatCompletionClient client = mock(ChatCompletionClient.class);
        Report r = Report.of(null, 1L, ReportType.COOL, null, null, false, false,
                OffsetDateTime.now(CLOCK).plusHours(1));
        ReflectionTestUtils.setField(r, "createdAt", OffsetDateTime.now(CLOCK).minusMinutes(10));
        when(reportRepository.findTop20ByPlaceIdAndExpiresAtAfterAndHiddenFalseOrderByCreatedAtDesc(eq(1L), any()))
                .thenReturn(List.of(r));
        when(client.complete(anyString(), anyString())).thenReturn(Optional.of("최근 제보 기준 시원해요"));

        AiSummaryService service = service(reportRepository, client);
        Optional<String> first = service.summarize(1L);
        Optional<String> second = service.summarize(1L);

        assertThat(first).contains("최근 제보 기준 시원해요");
        assertThat(second).contains("최근 제보 기준 시원해요");
        verify(reportRepository, times(1))
                .findTop20ByPlaceIdAndExpiresAtAfterAndHiddenFalseOrderByCreatedAtDesc(eq(1L), any());
        verify(client, times(1)).complete(anyString(), anyString());
    }

    @Test
    @DisplayName("empty 결과(제보 없음)는 캐시하지 않는다 — 다음 호출에서 다시 리포지토리를 조회한다")
    void emptyResultNotCached() {
        ReportRepository reportRepository = mock(ReportRepository.class);
        ChatCompletionClient client = mock(ChatCompletionClient.class);
        when(reportRepository.findTop20ByPlaceIdAndExpiresAtAfterAndHiddenFalseOrderByCreatedAtDesc(eq(2L), any()))
                .thenReturn(List.of());

        AiSummaryService service = service(reportRepository, client);
        service.summarize(2L);
        service.summarize(2L);

        verify(reportRepository, times(2))
                .findTop20ByPlaceIdAndExpiresAtAfterAndHiddenFalseOrderByCreatedAtDesc(eq(2L), any());
    }

    private static AiSummaryService service(ReportRepository reports, ChatCompletionClient client) {
        CacheManager cacheManager = new ConcurrentMapCacheManager("aiSummary");
        ReportDerivedCacheGenerationStore generations = mock(ReportDerivedCacheGenerationStore.class);
        ReportDerivedCacheService cache = new ReportDerivedCacheService(event -> {}, cacheManager, generations);
        return new AiSummaryService(reports, client, cache, CLOCK);
    }
}
