# ADR-0014. 관측성 — Micrometer/Prometheus(pull, 메트릭) + Spring Boot 4 OTel 스타터(push, 트레이싱) + 로컬 Grafana/Prometheus/Tempo

- 상태: 승인 (2026-07-10)
- 관련: `build.gradle`(spring-boot-starter-opentelemetry)·`application.yml`(management.*)·
  `PlaceSearchService`(커스텀 타이머)·`docker-compose.yml`(observability 프로필)·`observability/`(신규,
  Prometheus/Grafana/Tempo 설정)·`ActuatorExposureIT`·`ActuatorPrometheusOptInIT`·`PlaceSearchServiceTest`
- 선행: SPEC.md §7(Test/Ops: OpenTelemetry/Grafana)·§10 P4(관측성)·ADR-0012(k6 부하테스트 — 같은
  "간판 latency 실증" 문제의식을 외부 관측(k6 p95)에서 내부 관측(Micrometer p95)으로 보완)

## 문제(Context)

로드맵 P4 마지막 심화 조각: "관측성(OTel/Grafana)". 그늘은 이미 Spring Boot Actuator +
`micrometer-registry-prometheus`가 `build.gradle`/`application.yml`에 있었지만(이전 세션 스캐폴드),
**트레이싱은 전혀 없었고, `/actuator/prometheus` 노출 안전성도 검토된 적이 없었다.** 오케스트레이터
지시의 핵심 제약:

1. **과설계 금지** — 관리형 Grafana Cloud 등 유료 SaaS를 프로덕션에 세우지 않는다. "계측 + 로컬에서
   볼 수 있는 스택 + 문서"까지가 스코프.
2. **노출 안전** — `/actuator/prometheus`·트레이스 엔드포인트는 인증/네트워크로 보호하거나 프로덕션
   미노출.
3. **간판 강화** — 지리검색(반경/kNN) 등 핵심 경로에 의미 있는 커스텀 메트릭 1~2개.
4. **프로덕션 반영 최소** — ECS 태스크데프 변경(env/secret 추가)이 필요하면 코드/문서만, `apply`는
   오케스트레이터.

작업을 시작하기 전 프로덕션을 직접 확인한 결과(`curl http://<ALB>/actuator/prometheus`), **이미
200과 함께 전체 메트릭이 인증 없이 공개돼 있었다** — `management.endpoints.web.exposure.include:
health,info,prometheus`가 하드코딩돼 있었기 때문이다. 이 ADR의 첫 결정은 그래서 "새 기능 추가"가
아니라 "이미 라이브에 있는 노출 구멍을 닫는 것"이었다(TS-022).

## 결정(Decision)

### 1) `/actuator/prometheus` 노출 = 프로덕션 기본 미노출(옵트인), 별도 관리 포트·인증 계층은 안 만든다

`management.endpoints.web.exposure.include: ${MANAGEMENT_EXPOSURE:health,info}`로 바꿨다 — 기본값은
`health,info`뿐이고, ECS 태스크데프는 `MANAGEMENT_EXPOSURE`를 주입하지 않으므로 **프로덕션은 항상
이 안전한 기본값**이다. 로컬 observability 스택을 쓸 때만 `MANAGEMENT_EXPOSURE=health,info,prometheus`로
옵트인한다.

지시가 준 두 옵션("인증/네트워크로 보호" **또는** "프로덕션 미노출") 중 후자를 택했다 — 이유:

- **`management.server.port`로 관리 포트를 분리하는 안**을 먼저 검토했으나, 이 프로젝트의 ECS 인프라는
  ALB→target group이 컨테이너 포트 8080 하나만 바라보고, `/actuator/health`가 **바로 그 포트**에서
  ALB 헬스체크에 응답해야 한다(`alb.tf`). 관리 포트를 분리하면 health까지 그 포트로 옮겨가 ALB
  헬스체크 자체가 깨지므로, target group의 health-check 포트 오버라이드 + 보안그룹에 새 포트 추가가
  **필요해진다** — 이는 인프라(SG·target group) 변경이라 지시 제약 4("ECS 태스크데프/인프라 변경은
  코드/문서만, apply 금지")와 충돌한다.
- **actuator 전용 Basic Auth 계층을 새로 만드는 안**도 검토했으나, 이 프로젝트엔 이미 `/admin/**`용
  JWT+역할 기반 인가(SecurityConfig, ADR 없음·PR #33)가 있다 — actuator만을 위한 별도 자격증명
  체계를 또 만드는 건 관리 포인트가 하나 더 느는 과설계다(§0-2).
- **옵트인(환경변수 미설정 시 비활성)은 이 레포에서 이미 확립된 패턴**이다 — `GEUNEUL_PROXY_SECRET`
  (미설정이면 레이트리밋이 기존 동작으로 폴백)·`OPENROUTER_API_KEY`(미설정이면 AI 요약이 null로
  폴백)와 동일하게 "값이 없으면 안전한 기본"을 따른다. **인프라 변경 없이, 코드 레벨에서 즉시 닫히는
  게 가장 낮은 리스크**로 오늘 발견된 노출을 없앤다.
- `/actuator/env`처럼 정말 민감한 엔드포인트는 애초에 화이트리스트(`health,info[,prometheus]`)에
  없으므로 옵트인해도 노출되지 않는다(`ActuatorExposureIT`가 이 계약을 못 박는다).

### 2) 트레이싱 = Spring Boot 4.0 공식 OTel 스타터(`spring-boot-starter-opentelemetry`)

SPEC.md §7 원문이 "micrometer-tracing-bridge-otel + OTLP exporter, 또는 Boot의 OTel 스타터" 둘 다
허용했다. Maven Central에서 `org.springframework.boot:spring-boot-starter-opentelemetry`가 **정확히
이 프로젝트의 Boot 버전(4.0.6)으로 존재**함을 확인했다(2025-11 Boot 4.0 GA와 함께 출시,
spring.io 공식 블로그로 교차검증). 이 스타터 하나가 `micrometer-tracing-bridge-otel:1.6.5` +
`opentelemetry-exporter-otlp:1.55.0` + `micrometer-registry-otlp:1.16.5`를 묶어 버전 정합을 보장한다
— 수동으로 세 의존성을 따로 골라 버전을 맞추는 것보다 안전(Boot BOM이 관리).

메트릭은 여전히 **Prometheus(pull) 1순위** — OTLP 메트릭(push, `micrometer-registry-otlp`)은 같이
따라오지만 옵트인(`management.otlp.metrics.export.enabled=false` 기본)으로 꺼둔다. 이유: 로컬
Grafana는 Prometheus를 직접 스크레이프하는 걸로 충분하고, OTLP 메트릭 push까지 동시에 켜면 같은
지표를 두 경로로 중복 발행하는 것이라 불필요(§0-2). 트레이싱만 push(OTLP)가 필연적이다 — pull 방식
분산 트레이싱은 존재하지 않는다.

**프로퍼티는 전부 env 플레이스홀더로 기본 비활성**(`OTEL_TRACING_EXPORT_ENABLED:false`,
`OTEL_EXPORTER_OTLP_ENDPOINT:` 빈값, `OTEL_METRICS_EXPORT_ENABLED:false`) — ECS가 이 값을 안 주입하는
한 프로덕션은 주기적으로 어딘가에 OTLP를 쏘려는 시도조차 하지 않는다(연결거부 로그 노이즈·불필요한
백그라운드 스레드 방지). 관리형 백엔드(Grafana Cloud, Honeycomb 등)에 연결하고 싶어지면
`OTEL_EXPORTER_OTLP_ENDPOINT`·`OTEL_TRACING_EXPORT_ENABLED`를 ECS task def의 `environment`/`secrets`에
추가하면 되는데, **이번 스코프에서는 하지 않는다**(지시 제약 4 — 문서로만 남긴다, 아래 §5).

### 3) 커스텀 메트릭 = 반경(ST_DWithin)·kNN(&lt;-&gt;) 공간쿼리 실행시간 Timer 2개

`PlaceSearchService`에 `MeterRegistry`를 주입하고, `Timer.Sample`로 **PostGIS 쿼리 실행 구간만**(앱
레이어 매핑·날씨 호출 시간은 제외) 감싼다:

- `geuneul.place.search.radius{category}` — 반경 검색(`findWithinRadiusScored`, ST_DWithin geography)
- `geuneul.place.search.nearest{category}` — kNN 최근접(`findNearest`, `<->` 연산자)

SPEC.md의 핵심 차별점("PostGIS 대용량 지리검색(반경/kNN)")을 그대로 계측 대상으로 삼았다. 메트릭을
1~2개로 제한해 bounds는 제외했다(반경·kNN이 더 "간판"에 가깝다: ADR-0012 k6도 이
둘의 p95/p99를 핵심 지표로 이미 보고했다). `category` 태그는 `PlaceCategory` enum(고정 카디널리티,
현재 10개 미만)이라 Prometheus 카디널리티 폭발 위험이 없다(무제한 값을 태그로 쓰지 않는다는
Prometheus 표준 경고를 지킨다).

`management.metrics.distribution.percentiles-histogram`을 `http.server.requests`와 이 두 커스텀
Timer에만 켰다(전체 메트릭에 켜면 저장 비용이 커 과설계) — 히스토그램 버킷이 있어야
`histogram_quantile()`로 p95/p99를 Grafana에서 계산할 수 있다(기본은 count/sum/max뿐이라 평균만
가능). k6(ADR-0012)가 이미 p95/p99를 외부 관점에서 보고하므로, 같은 백분위 기준으로 **내부
관점(Micrometer)**을 맞춰 두 관측이 서로 검증 가능하게 했다.

### 4) 로컬 스택 = Prometheus + Grafana + Tempo(single-binary), docker-compose `observability` 프로필

- **Prometheus**: `/actuator/prometheus`를 15초 간격으로 pull 스크레이프. 백엔드는 이 레포 관례대로
  컨테이너가 아니라 호스트에서 `./gradlew bootRun`으로 뜨므로, `host.docker.internal` +
  `extra_hosts: host-gateway`로 호스트 루프백에 접근한다(Linux Docker Engine 호환, Docker
  Desktop은 기본 지원). **실측**: `docker-compose --profile observability up`으로 스택을 띄우고
  실제 로컬 `./gradlew bootRun`(옵트인 env 포함)을 붙였더니 Prometheus 타깃이 `up`으로 전환,
  `geuneul_place_search_radius_seconds_bucket` 등 커스텀 메트릭이 실제로 스크레이프됨을 확인했다.
- **Tempo(single-binary 모드)**: 별도 OTel Collector 없이 백엔드가 OTLP(gRPC 4317 / HTTP 4318)를
  Tempo에 **직접 push**한다 — Collector를 하나 더 얹는 건 이 규모(단일 서비스, 트래픽 적음)에
  과설계(§0-2)다. 설정은 `grafana/tempo` 공식 예제(`example/docker-compose/single-binary/tempo.yaml`)를
  기반으로 하되, k6 합성 트래픽 생성기·Alloy 게이트웨이·vulture 카오스 테스터·서비스그래프
  metrics_generator(Prometheus remote-write 수신 필요)는 이 레포 스코프에 불필요해 뺐다. **실측**:
  로컬 백엔드에 `OTEL_EXPORTER_OTLP_ENDPOINT=http://localhost:4318/v1/traces`를 주고 API를 호출한 뒤
  Tempo `/api/search`로 실제 트레이스(`rootServiceName=geuneul`, `http get /places` 등)가 수집됨을
  확인했다.
- **Grafana**: Prometheus + Tempo 두 데이터소스를 프로비저닝(`observability/grafana/provisioning/`)하고,
  대시보드 1개(`geuneul-overview.json`, 반경/kNN p95·HTTP 처리율/p95·JVM 힙·CPU 6패널)를 자동
  로드한다. 로컬 전용 익명 Admin(`GF_AUTH_ANONYMOUS_ENABLED=true`)으로 로그인 절차 없이 바로
  접근되게 했다 — **절대 프로덕션에 노출하지 않는다**(이 서비스 자체가 `observability` 프로필이라
  기본 `docker compose up`에 안 딸려오고, ECS에도 존재하지 않는다). **실측**: Grafana API로 두
  데이터소스 상태(Prometheus `OK`)·대시보드 6패널 로드·패널 쿼리(`histogram_quantile(...)`가 실제
  숫자를 반환)까지 전부 확인했다.
- 기존 `docker compose up`(postgres+redis만) 흐름은 **전혀 안 건드렸다** — 관측 스택은
  `profiles: ["observability"]`로 옵트인이라 기존 로컬 개발 습관을 깨지 않는다.

### 5) 프로덕션 반영 = 없음(문서로만 "이렇게 붙인다")

ECS 태스크데프(`ecs.tf`)·SSM(`ssm.tf`)은 이번 PR에서 **건드리지 않는다**. 관리형 트레이스/메트릭
백엔드(Grafana Cloud, Honeycomb, Datadog 등)를 프로덕션에 연결하고 싶어지면:

1. SSM(또는 일반 env)에 `OTEL_EXPORTER_OTLP_ENDPOINT`(관리형 백엔드의 OTLP 수신 URL)·
   `OTEL_TRACING_EXPORT_ENABLED=true`를 다른 비밀들과 동일 패턴(`ecs.tf` secrets/environment)으로
   추가.
2. 관리형 백엔드가 API 키를 요구하면 `management.opentelemetry.tracing.export.otlp.headers`에
   `Authorization=Bearer ...` 형태로 주입(SSM SecureString 값을 env로 해석해 프로퍼티 문자열
   조합 — 코드 변경 없이 가능).
3. `/actuator/prometheus`를 계속 미노출로 둔다면 메트릭은 `OTEL_METRICS_EXPORT_ENABLED=true`로
   OTLP push로 전환(같은 옵트인 스위치).

## 검토한 대안(Alternatives)

| 대안 | 기각 이유 |
|---|---|
| 관리형 Grafana Cloud/Datadog 등 SaaS를 프로덕션에 바로 연결 | 지시가 명시적으로 금지("과설계 금지", "관리형 Grafana Cloud 유료 스택을 프로덕션에 세우지 마라") |
| `management.server.port`로 관리 포트 분리 + SG/타깃그룹 오버라이드 | ALB 헬스체크(`/actuator/health`, 8080)가 깨져 인프라(SG·target group) 변경이 필요 — 지시 제약("ECS 변경은 코드/문서만") 위반. 옵트인 노출로 인프라 변경 없이 같은 안전성 확보 |
| actuator 전용 Basic Auth 계층 신설 | `/admin/**` JWT 인가가 이미 있는데 actuator만을 위한 별도 자격증명 체계를 하나 더 만드는 건 관리 포인트 중복(§0-2) |
| OTel Collector를 백엔드↔Tempo 사이에 추가 | 이 규모(트래픽 적음, 백엔드가 직접 OTLP push 가능)에서 배치/샘플링/라우팅 등 Collector의 이점을 쓸 필요가 없다 — 컨테이너 하나 늘리는 과설계 |
| 전체 메트릭에 percentiles-histogram 활성화 | 저장 비용·카디널리티 증가 — 실제로 관측할 3개(HTTP 전체 + 커스텀 타이머 2개)만 선택적으로 켬 |
| 커스텀 메트릭에 `place_id`/좌표 등 고카디널리티 태그 추가 | Prometheus 카디널리티 폭발 — `category`(고정 enum)만 태그로 사용 |
| `/actuator/prometheus`를 그대로 두고 "일단 문서만 정정" | 이미 라이브에 실존하는 공개 노출(TS-022) — 문서만으로는 실제 위험이 안 없어짐. 코드 기본값을 바꾸는 게 유일하게 확실한 해소 |

## 결과(Consequences)

- **프로덕션 노출 갭이 닫혔다** — 이 PR 배포 전까지 `/actuator/prometheus`가 ALB를 통해 인증 없이
  공개돼 있었음을 실측으로 확인했고(TS-022), 옵트인 기본값 전환으로 다음 배포부터 자동으로 닫힌다
  (ECS가 `MANAGEMENT_EXPOSURE`를 안 주므로 추가 조치 불필요).
- 로컬에서 `docker compose --profile observability up -d`만으로 Prometheus·Grafana·Tempo가 뜨고,
  실제 latency(p95/p99)·트레이스를 볼 수 있다 — 바로 시연 가능한 상태(대시보드·트레이스 검색
  스크린샷을 즉시 뜰 수 있음).
- k6(ADR-0012, 외부 관점 p95)와 Micrometer(이 ADR, 내부 관점 p95)가 같은 반경/kNN 경로를 서로 다른
  각도로 측정 — 두 수치가 로컬 재현 시 서로 근사해야 한다(교차검증 포인트, 향후 성능 회귀 발견에
  유용).
- 프로덕션 ECS/SSM/ALB는 **이번 PR로 전혀 바뀌지 않는다** — 배포해도 인프라 apply가 필요 없다(코드만
  배포하면 끝, 기존 `deploy.yml` 파이프라인 그대로).
- 트레이스 샘플링은 로컬 기본 100%(`OTEL_TRACES_SAMPLING_PROBABILITY:1.0`) — 트래픽이 커지면(관리형
  백엔드 연결 시점) 프로퍼티 값만 낮추면 된다(코드 변경 불필요, §5의 확장점과 동일 메커니즘).

## 근거(References)

- Spring Boot 4.0 공식 OTel 스타터 발표: [OpenTelemetry with Spring Boot](https://spring.io/blog/2025/11/18/opentelemetry-with-spring-boot/) (spring.io, 2025-11-18)
- Maven Central에서 `org.springframework.boot:spring-boot-starter-opentelemetry` 4.0.6 존재 및
  전이 의존성(micrometer-tracing-bridge-otel 1.6.5·opentelemetry-exporter-otlp 1.55.0·
  micrometer-registry-otlp 1.16.5) POM 직접 확인(`repo1.maven.org`).
- 프로퍼티 이름(`management.opentelemetry.tracing.export.otlp.endpoint`·
  `management.otlp.metrics.export.url` 등)은 실제 4.0.6 아티팩트(`spring-boot-opentelemetry`·
  `spring-boot-micrometer-tracing-opentelemetry`·`spring-boot-micrometer-metrics`)의
  `META-INF/spring-configuration-metadata.json`에서 직접 추출·확인(문서 페이지 버전 드리프트
  리스크 회피 — 실제 릴리스된 클래스패스가 유일한 근거).
- actuator 노출·보안 2026 베스트프랙티스(선택적 exposure, 관리 포트 분리, Basic Auth 등 대안 비교):
  웹 검색 다건(Spring Boot 공식 endpoints 문서, "Spring Boot Actuator in Production" 계열 2026 아티클).
- Grafana Tempo 공식 single-binary docker-compose 예제:
  [grafana/tempo/example/docker-compose/single-binary](https://github.com/grafana/tempo/tree/main/example/docker-compose/single-binary)
  (`tempo.yaml`·`docker-compose.yaml`·`grafana-datasources.yaml` 구조를 이 레포 규모로 축소해 채용).
- SPEC.md §0-2(과설계 금지)·§7(Test/Ops: OpenTelemetry/Grafana)·§10 P4(관측성)·
  ADR-0012(k6 부하테스트, 같은 반경/kNN 경로의 p95/p99를 이미 외부 관점에서 측정).
