-- P4 부하테스트용 합성 대량 좌표 시드 — Flyway 마이그레이션이 아니다(프로덕션 적재 금지, docs/SPEC.md 안전 제약).
-- 로컬 docker-compose PostGIS에서만 psql로 직접 실행한다.
--
-- 목적: PostGIS GiST 인덱스(반경 ST_DWithin geography · kNN <-> · bounds &&)가 "실제로" 대용량에서
-- 인덱스를 타는지 EXPLAIN ANALYZE + k6로 실증하려면, 프로덕션 규모(공중화장실 46,897건 등)에 준하는
-- places 행 수가 필요하다. 로컬 시드(R__seed_sample_places.sql)는 10건뿐이라 옵티마이저가 사실상
-- 항상 Seq Scan을 선택해도 차이가 안 보인다(테이블이 작으면 Seq Scan이 합리적 선택이기도 하다).
--
-- 분포: 실제 공공데이터 분포(인구 밀집지 편중)를 근사하기 위해 70%는 수도권 bbox, 30%는 전국 bbox에
-- 균등분포시킨다 — "전국 그대로 적재, 데이터가 많을수록 대용량 지리검색 간판이 강해진다"(docs/SPEC.md §3)
-- 원칙과 정합되게 nationwide 분포를 유지하면서도 실사용 쿼리(대부분 수도권에서 반경 800m~2km 검색)의
-- 국지성을 재현한다.
--
-- 재현: docker exec -i <postgres-container> psql -U geuneul -d geuneul -v n=300000 -f - < seed_synthetic_places.sql
-- (psql -v n=<개수> -v data_seed=0.20260718 로 규모/seed 조절. 기본값은 아래 \if 블록.)

\if :{?n}
\else
  \set n 300000
\endif
\if :{?data_seed}
\else
  \set data_seed 0.20260718
\endif

SELECT setseed(:data_seed);
\echo 합성 places 생성 시작 — n = :n, data_seed = :data_seed

-- 기존 합성 데이터는 재실행 시 정리(idempotent 재현을 위해 delete-then-insert).
DELETE FROM places WHERE source = 'synthetic_loadtest';

-- 주의: random()은 generate_series를 직접 읽는 MATERIALIZED CTE에서 행마다 한 번 평가한다.
-- (CROSS JOIN LATERAL로 감싸면 outer 참조가 없어 플래너가 한 번만 평가·캐시해 전 행이 같은 값이 된다 — 실측으로 확인된 함정.)
-- 수도권 여부도 행당 한 번만 뽑아 경도/위도가 서로 다른 분포를 선택하는 혼합 좌표를 만들지 않는다.
WITH generated AS MATERIALIZED (
    SELECT
        gs,
        random() < 0.7 AS is_metropolitan,
        (ARRAY['TOILET','TOILET','TOILET','WATER','PARK','LIBRARY','CIVIC','UNDERGROUND','COOLING_SHELTER','ETC'])
            [1 + floor(random() * 10)::int] AS category,
        random() AS lng_random,
        random() AS lat_random
    FROM generate_series(1, :n) AS gs
), randomized AS (
    SELECT
        gs,
        category,
        CASE WHEN is_metropolitan
             THEN 126.6 + lng_random * 0.7
             ELSE 124.6 + lng_random * 6.4
        END AS lng,
        CASE WHEN is_metropolitan
             THEN 37.2 + lat_random * 0.55
             ELSE 33.1 + lat_random * 5.5
        END AS lat
    FROM generated
)
INSERT INTO places (name, category, address, geom, source, source_external_id, geocoded, is_commercial, created_at, updated_at)
SELECT
    'synthetic-' || t.gs AS name,
    t.category,
    '합성 시드 — 좌표만 유효' AS address,
    ST_SetSRID(ST_MakePoint(t.lng, t.lat), 4326),
    'synthetic_loadtest',
    'synth-' || t.gs,
    false,
    t.category IN ('CAFE', 'STUDY_CAFE'),
    now(),
    now()
FROM randomized t;

\echo 합성 places 완료
SELECT count(*) AS synthetic_places FROM places WHERE source = 'synthetic_loadtest';

-- 합성 제보(reports) — place_report_signals 뷰(V4/ADR-0007) 조인 비용을 실측하려면 UGC 볼륨도 필요.
-- 프로덕션은 "현재 모든 제보가 익명"(WORKLOG 2026-07-09)이라 user_id NULL로 맞춘다(회귀 없는 동일 조건).
DELETE FROM reports WHERE comment = 'synthetic-loadtest-report';

INSERT INTO reports (place_id, report_type, status_value, comment, is_anonymous, created_at, expires_at)
SELECT
    p.id,
    (ARRAY['COOL','HOT','BUG','ODOR','SMOKE','SEAT_OK','CROWDED','WATER_OK','RESTROOM_CLEAN'])
        [1 + (get_byte(decode(md5(p.source_external_id || ':' || :'data_seed' || ':type'), 'hex'), 0) % 9)]
        AS report_type,
    NULL,
    'synthetic-loadtest-report',
    true,
    now() - ((get_byte(decode(md5(p.source_external_id || ':' || :'data_seed' || ':age'), 'hex'), 0)
              / 255.0) * interval '2 hours'), -- freshness 버킷(0~1h/1~3h)이 결정적으로 섞이게
    now() + interval '2 hours'                  -- 아직 유효(만료 전) — 뷰 집계에 실제로 잡힘
FROM (
    SELECT id, source_external_id
      FROM places
     WHERE source = 'synthetic_loadtest'
     ORDER BY md5(source_external_id || ':' || :'data_seed' || ':selection')
     LIMIT LEAST(:n / 30, 15000)
) p;

\echo 합성 reports 완료
SELECT count(*) AS synthetic_reports FROM reports WHERE comment = 'synthetic-loadtest-report';

-- 시간값은 실행 시각에 따라 달라지므로 fingerprint에서는 제외한다. 좌표·카테고리와 제보 대상·타입은 같은
-- PostgreSQL/PostGIS 버전과 data_seed에서 동일해야 한다. 두 값을 실행 기록의 DATA_FINGERPRINT로 결합한다.
SELECT md5(string_agg(
           p.source_external_id || ':' || p.category || ':' || ST_X(p.geom)::text || ':' || ST_Y(p.geom)::text,
           '|' ORDER BY p.source_external_id)) AS synthetic_place_fingerprint
  FROM places p
 WHERE p.source = 'synthetic_loadtest';

SELECT md5(string_agg(
           p.source_external_id || ':' || r.report_type,
           '|' ORDER BY p.source_external_id)) AS synthetic_report_fingerprint
  FROM reports r
  JOIN places p ON p.id = r.place_id
 WHERE r.comment = 'synthetic-loadtest-report';

ANALYZE places;
ANALYZE reports;
\echo ANALYZE 완료 — 옵티마이저 통계 갱신
