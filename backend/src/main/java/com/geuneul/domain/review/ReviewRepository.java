package com.geuneul.domain.review;

import com.geuneul.domain.activity.MyReviewView;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ReviewRepository extends JpaRepository<Review, Long> {

    /** Transaction-scoped lock also covers the no-row gap before the first review insert. */
    @Query(value = """
            SELECT 1
            FROM (SELECT pg_advisory_xact_lock(
                hashtextextended(CONCAT(CAST(:userId AS text), ':', CAST(:placeId AS text)), 0)
            )) AS locked
            """, nativeQuery = true)
    int lockUserPlace(@Param("userId") long userId, @Param("placeId") long placeId);

    /** 같은 유저·같은 장소의 기존 후기 — upsert 판정(정책: 장소당 1건, Review 클래스 주석 참고). */
    Optional<Review> findByUserIdAndPlaceId(long userId, long placeId);

    /** 공개 가능한 후기 존재 확인 — hidden 후기는 댓글/리액션 대상에서 404로 취급한다. */
    boolean existsByIdAndHiddenFalse(long id);

    /**
     * 유저의 총 후기 수(=리뷰한 장소 수, 장소당 1건 upsert 정책) — trust_score 활동량 신호
     * ({@link com.geuneul.domain.auth.TrustScore}). idx_reviews_user(V6) 경로.
     */
    long countByUserId(long userId);

    /**
     * 장소의 후기 목록(작성자 조인) 최신순 페이지네이션. 네이티브 쿼리라 countQuery를 별도로 명시해야
     * Spring Data가 total count를 낼 수 있다(복잡한 JOIN 쿼리에서 COUNT 자동 유도가 불가능하므로).
     */
    @Query(value = """
            SELECT r.id AS id, r.place_id AS placeId, r.user_id AS authorId, r.rating AS rating,
                   r.comment AS comment, r.photos_json AS photosJson, u.nickname AS nickname,
                   u.profile_image AS profileImage, r.created_at AS createdAt, r.updated_at AS updatedAt
            FROM reviews r
            JOIN users u ON u.id = r.user_id
            WHERE r.place_id = :placeId AND NOT r.hidden
            ORDER BY r.created_at DESC
            """,
            countQuery = "SELECT count(*) FROM reviews r WHERE r.place_id = :placeId AND NOT r.hidden",
            nativeQuery = true)
    Page<ReviewWithAuthorView> findByPlaceIdWithAuthor(@Param("placeId") long placeId, Pageable pageable);

    /**
     * 특정 작성자의 공개 후기 목록(장소명 조인, 최신순) — N6 "내 글 관리" · N7 "작성자 공개 프로필"이 공유한다.
     * 숨김 처리된 후기는 제외(공개·마이 활동 모두 hidden은 안 보인다, 모더레이션 일관).
     */
    @Query(value = """
            SELECT r.id AS id, r.place_id AS placeId, p.name AS placeName, r.rating AS rating,
                   r.comment AS comment, r.created_at AS createdAt
            FROM reviews r
            JOIN places p ON p.id = r.place_id
            WHERE r.user_id = :userId
              AND NOT r.hidden
              AND p.deleted_at IS NULL
            ORDER BY r.created_at DESC
            LIMIT 100
            """, nativeQuery = true)
    List<MyReviewView> findByUserIdWithPlace(@Param("userId") long userId);
}
