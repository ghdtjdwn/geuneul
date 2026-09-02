-- 알림(B1, ADR-0018). 두 테이블: 규칙(notification_rules) + 발송 이력(notification_deliveries).
-- ERD의 condition_json 대신 구조화 컬럼(center/radius) — 규칙 매칭이 공간쿼리(ST_DWithin)라 타입안전·인덱스에 유리(ADR-0018).

-- 규칙: 유저가 설정한 알림 조건. type별로 쓰는 컬럼이 다르다(SURGE_NEARBY=center+radius, BOOKMARK_SURGE=없음, HEAT_ESCAPE=center).
CREATE TABLE notification_rules (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id    BIGINT NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    type       VARCHAR(24) NOT NULL,          -- SURGE_NEARBY | BOOKMARK_SURGE | HEAT_ESCAPE
    center_lat DOUBLE PRECISION,              -- SURGE_NEARBY/HEAT_ESCAPE 중심(위치 프라이버시: 유저 명시 설정만)
    center_lng DOUBLE PRECISION,
    radius_m   INTEGER,                       -- SURGE_NEARBY 반경(m)
    is_active  BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL DEFAULT now()
);
CREATE INDEX idx_notification_rules_user ON notification_rules (user_id);

-- 발송 이력: 규칙 충족 시 1건. dedup_key UNIQUE가 멀티 인스턴스 중복 + cooldown(시간 버킷)을 동시에 막는다(ADR-0018 §3).
CREATE TABLE notification_deliveries (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id    BIGINT NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    rule_id    BIGINT REFERENCES notification_rules (id) ON DELETE SET NULL,
    type       VARCHAR(24) NOT NULL,
    title      VARCHAR(120) NOT NULL,
    body       VARCHAR(300) NOT NULL,
    place_id   BIGINT REFERENCES places (id) ON DELETE CASCADE,
    dedup_key  VARCHAR(200) NOT NULL,
    is_read    BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL DEFAULT now(),
    CONSTRAINT uq_notification_deliveries_dedup UNIQUE (dedup_key)
);
-- 알림 센터 = user_id 최신순. 전체스캔 방지(CLAUDE.md §0.4).
CREATE INDEX idx_notification_deliveries_user ON notification_deliveries (user_id, created_at DESC);
