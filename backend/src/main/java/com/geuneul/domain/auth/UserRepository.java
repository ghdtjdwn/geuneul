package com.geuneul.domain.auth;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {

    /** Atomic no-row-gap insert. Conflict losers re-read the winner under the row lock below. */
    @Modifying(flushAutomatically = true)
    @Query(value = """
            INSERT INTO users(provider, provider_id, email, nickname, profile_image)
            VALUES (:provider, :providerId, :email, :nickname, :profileImage)
            ON CONFLICT (provider, provider_id) DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(@Param("provider") String provider,
                       @Param("providerId") String providerId,
                       @Param("email") String email,
                       @Param("nickname") String nickname,
                       @Param("profileImage") String profileImage);

    /** Login profile refresh and logout revocation share this row lock as their linearization boundary. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from User u where u.provider = :provider and u.providerId = :providerId")
    Optional<User> findByProviderAndProviderIdForUpdate(@Param("provider") AuthProvider provider,
                                                        @Param("providerId") String providerId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from User u where u.id = :id")
    Optional<User> findByIdForUpdate(@Param("id") long id);
}
