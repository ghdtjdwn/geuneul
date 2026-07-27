package com.geuneul.domain.photo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

import java.time.OffsetDateTime;
import java.time.Instant;
import java.util.List;

import java.util.Optional;
import java.util.UUID;

interface PhotoUploadRepository extends JpaRepository<PhotoUpload, UUID> {

    @Query(value = "SELECT CURRENT_TIMESTAMP", nativeQuery = true)
    Instant databaseNowInstant();

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from PhotoUpload p where p.objectUrl = :objectUrl")
    Optional<PhotoUpload> findByObjectUrlForUpdate(@Param("objectUrl") String objectUrl);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from PhotoUpload p where p.id = :id")
    Optional<PhotoUpload> findByIdForUpdate(@Param("id") UUID id);

    @Query(value = """
            SELECT * FROM photo_uploads
            WHERE ((completed_at IS NULL AND expires_at < :expiredBefore) OR detached_at IS NOT NULL)
              AND (cleanup_started_at IS NULL OR cleanup_started_at < :reclaimBefore)
            ORDER BY expires_at, id
            LIMIT :batchSize
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<PhotoUpload> findCleanupCandidates(@Param("expiredBefore") OffsetDateTime expiredBefore,
                                            @Param("reclaimBefore") OffsetDateTime reclaimBefore,
                                            @Param("batchSize") int batchSize);

    @Query(value = """
            SELECT EXISTS (SELECT 1 FROM reports WHERE photo_url = :objectUrl)
                OR EXISTS (
                    SELECT 1 FROM reviews
                    WHERE photos_json IS NOT NULL
                      AND photos_json @> jsonb_build_array(CAST(:objectUrl AS text))
                )
            """, nativeQuery = true)
    boolean isCurrentlyReferenced(@Param("objectUrl") String objectUrl);
}
