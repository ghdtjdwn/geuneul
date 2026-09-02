# ADR-0012. 간판(PostGIS 대용량 지리검색) 성능 실증 — k6 부하테스트 + EXPLAIN 인덱스 튜닝

- 상태: 승인 (2026-07-10)
- 관련: `perf/k6/spatial_load.js`, `perf/seed/seed_synthetic_places.sql`, `perf/explain/explain_spatial_queries.sql`,
  `perf/explain/RESULTS.md`, `backend/.../db/migration/V8__reports_expires_index.sql`,
  `PlaceRepository`(반경/kNN/bounds/스코어드 쿼리), `place_report_signals` 뷰
- 선행: [ADR-0001](0001-geometry-storage-geography-function-index.md)(geometry 저장 + geography 함수 인덱스),
  [ADR-0007](0007-survival-score-sql-signals-java-compose.md)(place_report_signals 뷰), SPEC.md §7·§10 P4·§11(간판)

## 문제(Context)

SPEC.md는 이 프로젝트의 **간판**을 "PostGIS 대용량 지리검색(반경/kNN)"으로 못박는다(§11). P1~P3에서
반경(`ST_DWithin` geography)·kNN(`<->`)·bounds(`&&`) 쿼리와 survival_score 뷰 조인을 구현했지만, **"실제로
GiST 인덱스를 타서 빠른가"를 부하로 증명한 적은 없었다.** P4의 첫 산출물(§10)이 "k6 부하테스트 + EXPLAIN
인덱스 튜닝"인 이유다. 간판은 "빠르다고 주장"이 아니라 "부하 수치 + 실행계획"으로 서야 한다.

세 가지를 확인해야 했다:
1. 세 공간쿼리(반경/kNN/bounds)와 스코어드/추천 쿼리가 **GiST 인덱스**를 타는가(§0-4 "전체스캔 금지").
2. 부하에서 p95/p99·처리량이 어떤가.
3. Seq Scan이 잡히면 인덱스/쿼리를 튜닝한다(필요 시 Flyway 인덱스 추가).

## 실행 환경 — 정직한 한계 명시

- 부하·EXPLAIN은 **로컬 docker-compose PostGIS 16-3.4**에만 걸었다(SPEC.md 안전 제약 — 프로덕션 ALB 고부하 금지).
- 데이터: 합성 시드(`perf/seed/seed_synthetic_places.sql`)로 **places 30만 + reports 21만**(유효 1만·만료 20만).
  분포는 실사용을 반영해 수도권 70% + 전국 30%. TOILET을 최다(30%)로 둬 프로덕션(공중화장실 46,897건 우세)을 근사.
- 🔴 **에뮬레이션 한계:** 이 맥(arm64)의 colima는 2 vCPU고, `postgis/postgis:16-3.4`가 **amd64 이미지라 qemu로
  에뮬레이트**된다(compose가 platform mismatch 경고). 따라서 **절대 지연(ms)은 네이티브 RDS보다 크게 부풀려져
  있다.** 이 문서의 수치는 "네이티브 프로덕션 성능"이 아니라 (a) **실행계획(인덱스 사용)** — 에뮬레이션과 무관하게
  결정적, (b) **before/after 비율** — 동일 환경 비교라 유효, (c) **동일 환경 부하 특성(처리량 상한·포화 곡선)**
  으로만 읽어야 한다. 추정으로 프로덕션 수치를 지어내지 않는다(§0-B).
- TS-009 계열(colima Testcontainers 이슈)와 별개로, **이번에는 colima를 직접 기동해 실 PostGIS에 부하·EXPLAIN을
  실측**했다(엔드투엔드 IT는 여전히 CI에 위임).

## 결정 1 — EXPLAIN ANALYZE로 GiST 인덱스 사용 확증

`PlaceRepository`의 네이티브 쿼리를 1:1로 옮긴 `perf/explain/explain_spatial_queries.sql`을 30만 데이터에
실행. 전문 요약은 `perf/explain/RESULTS.md`, 핵심:

| 쿼리 | 실행계획 핵심 | 인덱스 |
|---|---|---|
| 반경(`ST_DWithin` geography) | `Bitmap Index Scan on idx_places_geom_geography` (`geography(geom) && _st_expand(...)`) | ✅ GiST(geography), ADR-0001 |
| kNN(`<->`) | `Index Scan using idx_places_geom_geography` + KNN 정렬, LIMIT 5 actual ~30ms | ✅ GiST(geography) KNN |
| kNN + 카테고리 | 동일 KNN 인덱스 스캔 + `Rows Removed by Filter` | ✅ GiST(geography) KNN |
| bounds(`&&`) 선택적 박스 | `Bitmap Index Scan on idx_places_geom` (강원 산간 박스 1.7ms) | ✅ GiST(geometry) |
| 추천(카테고리 집합) | `BitmapAnd(idx_places_geom_geography, idx_places_category)` | ✅ GiST + btree |
| 단건 상세 | `Index Scan using idx_places_active` | ✅ 부분 인덱스(V5) |

**세 간판 쿼리 모두 GiST를 탄다.** KNN은 웹 검색(2026)으로 재확인한 "인덱스 있으면 `<->` 최대 ~1800배" 특성과
정합(Crunchy Data·PostGIS 워크숍) — cost 상한이 5.1M이라도 KNN 인덱스가 상위 N개만 방문해 actual 30ms.

### bounds 대박스의 Seq Scan은 버그가 아니다 (조사 결과)

밀집 대박스(서울 도심) + `LIMIT 100` + `ORDER BY` 없음이면 플래너가 **Seq Scan(조기종료)** 을 고른다. 시드의
70%가 수도권이라 첫 ~9천 행 안에서 100건이 다 차기 때문 — 플래너가 정확히 옳다. **선택적(희소·작은) 박스에서는
`idx_places_geom`(geometry GiST)을 실제로 탄다**(실측 1.7ms). "인덱스는 이득이 될 때만 쓴다"는 옵티마이저의
정상 동작이라 인덱스를 강제하지 않는다(§0-4의 취지는 "전체스캔 방지"지 "무조건 인덱스"가 아니다).

## 결정 2 — Flyway V8: `place_report_signals` 뷰의 유효 제보 필터 인덱스 (튜닝)

스코어드/추천 쿼리는 매번 `place_report_signals` 뷰(ADR-0007)를 LEFT JOIN 한다. 뷰는 `WHERE r.expires_at >
now()`로 유효 제보만 집계하는데, **제보는 휘발성이라 만료 행이 계속 누적된다**(§1 — expires_at 이후에도 물리
삭제하지 않음). 플래너는 GROUP BY를 통과해 place_id 조건을 밀어넣지 못하므로 **뷰는 매 쿼리 통째로 집계**되고,
인덱스가 없으면 이 필터가 reports 전체를 Seq Scan 하며 만료 행을 버린다 → 비용이 **누적 총 제보 수에 비례**.

만료 20만 시나리오(프로덕션 수주 누적 근사)의 실측 before/after:

| 대상 | V8 이전 | V8 이후 | 계획 변화 |
|---|---|---|---|
| 뷰 빌드 | **256 ms** | **133 ms** | `Seq Scan on reports (Rows Removed: 200000)` → `Bitmap Index Scan on idx_reports_expires` |
| 반경 스코어드(800m) | **361 ms** | **172 ms** | 위 뷰 서브트리 동일 전환(공간 GiST 경로는 불변) |

→ **V8 = `CREATE INDEX idx_reports_expires ON reports (expires_at)`.** `now()`는 IMMUTABLE이 아니라 부분
인덱스 predicate(`WHERE expires_at > now()`)에 넣을 수 없어 **전체 btree**로 범위스캔을 태운다(유효 비율이
낮을수록 이득↑ = 누적 시나리오에 정확히 부합). §0-4 "전체스캔 금지"를 뷰의 시간 필터에도 적용한 것.

**공간 인덱스는 새로 추가하지 않았다** — 반경/kNN/bounds는 이미 최적 경로(ADR-0001의 GiST)를 탄다. 튜닝이
필요했던 유일한 지점은 "휘발성 UGC 누적"이라는 시간축이었고, 그게 V8 하나로 좁혀졌다.

## 결정 3 — k6 부하 시나리오·임계값

`perf/k6/spatial_load.js`: 한 이터레이션이 반경→kNN→bounds→추천 4개 엔드포인트를 실사용 분포(수도권 70%)로
랜덤 좌표에 호출. 엔드포인트별 `Trend`로 지연을 분리 관측하고 p95/p99 threshold를 건다(위반 시 k6 exit≠0 → CI 게이트).

**측정 결과(green, exit 0, PEAK_VUS=4, warm):**

| 엔드포인트 | median | p95 | max | 임계(p95/p99) |
|---|---|---|---|---|
| kNN 최근접(`<->`) | 85 ms | **213 ms** | 486 ms | 500 / 900 |
| bounds(`&&`) | 221 ms | 493 ms | 1.21 s | 900 / 1800 |
| 반경(`ST_DWithin`) | 515 ms | 1.40 s | 2.25 s | 2000 / 3200 |
| 추천(2단 검색) | 726 ms | 1.24 s | 2.29 s | 2000 / 3200 |

- 실패율 0%(564/564 체크 성공), 처리량 ~8 RPS.
- **kNN이 가장 빠르고 포화에 가장 강하다** — 순수 GiST KNN 경로라 뷰 조인이 없다(간판 그 자체).
- 반경/추천이 느린 건 뷰 조인(집계) 때문 — V8이 그 비용을 절반으로 줄였고, 남은 건 에뮬레이션 CPU 병목.

**포화 특성(정직한 한계):** VU를 4→8→12→30으로 올려도 **처리량이 ~10 RPS에서 평평**하고 지연만 증가했다
(30 VU에서 반경 p95 5.3s). 이는 GiST 문제가 아니라 **2 vCPU qemu 에뮬레이션 CPU 상한**의 전형적 신호다
(닫힌 모델에서 처리량 포화 = 자원 병목). 네이티브 RDS·provisioned IOPS면 이 상한이 훨씬 높다(측정 못 했으므로
수치로 주장하지 않는다).

### 임계값·시나리오 선택 근거 (2026 트렌드)

- **평균이 아니라 p95/p99에 threshold** — 웹 검색(2026)으로 확인한 k6 베스트프랙티스(꼬리 지연은 평균이 숨긴다).
- **CI/느린 인프라는 임계를 프로덕션보다 2~3배 완화** — 같은 가이드가 명시. 에뮬레이션 환경이 정확히 "느린 인프라"라,
  임계를 인덱스-서빙 지연 + 에뮬레이션 여유로 잡았다(**임계 통과 = 인덱스가 실제로 서빙 중**; GiST가 빠지면 이
  관대한 임계도 뚫린다 = 회귀 가드로 유효).
- **ramping-vus(닫힌 모델) 채택, ramping-arrival-rate는 확장점** — 트렌드는 "회귀 테스트는 arrival-rate가
  정석"(코드가 2배 느려지면 vus 모델은 트래픽을 2배 줄여 회귀를 가릴 수 있음)이라 하지만, **이 환경은 자원이
  포화하므로 열린 모델(arrival-rate)은 VU가 무한 적체돼 의미가 없다.** 닫힌 모델이 자원 상한을 정직하게 드러낸다.
  `PEAK_VUS` env로 부하를 조절 가능하게 뒀고, arrival-rate 정식 회귀 게이트는 네이티브 CI 하드웨어가 붙는 P4
  후속(관측성/오토스케일링)에서 additive하게 남긴다(§0-2 과설계 금지).

## 검토한 대안(Alternatives)

| 대안 | 기각/보류 이유 |
|---|---|
| **프로덕션 RDS에 부하** | SPEC.md 명시 금지(실서비스 교란). 로컬 emulated PostGIS + 합성 30만으로 대체 |
| **뷰를 correlated LATERAL 서브쿼리로 바꿔 place_id push-down** | 스코어드 3쿼리(반경/bounds/단건)의 DRY(ADR-0007)를 깨고 쿼리 3벌 중복. V8 인덱스로 뷰 유지한 채 절반 개선이 되므로 큰 리팩터는 근거 부족(측정 우선). 트래픽이 실제로 커지면 재검토할 확장점 |
| **place_report_signals를 MATERIALIZED VIEW로** | 제보는 실시간 freshness가 핵심(§5)이라 갱신 지연이 곧 신선도 손상. 온디맨드 집계 + 인덱스가 정합. 대량 트래픽 입증 후에만 |
| **reports(place_id, expires_at) 복합/부분 인덱스** | 부분 predicate는 now() 비허용. 복합은 뷰가 place_id로 push-down을 못 해 선두 컬럼 이점이 없음 — `expires_at` 단일이 유효 필터에 정확 |
| **공간 인덱스 추가 신설** | 불필요 — EXPLAIN상 반경/kNN/bounds가 이미 GiST 최적 경로. 없는 문제를 인덱스로 덮지 않는다 |
| **k6 임계를 무조건 통과하게 상향** | 게이밍. 대신 "인덱스 서빙 여부를 지키는 회귀 가드" 수준으로 잡고 에뮬레이션 한계를 문서화(정직) |

## 결과(Consequences)

- 간판(반경/kNN/bounds)이 GiST 인덱스를 탄다는 것을 **실행계획으로 확증**, k6로 **실측 p95/p99·처리량** 확보
  ("빠르다" 대신 "이만큼 빠르다 + 이 계획으로"를 수치로 보일 수 있다).
- V8로 스코어드/추천 쿼리의 유일한 확장성 위험(만료 제보 누적)을 인덱스 하나로 절반 완화. 재현 가능(Flyway).
- `perf/`(k6 스크립트·시드·EXPLAIN·RESULTS)로 **재현 절차를 레포에 고정** — 누구나 같은 수치를 재생성 가능.
- 알려진 한계: 절대 지연은 emulated PostGIS라 부풀려짐(네이티브 RDS 미측정). 처리량 상한 실측은 로컬 2 vCPU 기준.
- 다음 확장점(범위 확대 전 별도 제안): ① 네이티브 CI/스테이징에서 ramping-arrival-rate 정식 회귀 게이트,
  ② ECS Service Auto Scaling을 이 부하와 묶어 P4 오토스케일링(§7), ③ 트래픽 입증 시 뷰 LATERAL/materialized 재검토.

## 근거(References)

- k6 임계·시나리오(2026): p95/p99에 threshold(평균 금지), CI/느린 인프라는 2~3배 완화, 회귀는 arrival-rate 정석.
  Grafana k6 가이드류(예: oneuptime 2026-02, cargurus.dev 2026-06 엔지니어링 블로그).
- PostGIS 인덱스: ST_DWithin 자동 인덱스 필터 + GiST R-tree, KNN `<->` 인덱스 시 최대 ~1800배
  ([PostGIS 워크숍 §15 Spatial Indexing](http://postgis.net/workshops/postgis-intro/indexing.html),
  [Crunchy Data: PostGIS Nearest Neighbor](https://www.crunchydata.com/blog/a-deep-dive-into-postgis-nearest-neighbor-search)).
- 프로젝트 근거: SPEC.md §0-4(GiST·전체스캔 금지)·§7(k6·Test/Ops)·§10 P4·§11(간판), ADR-0001·ADR-0007.
