-- survival_score의 시공간(spatio-temporal) 신호를 DB 레이어에서 계산하는 뷰 (ADR-0007, CLAUDE.md §5).
--
-- 설계 의도(간판): "시공간 랭킹은 DB(PostGIS/SQL) 레이어에서 계산한다"(CLAUDE.md §5).
-- 장소별 유효(미만료) 제보를 최근성(freshness) 버킷 + 신뢰도(trust) 가중으로 집계해
--   · freshness_score : 가장 신선한 제보의 최근성(순수 recency, 0~1)
--   · comfort_score   : 긍정 신호(시원/자리/물/화장실) 누적, 신뢰도·최근성 가중, [0,1] 캡
--   · risk_score      : 부정 신호(더움/붐빔/벌레/냄새/담배/침수/미끄럼) 누적, 위험도(severity) 가중, [0,1] 캡
-- 를 만든다. 최종 가중합(0.25 거리 + 0.20 comfort + 0.20 freshness − 0.15 risk)과 등급(GOOD/OKAY/UNKNOWN)은
-- 순수 함수 SurvivalScore(Java)에서 조립·단위테스트한다 — DB는 "무거운 시공간 집계", Java는 "문서화된 가중치"를 담당.
--
-- 최근성 버킷은 §5 규약 그대로: 0~1h=1.0 / 1~3h=0.8 / 오늘=0.6 / 이번주=0.3 / 그 외=0.1.
-- (reports는 타입별 TTL이 ≤72h라 '이번주/그외' 버킷은 실질적으로 거의 안 걸리지만, 규약을 그대로 반영해 둔다.)
-- 신뢰도 가중은 "제보는 trust_score로 가중"(§5): 익명=0.7 기저, 로그인 유저는 trust_score(0~100)로 0.7→1.0 가산.
-- (현재 모든 제보가 익명이라 실질 0.7이지만, P2 로그인이 붙으면 자동으로 신뢰도가 반영되는 확장점.)
--
-- 뷰(정적 계산)인 이유: 제보 테이블은 UGC라 소규모이고, 뷰가 report 로직을 한 곳에 모아
-- 세 공간쿼리(반경/bounds/단건)가 동일하게 LEFT JOIN 한다(DRY). 장소당 상관 서브쿼리 대비
-- 대량 트래픽 시 튜닝(부분 인덱스/집계 캐시)은 P4(k6+EXPLAIN)에서 additive하게 다룬다.
CREATE VIEW place_report_signals AS
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
        -- 긍정 신호: 최근성 × 신뢰도
        CASE WHEN r.report_type IN ('COOL', 'SEAT_OK', 'WATER_OK', 'RESTROOM_CLEAN')
             THEN fw.w * tf.f
             ELSE 0 END AS pos_weight,
        -- 부정 신호: 최근성 × 신뢰도 × 위험도(침수·미끄럼=안전 위험이라 1.0, 그 외 체감 리스크 0.6)
        CASE WHEN r.report_type IN ('HOT', 'CROWDED', 'BUG', 'ODOR', 'SMOKE', 'FLOOD', 'SLIPPERY')
             THEN fw.w * tf.f * (CASE WHEN r.report_type IN ('FLOOD', 'SLIPPERY') THEN 1.0 ELSE 0.6 END)
             ELSE 0 END AS neg_weight
    FROM reports r
    LEFT JOIN users u ON u.id = r.user_id
    CROSS JOIN LATERAL (
        SELECT CASE
            WHEN r.created_at >= now() - interval '1 hour'  THEN 1.0
            WHEN r.created_at >= now() - interval '3 hours' THEN 0.8
            WHEN r.created_at >= date_trunc('day', now())   THEN 0.6   -- 오늘(자정 이후)
            WHEN r.created_at >= now() - interval '7 days'  THEN 0.3
            ELSE 0.1
        END AS w
    ) fw
    CROSS JOIN LATERAL (
        SELECT CASE
            WHEN r.user_id IS NULL THEN 0.7
            ELSE 0.7 + 0.3 * LEAST(COALESCE(u.trust_score, 0) / 100.0, 1.0)
        END AS f
    ) tf
    WHERE r.expires_at > now()      -- 만료된 제보는 시공간 스코어에서 제외(휘발성 규약)
) s
GROUP BY s.place_id;

COMMENT ON VIEW place_report_signals IS
    'survival_score 시공간 신호(장소별 유효제보 집계: freshness/comfort/risk). ADR-0007. 최종 조립은 Java SurvivalScore.';
