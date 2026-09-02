-- trust_score 산출 지원 (CLAUDE.md §5 "제보는 trust_score로 가중", P2). 배경(WORKLOG 2026-07-09):
--
-- place_report_signals 뷰(V4, ADR-0007)는 이미 처음부터 users.trust_score를 LEFT JOIN 해
-- comfort_score/risk_score를 "익명 0.7 기저, 로그인 유저는 0.7~1.0(trust_score/100 비례)"로 가중한다
-- (V4 파일의 tf 서브쿼리 참고). 그 SQL 가중 공식 자체는 이미 정답이라 이 마이그레이션에서 다시 만들지
-- 않는다(뷰 시맨틱을 건드리지 않는다 — 굳이 CREATE OR REPLACE로 동일 정의를 재선언하는 것도 no-op 리스크만
-- 지는 행위라 하지 않았다). 진짜 빠져 있던 조각은 users.trust_score 값 자체가 한 번도 계산되지 않아
-- 모든 로그인 유저가 신규 유저와 동일하게 0(=익명과 동일 0.7 가중치)이었다는 점이다 — 그 계산은
-- TrustScoreService(Java, 온디맨드)가 새로 맡는다. 이 마이그레이션은 그 계산이 의존하는 카운트 쿼리
-- (ReportRepository.countByUserId, ReviewRepository.countByUserId)를 전체스캔 없이 태우는 인덱스만 추가한다
-- (CLAUDE.md §0.4 "애플리케이션 레벨에서 전체 스캔하지 않는다"의 인덱스 원칙을 user_id 조회에도 적용).
--
-- reports.user_id는 대다수가 NULL(여전히 익명이 주류)이므로 부분 인덱스로 크기를 아낀다.
CREATE INDEX idx_reports_user ON reports (user_id) WHERE user_id IS NOT NULL;

-- reviews.user_id는 NOT NULL(로그인 필수)이라 일반 인덱스.
CREATE INDEX idx_reviews_user ON reviews (user_id);

COMMENT ON VIEW place_report_signals IS
    'survival_score 시공간 신호(장소별 유효제보 집계: freshness/comfort/risk). ADR-0007. '
    '신뢰도 가중(comfort/risk × users.trust_score)은 V4부터 SQL에 이미 있었고, '
    'trust_score 산출 자체는 P2 TrustScoreService(Java, 온디맨드)가 담당한다(V6).';
