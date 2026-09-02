-- 관심 장소(bookmarks) — ERD §8 (A7). 로그인 유저가 장소를 저장/해제한다. B1 알림의 "관심 장소 상태 변화"의
-- 선행 테이블이기도 하다. survival_score(간판)와 무관한 개인화 데이터(살).
CREATE TABLE bookmarks (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id    BIGINT NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    place_id   BIGINT NOT NULL REFERENCES places (id) ON DELETE CASCADE,
    memo       VARCHAR(200),
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL DEFAULT now(),
    -- 유저×장소 1건(중복 저장 방지) — 저장 토글의 자연키. upsert는 서비스 레이어(ON CONFLICT DO UPDATE 대신
    -- findByUserIdAndPlaceId로 memo 갱신, 저빈도 UGC라 레이스 리스크 허용, Review와 동일 정책).
    CONSTRAINT uq_bookmarks UNIQUE (user_id, place_id)
);

-- 마이페이지 "내 관심 장소" 목록 = user_id로 최신순. 전체스캔 방지(CLAUDE.md §0.4).
CREATE INDEX idx_bookmarks_user ON bookmarks (user_id, created_at DESC);
