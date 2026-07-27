package com.geuneul.domain.review;

import com.geuneul.domain.auth.AuthProvider;
import com.geuneul.domain.auth.TrustScoreService;
import com.geuneul.domain.auth.User;
import com.geuneul.domain.auth.UserRepository;
import com.geuneul.domain.photo.PhotoService;
import com.geuneul.domain.photo.PhotoUploadService;
import com.geuneul.domain.place.PlaceRepository;
import com.geuneul.domain.review.dto.ReviewCreateRequest;
import com.geuneul.domain.review.dto.ReviewListResponse;
import com.geuneul.domain.review.dto.ReviewResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.server.ResponseStatusException;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import tools.jackson.databind.json.JsonMapper;

import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * DB 없는 순수 오케스트레이션 단위테스트 — Docker 없이 로컬에서 항상 돈다(colima 이슈 무관, TS-009).
 * 실 upsert·조인 SQL 동작은 ReviewFlowIT(Testcontainers)가 검증한다.
 */
class ReviewServiceTest {

    private ReviewRepository reviewRepository;
    private PlaceRepository placeRepository;
    private UserRepository userRepository;
    private TrustScoreService trustScoreService;
    private PhotoUploadService photoUploadService;
    private ReviewService reviewService;

    @BeforeEach
    void setUp() {
        reviewRepository = mock(ReviewRepository.class);
        placeRepository = mock(PlaceRepository.class);
        userRepository = mock(UserRepository.class);
        trustScoreService = mock(TrustScoreService.class);
        // 실 Jackson 3 ObjectMapper — photos<->JSON 직렬화 계약을 실제로 태워 검증한다.
        // PhotoService는 버킷 미설정(빈 문자열)이라 presignGet이 저장 URL을 그대로 통과시킨다(N1 passthrough 분기).
        photoUploadService = mock(PhotoUploadService.class);
        PhotoService photoService = new PhotoService(mock(S3Presigner.class), "", "ap-northeast-2", Clock.systemUTC(), photoUploadService);
        reviewService = new ReviewService(reviewRepository, placeRepository, userRepository,
                trustScoreService, JsonMapper.builder().build(), photoService, photoUploadService);

        when(placeRepository.existsByIdAndDeletedAtIsNull(1L)).thenReturn(true);
        when(userRepository.findById(10L)).thenReturn(Optional.of(user(10L, "그늘러버")));
        when(reviewRepository.lockUserPlace(10L, 1L)).thenReturn(1);
    }

    private static User user(long id, String nickname) {
        User u = User.create(AuthProvider.KAKAO, "kid-" + id, null, nickname, "http://img");
        setId(u, id);
        return u;
    }

    // User#id는 GeneratedValue라 세터가 없다 — 테스트 전용 리플렉션 주입(엔티티 계약 변경 없이).
    private static void setId(User user, long id) {
        try {
            Field f = User.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(user, id);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    @DisplayName("신규 후기: 기존 없음 → save 후 응답 조립(사진 JSON 왕복 포함)")
    void createsNewReview() {
        when(reviewRepository.findByUserIdAndPlaceId(10L, 1L)).thenReturn(Optional.empty());
        when(reviewRepository.saveAndFlush(any(Review.class))).thenAnswer(inv -> inv.getArgument(0));

        ReviewResponse response = reviewService.create(10L,
                new ReviewCreateRequest(1L, 5, " 시원하고 좋아요 ", List.of("https://img/1.jpg", "https://img/2.jpg")));

        assertThat(response.rating()).isEqualTo(5);
        assertThat(response.comment()).isEqualTo("시원하고 좋아요"); // 공백 정규화
        assertThat(response.photos()).containsExactly("https://img/1.jpg", "https://img/2.jpg");
        assertThat(response.authorNickname()).isEqualTo("그늘러버");
        verify(reviewRepository).saveAndFlush(any(Review.class));
        verify(trustScoreService).recalculate(10L); // 후기도 trust_score 활동 신호(TrustScore 근거)
    }

    @Test
    @DisplayName("같은 유저·같은 장소 재작성은 upsert — 기존 엔티티를 갱신하고 신규 저장하지 않는다")
    void rewriteUpdatesExisting() {
        Review existing = Review.of(10L, 1L, (short) 3, "그저 그래요", null);
        when(reviewRepository.findByUserIdAndPlaceId(10L, 1L)).thenReturn(Optional.of(existing));
        when(reviewRepository.saveAndFlush(any(Review.class))).thenAnswer(inv -> inv.getArgument(0));

        ReviewResponse response = reviewService.create(10L, new ReviewCreateRequest(1L, 5, "역시 최고", null));

        assertThat(response.rating()).isEqualTo(5);
        assertThat(response.comment()).isEqualTo("역시 최고");
        assertThat(response.photos()).isEmpty();
        verify(reviewRepository).saveAndFlush(eq(existing)); // 같은 인스턴스를 갱신(신규 Review.of 아님)
    }

    @Test
    @DisplayName("같은 후기의 기존 사진 URL은 유지할 수 있지만 claim을 다시 소비하지 않는다")
    void rewriteRetainsExistingPhotoWithoutReconsumingClaim() {
        Review existing = Review.of(10L, 1L, (short) 3, "기존",
                "[\"https://img/existing.jpg\"]");
        when(reviewRepository.findByUserIdAndPlaceId(10L, 1L)).thenReturn(Optional.of(existing));
        when(reviewRepository.saveAndFlush(any(Review.class))).thenAnswer(inv -> inv.getArgument(0));

        ReviewResponse response = reviewService.create(10L,
                new ReviewCreateRequest(1L, 4, "수정", List.of("https://img/existing.jpg")));

        assertThat(response.photos()).containsExactly("https://img/existing.jpg");
        verify(photoUploadService).validateReview(List.of(), 10L);
        verify(photoUploadService).detachReview(List.of(), 10L);
    }

    @Test
    @DisplayName("후기 사진 A를 B로 교체하면 B를 consume하고 A를 같은 transaction에서 detached 전이한다")
    void rewriteDetachesRemovedPhoto() {
        Review existing = Review.of(10L, 1L, (short) 3, "기존", "[\"https://img/a.jpg\"]");
        when(reviewRepository.findByUserIdAndPlaceId(10L, 1L)).thenReturn(Optional.of(existing));
        when(reviewRepository.saveAndFlush(any(Review.class))).thenAnswer(inv -> inv.getArgument(0));

        reviewService.create(10L,
                new ReviewCreateRequest(1L, 4, "교체", List.of("https://img/b.jpg")));

        verify(photoUploadService).validateReview(List.of("https://img/b.jpg"), 10L);
        verify(photoUploadService).detachReview(List.of("https://img/a.jpg"), 10L);
    }

    @Test
    @DisplayName("없는 장소면 404 — 유령 장소에 후기가 쌓이지 않는다")
    void unknownPlaceIs404() {
        when(placeRepository.existsByIdAndDeletedAtIsNull(999L)).thenReturn(false);

        assertThatThrownBy(() -> reviewService.create(10L, new ReviewCreateRequest(999L, 5, null, null)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("404");
        verify(reviewRepository, never()).saveAndFlush(any());
        verify(trustScoreService, never()).recalculate(anyLong());
    }

    @Test
    @DisplayName("존재하지 않는 유저(구 JWT 등 예외 상황)면 404")
    void unknownUserIs404() {
        when(userRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> reviewService.create(999L, new ReviewCreateRequest(1L, 5, null, null)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("404");
    }

    @Test
    @DisplayName("빈 코멘트는 null로 정규화된다")
    void blankCommentNormalizesToNull() {
        when(reviewRepository.findByUserIdAndPlaceId(10L, 1L)).thenReturn(Optional.empty());
        when(reviewRepository.saveAndFlush(any(Review.class))).thenAnswer(inv -> inv.getArgument(0));

        ReviewResponse response = reviewService.create(10L, new ReviewCreateRequest(1L, 4, "   ", null));

        assertThat(response.comment()).isNull();
    }

    @Test
    @DisplayName("장소 후기 목록 — 없는 장소면 404, 있으면 페이지 응답으로 매핑")
    void listByPlace() {
        when(placeRepository.existsByIdAndDeletedAtIsNull(1L)).thenReturn(true);
        ReviewWithAuthorView view = mock(ReviewWithAuthorView.class);
        when(view.getId()).thenReturn(1L);
        when(view.getPlaceId()).thenReturn(1L);
        when(view.getRating()).thenReturn(5);
        when(view.getComment()).thenReturn("좋아요");
        when(view.getPhotosJson()).thenReturn(null);
        when(view.getNickname()).thenReturn("그늘러버");
        when(view.getProfileImage()).thenReturn(null);
        when(view.getCreatedAt()).thenReturn(Instant.now());
        when(view.getUpdatedAt()).thenReturn(Instant.now());
        Page<ReviewWithAuthorView> page = new PageImpl<>(List.of(view), PageRequest.of(0, 20), 1);
        when(reviewRepository.findByPlaceIdWithAuthor(eq(1L), any())).thenReturn(page);

        ReviewListResponse result = reviewService.listByPlace(1L, 0, 20);

        assertThat(result.reviews()).hasSize(1);
        assertThat(result.totalElements()).isEqualTo(1);
        assertThat(result.hasNext()).isFalse();
        assertThat(result.reviews().get(0).authorNickname()).isEqualTo("그늘러버");
    }

    @Test
    @DisplayName("없는 장소의 후기 목록 조회는 404")
    void listUnknownPlaceIs404() {
        when(placeRepository.existsByIdAndDeletedAtIsNull(999L)).thenReturn(false);
        assertThatThrownBy(() -> reviewService.listByPlace(999L, 0, 20))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("404");
    }
}
