package com.geuneul.domain.photo;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/** Short DB transactions lease cleanup work; S3 network calls happen after the row locks are released. */
@Service
public class PhotoUploadCleanupClaimService {

    private final PhotoUploadRepository repository;

    public PhotoUploadCleanupClaimService(PhotoUploadRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public List<CleanupClaim> claimBatch(int batchSize, Duration lease) {
        OffsetDateTime now = repository.databaseNowInstant().atOffset(ZoneOffset.UTC);
        List<PhotoUpload> candidates = repository.findCleanupCandidates(now, now.minus(lease), batchSize);
        return candidates.stream().map(upload -> {
            UUID token = UUID.randomUUID();
            upload.startCleanup(now, token);
            return new CleanupClaim(upload.getId(), upload.getObjectKey(), token);
        }).toList();
    }

    /** Missing rows and stale lease tokens are successful no-ops, making S3-delete/DB-delete retries idempotent. */
    @Transactional
    public boolean prepareDelete(CleanupClaim claim) {
        return repository.findByIdForUpdate(claim.id()).map(upload -> {
            if (!upload.canDeleteForCleanup(claim.token())) return false;
            if (repository.isCurrentlyReferenced(upload.getObjectUrl())) {
                upload.retainReferenced(claim.token());
                return false;
            }
            return true;
        }).orElse(false);
    }

    @Transactional
    public void complete(CleanupClaim claim) {
        repository.findByIdForUpdate(claim.id()).ifPresent(upload -> {
            boolean referenced = repository.isCurrentlyReferenced(upload.getObjectUrl());
            if (upload.canDeleteForCleanup(claim.token()) && !referenced) {
                repository.delete(upload);
            } else if (referenced) {
                upload.retainReferenced(claim.token());
            }
        });
    }

    @Transactional
    public void release(CleanupClaim claim) {
        repository.findByIdForUpdate(claim.id()).ifPresent(upload -> upload.releaseCleanup(claim.token()));
    }

    public record CleanupClaim(UUID id, String objectKey, UUID token) {}
}
