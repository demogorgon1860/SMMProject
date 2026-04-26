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
public interface EmailVerificationTokenRepository extends JpaRepository<EmailVerificationToken, Long> {

    Optional<EmailVerificationToken> findByCodeHash(String codeHash);

    /** Used for the "resend cooldown" check. */
    @Query(
            "SELECT t FROM EmailVerificationToken t WHERE t.userId = :userId AND t.usedAt IS NULL"
                    + " ORDER BY t.createdAt DESC")
    Optional<EmailVerificationToken> findLatestActiveByUserId(@Param("userId") Long userId);

    /** Invalidate all unused codes for a user — called after a successful verify. */
    @Modifying
    @Query(
            "UPDATE EmailVerificationToken t SET t.usedAt = :now WHERE t.userId = :userId"
                    + " AND t.usedAt IS NULL")
    int markAllUsedForUser(@Param("userId") Long userId, @Param("now") LocalDateTime now);
}
