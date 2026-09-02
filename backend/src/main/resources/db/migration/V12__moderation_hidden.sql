-- 모더레이션 확장(CLAUDE.md §0-7, §9 신고/검수) — 신고가 타당(RESOLVED)하면 대상(제보/후기)을 숨긴다.
-- 지금까지 신고 큐는 상태만 마킹하고 실제 콘텐츠는 그대로 남아 "이빨이 없었다". hidden 플래그로
-- 관리자 처리에 실효를 준다: hidden 제보/후기는 공개 조회·survival_score·급증 알림·혼잡 파생에서 전부 빠진다.
--
-- 기본값 false: 기존 제보/후기는 노출 유지(회귀 없음). 숨김은 관리자 resolve(RESOLVED)로만 켜진다.

ALTER TABLE reports ADD COLUMN hidden BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE reviews ADD COLUMN hidden BOOLEAN NOT NULL DEFAULT FALSE;

COMMENT ON COLUMN reports.hidden IS '모더레이션 숨김(신고 RESOLVED 시 true) — 공개·스코어·급증·혼잡에서 제외.';
COMMENT ON COLUMN reviews.hidden IS '모더레이션 숨김(신고 RESOLVED 시 true) — 공개 후기 목록에서 제외.';

-- place_report_signals 뷰를 hidden 제보 제외로 교체(V10 정의 + AND NOT r.hidden).
-- 변경점은 내부 서브쿼리 WHERE의 hidden 필터 한 줄뿐 — 나머지(verified 가중 등)는 V10과 동일.
CREATE OR REPLACE VIEW place_report_signals AS
SELECT
    s.place_id                                       AS place_id,
    COUNT(*)                                         AS report_count,
    MAX(s.freshness_weight)::double precision        AS freshness_score,
    LEAST(SUM(s.pos_weight), 1.0)::double precision  AS comfort_score,
    LEAST(SUM(s.neg_weight), 1.0)::double precision  AS risk_score
FROM (
    SELECT
        r.place_id,
        fw.w AS freshness_weight,
        CASE WHEN r.report_type IN ('COOL', 'SEAT_OK', 'WATER_OK', 'RESTROOM_CLEAN')
             THEN fw.w * tf.f
             ELSE 0 END AS pos_weight,
        CASE WHEN r.report_type IN ('HOT', 'CROWDED', 'BUG', 'ODOR', 'SMOKE', 'FLOOD', 'SLIPPERY')
             THEN fw.w * tf.f * (CASE WHEN r.report_type IN ('FLOOD', 'SLIPPERY') THEN 1.0 ELSE 0.6 END)
             ELSE 0 END AS neg_weight
    FROM reports r
    LEFT JOIN users u ON u.id = r.user_id
    CROSS JOIN LATERAL (
        SELECT CASE
            WHEN r.created_at >= now() - interval '1 hour'  THEN 1.0
            WHEN r.created_at >= now() - interval '3 hours' THEN 0.8
            WHEN r.created_at >= date_trunc('day', now())   THEN 0.6
            WHEN r.created_at >= now() - interval '7 days'  THEN 0.3
            ELSE 0.1
        END AS w
    ) fw
    CROSS JOIN LATERAL (
        SELECT LEAST(1.0,
            (CASE
                WHEN r.user_id IS NULL THEN 0.7
                ELSE 0.7 + 0.3 * LEAST(COALESCE(u.trust_score, 0) / 100.0, 1.0)
            END)
            * (CASE WHEN r.verified THEN 1.3 ELSE 1.0 END)
        ) AS f
    ) tf
    WHERE r.expires_at > now()
      AND NOT r.hidden          -- 모더레이션 숨김 제보는 스코어에서 제외(V12)
) s
GROUP BY s.place_id;

COMMENT ON VIEW place_report_signals IS
    'survival_score 시공간 신호. ADR-0007 + V10 verified 가중 + V12 hidden 제외(모더레이션).';
