package com.geuneul.domain.photo;

import com.geuneul.domain.auth.JwtService;
import com.geuneul.domain.auth.Role;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PhotoUploadServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-07-27T00:00:00Z"), ZoneOffset.UTC);
    private PhotoUploadRepository repository;
    private S3Client s3;
    private PhotoUploadService service;

    @BeforeEach
    void setUp() {
        repository = mock(PhotoUploadRepository.class);
        s3 = mock(S3Client.class);
        service = new PhotoUploadService(repository, s3, "bucket");
        when(repository.databaseNowInstant()).thenReturn(CLOCK.instant());
    }

    @Test
    @DisplayName("외부 임의 HTTPS URL은 업로드 발급 기록이 없어 거부한다")
    void rejectsArbitraryHttpsUrl() {
        when(repository.findByObjectUrlForUpdate("https://example.com/a.jpg")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.validateReport("https://example.com/a.jpg", null, "c:1"))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("400");
    }

    @Test
    @DisplayName("익명 report 업로드는 같은 pseudonymous client와 완료된 S3 metadata일 때만 승인한다")
    void validatesAnonymousCompletedUpload() {
        PhotoUpload upload = PhotoUpload.create("report/a.jpg", objectUrl("report/a.jpg"), PhotoPurpose.REPORT,
                null, PhotoUploadService.hashClient("c:1"), "image/jpeg", 123,
                OffsetDateTime.now(CLOCK).plusMinutes(10));
        when(repository.findByObjectUrlForUpdate(upload.getObjectUrl())).thenReturn(Optional.of(upload));
        when(s3.headObject(any(HeadObjectRequest.class))).thenReturn(
                HeadObjectResponse.builder().contentLength(123L).contentType("image/jpeg").build());

        service.validateReport(upload.getObjectUrl(), null, "c:1");

        assertThat(upload.getCompletedAt()).isEqualTo(OffsetDateTime.now(CLOCK));

        assertThatThrownBy(() -> service.validateReport(upload.getObjectUrl(), null, "c:1"))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("400");
    }

    @Test
    @DisplayName("review 업로드는 다른 사용자나 report 용도로 재사용할 수 없다")
    void enforcesOwnerAndPurpose() {
        PhotoUpload upload = PhotoUpload.create("review/a.jpg", objectUrl("review/a.jpg"), PhotoPurpose.REVIEW,
                10L, null, "image/jpeg", 123, OffsetDateTime.now(CLOCK).plusMinutes(10));
        when(repository.findByObjectUrlForUpdate(upload.getObjectUrl())).thenReturn(Optional.of(upload));

        assertThatThrownBy(() -> service.validateReview(java.util.List.of(upload.getObjectUrl()), 11L))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("400");
        assertThatThrownBy(() -> service.validateReport(upload.getObjectUrl(),
                new JwtService.AuthPrincipal(10L, Role.USER), "c:1"))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("400");
    }

    @Test
    @DisplayName("S3 HEAD 실패는 미완료 업로드로 거부한다")
    void rejectsIncompleteUpload() {
        PhotoUpload upload = PhotoUpload.create("report/a.jpg", objectUrl("report/a.jpg"), PhotoPurpose.REPORT,
                null, PhotoUploadService.hashClient("c:1"), "image/jpeg", 123,
                OffsetDateTime.now(CLOCK).plusMinutes(10));
        when(repository.findByObjectUrlForUpdate(upload.getObjectUrl())).thenReturn(Optional.of(upload));
        when(s3.headObject(any(HeadObjectRequest.class)))
                .thenThrow(S3Exception.builder().statusCode(404).build());

        assertThatThrownBy(() -> service.validateReport(upload.getObjectUrl(), null, "c:1"))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("400");
    }

    @Test
    @DisplayName("presign 발급은 소유자·용도·예상 metadata claim을 저장한다")
    void registersClaim() {
        service.register("review/a.jpg", objectUrl("review/a.jpg"), PhotoPurpose.REVIEW,
                new JwtService.AuthPrincipal(10L, Role.USER), "c:ignored", "image/jpeg", 123);
        verify(repository).save(any(PhotoUpload.class));
    }

    @Test
    @DisplayName("review claim 목록은 정렬된 순서로 잠그며 중복 URL을 거부한다")
    void locksReviewClaimsDeterministicallyAndRejectsDuplicates() {
        assertThatThrownBy(() -> service.validateReview(java.util.List.of("https://a", "https://a"), 10L))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("400");

        PhotoUpload a = PhotoUpload.create("review/a.jpg", objectUrl("review/a.jpg"), PhotoPurpose.REVIEW,
                10L, null, "image/jpeg", 1, OffsetDateTime.now(CLOCK).plusMinutes(10));
        PhotoUpload b = PhotoUpload.create("review/b.jpg", objectUrl("review/b.jpg"), PhotoPurpose.REVIEW,
                10L, null, "image/jpeg", 1, OffsetDateTime.now(CLOCK).plusMinutes(10));
        when(repository.findByObjectUrlForUpdate(a.getObjectUrl())).thenReturn(Optional.of(a));
        when(repository.findByObjectUrlForUpdate(b.getObjectUrl())).thenReturn(Optional.of(b));
        when(s3.headObject(any(HeadObjectRequest.class)))
                .thenReturn(HeadObjectResponse.builder().contentLength(1L).contentType("image/jpeg").build());

        service.validateReview(java.util.List.of(b.getObjectUrl(), a.getObjectUrl()), 10L);

        var order = org.mockito.Mockito.inOrder(repository);
        order.verify(repository).findByObjectUrlForUpdate(a.getObjectUrl());
        order.verify(repository).findByObjectUrlForUpdate(b.getObjectUrl());
    }

    @Test
    @DisplayName("V21 이전 review에서 제거된 claim 없는 URL은 legacy unmanaged object로 무시한다")
    void ignoresMissingLegacyClaimWhenDetachingExistingReviewPhoto() {
        String legacy = "https://legacy.example/review.jpg";
        when(repository.findByObjectUrlForUpdate(legacy)).thenReturn(Optional.empty());

        service.detachReview(java.util.List.of(legacy), 10L);

        verify(repository).findByObjectUrlForUpdate(legacy);
        verify(s3, never()).headObject(any(HeadObjectRequest.class));
    }

    @Test
    @DisplayName("제거 URL의 claim이 존재하면 owner·purpose·completed invariant를 계속 강제한다")
    void validatesManagedClaimWhenDetachingReviewPhoto() {
        PhotoUpload wrongOwner = PhotoUpload.create("review/wrong-owner.jpg", objectUrl("review/wrong-owner.jpg"),
                PhotoPurpose.REVIEW, 11L, null, "image/jpeg", 1, OffsetDateTime.now(CLOCK).plusMinutes(10));
        PhotoUpload wrongPurpose = PhotoUpload.create("report/wrong-purpose.jpg", objectUrl("report/wrong-purpose.jpg"),
                PhotoPurpose.REPORT, 10L, null, "image/jpeg", 1, OffsetDateTime.now(CLOCK).plusMinutes(10));
        PhotoUpload unused = PhotoUpload.create("review/unused.jpg", objectUrl("review/unused.jpg"),
                PhotoPurpose.REVIEW, 10L, null, "image/jpeg", 1, OffsetDateTime.now(CLOCK).plusMinutes(10));
        when(repository.findByObjectUrlForUpdate(wrongOwner.getObjectUrl())).thenReturn(Optional.of(wrongOwner));
        when(repository.findByObjectUrlForUpdate(wrongPurpose.getObjectUrl())).thenReturn(Optional.of(wrongPurpose));
        when(repository.findByObjectUrlForUpdate(unused.getObjectUrl())).thenReturn(Optional.of(unused));

        assertThatThrownBy(() -> service.detachReview(java.util.List.of(wrongOwner.getObjectUrl()), 10L))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("400");
        assertThatThrownBy(() -> service.detachReview(java.util.List.of(wrongPurpose.getObjectUrl()), 10L))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("400");
        assertThatThrownBy(() -> service.detachReview(java.util.List.of(unused.getObjectUrl()), 10L))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("unused");
    }

    private static String objectUrl(String key) {
        return "https://bucket.s3.ap-northeast-2.amazonaws.com/" + key;
    }
}
