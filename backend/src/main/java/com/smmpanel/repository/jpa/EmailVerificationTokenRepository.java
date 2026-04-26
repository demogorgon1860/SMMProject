package com.smmpanel.repository.jpa;

import com.smmpanel.entity.EmailVerificationToken;
import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface EmailVerificationTokenRepository
        extends JpaRepository<EmailVerificationToken, Long> {

    /**
     * Lookup a fresh, unused token for this specific user. Filtering by user_id avoids the
     * 6-digit-code collision space across users. Returns the newest match if multiple exist (only
     * possible briefly between issue and the markAllUsedForUser sweep).
     */
    @Query(
            "SELECT t FROM EmailVerificationToken t WHERE t.userId = :userId"
                    + " AND t.codeHash = :codeHash AND t.usedAt IS NULL"
                    + " ORDER BY t.createdAt DESC")
    Optional<EmailVerificationToken> findActiveForUser(
            @Param("userId") Long userId, @Param("codeHash") String codeHash);

    /** Used for the "resend cooldown" check. */
    @Query(
            "SELECT t FROM EmailVerificationToken t WHERE t.userId = :userId AND t.usedAt IS NULL"
                    + " ORDER BY t.createdAt DESC")
    Optional<EmailVerificationToken> findLatestActiveByUserId(@Param("userId") Long userId);

    /**
     * Atomic single-shot redeem: flip usedAt only if it's still NULL. JPA returns the row count, so
     * exactly one of N concurrent verify calls wins the race.
     */
    @Modifying
    @Query(
            "UPDATE EmailVerificationToken t SET t.usedAt = :now"
                    + " WHERE t.id = :id AND t.usedAt IS NULL")
    int markUsedIfUnused(@Param("id") Long id, @Param("now") LocalDateTime now);

    /** Invalidate all unused codes for a user — called after a successful verify. */
    @Modifying
    @Query(
            "UPDATE EmailVerificationToken t SET t.usedAt = :now WHERE t.userId = :userId"
                    + " AND t.usedAt IS NULL")
    int markAllUsedForUser(@Param("userId") Long userId, @Param("now") LocalDateTime now);
}
