package com.geuneul.domain.photo;

import com.geuneul.AbstractIntegrationTest;
import com.geuneul.domain.auth.AuthProvider;
import com.geuneul.domain.auth.JwtService;
import com.geuneul.domain.auth.Role;
import com.geuneul.domain.auth.User;
import com.geuneul.domain.auth.UserRepository;
import com.geuneul.domain.place.Place;
import com.geuneul.domain.place.PlaceCategory;
import com.geuneul.domain.place.PlaceRepository;
import com.geuneul.domain.review.ReviewRepository;
import com.geuneul.domain.review.ReviewService;
import com.geuneul.domain.review.Review;
import com.geuneul.domain.review.dto.ReviewCreateRequest;
import com.geuneul.global.geo.GeoUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@TestPropertySource(properties = "aws.s3.bucket=bucket")
class ReviewPhotoTransactionIT extends AbstractIntegrationTest {

    @Autowired ReviewService reviews;
    @Autowired ReviewRepository reviewRepository;
    @Autowired PhotoUploadService uploads;
    @Autowired PhotoUploadCleanupClaimService cleanup;
    @Autowired PhotoUploadRepository uploadRepository;
    @Autowired PlaceRepository places;
    @Autowired UserRepository users;
    @MockitoBean S3Client s3;
    @MockitoBean PhotoService photoService;

    private long userId;
    private long placeId;

    @BeforeEach
    void setUp() {
        reviewRepository.deleteAll();
        uploadRepository.deleteAll();
        places.deleteAll();
        users.deleteAll();
        Place place = places.save(Place.of("tx-place", PlaceCategory.COOLING_SHELTER, null,
                GeoUtils.point(37.5, 127.0), "test", "review-photo-tx"));
        User user = users.save(User.create(AuthProvider.KAKAO, "review-photo-user", null, "tester", null));
        placeId = place.getId();
        userId = user.getId();
        when(s3.headObject(any(HeadObjectRequest.class))).thenReturn(
                HeadObjectResponse.builder().contentLength(1L).contentType("image/jpeg").build());
        when(photoService.presignGet(any(List.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    @DisplayName("동시 첫 review A/B는 natural-key lock으로 직렬화되고 두 photo claim 모두 같은 transaction에서 consume된다")
    void concurrentFirstReviewConsumesBothClaimsWithoutUniqueViolation() throws Exception {
        String a = register("review/a.jpg");
        String b = register("review/b.jpg");

        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> reviews.create(userId,
                    new ReviewCreateRequest(placeId, 4, "a", List.of(a))));
            var second = executor.submit(() -> reviews.create(userId,
                    new ReviewCreateRequest(placeId, 5, "b", List.of(b))));
            first.get(10, TimeUnit.SECONDS);
            second.get(10, TimeUnit.SECONDS);
        }

        assertThat(reviewRepository.count()).isEqualTo(1);
        String finalUrl = reviewRepository.findByUserIdAndPlaceId(userId, placeId).orElseThrow()
                .getPhotosJson().contains(a) ? a : b;
        String removedUrl = finalUrl.equals(a) ? b : a;
        assertThat(uploadByUrl(finalUrl).getCompletedAt()).isNotNull();
        assertThat(uploadByUrl(finalUrl).getDetachedAt()).isNull();
        assertThat(uploadByUrl(removedUrl).getCompletedAt()).isNotNull();
        assertThat(uploadByUrl(removedUrl).getDetachedAt()).isNotNull();

        var detached = cleanup.claimBatch(10, java.time.Duration.ofMinutes(15));
        assertThat(detached).singleElement().satisfies(claim ->
                assertThat(claim.objectKey()).contains(removedUrl.endsWith("a.jpg") ? "a.jpg" : "b.jpg"));
        assertThat(cleanup.prepareDelete(detached.getFirst())).isTrue();
        cleanup.complete(detached.getFirst());
        cleanup.complete(detached.getFirst());
        assertThat(uploadRepository.findAll()).singleElement().satisfies(upload ->
                assertThat(upload.getObjectUrl()).isEqualTo(finalUrl));
    }

    @Test
    @DisplayName("사진 A→B 교체 review flush 실패는 A detach와 B consume을 둘 다 rollback한다")
    void reviewReplacementFailureRollsBackDetachAndConsumption() {
        String a = register("review/rollback-a.jpg");
        reviews.create(userId, new ReviewCreateRequest(placeId, 5, "a", List.of(a)));
        String b = register("review/rollback-b.jpg");

        assertThatThrownBy(() -> reviews.create(userId,
                new ReviewCreateRequest(placeId, 99, "invalid", List.of(b))))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(reviewRepository.findByUserIdAndPlaceId(userId, placeId).orElseThrow().getPhotosJson()).contains(a);
        assertThat(uploadByUrl(a).getCompletedAt()).isNotNull();
        assertThat(uploadByUrl(a).getDetachedAt()).isNull();
        assertThat(uploadByUrl(b).getCompletedAt()).isNull();

        reviews.create(userId, new ReviewCreateRequest(placeId, 5, "retry", List.of(b)));
        assertThat(reviewRepository.count()).isEqualTo(1);
        assertThat(uploadByUrl(a).getDetachedAt()).isNotNull();
        assertThat(uploadByUrl(b).getCompletedAt()).isNotNull();
        assertThat(uploadByUrl(b).getDetachedAt()).isNull();
    }

    @Test
    @DisplayName("detached 표시가 잘못된 현재 참조 사진은 cleanup 직전 DB 재확인으로 복구·보호한다")
    void cleanupRecheckProtectsCurrentlyReferencedPhoto() {
        String photo = register("review/reference-guard.jpg");
        reviews.create(userId, new ReviewCreateRequest(placeId, 5, "kept", List.of(photo)));
        PhotoUpload upload = uploadByUrl(photo);
        upload.detach(uploadRepository.databaseNowInstant().atOffset(java.time.ZoneOffset.UTC));
        uploadRepository.saveAndFlush(upload);

        var claim = cleanup.claimBatch(1, java.time.Duration.ofMinutes(15)).getFirst();

        assertThat(cleanup.prepareDelete(claim)).isFalse();
        cleanup.complete(claim);
        assertThat(uploadRepository.findById(upload.getId())).get().satisfies(protectedUpload -> {
            assertThat(protectedUpload.getDetachedAt()).isNull();
            assertThat(protectedUpload.getCleanupToken()).isNull();
        });
    }

    @Test
    @DisplayName("V21 이전 claim 없는 legacy 사진은 review에서 제거해도 수정이 성공한다")
    void removesLegacyUnmanagedPhotoWithoutClaim() {
        String legacy = "https://legacy.example/review-a.jpg";
        reviewRepository.saveAndFlush(Review.of(userId, placeId, (short) 4, "legacy",
                "[\"" + legacy + "\"]"));

        reviews.create(userId, new ReviewCreateRequest(placeId, 5, "removed", List.of()));

        assertThat(reviewRepository.findByUserIdAndPlaceId(userId, placeId).orElseThrow().getPhotosJson()).isNull();
        assertThat(uploadRepository.count()).isZero();
    }

    @Test
    @DisplayName("V21 이전 legacy 사진을 새 managed 사진으로 교체하면 새 claim만 consume한다")
    void replacesLegacyUnmanagedPhotoWithManagedClaim() {
        String legacy = "https://legacy.example/review-a.jpg";
        reviewRepository.saveAndFlush(Review.of(userId, placeId, (short) 4, "legacy",
                "[\"" + legacy + "\"]"));
        String managed = register("review/managed-b.jpg");

        reviews.create(userId, new ReviewCreateRequest(placeId, 5, "replaced", List.of(managed)));

        assertThat(reviewRepository.findByUserIdAndPlaceId(userId, placeId).orElseThrow().getPhotosJson())
                .contains(managed).doesNotContain(legacy);
        assertThat(uploadRepository.findAll()).singleElement().satisfies(upload -> {
            assertThat(upload.getObjectUrl()).isEqualTo(managed);
            assertThat(upload.getCompletedAt()).isNotNull();
            assertThat(upload.getDetachedAt()).isNull();
        });
    }

    private String register(String key) {
        String url = "https://bucket.s3.ap-northeast-2.amazonaws.com/" + key;
        uploads.register(key, url, PhotoPurpose.REVIEW,
                new JwtService.AuthPrincipal(userId, Role.USER), "ignored", "image/jpeg", 1);
        return url;
    }

    private PhotoUpload uploadByUrl(String url) {
        return uploadRepository.findAll().stream()
                .filter(upload -> url.equals(upload.getObjectUrl()))
                .findFirst().orElseThrow();
    }
}
