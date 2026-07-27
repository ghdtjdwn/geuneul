package com.geuneul.domain.review;

import com.geuneul.domain.auth.TrustScoreService;
import com.geuneul.domain.auth.User;
import com.geuneul.domain.auth.UserRepository;
import com.geuneul.domain.photo.PhotoService;
import com.geuneul.domain.photo.PhotoUploadService;
import com.geuneul.domain.place.PlaceRepository;
import com.geuneul.domain.review.dto.ReviewCreateRequest;
import com.geuneul.domain.review.dto.ReviewListResponse;
import com.geuneul.domain.review.dto.ReviewResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Optional;

import static org.springframework.http.HttpStatus.NOT_FOUND;
import static org.springframework.http.HttpStatus.BAD_REQUEST;

/**
 * 영구 후기(review) 오케스트레이션. survival_score(휘발성 제보)와 완전히 분리된 파이프라인(§5) —
 * ReportService를 참고 삼되 place_report_signals 뷰·SurvivalScore는 절대 건드리지 않는다.
 */
@Service
@Transactional(readOnly = true)
public class ReviewService {

    private final ReviewRepository reviewRepository;
    private final PlaceRepository placeRepository;
    private final UserRepository userRepository;
    private final TrustScoreService trustScoreService;
    private final ObjectMapper objectMapper;
    private final PhotoService photoService;
    private final PhotoUploadService photoUploadService;

    public ReviewService(ReviewRepository reviewRepository, PlaceRepository placeRepository,
                         UserRepository userRepository, TrustScoreService trustScoreService,
                         ObjectMapper objectMapper, PhotoService photoService, PhotoUploadService photoUploadService) {
        this.reviewRepository = reviewRepository;
        this.placeRepository = placeRepository;
        this.userRepository = userRepository;
        this.trustScoreService = trustScoreService;
        this.objectMapper = objectMapper;
        this.photoService = photoService;
        this.photoUploadService = photoUploadService;
    }

    /**
     * 후기 작성/수정. 정책: <b>장소당 1건 upsert</b> — 같은 유저가 같은 장소에 재작성하면
     * 기존 후기를 갱신한다(Review 클래스 주석의 근거 참고). userId는 호출부(컨트롤러)가 JWT에서
     * 뽑아 전달한다 — 요청 바디로 받지 않는다(신원 위조 방지).
     */
    @Transactional
    public ReviewResponse create(long userId, ReviewCreateRequest request) {
        requirePlace(request.placeId());
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "user not found: " + userId));
        // Advisory xact lock serializes both the existing row and the no-row gap for this natural key.
        reviewRepository.lockUserPlace(userId, request.placeId());
        Optional<Review> existing = reviewRepository.findByUserIdAndPlaceId(userId, request.placeId());
        List<String> retainedPhotos = existing.map(review -> toPhotos(review.getPhotosJson())).orElseGet(List::of);
        List<String> requestedPhotos = request.photos() == null ? List.of() : request.photos();
        requireDistinctPhotos(requestedPhotos);
        List<String> newPhotos = requestedPhotos.stream().filter(url -> !retainedPhotos.contains(url)).toList();
        List<String> removedPhotos = retainedPhotos.stream().filter(url -> !requestedPhotos.contains(url)).toList();
        photoUploadService.validateReview(newPhotos, userId);
        photoUploadService.detachReview(removedPhotos, userId);

        short rating = (short) request.rating().intValue();
        String comment = normalize(request.comment());
        String photosJson = toJson(request.photos());

        Review saved = saveUpsert(existing, userId, request.placeId(), rating, comment, photosJson);
        // 후기도 trust_score 활동 신호다(TrustScore 근거) — 신규 작성·재작성(upsert) 모두 재계산해 둔다
        // (재작성은 count가 안 변하지만 age는 계속 흐르므로 최신 유지, 비용은 저빈도 UGC라 무시할 만함).
        trustScoreService.recalculate(userId);
        // 비공개 버킷이라 저장 URL은 그대로 못 본다 → 조회 시점 presigned GET으로 변환해 내려준다(N1).
        return ReviewResponse.of(saved, user.getNickname(), user.getProfileImage(),
                photoService.presignGet(toPhotos(photosJson)));
    }

    /** 장소의 후기 목록 — 공개, 최신순 페이지네이션(작성자 닉네임/프로필 조인). */
    public ReviewListResponse listByPlace(long placeId, int page, int size) {
        requirePlace(placeId);
        Pageable pageable = PageRequest.of(page, size);
        Page<ReviewWithAuthorView> found = reviewRepository.findByPlaceIdWithAuthor(placeId, pageable);
        // 사진 URL은 presignGet으로 임시 서명해 내려준다(N1 — 비공개 S3 403 해소, 버킷 퍼블릭 전환 없이).
        Page<ReviewResponse> mapped = found.map(v -> ReviewResponse.of(v, photoService.presignGet(toPhotos(v.getPhotosJson()))));
        return ReviewListResponse.of(mapped);
    }

    private void requirePlace(long placeId) {
        if (!placeRepository.existsByIdAndDeletedAtIsNull(placeId)) {
            throw new ResponseStatusException(NOT_FOUND, "place not found: " + placeId);
        }
    }

    private Review saveUpsert(Optional<Review> existing, long userId, long placeId,
                              short rating, String comment, String photosJson) {
        return existing
                .map(review -> updateAndSave(review, rating, comment, photosJson))
                .orElseGet(() -> reviewRepository.saveAndFlush(
                        Review.of(userId, placeId, rating, comment, photosJson)));
    }

    private static void requireDistinctPhotos(List<String> requested) {
        if (requested.stream().distinct().count() != requested.size()) {
            throw new ResponseStatusException(BAD_REQUEST, "사진 URL은 중복 없이 제출해야 합니다");
        }
    }

    private Review updateAndSave(Review existing, short rating, String comment, String photosJson) {
        existing.updateContent(rating, comment, photosJson);
        return reviewRepository.saveAndFlush(existing);
    }

    private static String normalize(String comment) {
        if (comment == null) return null;
        String trimmed = comment.strip();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** photos → JSONB 저장 문자열. 빈/미제공이면 null(칼럼 nullable). */
    private String toJson(List<String> photos) {
        if (photos == null || photos.isEmpty()) return null;
        return objectMapper.writeValueAsString(photos);
    }

    /** JSONB 문자열 → photos. null/blank면 빈 리스트(응답에서 [] 로 보이게, null 아님). */
    private List<String> toPhotos(String photosJson) {
        if (photosJson == null || photosJson.isBlank()) return List.of();
        return List.of(objectMapper.readValue(photosJson, String[].class));
    }
}
