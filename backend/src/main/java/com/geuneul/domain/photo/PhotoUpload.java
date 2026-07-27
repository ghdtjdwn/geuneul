package com.geuneul.domain.photo;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Server-side claim for one private S3 upload. Raw client URLs are never treated as upload proof. */
@Entity
@Table(name = "photo_uploads")
public class PhotoUpload {

    @Id
    private UUID id;

    @Column(name = "object_key", nullable = false, length = 512, unique = true)
    private String objectKey;

    @Column(name = "object_url", nullable = false, length = 512, unique = true)
    private String objectUrl;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private PhotoPurpose purpose;

    @Column(name = "owner_user_id")
    private Long ownerUserId;

    @Column(name = "owner_client_hash", length = 64)
    private String ownerClientHash;

    @Column(name = "content_type", nullable = false, length = 64)
    private String contentType;

    @Column(name = "content_length", nullable = false)
    private long contentLength;

    @Column(name = "expires_at", nullable = false)
    private OffsetDateTime expiresAt;

    @Column(name = "completed_at")
    private OffsetDateTime completedAt;

    @Column(name = "detached_at")
    private OffsetDateTime detachedAt;

    @Column(name = "cleanup_started_at")
    private OffsetDateTime cleanupStartedAt;

    @Column(name = "cleanup_token")
    private UUID cleanupToken;

    @Column(name = "cleanup_attempts", nullable = false)
    private int cleanupAttempts;

    protected PhotoUpload() {
    }

    static PhotoUpload create(String objectKey, String objectUrl, PhotoPurpose purpose, Long ownerUserId,
                              String ownerClientHash, String contentType, long contentLength,
                              OffsetDateTime expiresAt) {
        PhotoUpload upload = new PhotoUpload();
        upload.id = UUID.randomUUID();
        upload.objectKey = objectKey;
        upload.objectUrl = objectUrl;
        upload.purpose = purpose;
        upload.ownerUserId = ownerUserId;
        upload.ownerClientHash = ownerClientHash;
        upload.contentType = contentType;
        upload.contentLength = contentLength;
        upload.expiresAt = expiresAt;
        return upload;
    }

    void consume(OffsetDateTime at) {
        if (completedAt != null) throw new IllegalStateException("photo upload claim already consumed");
        if (cleanupToken != null) throw new IllegalStateException("photo upload claim is leased for cleanup");
        completedAt = at;
        detachedAt = null;
    }

    void detach(OffsetDateTime at) {
        if (completedAt == null) throw new IllegalStateException("unused photo upload cannot be detached");
        if (cleanupToken != null) throw new IllegalStateException("photo upload claim is leased for cleanup");
        detachedAt = at;
    }

    void startCleanup(OffsetDateTime at, UUID token) {
        if (completedAt != null && detachedAt == null) {
            throw new IllegalStateException("referenced photo upload cannot be cleaned");
        }
        cleanupStartedAt = at;
        cleanupToken = token;
        cleanupAttempts = Math.addExact(cleanupAttempts, 1);
    }

    void releaseCleanup(UUID token) {
        if (!token.equals(cleanupToken)) return;
        cleanupStartedAt = null;
        cleanupToken = null;
    }

    boolean canDeleteForCleanup(UUID token) {
        return (completedAt == null || detachedAt != null) && token.equals(cleanupToken);
    }

    void retainReferenced(UUID token) {
        if (!token.equals(cleanupToken)) return;
        detachedAt = null;
        releaseCleanup(token);
    }

    public UUID getId() { return id; }
    public String getObjectKey() { return objectKey; }
    public String getObjectUrl() { return objectUrl; }
    public PhotoPurpose getPurpose() { return purpose; }
    public Long getOwnerUserId() { return ownerUserId; }
    public String getOwnerClientHash() { return ownerClientHash; }
    public String getContentType() { return contentType; }
    public long getContentLength() { return contentLength; }
    public OffsetDateTime getExpiresAt() { return expiresAt; }
    public OffsetDateTime getCompletedAt() { return completedAt; }
    public OffsetDateTime getDetachedAt() { return detachedAt; }
    public OffsetDateTime getCleanupStartedAt() { return cleanupStartedAt; }
    public UUID getCleanupToken() { return cleanupToken; }
    public int getCleanupAttempts() { return cleanupAttempts; }
}
