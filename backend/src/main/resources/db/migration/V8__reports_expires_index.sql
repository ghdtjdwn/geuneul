-- P4 부하테스트·EXPLAIN 튜닝 결과 (ADR-0010) — place_report_signals 뷰의 "유효 제보" 필터 인덱스.
--
-- 배경: survival_score의 시공간 신호 뷰(place_report_signals, V4/ADR-0007)는 매 스코어드 공간쿼리
-- (반경·bounds·단건)마다 LEFT JOIN 되어 재계산된다. 뷰는 `WHERE r.expires_at > now()`로 유효(미만료)
-- 제보만 집계하는데, 제보는 휘발성이라 시간이 지나면 만료 행이 계속 누적된다(§1 UGC 2단 구조 — 제보는
-- expires_at 이후 폐기 대상이지만 물리 삭제는 안 함). 인덱스가 없으면 이 필터는 reports 전체를 Seq Scan
-- 하며 만료 행을 Filter로 버린다 → 비용이 "누적 총 제보 수"에 비례해 무한정 커진다(공간 인덱스가 장소를
-- 68건으로 좁혀도 뷰는 여전히 전체 제보를 훑는다 — 플래너는 GROUP BY를 통과해 place_id 조건을 밀어넣지
-- 못하므로 뷰는 항상 통째로 집계된다).
--
-- 실측(로컬 PostGIS, places 30만 + reports 21만 중 유효 1만·만료 20만, EXPLAIN ANALYZE):
--   · 뷰 빌드              : Seq Scan(만료 20만 Filter 제거) 256ms → Bitmap Index Scan(expires_at>now()) 133ms
--   · 반경 스코어드(800m)  : 361ms → 172ms  (약 2.1배)
--   Seq Scan on reports (Rows Removed by Filter: 200000) → Bitmap Index Scan on idx_reports_expires 로 전환.
--
-- 왜 부분 인덱스(WHERE expires_at > now())가 아니라 전체 btree인가: now()는 STABLE/VOLATILE라 인덱스
-- predicate(IMMUTABLE만 허용)에 넣을 수 없다. expires_at 전체 btree면 `expires_at > now()` 범위 스캔으로
-- 유효 행만 읽어 목적을 동일하게 달성한다(유효 비율이 낮을수록 이득이 커지는, 프로덕션 누적 시나리오에 정확히 부합).
-- CLAUDE.md §0.4 "애플리케이션 레벨에서 전체 스캔하지 않는다"의 인덱스 원칙을 뷰의 시간 필터에도 적용.
CREATE INDEX idx_reports_expires ON reports (expires_at);

COMMENT ON INDEX idx_reports_expires IS
    'place_report_signals 뷰의 유효 제보 필터(expires_at > now()) 지원 — 만료 제보 누적 시 뷰 빌드 전체스캔 방지 (ADR-0010, P4).';
