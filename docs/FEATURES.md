# 그늘 기능 가이드

이 문서는 사용자가 볼 수 있는 기능, 구현 방식, 데이터 흐름을 한곳에 모은 안내서다. 화면 설명만이 아니라 어떤 데이터와 API를 쓰는지, 로그인이나 권한이 필요한지까지 함께 적는다.

서비스 주소: [geuneul.vercel.app](https://geuneul.vercel.app)

## 1. 서비스가 해결하는 일

그늘은 더위·비·급한 용무처럼 지금의 상황에 맞춰 가까운 쉼터와 편의시설을 찾는 지도 서비스다. 무더위쉼터, 공중화장실, 도서관, 카페·스터디카페 등 15만여 공공·상권 장소를 지도에서 찾고, 최근 제보와 거리·시설 정보를 조합해 현재 가기 좋은 곳을 보여 준다.

| 구분 | 내용 |
|---|---|
| 지도와 장소 데이터 | PostGIS에 저장한 전국 장소 데이터와 Kakao Maps 지도 |
| 현재 상태 | 만료되는 현장 제보와 영구 후기에서 계산한 `survival_score` |
| 빠른 의사결정 | 화장실, 잠깐 휴식, 비 피하기, 공부, 오래 머무르기 시나리오 추천 |
| 이용 환경 | 데스크톱 웹, 모바일 웹, 설치형 PWA, Android TWA/APK |

## 2. 지도에서 장소 찾기

### 현재 위치와 지도 이동

- 현재 위치 버튼을 누르면 브라우저 위치 권한을 요청하고, 좌표를 받은 뒤 지도를 해당 위치로 이동한다.
- 첫 진입에서 권한창을 강제로 띄우지 않는다. 이미 허용된 권한이면 최근 위치를 복원하고, 허용하지 않았거나 위치를 못 받으면 기본 위치와 안내 문구를 사용한다.
- 지도 이동 뒤에는 현재 보이는 영역(bounds)을 기준으로 마커를 다시 조회한다.
- 지도 SDK가 동일한 중심 좌표 변경을 무시하는 경우를 대비해, 현재 위치 버튼은 지도 인스턴스의 `panTo`를 직접 호출한다.

구현: `navigator.geolocation` → React 위치 컨텍스트 → Kakao Maps 인스턴스. 장소 조회 API는 `GET /places`와 `GET /places/nearest`다.

### 검색, 필터, 목록

| 기능 | 사용 방법 | 구현 |
|---|---|---|
| 지역·장소 검색 | 검색창에 두 글자 이상 입력하고 결과를 선택 | 300ms 디바운스 후 Kakao 키워드 검색을 BFF로 호출, 선택 좌표로 지도 이동 |
| 카테고리 필터 | 전체, 무더위쉼터, 공중화장실, 음수대, 카페 등 선택 | `category` 조건과 지도 bounds를 함께 전달해 DB에서 필터링 |
| 주변 목록 | 현재 위치 주변 장소를 거리순으로 확인 | 반경 검색 결과를 데스크톱 사이드바·모바일 바텀시트에 같은 데이터로 표시 |
| 마커와 상세 | 목록 또는 지도 마커 선택 | `GET /places/{id}`로 장소·시설·현재 상태를 읽어 상세 패널 표시 |

공간 검색은 애플리케이션에서 모든 좌표를 계산하지 않는다. PostgreSQL/PostGIS의 `ST_DWithin`으로 반경을 찾고, 최근접 검색은 kNN `<->`, 화면 범위 검색은 GiST 인덱스를 이용한다. 쿼리 계획과 k6 측정으로 인덱스 사용을 검증했다. 자세한 결정은 [ADR-0012](./adr/0012-k6-load-explain-index-tuning.md)에 있다.

## 3. 장소 상태와 추천

### survival_score

장소 상세와 목록에는 현재 상태를 세 단계로 표시한다.

| 상태 | 의미 |
|---|---|
| 지금 좋음 | 최근 제보 기준으로 이용하기 좋은 상태 |
| 보통 | 이용 가능하지만 위험·혼잡·정보 신선도 등을 함께 봐야 하는 상태 |
| 정보 부족 | 유효한 최근 제보가 없어 상태를 단정할 수 없는 상태 |

점수는 거리, 시설 쾌적도, 제보 신선도, 위험 신호를 조합한다. 무거운 시공간 집계는 SQL 뷰 `place_report_signals`가 처리하고, 가중치 조립과 등급은 테스트 가능한 순수 Java 함수가 맡는다. 운영시간처럼 원본 데이터가 없는 성분은 임의로 채우지 않고, 있는 성분의 가중치를 다시 정규화한다.

```text
survival_score = 0.25·distance + 0.20·comfort + 0.20·freshness − 0.15·risk
```

관련 API: `GET /places`, `GET /places/{id}`, `GET /recommendations`
관련 문서: [ADR-0007](./adr/0007-survival-score-sql-signals-java-compose.md), [ADR-0017](./adr/0017-place-feature-comfort-signal.md)

### 상황별 빠른 추천

모바일의 ‘지금 급해요’ 화면과 추천 API는 아래 시나리오를 제공한다.

| 시나리오 | 찾는 장소 |
|---|---|
| 화장실 급함 | 가까운 공중화장실 |
| 잠깐 쉬어갈 곳 | 짧게 쉴 수 있는 실내·쉼터 |
| 비 피할 곳 | 실내 또는 지하 공간 |
| 집중해서 공부 | 조용한 카페·스터디 공간 |
| 오래 버틸 곳 | 더위를 피하며 오래 머무를 수 있는 장소 |

`GET /recommendations?lat=&lng=&scenario=`은 거리와 `survival_score`를 기본으로 하고, 시나리오마다 다른 가중치를 적용한다. 결과에는 적합도와 최근 제보 기반의 이유를 함께 돌려준다. 추천이 별도 데이터 모델을 중복하지 않고 같은 점수 조합 함수를 재사용하는 이유는 [ADR-0008](./adr/0008-recommendations-scenario-weighted-ranking.md)에 정리했다.

### 날씨와 AI 요약

- 기상청 초단기실황을 조회해 현재 날씨와 체감 조건을 반영한다.
- 날씨 응답은 Redis에서 30분 동안 캐시한다. 캐시가 만료된 다음 요청에서 새 데이터를 가져온다.
- 장소 상세의 AI 한 줄 요약은 최근 유효 제보가 있을 때만 생성한다. AI 제공자가 응답하지 않아도 핵심 지도·제보 기능은 계속 동작하며, 안내 문구로 자연스럽게 대체한다.
- AI 호출은 특정 회사 SDK에 묶이지 않은 OpenAI 호환 Chat Completions 인터페이스로 분리했다.

관련 API: `GET /weather`
관련 문서: [ADR-0009](./adr/0009-weather-comfort-additive-restore.md), [ADR-0010](./adr/0010-ai-summary-openrouter-provider.md)

## 4. 길찾기

장소 상세에서 현재 위치와 선택 장소 사이의 경로를 볼 수 있다.

| 기능 | 동작 |
|---|---|
| 화장실 들르기 | 출발지와 목적지 사이에서 우회가 가장 적은 공중화장실을 찾아 경유지로 제안 |
| 그늘 경유 | 같은 방식으로 무더위쉼터를 경유지로 제안 |
| 도로 폴리라인 | Kakao 길찾기 응답이 가능하면 실제 도로 선을 지도에 표시 |
| 폴백 | 외부 길찾기를 못 쓰는 경우에도 PostGIS가 고른 경유지와 직선 요약을 제공 |

경유지를 고르는 것은 자체 도로 라우팅 엔진이 아니라 PostGIS 공간 검색이다. 도로 선은 외부 길찾기 API를 사용하고, API 키가 없거나 응답을 못 받는 경우 기능 전체를 실패시키지 않는다.

관련 API: `GET /routes/toilet`, `GET /routes/shade`
관련 문서: [ADR-0019](./adr/0019-routes-toilet-waypoint-external-directions.md), [ADR-0021](./adr/0021-road-polyline-kakao-navi-key-reuse.md), [ADR-0027](./adr/0027-shade-waypoint-route.md)

## 5. 현장 제보와 커뮤니티

### 현장 제보

제보는 장소의 지금 상태를 빠르게 반영하기 위한 휘발성 데이터다. 로그인하지 않아도 작성할 수 있고, 로그인 사용자가 익명을 해제하면 신뢰도 맥락을 함께 반영한다.

- 자리 여유, 혼잡, 시원함, 위험, 미끄러움 등 11개 상태 유형을 선택할 수 있다.
- 제보 유형마다 TTL이 있어 시간이 지나면 자동으로 만료된다. 만료된 제보는 현재 상태 점수에서 제외한다.
- 위치를 함께 보내고 장소 100m 안에 있으면 방문 인증(`verified`)으로 기록한다.
- 사진은 애플리케이션 서버를 거치지 않고 OCI Object Storage presigned URL로 직접 업로드한다. 서버는 업로드 권한과 메타데이터만 발급한다.
- 제보 작성은 분당·시간당 제한을 두고, 원래 클라이언트 IP를 BFF에서 보존해 우회 가능성을 낮춘다.

관련 API: `POST /reports`, `GET /places/{id}/reports`, `GET /places/{id}/popular-times`, `POST /photos/presign`

### 후기, 댓글, 반응

후기는 장소의 장기적인 평판을 남기는 로그인 기능이며, 휘발성 제보와 분리한다.

- 한 사용자는 장소마다 후기 하나를 작성하거나 수정할 수 있다. 중복 글을 만들지 않는 upsert 방식이다.
- 후기에는 사진을 붙일 수 있고, 다른 사용자는 댓글과 ‘유용해요’ 반응을 남길 수 있다.
- 작성자 닉네임을 누르면 공개 프로필과 작성한 공개 후기를 볼 수 있다.
- 프로필 팔로우와 내 팔로잉 목록을 제공한다.
- 마이페이지에서는 내가 쓴 후기·댓글·반응을 모아 확인한다.

관련 API: `POST /reviews`, `GET /places/{id}/reviews`, `POST/GET /reviews/{id}/comments`, `POST/DELETE /reactions`, `GET /users/{id}`, `POST/DELETE /users/{id}/follow`, `GET /me/*`

### 관심 장소, 신고, 관리자 검수

- 로그인 사용자는 장소를 관심 장소로 저장하고 마이페이지에서 다시 열 수 있다.
- 제보와 후기는 사유와 선택 코멘트를 넣어 신고할 수 있다. 같은 사용자가 같은 대상을 중복 신고하면 막는다.
- ADMIN은 신고 큐에서 대기 건을 확인하고, 해결 처리하면 대상 콘텐츠를 숨기거나 반려할 수 있다.

관련 API: `POST /bookmarks`, `DELETE /bookmarks/{placeId}`, `GET /me/bookmarks`, `POST /flags`, `GET /admin/flags*`, `POST /admin/flags/{id}/resolve`

## 6. 실시간 알림

### 제보 급증

한 장소 주변에 제보가 짧은 시간에 몰리면 지도 화면에 중립적인 급증 배너를 띄운다.

1. 제보 저장 후 PostgreSQL `LISTEN/NOTIFY`로 이벤트를 발행한다.
2. 실행 중인 app instance가 이벤트를 받아 구독 중인 브라우저에 SSE로 전달한다.
3. SSE가 끊기거나 지원되지 않는 환경은 45초 주기의 스냅샷 폴링으로 보완한다.

관련 API: `GET /alerts/surge`, `GET /alerts/stream`
관련 문서: [ADR-0016](./adr/0016-realtime-report-surge-listen-notify-sse.md)

### 개인 알림과 Web Push

로그인 사용자는 마이페이지에서 다음 규칙을 켤 수 있다.

| 알림 규칙 | 조건 |
|---|---|
| 내 주변 제보 급증 | 현재 위치 반경에서 제보가 급증 |
| 관심 장소 소식 | 저장한 장소에 제보가 급증 |
| 폭염 피난 추천 | 현재 날씨와 위치를 기준으로 가까운 무더위쉼터 추천 |

알림 센터는 발송 이력과 읽지 않은 수를 보관한다. 브라우저가 지원하면 VAPID Web Push 구독을 등록해 앱을 닫은 상태에서도 OS 배너를 보낼 수 있다. iOS는 설치형 PWA에서만 푸시를 지원한다. VAPID 키가 배포 환경에 설정되지 않으면 해당 기능은 숨겨져 기본 기능에 영향을 주지 않는다.

관련 API: `GET/POST/PATCH/DELETE /notifications*`, `GET /push/public-key`, `POST /push/subscribe`, `POST /push/test`
관련 문서: [ADR-0018](./adr/0018-notifications-in-app-center-surge-reuse.md), [ADR-0020](./adr/0020-heat-escape-notification-on-demand-weather.md), [ADR-0022](./adr/0022-web-push-zerodep-vapid-feature-gated.md), [ADR-0026](./adr/0026-bookmark-status-change-notification.md)

## 7. 로그인과 권한

지도 탐색·장소 조회·익명 제보는 로그인 없이 쓸 수 있다. 후기를 쓰거나, 관심 장소·팔로우·알림·신고를 쓰려면 로그인해야 한다.

| 항목 | 방식 |
|---|---|
| 로그인 제공자 | Kakao OAuth2, Google OAuth2 |
| 세션 | BFF가 인증 코드를 교환하고 httpOnly JWT 쿠키를 관리 |
| CSRF 방어 | 로그인 시작 시 state를 httpOnly 쿠키로 저장하고 콜백에서 대조 |
| 백엔드 전달 | BFF가 필요한 요청에만 JWT를 Bearer 토큰으로 전달 |
| 역할 | 일반 사용자와 ADMIN 권한을 분리 |

관련 API: `GET /api/auth/kakao`, `GET /api/auth/google`, `GET /api/me`, `POST /auth/kakao`, `POST /auth/google`

## 8. 설치와 접근성

| 항목 | 내용 |
|---|---|
| PWA | Service Worker와 Web App Manifest로 오프라인 셸과 설치 흐름 제공 |
| Android | 브라우저 설치, WebAPK, Bubblewrap TWA 기반 서명 APK 다운로드 지원 |
| iOS | Safari 공유 메뉴의 ‘홈 화면에 추가’ 안내 제공 |
| 오프라인 | 마지막으로 캐시된 PWA 셸과 오프라인 안내 화면 제공 |
| 반응형 UI | 데스크톱은 지도·목록·상세를 함께, 모바일은 지도·바텀시트·하단 탭을 중심으로 구성 |
| 접근성 | 모달 포커스 트랩, 키보드 이동, 명시적인 aria-label, iOS 확대 방지를 위한 모바일 입력 글자 크기 적용 |

설치 방법은 [설치 안내](https://geuneul.vercel.app/install), 구현은 [frontend README](../frontend/README.md)에서 확인할 수 있다.

## 9. 데이터 수집과 갱신

### 데이터 적재

공공데이터는 `source + source_external_id` 자연 키로 upsert한다. 같은 파일이나 API 응답을 다시 적재해도 중복이 생기지 않고, 새 전체 스냅샷에서 사라진 행은 soft-delete로 비활성화한다.

| 원본 | 대상 | 갱신 방식 |
|---|---|---|
| data.go.kr 전국도서관표준데이터 API | 도서관 | 기존 데이터는 보존. AWS 월별 scheduler 제거 뒤 OCI 대체 timer가 아직 없어 자동 갱신은 일시 중단 |
| GitHub Release CSV | 무더위쉼터, 공중화장실 | 새 snapshot의 digest·완전성을 검증한 뒤 OCI one-off ingestion을 통제 실행 |
| 상권정보 API | 카페, 스터디카페 | 수집 범위와 API 이용 조건을 확인한 뒤 OCI one-off ingestion을 통제 실행 |
| 기상청 API | 날씨 | 요청 시 조회, Redis TTL 30분 |
| 서비스 사용자 | 현장 제보·후기 | API 저장 직후 반영 |

공중화장실처럼 원본에 WGS84 좌표가 없는 경우 Kakao Local API로 주소를 지오코딩하고 결과를 저장한다. 인제스천 작업은 PostgreSQL advisory lock으로 보호해 수동 작업과 스케줄 작업이 겹쳐도 동시에 soft-delete 하지 않는다.

관련 문서: [DEPLOY.md](../DEPLOY.md), [ADR-0002](./adr/0002-idempotent-ingestion-upsert.md), [ADR-0003](./adr/0003-geocoding-pipeline.md), [ADR-0006](./adr/0006-study-space-coverage-expansion.md), [ADR-0011](./adr/0011-scheduled-public-data-sync.md)

## 10. 기술 구조와 스펙

### 런타임 구조

```text
Browser / PWA
  → Vercel Next.js BFF (/api/*, same origin)
  → OCI shared Caddy HTTPS → rootless Spring Boot API
  → PostgreSQL + PostGIS / Redis / OCI Object Storage
```

- 브라우저는 OCI backend를 직접 호출하지 않고, 같은 도메인의 Next.js Route Handler만 호출한다. TLS와 인증 계약을 한곳에서 처리하고 OCI origin과 외부 API 키를 브라우저에 노출하지 않는다.
- 공간 검색과 집계는 rootless PostgreSQL 16 + PostGIS의 GiST 인덱스와 SQL 뷰에서 처리한다.
- Redis는 날씨와 조회 cache·분산 rate limit을, private OCI Object Storage는 제보·후기 사진과 DB backup을 맡는다.
- 기존 Caddy가 공개 HTTPS를 종단하고 Geuneul의 private host bind로 proxy한다. PostgreSQL과 Redis에는 public port가 없다.
- Terraform은 Object Storage bucket·versioning·lifecycle·IAM을 선언하고, Compose와 systemd가 app/data/health/backup을 운영한다.

### 사용 기술

| 영역 | 스펙 |
|---|---|
| Backend | Spring Boot 4, Java 21, Spring MVC, Spring Security, JPA/Hibernate Spatial, Flyway, Micrometer, OpenTelemetry |
| Database | PostgreSQL 16, PostGIS, GiST 인덱스, SQL 뷰, PostgreSQL LISTEN/NOTIFY |
| Cache and storage | Redis 7.4, OCI Object Storage S3 compatibility presigned URL |
| Frontend | Next.js 16 App Router, TypeScript, Tailwind CSS v4, TanStack Query, Kakao Maps, Serwist |
| Infrastructure | Vercel, OCI Ampere A1 ARM64 rootless Compose, Caddy, Object Storage, Terraform |
| Delivery | GitHub Actions ARM64 archive, restricted forced-command SSH gateway, release checksum·revision 검증 |
| Testing | JUnit, Testcontainers 실제 PostGIS, JaCoCo line coverage 70% 게이트, k6, gitleaks |

## 11. API 찾아보기

프론트엔드는 `/api/*` BFF 경로를 사용한다. Swagger/OpenAPI는 운영에서 비활성화하고 로컬에서
`SPRINGDOC_ENABLED=true`로 실행할 때만 요청·응답 스키마 확인에 사용한다.

- 로컬 Swagger: `http://localhost:8080/swagger-ui.html` (`SPRINGDOC_ENABLED=true` 필요)
- 라이브 사용자 경로: [Vercel production](https://geuneul.vercel.app)
- OCI host·container·storage 상세: [OCI-RUNTIME.md](./OCI-RUNTIME.md)
- 세부 아키텍처와 ETL: [architecture.md](./architecture.md)
- 기술 선택의 근거: [ADR 색인](./adr/README.md)

## 12. 검증 기준

- 백엔드 CI는 실제 PostgreSQL/PostGIS Testcontainers에서 공간 쿼리와 인제스천을 검증한다.
- JaCoCo line coverage가 70% 아래로 내려가면 빌드가 실패한다.
- 프론트엔드는 TypeScript 검사, ESLint, Next.js 프로덕션 빌드를 실행한다.
- k6 부하 테스트와 `EXPLAIN`으로 반경·최근접·bounds 쿼리의 성능과 인덱스 사용을 확인한다.
- gitleaks가 커밋의 비밀 유출을 검사한다.

문서의 내용은 현재 `main` 브랜치 구현을 기준으로 한다. 외부 API 키·VAPID 키처럼 배포 환경 설정에 따라 켜지는 기능은 해당 설정이 없을 때 안전하게 비활성화된다.
