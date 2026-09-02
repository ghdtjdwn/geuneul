-- 시설(place_features) → comfort 신호를 DB 레이어에서 집계하는 뷰 (A1, ADR-0017, CLAUDE.md §5).
--
-- 배경: place_features(에어컨·콘센트·wifi·좌석·음수대·study_ok·quiet 등)는 지금까지 상세 화면의
-- 등급 칩(FeatureGrade)으로 "표시"만 되고 survival_score에는 전혀 들어가지 않았다. 그 결과 냉방 쉼터·
-- 콘센트 카페가 "무제보"이면 comfort=0으로 취급돼 종합점수·추천 랭킹에서 정적 시설의 이점을 못 받았다.
-- 이 뷰는 그 정적 시설 사실을 장소별 feature_comfort[0,1]로 집계해, place_report_signals(V4~V10,
-- 제보 기반 comfort)와 별개의 LEFT JOIN 신호로 스코어드 쿼리에 실어 준다(간판 = "시공간·시설 신호는
-- DB에서 집계, 가중 조립은 Java SurvivalScore", ADR-0007 계보 준수).
--
-- place_report_signals와 별도 뷰인 이유: 그 뷰는 reports를 GROUP BY라 "제보 있는 장소"만 행이 있다.
-- 시설은 "제보 없는 장소"에도 붙으므로 같은 뷰에 넣으면 FULL OUTER JOIN 재구성이 필요해 복잡하다.
-- 두 신호를 각각 뷰로 두고 스코어드 쿼리가 둘 다 LEFT JOIN 하면(제보 없으면 COALESCE 0) DRY하고 단순하다.
--
-- polarity·confidence 가중(A1 함정 대응):
--   · 긍정 시설(불리언 truthy): 에어컨/좌석/음수대/화장실/study_ok/quiet/no_eyes → confidence × 0.5
--   · 등급 시설(콘센트/wifi): many/high/fast=강, some/medium/ok=중, few/low/slow=약 → confidence × 등급강도
--   · 부정 시설: noise_level=loud → confidence × 0.3 만큼 comfort 차감(§6대로 "위험"이 아니라 comfort 감소)
--   · confidence NULL은 0.5로 본다(중립). PUBLIC 백필 feature는 confidence가 낮게(0.3~0.6) 심겨
--     UGC(제보) comfort를 덮지 못한다(가중 조립은 Java에서 report>feature 순으로, ADR-0017).
-- feature_comfort = clamp(Σ긍정 − Σ부정, 0, 1). 성분당 상한 0.5라 단일 시설이 comfort를 포화시키지 못하고
-- (한 시설만으로 "완벽히 쾌적"은 아니다), 시설이 여럿이거나 confidence가 높을수록 1.0에 수렴한다.
CREATE VIEW place_feature_signals AS
SELECT
    f.place_id                                                       AS place_id,
    LEAST(GREATEST(SUM(f.pos) - SUM(f.neg), 0.0), 1.0)::double precision AS feature_comfort
FROM (
    SELECT
        pf.place_id,
        -- 긍정 기여(comfort↑): 시설 종류별 강도 × confidence
        CASE
            WHEN pf.feature_type IN ('air_conditioned', 'seating', 'water', 'restroom',
                                     'study_ok', 'quiet', 'no_eyes')
                 AND lower(coalesce(pf.value, 'true')) IN ('true', '1', 'yes', 'y', 'o')
                THEN coalesce(pf.confidence, 0.5) * 0.5
            WHEN pf.feature_type IN ('outlet', 'wifi')
                THEN coalesce(pf.confidence, 0.5) * (CASE lower(coalesce(pf.value, ''))
                        WHEN 'many'   THEN 0.5  WHEN 'high'   THEN 0.5  WHEN 'fast' THEN 0.5
                        WHEN 'some'   THEN 0.3  WHEN 'medium' THEN 0.3  WHEN 'ok'   THEN 0.3
                        WHEN 'few'    THEN 0.15 WHEN 'low'    THEN 0.15 WHEN 'slow' THEN 0.15
                        WHEN 'true'   THEN 0.3  WHEN '1'      THEN 0.3  WHEN 'yes'  THEN 0.3
                        ELSE 0.0 END)
            ELSE 0.0
        END AS pos,
        -- 부정 기여(comfort↓): 소음 loud 등
        CASE
            WHEN pf.feature_type = 'noise_level'
                 AND lower(coalesce(pf.value, '')) IN ('loud', 'high', '3')
                THEN coalesce(pf.confidence, 0.5) * 0.3
            ELSE 0.0
        END AS neg
    FROM place_features pf
) f
GROUP BY f.place_id;

COMMENT ON VIEW place_feature_signals IS
    '시설(place_features) 기반 comfort 신호(장소별 feature_comfort[0,1]). A1/ADR-0017. '
    '제보 기반 place_report_signals와 별개 LEFT JOIN 신호이며 최종 조립(report>feature>weather)은 Java SurvivalScore.';
