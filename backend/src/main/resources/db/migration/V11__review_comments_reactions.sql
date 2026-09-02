-- 후기 커뮤니티(CLAUDE.md §8 2차·살) — 후기 댓글 + 리액션("유용했어요"). ERD(§8)에 이미 설계된 테이블.
--
-- 규율(CLAUDE.md §0-9): 커뮤니티는 "살"이지 간판이 아니다 — survival_score(간판)와 완전히 분리된
-- 평판/상호작용 콘텐츠다. 여기 어떤 것도 place_report_signals·SurvivalScore를 건드리지 않는다.

-- 후기 댓글 — 후기(review)에 달리는 댓글. 로그인 필요. 후기 삭제 시 함께 삭제(CASCADE).
CREATE TABLE review_comments (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    review_id  BIGINT NOT NULL REFERENCES reviews (id) ON DELETE CASCADE,
    user_id    BIGINT NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    comment    VARCHAR(500) NOT NULL,
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL DEFAULT now()
);
-- 후기별 댓글 최신/오래된 순 조회 최적화.
CREATE INDEX idx_review_comments_review ON review_comments (review_id, created_at);

-- 리액션 — 후기/제보/댓글에 대한 "유용했어요" 등. target은 다형(target_type + target_id)이라 FK를 걸지
-- 않는다(대상 존재 검증은 애플리케이션이 target_type별 리포지토리로 한다). 한 유저가 같은 대상에 같은
-- 타입 리액션을 중복으로 못 남기게 유니크(토글 멱등).
CREATE TABLE reactions (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    target_type VARCHAR(16) NOT NULL,   -- REVIEW | REPORT | COMMENT
    target_id   BIGINT NOT NULL,
    user_id     BIGINT NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    type        VARCHAR(16) NOT NULL,   -- HELPFUL(유용했어요)
    created_at  TIMESTAMP(6) WITH TIME ZONE NOT NULL DEFAULT now(),
    CONSTRAINT uq_reaction UNIQUE (target_type, target_id, user_id, type)
);
-- 대상별 리액션 수 집계 최적화.
CREATE INDEX idx_reactions_target ON reactions (target_type, target_id, type);
