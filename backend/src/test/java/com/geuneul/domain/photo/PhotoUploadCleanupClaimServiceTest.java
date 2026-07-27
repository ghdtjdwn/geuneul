package com.geuneul.domain.photo;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PhotoUploadCleanupClaimServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-07-27T00:00:00Z"), ZoneOffset.UTC);

    @Test
    @DisplayName("cleanup lease token이 일치할 때만 완료 삭제하며 오래된 worker의 release는 새 lease를 건드리지 않는다")
    void leaseTokenProtectsIdempotentCompletionAndRetry() {
        PhotoUploadRepository repository = mock(PhotoUploadRepository.class);
        PhotoUpload upload = PhotoUpload.create("report/a.jpg", "https://bucket/report/a.jpg", PhotoPurpose.REPORT,
                null, PhotoUploadService.hashClient("c:1"), "image/jpeg", 1,
                OffsetDateTime.now(CLOCK).minusMinutes(1));
        when(repository.findCleanupCandidates(OffsetDateTime.now(CLOCK),
                OffsetDateTime.now(CLOCK).minusMinutes(15), 1)).thenReturn(List.of(upload));
        when(repository.databaseNowInstant()).thenReturn(CLOCK.instant());
        PhotoUploadCleanupClaimService service = new PhotoUploadCleanupClaimService(repository);

        var first = service.claimBatch(1, Duration.ofMinutes(15)).getFirst();
        var newerToken = java.util.UUID.randomUUID();
        upload.startCleanup(OffsetDateTime.now(CLOCK).plusMinutes(16), newerToken);
        when(repository.findByIdForUpdate(upload.getId())).thenReturn(Optional.of(upload));

        service.release(first);
        service.complete(first);

        assertThat(upload.getCleanupToken()).isEqualTo(newerToken);
        verify(repository, never()).delete(upload);
    }

    @Test
    @DisplayName("완료 단계는 token이 일치해도 consumed claim을 삭제하지 않는다")
    void completionNeverDeletesConsumedClaim() {
        PhotoUploadRepository repository = mock(PhotoUploadRepository.class);
        PhotoUpload upload = PhotoUpload.create("report/legacy.jpg", "https://bucket/report/legacy.jpg",
                PhotoPurpose.REPORT, null, PhotoUploadService.hashClient("c:1"), "image/jpeg", 1,
                OffsetDateTime.now(CLOCK).minusMinutes(1));
        java.util.UUID token = java.util.UUID.randomUUID();
        // Simulates a pre-invariant/legacy row so the completion guard itself remains regression-covered.
        ReflectionTestUtils.setField(upload, "completedAt", OffsetDateTime.now(CLOCK));
        ReflectionTestUtils.setField(upload, "cleanupStartedAt", OffsetDateTime.now(CLOCK));
        ReflectionTestUtils.setField(upload, "cleanupToken", token);
        when(repository.findByIdForUpdate(upload.getId())).thenReturn(Optional.of(upload));
        when(repository.isCurrentlyReferenced(upload.getObjectUrl())).thenReturn(true);
        PhotoUploadCleanupClaimService service = new PhotoUploadCleanupClaimService(repository);

        service.complete(new PhotoUploadCleanupClaimService.CleanupClaim(upload.getId(), upload.getObjectKey(), token));

        verify(repository, never()).delete(upload);
        assertThat(upload.getCleanupToken()).isNull();
    }
}
