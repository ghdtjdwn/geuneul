package com.geuneul.domain.ai;

import com.geuneul.domain.report.Report;
import com.geuneul.domain.report.ReportDerivedCacheService;
import com.geuneul.domain.report.ReportRepository;
import com.geuneul.domain.report.ReportType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AiSummaryService 단위 테스트(Mockito, DB·네트워크 불필요) — ADR-0010.
 * 캐시 프록시(@Cacheable)를 통한 캐시 히트/미스 검증은 {@link AiSummaryCacheProxyTest} 참고
 * (WeatherCacheProxyTest와 같은 취지, TS-011 재발 방지).
 */
class AiSummaryServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-07-10T12:00:00Z"), ZoneOffset.UTC);

    private final ReportRepository reportRepository = mock(ReportRepository.class);
    private final ChatCompletionClient client = mock(ChatCompletionClient.class);
    private final ReportDerivedCacheService cache = passThroughCache();
    private final AiSummaryService service = new AiSummaryService(reportRepository, client, cache, CLOCK);

    private static ReportDerivedCacheService passThroughCache() {
        ReportDerivedCacheService cache = mock(ReportDerivedCacheService.class);
        when(cache.cached(anyString(), org.mockito.ArgumentMatchers.anyLong(), any(), any()))
                .thenAnswer(invocation -> ((java.util.function.Supplier<?>) invocation.getArgument(2)).get());
        return cache;
    }

    @Test
    @DisplayName("유효 제보가 없으면 AI를 호출하지 않고 empty를 반환한다(비용 방어)")
    void noReportsSkipsAiCall() {
        when(reportRepository.findTop20ByPlaceIdAndExpiresAtAfterAndHiddenFalseOrderByCreatedAtDesc(eq(1L), any()))
                .thenReturn(List.of());

        Optional<String> result = service.summarize(1L);

        assertThat(result).isEmpty();
        verify(client, never()).complete(anyString(), anyString());
    }

    @Test
    @DisplayName("유효 제보가 있으면 프롬프트를 구성해 클라이언트를 호출하고 결과를 그대로 반환한다")
    void reportsPresentDelegatesToClient() {
        Report r = reportOf(ReportType.COOL, OffsetDateTime.now(CLOCK).minusMinutes(30));
        when(reportRepository.findTop20ByPlaceIdAndExpiresAtAfterAndHiddenFalseOrderByCreatedAtDesc(eq(1L), any()))
                .thenReturn(List.of(r));
        when(client.complete(anyString(), anyString())).thenReturn(Optional.of("최근 제보 기준 시원해요"));

        Optional<String> result = service.summarize(1L);

        assertThat(result).contains("최근 제보 기준 시원해요");
        verify(client, times(1)).complete(anyString(), anyString());
    }

    @Test
    @DisplayName("클라이언트가 실패(empty)하면 그대로 empty를 반환한다(graceful degradation, 예외 없음)")
    void clientFailurePropagatesAsEmpty() {
        Report r = reportOf(ReportType.HOT, OffsetDateTime.now(CLOCK).minusMinutes(10));
        when(reportRepository.findTop20ByPlaceIdAndExpiresAtAfterAndHiddenFalseOrderByCreatedAtDesc(eq(1L), any()))
                .thenReturn(List.of(r));
        when(client.complete(anyString(), anyString())).thenReturn(Optional.empty());

        assertThat(service.summarize(1L)).isEmpty();
    }

    @Test
    @DisplayName("프롬프트는 침수 위험 표현을 순화하도록 시스템 프롬프트에 명시한다(공포 조장 금지, docs/SPEC.md §0-6)")
    void systemPromptForbidsFearMongering() {
        Report r = reportOf(ReportType.FLOOD, OffsetDateTime.now(CLOCK).minusMinutes(5));
        when(reportRepository.findTop20ByPlaceIdAndExpiresAtAfterAndHiddenFalseOrderByCreatedAtDesc(eq(1L), any()))
                .thenReturn(List.of(r));
        when(client.complete(anyString(), anyString())).thenReturn(Optional.of("요약"));

        service.summarize(1L);

        org.mockito.ArgumentCaptor<String> systemPromptCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(client).complete(systemPromptCaptor.capture(), anyString());
        assertThat(systemPromptCaptor.getValue()).contains("위험!").contains("우회 권장");
    }

    @Test
    @DisplayName("같은 타입의 제보가 여러 건이면 최신 1건으로 중복 제거해 프롬프트에 담는다")
    void deduplicatesSameTypeToLatest() {
        Report older = reportOf(ReportType.COOL, OffsetDateTime.now(CLOCK).minusHours(3));
        Report newer = reportOf(ReportType.COOL, OffsetDateTime.now(CLOCK).minusMinutes(5));
        when(reportRepository.findTop20ByPlaceIdAndExpiresAtAfterAndHiddenFalseOrderByCreatedAtDesc(eq(1L), any()))
                .thenReturn(List.of(newer, older));
        when(client.complete(anyString(), anyString())).thenReturn(Optional.of("요약"));

        service.summarize(1L);

        org.mockito.ArgumentCaptor<String> userPromptCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(client).complete(anyString(), userPromptCaptor.capture());
        String prompt = userPromptCaptor.getValue();
        int occurrences = prompt.split(ReportType.COOL.label(), -1).length - 1;
        assertThat(occurrences).isEqualTo(1);
        assertThat(prompt).contains("5분 전"); // 최신 값(newer)이 남아야 한다
    }

    @Test
    @DisplayName("제보 타입이 상한(8종)보다 많으면 최신순으로 상한만 프롬프트에 담는다(토큰·비용 상한)")
    void capsDistinctTypesInPrompt() {
        List<Report> all = new ArrayList<>();
        ReportType[] types = ReportType.values(); // 11종 — 상한(8)보다 많다
        for (int i = 0; i < types.length; i++) {
            all.add(reportOf(types[i], OffsetDateTime.now(CLOCK).minusMinutes(i + 1)));
        }
        when(reportRepository.findTop20ByPlaceIdAndExpiresAtAfterAndHiddenFalseOrderByCreatedAtDesc(eq(1L), any()))
                .thenReturn(all);
        when(client.complete(anyString(), anyString())).thenReturn(Optional.of("요약"));

        service.summarize(1L);

        org.mockito.ArgumentCaptor<String> userPromptCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(client).complete(anyString(), userPromptCaptor.capture());
        String prompt = userPromptCaptor.getValue();
        long lineCount = prompt.lines().filter(l -> l.startsWith("- ")).count();
        assertThat(lineCount).isEqualTo(8);
    }

    private static Report reportOf(ReportType type, OffsetDateTime createdAt) {
        Report r = Report.of(null, 1L, type, null, null, false, false, createdAt.plusHours(1));
        ReflectionTestUtils.setField(r, "createdAt", createdAt);
        return r;
    }
}
