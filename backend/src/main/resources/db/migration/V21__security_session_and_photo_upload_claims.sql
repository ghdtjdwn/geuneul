-- Security hardening: immediately revocable JWT sessions and server-validated S3 upload claims.

ALTER TABLE users
    ADD COLUMN token_version BIGINT NOT NULL DEFAULT 0,
    ADD CONSTRAINT chk_users_token_version_nonnegative CHECK (token_version >= 0);

CREATE TABLE photo_uploads (
    id                UUID PRIMARY KEY,
    object_key        VARCHAR(512) NOT NULL UNIQUE,
    object_url        VARCHAR(512) NOT NULL UNIQUE,
    purpose           VARCHAR(16) NOT NULL CHECK (purpose IN ('REPORT', 'REVIEW')),
    owner_user_id     BIGINT REFERENCES users(id) ON DELETE CASCADE,
    owner_client_hash VARCHAR(64),
    content_type      VARCHAR(64) NOT NULL,
    content_length    BIGINT NOT NULL CHECK (content_length > 0),
    expires_at        TIMESTAMPTZ NOT NULL,
    completed_at      TIMESTAMPTZ,
    detached_at       TIMESTAMPTZ,
    cleanup_started_at TIMESTAMPTZ,
    cleanup_token     UUID,
    cleanup_attempts  INTEGER NOT NULL DEFAULT 0 CHECK (cleanup_attempts >= 0),
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_photo_upload_owner CHECK (
        (owner_user_id IS NOT NULL AND owner_client_hash IS NULL)
        OR (owner_user_id IS NULL AND owner_client_hash IS NOT NULL)
    ),
    CONSTRAINT chk_review_upload_owner CHECK (purpose <> 'REVIEW' OR owner_user_id IS NOT NULL),
    CONSTRAINT chk_photo_cleanup_lease CHECK (
        (cleanup_started_at IS NULL AND cleanup_token IS NULL)
        OR (cleanup_started_at IS NOT NULL AND cleanup_token IS NOT NULL)
    ),
    CONSTRAINT chk_photo_detached_consumed CHECK (
        detached_at IS NULL OR completed_at IS NOT NULL
    ),
    CONSTRAINT chk_photo_attached_not_leased CHECK (
        cleanup_started_at IS NULL OR completed_at IS NULL OR detached_at IS NOT NULL
    )
);

CREATE INDEX idx_photo_uploads_cleanup
    ON photo_uploads (expires_at, id)
    WHERE completed_at IS NULL OR detached_at IS NOT NULL;

CREATE TABLE report_cache_generations (
    place_id   BIGINT PRIMARY KEY REFERENCES places(id) ON DELETE CASCADE,
    generation BIGINT NOT NULL DEFAULT 0 CHECK (generation >= 0)
);
