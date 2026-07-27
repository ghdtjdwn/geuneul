package com.geuneul.domain.photo;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;

import java.util.List;

/** Deletes one bounded batch of expired unused or review-detached objects after a live-reference recheck. */
@Component
@ConditionalOnProperty(prefix = "geuneul.photo-cleanup", name = "enabled", havingValue = "true", matchIfMissing = true)
public class PhotoUploadCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(PhotoUploadCleanupJob.class);

    private final PhotoUploadCleanupClaimService claims;
    private final PhotoCleanupProperties properties;
    private final S3Client s3;
    private final String bucket;
    private final Counter claimed;
    private final Counter deleted;
    private final Counter failed;
    private final Counter runFailed;

    public PhotoUploadCleanupJob(PhotoUploadCleanupClaimService claims, PhotoCleanupProperties properties,
                                 S3Client s3,
                                 @org.springframework.beans.factory.annotation.Value("${aws.s3.bucket:}") String bucket,
                                 MeterRegistry metrics) {
        this.claims = claims;
        this.properties = properties;
        this.s3 = s3;
        this.bucket = bucket;
        this.claimed = metrics.counter("geuneul.photo.cleanup.objects", "outcome", "claimed");
        this.deleted = metrics.counter("geuneul.photo.cleanup.objects", "outcome", "deleted");
        this.failed = metrics.counter("geuneul.photo.cleanup.objects", "outcome", "failed");
        this.runFailed = metrics.counter("geuneul.photo.cleanup.runs", "outcome", "claim_failed");
    }

    @Scheduled(fixedDelayString = "${geuneul.photo-cleanup.fixed-delay-ms:600000}",
            initialDelayString = "${geuneul.photo-cleanup.initial-delay-ms:600000}")
    public void cleanup() {
        List<PhotoUploadCleanupClaimService.CleanupClaim> batch;
        try {
            batch = claims.claimBatch(properties.batchSize(), properties.lease());
            claimed.increment(batch.size());
        } catch (RuntimeException databaseFailure) {
            runFailed.increment();
            log.warn("[photo-cleanup] claim batch failed(errorCode={})",
                    databaseFailure.getClass().getSimpleName());
            return;
        }
        for (var claim : batch) cleanupOne(claim);
    }

    private void cleanupOne(PhotoUploadCleanupClaimService.CleanupClaim claim) {
        try {
            if (!claims.prepareDelete(claim)) return;
            // S3 DELETE is idempotent: retrying after DB completion failure is safe even when the key is already absent.
            s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(claim.objectKey()).build());
            claims.complete(claim);
            deleted.increment();
        } catch (RuntimeException failure) {
            failed.increment();
            try {
                claims.release(claim);
            } catch (RuntimeException releaseFailure) {
                // The lease timeout makes a failed release recoverable by a later scheduler instance.
                log.warn("[photo-cleanup] lease release failed(errorCode={})",
                        releaseFailure.getClass().getSimpleName());
            }
            log.warn("[photo-cleanup] object cleanup failed(errorCode={})",
                    failure.getClass().getSimpleName());
        }
    }
}
