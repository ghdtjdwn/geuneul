-- 신고/모더레이션 큐 (CLAUDE.md §0-7·§8·§9). 제보(reports)·후기(reviews) 둘 다 신고 대상이 될 수 있다.
--
-- 스키마 결정(WORKLOG 근거): ERD 초안(§8)은 reports_flags/reviews_flags 두 테이블로 나눠뒀지만,
-- 실제로는 "신고→검수" 라이프사이클이 대상 타입과 무관하게 완전히 동일하고(사유·상태·처리시각),
-- 관리자 큐(GET /admin/flags/pending)는 두 종류를 한 화면에서 최신순으로 봐야 하므로 분리하면
-- UNION 조회가 필요해진다. 통합 테이블(target_type, target_id) 다형 연관으로 단순화 — 신규 신고 대상
-- (예: 장소 자체)이 늘어도 target_type만 추가하면 되는 확장성도 이점.
-- target은 다형이라 강제 FK를 걸지 않는다(reports/reviews 어느 쪽도 가리킬 수 있어 단일 FK 불가) —
-- 대신 (target_type, target_id) 인덱스로 조회 성능을 보장하고, 대상 존재 검증은 앱 레벨(FlagService)에서 한다.
CREATE TABLE flags (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    target_type VARCHAR(16) NOT NULL,                     -- REPORT | REVIEW
    target_id   BIGINT NOT NULL,
    reporter_id BIGINT NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    reason      VARCHAR(16) NOT NULL,                     -- SPAM | FALSE_INFO | OFFENSIVE | OTHER
    detail      VARCHAR(500),                              -- 자유 텍스트(선택)
    status      VARCHAR(16) NOT NULL DEFAULT 'PENDING',    -- PENDING | RESOLVED | DISMISSED
    created_at  TIMESTAMP(6) WITH TIME ZONE NOT NULL DEFAULT now(),
    resolved_at TIMESTAMP(6) WITH TIME ZONE,
    -- 같은 유저가 같은 대상을 두 번 신고 못 하게(스팸 신고 억제) — 서비스 레이어 사전 체크 + DB 레벨 안전망.
    CONSTRAINT uq_flags_target_reporter UNIQUE (target_type, target_id, reporter_id)
);

-- 대상별 신고 조회(중복 체크·향후 "이 제보/후기 신고 N건" 표시)
CREATE INDEX idx_flags_target ON flags (target_type, target_id);
-- 관리자 검수 큐(GET /admin/flags/pending) — 상태별 최신순 조회 최적화.
CREATE INDEX idx_flags_status_created ON flags (status, created_at DESC);
