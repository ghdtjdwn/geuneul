package com.geuneul.domain.photo;

import com.geuneul.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@TestPropertySource(properties = "aws.s3.bucket=bucket")
class PhotoUploadCleanupClaimServiceIT extends AbstractIntegrationTest {

    @Autowired PhotoUploadRepository repository;
    @Autowired PhotoUploadCleanupClaimService service;
    @Autowired PhotoUploadService uploads;
    @Autowired Clock clock;
    @MockitoBean S3Client s3;

    @BeforeEach
    void clean() {
        repository.deleteAll();
    }

    @Test
    @DisplayName("expired unused claim query는 bounded lease·release retry·idempotent completion을 실제 Postgres에서 보장한다")
    void leasesAndRetriesExpiredClaims() {
        PhotoUpload expired = PhotoUpload.create("report/expired.jpg", objectUrl("report/expired.jpg"),
                PhotoPurpose.REPORT, null, PhotoUploadService.hashClient("c:1"), "image/jpeg", 1,
                OffsetDateTime.now(clock).minusMinutes(1));
        PhotoUpload current = PhotoUpload.create("report/current.jpg", objectUrl("report/current.jpg"),
                PhotoPurpose.REPORT, null, PhotoUploadService.hashClient("c:1"), "image/jpeg", 1,
                OffsetDateTime.now(clock).plusMinutes(10));
        repository.saveAll(List.of(expired, current));

        var first = service.claimBatch(1, Duration.ofMinutes(15));
        assertThat(first).singleElement().satisfies(claim -> {
            assertThat(claim.id()).isEqualTo(expired.getId());
            assertThat(claim.objectKey()).isEqualTo("report/expired.jpg");
        });
        assertThat(service.claimBatch(1, Duration.ofMinutes(15))).isEmpty();

        service.release(first.getFirst());
        var retry = service.claimBatch(1, Duration.ofMinutes(15));
        assertThat(retry).hasSize(1);
        assertThat(retry.getFirst().token()).isNotEqualTo(first.getFirst().token());

        service.complete(retry.getFirst());
        service.complete(retry.getFirst());
        assertThat(repository.findById(expired.getId())).isEmpty();
        assertThat(repository.findById(current.getId())).isPresent();
    }

    @Test
    @DisplayName("소비자가 row lock을 잡은 사이 만료돼도 cleanup은 commit 후 completed DB 상태를 보고 객체를 삭제하지 않는다")
    void cleanupCannotDeleteObjectConsumedAcrossExpiryBoundary() throws Exception {
        OffsetDateTime expiresAt = repository.databaseNowInstant().atOffset(java.time.ZoneOffset.UTC)
                .plusNanos(500_000_000);
        PhotoUpload upload = repository.save(PhotoUpload.create(
                "report/race.jpg", objectUrl("report/race.jpg"), PhotoPurpose.REPORT,
                null, PhotoUploadService.hashClient("c:race"), "image/jpeg", 1, expiresAt));
        CountDownLatch headEntered = new CountDownLatch(1);
        CountDownLatch releaseHead = new CountDownLatch(1);
        when(s3.headObject(any(HeadObjectRequest.class))).thenAnswer(invocation -> {
            headEntered.countDown();
            if (!releaseHead.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("test timeout");
            return HeadObjectResponse.builder().contentLength(1L).contentType("image/jpeg").build();
        });

        try (var executor = Executors.newFixedThreadPool(2)) {
            var consume = executor.submit(() -> uploads.validateReport(upload.getObjectUrl(), null, "c:race"));
            assertThat(headEntered.await(5, TimeUnit.SECONDS)).isTrue();
            while (!repository.databaseNowInstant().isAfter(expiresAt.toInstant())) Thread.sleep(10);

            var cleanup = executor.submit(() -> service.claimBatch(1, Duration.ofMinutes(15)));
            // SKIP LOCKED does not wait for the consumer; it omits the locked candidate immediately.
            assertThat(cleanup.get(5, TimeUnit.SECONDS)).isEmpty();

            releaseHead.countDown();
            consume.get(5, TimeUnit.SECONDS);
        } finally {
            releaseHead.countDown();
        }

        assertThat(repository.findById(upload.getId())).get()
                .extracting(PhotoUpload::getCompletedAt).isNotNull();
    }

    private static String objectUrl(String key) {
        return "https://bucket.s3.ap-northeast-2.amazonaws.com/" + key;
    }
}
