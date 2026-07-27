package com.geuneul.domain.photo;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PhotoUploadCleanupJobTest {

    @Test
    @DisplayName("bounded batch를 삭제하고 성공/실패 metric을 기록하며 실패 lease는 재시도 가능하게 해제한다")
    void cleansBatchAndReleasesFailuresForRetry() {
        PhotoUploadCleanupClaimService claimService = mock(PhotoUploadCleanupClaimService.class);
        S3Client s3 = mock(S3Client.class);
        SimpleMeterRegistry metrics = new SimpleMeterRegistry();
        var success = claim("report/ok.jpg");
        var failure = claim("report/fail.jpg");
        when(claimService.claimBatch(2, Duration.ofMinutes(15))).thenReturn(List.of(success, failure));
        when(claimService.prepareDelete(success)).thenReturn(true);
        when(claimService.prepareDelete(failure)).thenReturn(true);
        // Let the first key succeed and only the second fail.
        when(s3.deleteObject(any(DeleteObjectRequest.class))).thenAnswer(invocation -> {
            DeleteObjectRequest request = invocation.getArgument(0);
            if (request.key().contains("fail")) throw new IllegalStateException("s3 unavailable");
            return null;
        });
        PhotoUploadCleanupJob job = new PhotoUploadCleanupJob(claimService,
                new PhotoCleanupProperties(true, 2, Duration.ofMinutes(15)), s3, "bucket", metrics);

        job.cleanup();

        verify(claimService).complete(success);
        verify(claimService).release(failure);
        assertThat(metrics.get("geuneul.photo.cleanup.objects").tag("outcome", "claimed").counter().count())
                .isEqualTo(2);
        assertThat(metrics.get("geuneul.photo.cleanup.objects").tag("outcome", "deleted").counter().count())
                .isEqualTo(1);
        assertThat(metrics.get("geuneul.photo.cleanup.objects").tag("outcome", "failed").counter().count())
                .isEqualTo(1);
    }

    @Test
    @DisplayName("DB claim 실패는 run metric으로 남기고 S3를 호출하지 않는다")
    void recordsClaimFailure() {
        PhotoUploadCleanupClaimService claimService = mock(PhotoUploadCleanupClaimService.class);
        S3Client s3 = mock(S3Client.class);
        SimpleMeterRegistry metrics = new SimpleMeterRegistry();
        when(claimService.claimBatch(50, Duration.ofMinutes(15))).thenThrow(new IllegalStateException("db"));
        PhotoUploadCleanupJob job = new PhotoUploadCleanupJob(claimService,
                new PhotoCleanupProperties(true, 50, Duration.ofMinutes(15)), s3, "bucket", metrics);

        job.cleanup();

        assertThat(metrics.get("geuneul.photo.cleanup.runs").tag("outcome", "claim_failed").counter().count())
                .isEqualTo(1);
        org.mockito.Mockito.verifyNoInteractions(s3);
    }

    private static PhotoUploadCleanupClaimService.CleanupClaim claim(String key) {
        return new PhotoUploadCleanupClaimService.CleanupClaim(UUID.randomUUID(), key, UUID.randomUUID());
    }
}
