# 그늘 (Geuneul) — 여름 생존 지도

[English](./README.en.md)

> 폭염이나 비 오는 날 가까운 쉼터와 편의시설을 찾는 지도 서비스입니다. 전국 공공데이터 15만여 곳을 PostGIS로 검색하고, 최근 제보를 반영한 survival_score로 현재 상태를 함께 보여줍니다.

<p>
  <a href="https://geuneul.vercel.app"><img src="https://img.shields.io/badge/웹에서_열기-그늘-163c2f?logo=vercel&logoColor=white" alt="웹에서 열기" /></a>
  <a href="https://geuneul.vercel.app/install"><img src="https://img.shields.io/badge/PWA_설치-안내_페이지-17957e?logo=pwa&logoColor=white" alt="PWA 설치" /></a>
  <a href="https://geuneul.vercel.app/geuneul.apk"><img src="https://img.shields.io/badge/Android_APK-다운로드-3DDC84?logo=android&logoColor=white" alt="Android APK 다운로드" /></a>
</p>

[![Production](https://img.shields.io/badge/production-Vercel_%2B_OCI-17957e)](https://geuneul.vercel.app)

[![Public Data](https://img.shields.io/badge/공공데이터-150k%2B_POI-2f9e44)](#데이터--etl-멱등-적재--지오코딩)
[![Radius p95](https://img.shields.io/badge/반경검색_p95-1.35s_로컬30만-17957e)](./docs/adr/0030-ingest-operational-ledger-deterministic-load.md)
[![Coverage](https://img.shields.io/badge/JaCoCo-87.22%25-17957e)](#기술-스택)
[![ADR](https://img.shields.io/badge/ADR-32-informational)](./docs/adr/README.md)
[![License: MIT](https://img.shields.io/badge/License-MIT-lightgrey.svg)](./LICENSE)

[![CI](https://github.com/ghdtjdwn/geuneul/actions/workflows/ci.yml/badge.svg)](https://github.com/ghdtjdwn/geuneul/actions/workflows/ci.yml)
[![Frontend CI](https://github.com/ghdtjdwn/geuneul/actions/workflows/frontend-ci.yml/badge.svg)](https://github.com/ghdtjdwn/geuneul/actions/workflows/frontend-ci.yml)
[![Deploy (OCI ARM64)](https://github.com/ghdtjdwn/geuneul/actions/workflows/deploy.yml/badge.svg)](https://github.com/ghdtjdwn/geuneul/actions/workflows/deploy.yml)

> 운영 상태(2026-09-03): Vercel 프론트/BFF는 유지하고 AWS의 백엔드와 운영 데이터를 OCI Ampere A1로 이전했다. 출발·도착 인프라 사양, PITR 데이터 구조, 무결성 검증과 컷오버 절차는 [AWS → OCI 무손실 마이그레이션 기록](./docs/OCI-MIGRATION.md)에 공개한다.

## 서비스 화면

### 지도 검색과 장소 탐색

<a href="https://geuneul.vercel.app"><img src="./docs/media/demo-desktop-3pane.png" alt="데스크톱 웹 — 검색, 주변 장소 목록, 지도를 함께 보여 주는 3분할 화면" /></a>

지도에서 현재 위치 주변의 쉼터·화장실·카페를 찾고, 목록과 마커를 함께 보며 장소를 고를 수 있습니다.

### 그늘 경유와 장소 상세

<a href="https://geuneul.vercel.app"><img src="./docs/media/demo-shade-route.png" alt="데스크톱 웹 — 장소 상세와 그늘 경유 경로" /></a>

선택한 장소의 상태·시설·최근 제보를 확인하고, 출발지와 목적지 사이에 그늘 또는 화장실을 경유하는 경로를 찾습니다.

### 모바일 PWA

<p align="center">
  <a href="https://geuneul.vercel.app"><img src="https://github.com/user-attachments/assets/d437d24f-d1f0-4250-a3bf-0762ae36b612" alt="모바일 지도 — 검색과 주변 장소 바텀시트" width="260" /></a>
</p>

모바일에서는 지도와 바텀시트로 주변 장소를 탐색하고, 상황별 추천으로 바로 갈 곳을 찾습니다. 브라우저로 바로 열거나 PWA와 Android 앱으로 설치해 사용할 수 있습니다. 시나리오 추천을 포함한 다른 화면은 [아키텍처 문서](./docs/architecture.md#데모)에 정리했습니다.

---

## 핵심 설계

- 공간 검색은 DB 레이어에서 — 반경 `ST_DWithin` · 최근접 kNN `<->` · bounds 조회를 PostGIS GiST 인덱스로 처리한다. 프로덕션 저부하 k6로 반경 p95를 2.68s→~1.4s로 튜닝했고([ADR-0012](./docs/adr/0012-k6-load-explain-index-tuning.md)), 현재 코드는 fingerprint를 고정한 로컬 30만 places에서 p95 1.35s·실패율 0%를 재검증했다(조건이 달라 개선률은 직접 비교하지 않음, [ADR-0030](./docs/adr/0030-ingest-operational-ledger-deterministic-load.md)).
- 멱등 ETL + 지오코딩 — `source + source_external_id` 자연키로 재실행해도 중복 없는 배치 upsert. 무더위쉼터 60,297 · 공중화장실 52,334 · 도서관 3,551 · 상권 카페/스터디카페 등 전국 표준데이터를 그대로 적재하고, WGS84 결측 좌표는 카카오 지오코딩으로 보완한다. V20 실행 원장은 retry/dead-letter/backfill/freshness를 원본 payload 없이 추적하며, API 부분 응답과 원격 CSV digest 불일치는 DB 변경 전에 실패시킨다([ADR-0030](./docs/adr/0030-ingest-operational-ledger-deterministic-load.md)).
- 실시간 UGC 시공간 스코어링 — 제보(휘발성 상태)/후기(영구 평판) 2단 UGC를 신뢰도 가중으로 `survival_score`에 집계. 제보 급증은 Postgres `LISTEN/NOTIFY` → 멀티 인스턴스 팬아웃 → SSE로, 관심 장소 알림은 `INSERT … RETURNING`으로 정확히 1회 푸시한다([ADR-0016](./docs/adr/0016-realtime-report-surge-listen-notify-sse.md)·[0026](./docs/adr/0026-bookmark-status-change-notification.md)).
- 서버 검증 보안 경계 — private Object Storage 사진은 조건부 PUT과 일회성 claim으로 소유자·용도·실제 object metadata를 확인한 뒤에만 저장하고, 만료 미사용 object는 bounded cleanup한다. JWT login/logout은 같은 user row에서 직렬화하며 DB token version으로 기존 토큰 사본까지 폐기한다. Redis 레이트리밋은 app instance 간 공유한다([ADR-0031](./docs/adr/0031-security-boundaries-session-upload-rate-limit.md)).

review와 photo claim은 하나의 transaction으로 commit/rollback하고, 후기에서 제거된 detached 사진은 현재 참조를 재확인한 뒤 cleanup합니다. V21 이전 claim 없는 사진은 기존 후기에서 제거할 수 있지만 자동 cleanup 대상은 아닙니다. 동시 첫 OAuth login·report cache stale reader·cleanup clock skew는 PostgreSQL lock·generation·DB clock을 각 일관성 경계로 삼습니다.
보안 취약점은 공개 issue 대신 [GitHub private vulnerability reporting](./.github/SECURITY.md)으로 제보해 주세요.

> 스택: Spring Boot 4 · Java 21 · PostgreSQL+PostGIS · Redis · OCI Ampere A1 · OCI Object Storage · Terraform · Next.js(PWA)

## 이용 방법

- 웹: 데스크톱과 모바일 브라우저에서 같은 주소로 이용할 수 있습니다. 데스크톱은 지도와 목록을 나란히, 모바일은 지도와 바텀시트 중심으로 구성했습니다.
- 설치: 서비스 워커와 Web App Manifest를 적용한 PWA입니다. Android에서는 브라우저 설치와 [서명 APK](https://geuneul.vercel.app/geuneul.apk)를, iOS에서는 홈 화면 추가를 지원합니다.
- 구현: 웹과 설치형 앱은 같은 Next.js 클라이언트와 BFF를 사용합니다. 화면 크기에 맞는 UI와 설치 환경에 필요한 서비스 워커·TWA 패키지만 따로 처리했습니다.

## 아키텍처

![아키텍처 다이어그램](./docs/media/architecture.svg)

브라우저의 애플리케이션 API는 동일 오리진 `/api/*` 서버 프록시(BFF)만 호출합니다. 사진 바이너리 PUT만 짧게 서명된 URL로 고정 Object Storage gateway에 전달하며, 그 외 공간 검색과 시공간 집계는 DB의 GiST 인덱스와 SQL 뷰에서 처리합니다([ADR-0004](./docs/adr/0004-frontend-same-origin-proxy.md), [ADR-0032](./docs/adr/0032-oci-arm64-self-hosted-migration.md)).

> 상세 다이어그램과 데모는 [docs/architecture.md](./docs/architecture.md), 현재 서버·서비스·용량은 [docs/OCI-RUNTIME.md](./docs/OCI-RUNTIME.md), 배포는 [DEPLOY.md](./DEPLOY.md), 이전 증거는 [docs/OCI-MIGRATION.md](./docs/OCI-MIGRATION.md)에 있다.

## `survival_score` — 동작 방식

survival_score는 현재 장소 상태를 0~100점과 3색 등급(초록 좋음 / 노랑 보통 / 회색 정보 부족)으로 정리한 값입니다.

```
survival_score = 0.25·distance + 0.20·comfort + 0.20·freshness − 0.15·risk   (+ open_now: 데이터 붙으면 복원)
freshness 버킷:  0~1h=1.0 | 1~3h=0.8 | 오늘=0.6 | 이번주=0.3 | 그 외=0.1
```

- 시공간 집계는 SQL 뷰(`place_report_signals`)가, 가중치 조립·등급은 순수 함수가 담당한다 — 무거운 집계는 DB, 자주 튜닝하는 정책은 테스트 가능한 Java로 분리([ADR-0007](./docs/adr/0007-survival-score-sql-signals-java-compose.md)).
- 제보는 신뢰도(trust) 가중, 만료(휘발성) 제외, 후기(영구 평판)와 분리.
- 없는 성분(운영시간 `open_now`)은 지어내지 않고 재정규화 — 데이터가 붙으면 가중치 복원만으로 additive 확장.
- 추천은 같은 함수를 시나리오 가중치로 재사용한다([ADR-0008](./docs/adr/0008-recommendations-scenario-weighted-ranking.md)).

## 데이터 · ETL (멱등 적재 + 지오코딩)

전국 공공 표준데이터를 멱등(idempotent) 하게 적재한다 — 같은 소스를 두 번 넣어도 `source + source_external_id` 자연키 upsert라 중복이 없고, 스냅샷에서 사라진 행은 soft-delete로 비활성화된다. 공중화장실은 2025-02 이후 WGS84 좌표 미제공 → 카카오 로컬 API로 주소→좌표를 보완하고 결과를 저장한다(멱등·rate limit 회피). one-off 실행 결과는 DB 원장에 남아 상시 API의 로컬 Prometheus/Grafana 대시보드에서 freshness·부분 실패·재실행 계보를 확인할 수 있다. 상세 흐름은 [docs/architecture.md](./docs/architecture.md#데이터--etl).

## 빠른 시작 (로컬)

```bash
# 1) 인프라 — PostGIS + Redis
docker compose up -d

# 2) 백엔드 — API 문서는 로컬에서만 명시적으로 활성화
cd backend && SPRINGDOC_ENABLED=true ./gradlew bootRun

# 3) 프론트 — http://localhost:3000  (Kakao JS 키 없으면 지도는 placeholder, 데이터는 정상)
cd frontend && pnpm install && pnpm dev
```

공공데이터 적재는 멱등(재실행해도 중복 없음):

```bash
cd backend
./gradlew bootRun --args='--ingest.source=cooling_shelter --ingest.file=/path/무더위쉼터.csv --ingest.charset=MS949'
```

## API 맛보기

```bash
# 반경 검색 — 가까운 순 + 거리(m) + survival 배지
GET /places?lat=37.4963&lng=126.9575&radius=1000

# 뷰포트(bounds) 마커 — 무더위쉼터
GET /places?bounds=126.93,37.49,126.97,37.52&category=COOLING_SHELTER

# 최근접(kNN) — 서울 밖 어디서나 동작 (예: 부산)
GET /places/nearest?lat=35.2133&lng=129.0157&category=COOLING_SHELTER&limit=3

# 시나리오 추천 — 거리+실시간 상태로 "지금 갈만한 순" (scenario: rest30|restroom|rain|focus|longstay)
GET /recommendations?lat=37.4963&lng=126.9575&scenario=restroom
#   → 각 결과 = 장소(survival 배지) + matchScore(적합도) + reason(제보 요약)

# 휘발성 제보 (익명, 타입별 TTL로 자동 만료 · 분당 3·시간당 10 레이트리밋)
#   lat/lng를 함께 보내면 장소 100m 이내일 때 "방문 인증"(verified)
POST /reports   {"placeId":1,"reportType":"COOL","comment":"에어컨 빵빵","lat":37.4963,"lng":126.9575}
GET  /places/1/reports

# 그늘/화장실 경유 경로 — 현재 위치→도착 사이 우회 최소 쿨링쉼터·화장실 1곳 경유 (ADR-0027)
GET /routes/shade?fromLat=37.50&fromLng=126.95&toLat=37.51&toLng=126.96

# 실시간 제보 급증 알림 — 뷰포트 스냅샷 + SSE 스트림 (ADR-0016)
GET /alerts/surge?bounds=126.93,37.49,126.97,37.52
GET /alerts/stream            # text/event-stream (SSE)
```

## 기술 스택

| | |
|---|---|
| Backend | Spring Boot 4 · Java 21 · PostgreSQL + PostGIS(Hibernate Spatial + JTS) · Flyway · Redis |
| Frontend | Next.js 16.2.12(App Router) · TypeScript · Tailwind v4 · TanStack Query · Kakao Maps · Serwist(PWA) — `frontend/` ([README](./frontend/README.md)) |
| Infra | Vercel frontend/BFF · OCI Ampere A1 ARM64 rootless Compose · OCI Object Storage · Terraform · AWS ECS/RDS 이전 원본 |
| Test/Ops | Testcontainers 2(실 PostGIS) · JaCoCo(게이트 70%) · DB/요청 seed 고정 k6+JSON summary · Prometheus/Grafana · gitleaks · CodeQL · Dependabot · 로컬 전용 Swagger |

## 문서

- 프로젝트 스펙(목표·범위·ERD·API): [`docs/SPEC.md`](./docs/SPEC.md)
- 전체 기능·구현 방식·기술 스택: [`docs/FEATURES.md`](./docs/FEATURES.md)
- 아키텍처·데모: [`docs/architecture.md`](./docs/architecture.md) · 의사결정 기록(ADR): [`docs/adr/`](./docs/adr) (0001–0032, [색인](./docs/adr/README.md))
- 트러블슈팅: [브라우저 위치 좌표 지속 저장 제거](./docs/troubleshooting/location-storage-retention.md)
- 배포(OCI): [`DEPLOY.md`](./DEPLOY.md) · 서버 구조·용량: [`docs/OCI-RUNTIME.md`](./docs/OCI-RUNTIME.md)
- AWS → OCI 무손실 이전 사양·실행 기록: [`docs/OCI-MIGRATION.md`](./docs/OCI-MIGRATION.md) · [ADR-0032](./docs/adr/0032-oci-arm64-self-hosted-migration.md)
- 디자인·API 계약 레퍼런스: [`docs/design-brief.md`](./docs/design-brief.md) · 프론트엔드: [`frontend/README.md`](./frontend/README.md)

<details>
<summary><b>구현 이력 (W0 → P5, AWS 항목은 2026-09-03 이전 운영 이력)</b></summary>

- W0 — Spring Boot 4 + PostGIS/Flyway + Testcontainers CI + AWS(ECS Fargate·RDS·Terraform·OIDC) 배포 파이프라인.
- P1 — 반경/kNN/bounds 공간검색 API 라이브 + 공공데이터 멱등 인제스천(쉼터 100건 + 공중화장실 59,768행 파서 → 카카오 지오코딩으로 52,334건 좌표 보완 + 전국 도서관 3,551건).
- P2 · UGC+인증 — 휘발성 제보(11타입·타입별 TTL, 레이트리밋 XFF/OOM 하드닝 TS-008) · 소셜 로그인(카카오/구글 OAuth2+JWT, BFF code 교환) · 후기(review) 영구 평판(로그인, 장소당 1건 upsert) · 사진 presign(S3 SigV4, image 화이트리스트) · trust_score 실배선(로그인 제보 user_id 가중, V6) · 모더레이션(신고 + ADMIN 검수 큐, V7).
- P3 · 스코어·추천·AI·데이터 — `survival_score`(SQL 뷰 + 순수 함수, 마커 3색, [ADR-0007](./docs/adr/0007-survival-score-sql-signals-java-compose.md)) · 시나리오 추천 [ADR-0008](./docs/adr/0008-recommendations-scenario-weighted-ranking.md) · 날씨(기상청 초단기실황 + Redis TTL 캐시) + comfort 복원([ADR-0009](./docs/adr/0009-weather-comfort-additive-restore.md)) · AI 한줄요약(프로바이더 중립 OpenAI 호환 클라이언트, graceful degradation, [ADR-0010](./docs/adr/0010-ai-summary-openrouter-provider.md)) · 공부공간 데이터 확장(CAFE/STUDY_CAFE·soft-delete, V5, [ADR-0006](./docs/adr/0006-study-space-coverage-expansion.md)) · AWS 운영 당시 주기동기화(EventBridge→ECS RunTask + advisory lock, [ADR-0011](./docs/adr/0011-scheduled-public-data-sync.md); OCI 대체 timer는 후속).
- P4 · 성능·관측성 — k6 부하테스트 + EXPLAIN 인덱스 튜닝(반경/kNN/bounds GiST 사용 실증, V8 만료제보 인덱스, [ADR-0012](./docs/adr/0012-k6-load-explain-index-tuning.md)) · AWS 운영 당시 ECS Service Auto Scaling(CPU target-tracking, [ADR-0013](./docs/adr/0013-ecs-service-autoscaling.md))과 CloudFront 무료 HTTPS([ADR-0015](./docs/adr/0015-cloudfront-default-domain-https.md)) · 관측성(Micrometer/Prometheus + OTel 트레이싱 + 로컬 Grafana/Tempo, [ADR-0014](./docs/adr/0014-observability-otel-micrometer-grafana.md)).
- P4 · 실시간·커뮤니티 — 실시간 제보 급증 알림(Postgres LISTEN/NOTIFY→SSE, 멀티 인스턴스 팬아웃, V9, [ADR-0016](./docs/adr/0016-realtime-report-surge-listen-notify-sse.md)) · 시간대별 혼잡 파생(자체 popular-times) · GPS 방문 인증(`verified`, ST_DWithin 100m, V10) · 추천 시나리오 focus/longstay · 후기 커뮤니티(댓글·리액션, V11) · 모더레이션 확장(신고 RESOLVED→콘텐츠 숨김, V12) · JaCoCo 0.70 게이트.
- 데이터 커버리지(2026-07) — 화장실 52,334 · 무더위쉼터 60,297(냉방 정보 57,070) · 상권 카페/스터디카페(서울 + 6대 광역시 + 9개 도시) · 도서관 3,551. 총 15만+곳.
- 기능 확장(PR #61~#99) — 시설 comfort SQL 통합(V13, [ADR-0017](./docs/adr/0017-place-feature-comfort-signal.md)) · 쉼터 냉방 백필 · 급증 SSE 프론트 · popular-times 히트맵 · bookmarks(V14) · 알림(V15, [ADR-0018](./docs/adr/0018-notifications-in-app-center-surge-reuse.md)) · 화장실/그늘 경유 경로([ADR-0019](./docs/adr/0019-routes-toilet-waypoint-external-directions.md)·[0027](./docs/adr/0027-shade-waypoint-route.md), 도로 폴리라인 [ADR-0021](./docs/adr/0021-road-polyline-kakao-navi-key-reuse.md)) · Web Push([ADR-0022](./docs/adr/0022-web-push-zerodep-vapid-feature-gated.md)) · 팔로우(V17·[ADR-0023](./docs/adr/0023-commons-safe-follow.md)) · 그늘/비 corridor([ADR-0024](./docs/adr/0024-shade-rain-route-corridor-overlay.md)) · 부하 기반 튜닝(V18·[ADR-0025](./docs/adr/0025-scale-prep-load-based-tuning.md)) · 데스크톱 3분할 · 신고/모더레이션 프론트 · a11y 포커스 트랩 · 관심장소 상태변화 알림([ADR-0026](./docs/adr/0026-bookmark-status-change-notification.md)).
- P5 · 배포 — PWA 설치([/install](https://geuneul.vercel.app/install)) — 안드로이드 WebAPK 원탭 + 다운로드 서명 APK([`/geuneul.apk`](https://geuneul.vercel.app/geuneul.apk), Bubblewrap TWA + [`assetlinks.json`](https://geuneul.vercel.app/.well-known/assetlinks.json) 도메인 검증) + iOS 홈 화면 추가. [개인정보처리방침](https://geuneul.vercel.app/privacy).

</details>

## License

[MIT](./LICENSE) © 2026 ghdtjdwn
