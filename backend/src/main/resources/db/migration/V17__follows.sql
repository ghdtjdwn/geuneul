-- 커먼스 세이프 팔로우(N7, ADR-0023) — "좋은 작성자 후기를 다시 찾기".
--
-- 규율(CLAUDE.md §0-9): 그늘은 공개 커먼스지 친구망이 아니다. 그래서 팔로우를 "허영 소셜 그래프"가 아니라
-- 사적 북마크 + 신뢰/인기 신호로만 쓴다:
--   · 팔로워 "수"만 공개(팔로워 목록은 어디에도 노출하지 않는다).
--   · 팔로잉 목록은 "나만" 본다(마이페이지) — 내가 다시 찾아볼 작성자.
--   · 팔로우 피드·맞팔 알림·스토리는 없다.
CREATE TABLE follows (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    follower_id BIGINT NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    followee_id BIGINT NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    created_at  TIMESTAMP(6) WITH TIME ZONE NOT NULL DEFAULT now(),
    -- 같은 사람을 두 번 팔로우 못 함(토글 멱등). 자기 자신은 팔로우 불가.
    CONSTRAINT uq_follow UNIQUE (follower_id, followee_id),
    CONSTRAINT chk_no_self_follow CHECK (follower_id <> followee_id)
);

-- 내 팔로잉 목록(최신순) — WHERE follower_id = ? ORDER BY created_at DESC.
CREATE INDEX idx_follows_follower ON follows (follower_id, created_at);
-- 팔로워 수 카운트 — COUNT(*) WHERE followee_id = ?.
CREATE INDEX idx_follows_followee ON follows (followee_id);
