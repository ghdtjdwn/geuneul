package com.geuneul.domain.ai;

import com.geuneul.domain.report.Report;
import com.geuneul.domain.report.ReportDerivedCacheService;
import com.geuneul.domain.report.ReportRepository;
import com.geuneul.domain.report.ReportType;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 장소 상세(GET /places/{id})의 "최근 제보 기준" 한국어 한 문장 AI 요약 — P3, **곁다리**(docs/SPEC.md §0-8·§0-9).
 *
 * <p>간판(지리공간 검색·survival_score)을 가리지 않도록 설계 제약을 지킨다:
 * <ul>
 *   <li><b>상세(getById) 경로에만 붙는다</b> — 목록/반경/bounds 응답에는 넣지 않는다(비용·성능, 지시사항).</li>
 *   <li><b>Redis 캐시(장소별, TTL 3h)</b> — 상세 조회마다 AI를 호출하지 않는다. TTL은 "제보 기준 요약"이
 *       너무 오래 굳지 않으면서도(1~6h 지시 범위의 중간값) 호출 비용을 크게 줄이는 지점으로 골랐다.</li>
 *   <li><b>유효 제보가 0건이면 AI를 호출하지 않는다</b> — 지시사항의 "제보 없으면 요약 생성 안 함" 선택지를
 *       택했다("정보 부족" 정적 문구 대신): 상세 화면이 이미 "최근 제보 없음"을 별도로 보여주므로 AI가
 *       같은 말을 문장으로 반복할 필요가 없고, 빈 응답은 캐시하지 않아(unless) 다음 제보가 오면 즉시
 *       재평가된다(정적 문구를 캐시했다면 그 TTL 동안 새 제보를 반영 못 했을 것).</li>
 *   <li><b>graceful degradation</b> — {@link ChatCompletionClient}가 이미 모든 실패(키 없음·타임아웃·5xx)를
 *       삼켜 empty를 반환한다. 이 서비스는 그 위에 얹을 뿐 추가 예외 경로가 없다 — AI 없이도 상세 응답은
 *       항상 200이다.</li>
 * </ul>
 */
@Service
public class AiSummaryService {

    /** 침수 등 위험 정보를 공포 조장 없이 순화하라는 docs/SPEC.md §0-6 규칙을 프롬프트 레벨에서 강제한다. */
    private static final String SYSTEM_PROMPT = """
            너는 '그늘' 앱의 장소 요약 도우미다. 사용자가 준 최근 제보 목록만 근거로 한국어 한 문장 요약을 작성해라.
            규칙:
            - 반드시 한 문장, 존댓말, 40자 내외.
            - 제보에 없는 정보는 추측하지 마라.
            - 침수·미끄럼 등 위험 제보는 "위험!"처럼 공포를 조장하지 말고 "최근 침수 제보 있음, 우회 권장"처럼 순화해서 표현해라.
            - 요약 문장 외의 다른 말(인사말·설명·따옴표)은 절대 덧붙이지 마라.
            """;

    /** 프롬프트에 담을 제보 종류 최대 개수 — 토큰·비용 상한(가장 최근 발생한 타입 우선). */
    private static final int MAX_REPORT_TYPES_IN_PROMPT = 8;

    private final ReportRepository reportRepository;
    private final ChatCompletionClient client;
    private final ReportDerivedCacheService derivedCacheService;
    private final Clock clock;

    public AiSummaryService(ReportRepository reportRepository, ChatCompletionClient client,
                            ReportDerivedCacheService derivedCacheService, Clock clock) {
        this.reportRepository = reportRepository;
        this.client = client;
        this.derivedCacheService = derivedCacheService;
        this.clock = clock;
    }

    /**
     * 장소의 최근(유효) 제보를 근거로 한 문장 요약을 반환한다. 캐시 키는 placeId만 — 캐시가 살아있는
     * 동안은 그 장소의 제보가 더 들어와도 TTL이 지나야 재평가된다(비용 방어, 지시사항).
     * 단, report 생성·숨김은 DB generation을 올리므로 기존 캐시를 즉시 대체한다.
     * Optional은 캐시 경계에서 nullable String으로 변환해 기존 String 전용 Redis serializer 계약을 유지한다.
     */
    public Optional<String> summarize(long placeId) {
        String summary = derivedCacheService.cached("aiSummary", placeId,
                () -> loadSummary(placeId).orElse(null), java.util.Objects::nonNull);
        return Optional.ofNullable(summary);
    }

    private Optional<String> loadSummary(long placeId) {
        List<Report> reports = reportRepository
                .findTop20ByPlaceIdAndExpiresAtAfterAndHiddenFalseOrderByCreatedAtDesc(placeId, OffsetDateTime.now(clock));
        if (reports.isEmpty()) {
            return Optional.empty();
        }
        String userPrompt = buildUserPrompt(reports);
        return client.complete(SYSTEM_PROMPT, userPrompt);
    }

    /** 제보 타입별 최근 발생 시각만 남기고(중복 제거), 최신순으로 상한 개수만 프롬프트에 담는다. */
    private String buildUserPrompt(List<Report> reports) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        Map<ReportType, OffsetDateTime> latestByType = new EnumMap<>(ReportType.class);
        for (Report r : reports) {
            latestByType.merge(r.getReportType(), r.getCreatedAt(),
                    (a, b) -> a.isAfter(b) ? a : b);
        }

        StringBuilder sb = new StringBuilder("최근 제보 목록:\n");
        latestByType.entrySet().stream()
                .sorted(Comparator.<Map.Entry<ReportType, OffsetDateTime>>comparingLong(
                        e -> Duration.between(e.getValue(), now).toSeconds()))
                .limit(MAX_REPORT_TYPES_IN_PROMPT)
                .forEach(e -> sb.append("- ")
                        .append(e.getKey().label())
                        .append(" (")
                        .append(formatAgo(e.getValue(), now))
                        .append(")\n"));
        sb.append("\n위 제보만 근거로 한 문장 요약을 작성해라.");
        return sb.toString();
    }

    private static String formatAgo(OffsetDateTime at, OffsetDateTime now) {
        long minutes = Duration.between(at, now).toMinutes();
        if (minutes < 1) {
            return "방금";
        }
        if (minutes < 60) {
            return minutes + "분 전";
        }
        long hours = minutes / 60;
        if (hours < 24) {
            return hours + "시간 전";
        }
        return (hours / 24) + "일 전";
    }
}
