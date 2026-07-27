package com.geuneul.domain.photo;

import com.geuneul.AbstractIntegrationTest;
import com.geuneul.domain.auth.AuthProvider;
import com.geuneul.domain.auth.JwtService;
import com.geuneul.domain.auth.Role;
import com.geuneul.domain.auth.User;
import com.geuneul.domain.auth.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.server.ResponseStatusException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@TestPropertySource(properties = "aws.s3.bucket=bucket")
class PhotoUploadConcurrencyIT extends AbstractIntegrationTest {

    @Autowired PhotoUploadService service;
    @Autowired PhotoUploadRepository uploads;
    @Autowired UserRepository users;
    @MockitoBean S3Client s3;

    @BeforeEach
    void clean() {
        uploads.deleteAll();
        users.deleteAll();
        reset(s3);
    }

    @Test
    @DisplayName("같은 claim의 동시 소비는 row lock 뒤 정확히 한 요청만 성공하고 재사용은 400이다")
    void concurrentClaimConsumptionSucceedsOnce() throws Exception {
        service.register("report/once.jpg", objectUrl("report/once.jpg"), PhotoPurpose.REPORT,
                null, "c:1", "image/jpeg", 123);
        CountDownLatch headEntered = new CountDownLatch(1);
        CountDownLatch releaseHead = new CountDownLatch(1);
        AtomicBoolean first = new AtomicBoolean(true);
        when(s3.headObject(any(HeadObjectRequest.class))).thenAnswer(invocation -> {
            if (first.compareAndSet(true, false)) {
                headEntered.countDown();
                if (!releaseHead.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("test timeout");
            }
            return HeadObjectResponse.builder().contentLength(123L).contentType("image/jpeg").build();
        });

        try (var executor = Executors.newFixedThreadPool(2)) {
            var winner = executor.submit(() -> service.validateReport(objectUrl("report/once.jpg"), null, "c:1"));
            assertThat(headEntered.await(5, TimeUnit.SECONDS)).isTrue();
            var loser = executor.submit(() -> service.validateReport(objectUrl("report/once.jpg"), null, "c:1"));
            assertThatThrownBy(() -> loser.get(200, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);

            releaseHead.countDown();
            winner.get(5, TimeUnit.SECONDS);
            assertThatThrownBy(() -> loser.get(5, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(ResponseStatusException.class);
        } finally {
            releaseHead.countDown();
        }
        verify(s3).headObject(any(HeadObjectRequest.class));
    }

    @Test
    @DisplayName("서로 반대 순서의 review URL 목록도 deterministic lock order로 deadlock 없이 한 번만 소비된다")
    void reviewClaimsUseDeterministicLockOrder() throws Exception {
        User user = users.save(User.create(AuthProvider.KAKAO, "reviewer", null, "reviewer", null));
        JwtService.AuthPrincipal principal = new JwtService.AuthPrincipal(user.getId(), Role.USER);
        service.register("review/a.jpg", objectUrl("review/a.jpg"), PhotoPurpose.REVIEW,
                principal, "ignored", "image/jpeg", 1);
        service.register("review/b.jpg", objectUrl("review/b.jpg"), PhotoPurpose.REVIEW,
                principal, "ignored", "image/jpeg", 1);
        when(s3.headObject(any(HeadObjectRequest.class)))
                .thenReturn(HeadObjectResponse.builder().contentLength(1L).contentType("image/jpeg").build());
        CyclicBarrier start = new CyclicBarrier(2);

        try (var executor = Executors.newFixedThreadPool(2)) {
            var one = executor.submit(() -> consumeReviews(start,
                    List.of(objectUrl("review/a.jpg"), objectUrl("review/b.jpg")), user.getId()));
            var two = executor.submit(() -> consumeReviews(start,
                    List.of(objectUrl("review/b.jpg"), objectUrl("review/a.jpg")), user.getId()));
            List<Throwable> outcomes = java.util.Arrays.asList(
                    one.get(5, TimeUnit.SECONDS), two.get(5, TimeUnit.SECONDS));

            assertThat(outcomes.stream().filter(java.util.Objects::isNull)).hasSize(1);
            assertThat(outcomes.stream().filter(java.util.Objects::nonNull)).hasSize(1);
            assertThat(outcomes.stream().filter(java.util.Objects::nonNull).findFirst().orElseThrow())
                    .isInstanceOf(ResponseStatusException.class);
        }
    }

    private Throwable consumeReviews(CyclicBarrier start, List<String> urls, long userId) {
        try {
            start.await(5, TimeUnit.SECONDS);
            service.validateReview(urls, userId);
            return null;
        } catch (Throwable failure) {
            return failure;
        }
    }

    private static String objectUrl(String key) {
        return "https://bucket.s3.ap-northeast-2.amazonaws.com/" + key;
    }
}
