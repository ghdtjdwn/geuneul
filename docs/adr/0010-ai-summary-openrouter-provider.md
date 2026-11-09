# ADR-0010. AI 한줄 요약 — 프로바이더 중립 OpenAI 호환 클라이언트(현재 Mistral, Anthropic 키 부재로 이탈), 상세(getById) 전용 additive

- 상태: 승인 (2026-07-10) · 프로바이더 중립 리네임 + Mistral 전환 반영 (2026-07-10, §4)
- 관련: `domain/ai/ChatCompletionClient`, `domain/ai/AiSummaryService`, `PlaceResponse.of(ScoredPlaceView, Double, Double, String)`,
  `PlaceSearchService.getById`, `RedisCacheConfig`(aiSummary 캐시), `application.yml`(`ai.summary.*`),
  `infra/archive/aws/terraform/ssm.tf`·`variables.tf`·`ecs.tf`(`ai_summary_api_key`, apply는 오케스트레이터가 별도 실행)
- 선행: [ADR-0009](0009-weather-comfort-additive-restore.md)(같은 "요청당 1회·graceful degradation" 패턴을
  AI 요약에도 재사용), SPEC.md §3(AI 요약 MVP)·§6(상세화면 AI요약)·§8(AI 스택)·§0-8/9(곁다리 원칙)

## 문제(Context)

SPEC.md §8은 "AI(요약·제보 분류·상태 정규화)는 Claude API를 기본으로 쓴다"고 명시한다. 그러나 이
작업 환경에는 **Anthropic API 키가 없다** — `claude-api` 스킬의 인증 해석 순서(ANTHROPIC_API_KEY →
ANTHROPIC_AUTH_TOKEN → OAuth 프로필 → WIF)를 모두 확인했지만 어느 것도 사용 가능하지 않았다. 반면
사용자는 대화 중 명시적으로 멀티프로바이더 무료/저가 폴백 키체인(`.local/ai.env`, OpenRouter/Groq/
Gemini/Cerebras/Mistral/SambaNova 등)의 존재와 사용을 허용했다. §0-B 의사결정 프로토콜(①적합성 ②2026
트렌드 확인 ③방어 가능성)에 따라 이 이탈을 검토하고 기록한다.

기능 자체는 §3("AI 요약: MVP")·§6(상세화면 "AI 요약")에 이미 정의돼 있다 — 장소 상세에서 "최근 제보
기준" 한국어 한 문장 요약. §0-8은 "AI는 곁다리 — 간판은 지리공간이다"를 못 박는다: 이 기능이 비용·
지연·장애 벡터로 지리공간 핵심 경로를 가리면 안 된다.

## 결정(Decision)

### 1) 프로바이더 — 프로바이더 중립 OpenAI 호환 Chat Completions 클라이언트(현재 Mistral), Anthropic SDK 아님

클라이언트(`ChatCompletionClient`)는 OpenAI 호환 `/chat/completions` 엔드포인트만 가정한다 —
base-url·API 키·모델이 전부 설정값(`ai.summary.*`, env `AI_SUMMARY_*`)이라 **프로바이더를 바꿔도 코드
변경이 필요 없다**(config만 교체). 현재 프라이머리는 Mistral(`mistral-small-latest`)이다 — 최초
채택했던 프로바이더에서 전환한 경위와 이유는 §4에 기록했다.

클라이언트는 Anthropic SDK/`claude-api` 스킬 대신 Spring `RestClient`로 직접 구현했다(WeatherClient와
동일 패턴 — 이 프로젝트는 외부 HTTP 연동을 이미 이 방식으로 표준화했고, OpenAI 호환 REST 계약은
가볍다: 별도 SDK 의존성 추가가 정당화되지 않는다). 나중에 Anthropic 키가 확보되면, Anthropic이
OpenAI 호환 엔드포인트를 제공하는 경로가 있다면 `ai.summary.base-url`·`ai.summary.model`만 바꾸는
config-only 전환이 가능하고, 그렇지 않다면 `ChatCompletionClient`를 Anthropic 공식 SDK로 교체하는
리팩터(요청/응답 계약이 유사해 전환 비용 낮음) 경로가 열려 있다 — 이탈이 일방통행이 아니게 설계했다.

### 2) 모델 — `mistral-small-latest`(설정으로 교체 가능, 하드코딩 아님)

**모델은 `application.yml`의 `ai.summary.model`(env `AI_SUMMARY_MODEL`)로만 결정** — 코드에
하드코딩하지 않는다. 무료 모델 라인업은 프로바이더마다 주기적으로 회전한다(오늘 있는 모델이 다음
달엔 없을 수 있음, WebSearch로 확인된 업계 공통 주의사항) — 설정값 교체만으로 대응 가능하게 만들어
이 리스크를 흡수했다. `mistral-small-latest`를 기본값으로 고른 근거는 §4에 정리했다(같은 날 여러
무료 프로바이더를 실측 비교한 결과).

### 3) 설계 제약(지시사항 그대로 반영, ADR-0009 패턴 재사용)

| 제약 | 구현 |
|---|---|
| 상세(getById) 전용, 목록/반경/bounds 미포함 | `PlaceSearchService.searchRadius/searchBounds/searchNearest`는 `AiSummaryService`를 호출하지 않음(`PlaceSearchServiceTest`가 각 경로에서 `never()` 검증). `PlaceResponse.of(ScoredPlaceView, Double, Double)`(3-인자, 기존 계약)는 aiSummary=null로 위임, 4-인자 오버로드만 실제로 채운다. |
| Redis 캐시(장소별, TTL 1~6h) | `RedisCacheConfig`에 `aiSummary` 캐시 신설, TTL 3시간(지시 범위 중간값) — WeatherClient와 동일하게 값 직렬화는 타입 바인드(`JacksonJsonRedisSerializer<String>`, TS-011 재발 방지). |
| 제보 없으면 생성 안 함 | `AiSummaryService.summarize`가 유효 제보 0건이면 `ChatCompletionClient`를 호출조차 하지 않고 empty 반환(정적 "정보 부족" 문구 대신 — 상세 화면이 이미 "최근 제보 없음"을 별도로 보여주므로 AI가 같은 말을 반복할 필요가 없고, empty는 캐시되지 않아 다음 제보가 오면 즉시 재평가됨). |
| graceful degradation, 절대 500 금지 | `ChatCompletionClient.complete`가 키 미설정·네트워크 오류·타임아웃·5xx·빈 응답·JSON 파싱 실패를 전부 `Optional.empty()`로 흡수(WeatherClient와 동일 패턴). `AiSummaryService`는 그 위에 얹을 뿐 추가 예외 경로가 없다. `PlaceSearchService.getById`는 `aiSummaryService.summarize(id).orElse(null)`로 단순 위임 — AI가 죽어도 상세 API는 200이다. |
| 입력 = 유효(만료 전) 최근 제보 요약 | `ReportRepository.findTop20ByPlaceIdAndExpiresAtAfterOrderByCreatedAtDesc`(기존 쿼리, "최근 제보" 섹션과 동일 소스) 재사용 — 새 쿼리를 만들지 않았다. 타입별 최신 1건으로 중복 제거 후 최신순 상한 8종만 프롬프트에 담아 토큰·비용을 방어한다. |
| 공포 조장 금지(§0-6) | 시스템 프롬프트에 "침수 등 위험 제보는 '위험!'처럼 공포를 조장하지 말고 '최근 침수 제보 있음, 우회 권장'처럼 순화하라"를 명시(단위테스트로 프롬프트 내용을 못 박음). |
| 격리 — survival_score/검색 시맨틱 불변 | `domain/ai` 별도 패키지. `PlaceResponse`에 aiSummary는 표시용 필드로만 추가 — `SurvivalScore` 계산·정렬·필터링 로직은 전혀 건드리지 않는다. |
| 타임아웃 짧게(2~3s) | `SimpleClientHttpRequestFactory` connect=1.5s·read=2.5s. |

### 4) 프로바이더 중립 리네임 + Mistral 전환 (2026-07-10, 같은 날 후속 결정)

최초 구현 시점에는 클래스명 `OpenRouterClient`·설정 프리픽스 `ai.openrouter.*`·환경변수
`OPENROUTER_*`로 OpenRouter를 프라이머리 프로바이더로 삼아 시작했다. 같은 날 안에 두 가지를 이유로
이름과 프로바이더를 모두 바꿨다.

**왜 중립화했는가(이름-실체 불일치 제거)**: 코드 로직 자체는 처음부터 "OpenAI 호환 `/chat/completions`
엔드포인트만 가정하는 범용 클라이언트"였다 — base-url·key·model이 전부 설정값이라 프로바이더 교체는
애초에 config만으로 가능했다. 그런데 클래스/설정 이름이 `OpenRouterClient`/`ai.openrouter.*`로 특정
프로바이더에 고정돼 있으면 ① 실제로는 다른 프로바이더로 바꿨는데 이름이 옛 프로바이더로 남아 다음에
읽는 사람이 오해하고, ② "config만 바꾸면 프로바이더를 교체할 수 있다"는 이 설계의 핵심 장점이 이름
에서 전혀 드러나지 않는다. `ChatCompletionClient`/`ai.summary.*`/`AI_SUMMARY_*`로 리네임해 이름과
실체를 맞췄다 — **로직·동작은 전혀 바뀌지 않는 순수 리네임**(타임아웃·MAX_TOKENS·프롬프트·캐시·
graceful degradation 전부 그대로)이고, 프로바이더 교체는 이 리네임과 별개로 이미 config 한 줄이면
충분했다는 사실 자체가 중립화가 정당한 근거다.

**왜 Mistral인가(실측 기반 선택)**: 같은 날 여러 무료/저가 프로바이더를 순차로 실측했다 —
OpenRouter는 무료 티어가 플랫폼 전반에서 rate-limit(429, 특정 모델이 아니라 계정 단위로 걸림)이라
데모 출력 자체가 막혔고, Groq는 보유 키가 무효(인증 실패로 확인), Gemini는 429, DeepInfra는 잔액
부족으로 각각 탈락했다. 남은 후보 중 정상 응답이 확인된 건 Mistral과 SambaNova뿐이었고, 그중
Mistral(`mistral-small-latest`, 무료 티어)을 최종 프라이머리로 선택한 이유는 두 가지다: ① 한국어
한 문장 요약 품질이 양호함을 직접 프롬프트 실행으로 확인했고, ② SPEC.md §0-6이 요구하는 "침수 등
위험 정보를 공포 조장 없이 순화" 표현이 같은 시스템 프롬프트로 비교했을 때 SambaNova보다 더 자연
스러웠다. 곁다리 기능의 비용 방어(무료 티어)와 §0-6 안전 요구를 동시에 만족시켜 Mistral을 확정했다.

## 검토한 대안(Alternatives)

| 대안 | 기각 이유 |
|---|---|
| **Anthropic 키가 생길 때까지 기능 보류** | §3/§6이 MVP로 지정한 기능을 인프라 공백 때문에 미루는 건 과도하게 보수적 — OpenAI 호환 프로바이더를 프라이머리로 쓰고 이탈을 투명하게 기록하는 쪽이 §0-B③(방어 가능성)을 충족하면서 스펙을 실현한다. |
| **OpenRouter 오토라우터(`openrouter/free`)** | (최초 검토 시점 기록) 요청마다 실제 서빙 모델이 달라질 수 있어 MockRestServiceServer 계약 테스트·품질 예측 가능성이 떨어진다. 명시적 모델 ID가 테스트 가능성·재현성 면에서 낫다 — 이후 OpenRouter 자체가 rate-limit로 탈락했지만(§4), 이 판단 기준(명시적 모델 ID 선호)은 Mistral 채택에도 그대로 이어졌다. |
| **유료 모델을 기본값으로** | 곁다리 기능에 기본값으로 비용을 발생시키는 건 "AI는 곁다리, 비용 방어" 원칙과 어긋난다. 무료 모델을 기본값으로 두고, 품질이 부족하면 `AI_SUMMARY_MODEL` 환경변수만 바꿔 유료 모델로 전환할 수 있게 설정으로 열어뒀다. |
| **기존 이름(`OpenRouterClient`/`ai.openrouter.*`) 유지** | 프로바이더만 바꾸고 이름은 그대로 두면 변경 범위가 작아 빠르지만, "실제로는 Mistral을 쓰면서 코드/설정은 OpenRouter라 부르는" 이름-실체 불일치가 남아 다음에 이 코드를 읽는 사람(또는 미래의 나)이 오해할 소지가 크다고 판단해 기각했다(§4). |
| **캐시 키에 제보 내용 해시 포함(제보가 바뀌면 즉시 캐시 무효화)** | 지시사항이 "장소별" 캐시를 명시했고, TTL 1~6h 안에서 일정 수준의 지연을 감수하는 게 설계 의도로 읽힌다. 해시 키는 구현 복잡도만 늘리고(과설계, §0-2) 캐시 히트율을 낮춰 비용 방어 효과를 해친다. |
| **제보 없을 때 "정보 부족" 정적 문구를 캐시** | 지시사항이 허용한 대안이지만, 정적 문구를 캐시하면 그 TTL 동안 새 제보가 들어와도 요약이 갱신되지 않는 부작용이 있다. "생성 안 함(null)"을 택해 프론트가 기존처럼 "최근 제보 없음"을 별도로 보여주게 하고, 텍스트 중복도 피했다. |

## 결과(Consequences)

- `GET /places/{id}` 응답에 `aiSummary`(nullable) 필드가 additive로 추가됨 — 기존 클라이언트(프론트)는
  필드를 무시해도 무방하고, 목록/반경/bounds 응답 스키마는 전혀 변경되지 않는다.
- AI 클라이언트는 특정 프로바이더에 묶이지 않은 OpenAI 호환 계약을 사용한다. 이 ADR은 최초 모델·제공자
  선택의 근거이며, 현재 제공자를 바꾸려면 `ai.summary.model`과 필요 시 `base-url`만 설정으로 교체하면 된다.
- 클래스/설정/환경변수가 `ChatCompletionClient`/`ai.summary.*`/`AI_SUMMARY_*`로 리네임되어, 프로바이더
  이름에 코드가 더 이상 묶이지 않는다 — 다음에 프로바이더를 다시 바꿔도(예: Mistral 무료 티어가
  탈락하는 경우) 리네임 없이 config만 바꾸면 된다.
- Redis(ElastiCache)가 죽어도(TS-011/TS-012 하드닝된 `CacheErrorHandler`가 이미 처리) AI 요약은 매
  요청 새로 계산될 뿐 500을 내지 않는다 — 캐시는 비용 최적화이지 가용성 전제조건이 아니다.
- SSM 파라미터(`/geuneul/ai_summary_api_key`)·태스크데프 secret 항목은 Terraform 코드로 리네임되어
  있음 — `terraform apply`·태스크데프 재등록(라이브 rev는 `ignore_changes`라 수동 rev 등록 필요)은
  오케스트레이터가 별도로 실행해야 한다(코드/문서에 실제 키 없음, 규칙 D).

## 근거(References)

- OpenRouter 무료 티어 요청 한도(20 req/min)·모델 라인업 회전 주의사항(최초 프로바이더 검토 시점
  근거, §4에서 탈락 사유 기록): OpenRouter 공식 블로그 "Free LLM API in 2026: 13 Options Ranked and
  Compared"(openrouter.ai/blog).
- Qwen3 다국어(119개 언어) 지원 및 한중일 성능 우위(최초 모델 검토 시점 근거, 이후 §4에서 프로바이더
  자체가 교체됨): Qwen 공식 발표(x.com/Alibaba_Qwen), Qwen3 Technical Report(arxiv.org/html/2505.09388v1).
- Mistral·SambaNova 실측 비교(현재 채택 근거, §4): 동일 시스템 프롬프트로 각 프로바이더의 한국어 품질과
  위험 표현 순화 톤을 비교해 결정했다(2026-07-10).
- SPEC.md §0-B(의사결정 프로토콜)·§0-8/9(AI는 곁다리)·§3/§6(AI 요약 MVP)·§8(AI 스택 기본값).
