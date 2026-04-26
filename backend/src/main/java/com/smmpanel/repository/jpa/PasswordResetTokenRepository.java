package com.smmpanel.repository.jpa;

import com.smmpanel.entity.PasswordResetToken;
import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface PasswordResetTokenRepository extends JpaRepository<PasswordResetToken, Long> {

    Optional<PasswordResetToken> findByTokenHash(String tokenHash);

    /** Rate-limit helper: count active tokens for a user in the last hour. */
    @Query(
            "SELECT COUNT(t) FROM PasswordResetToken t WHERE t.userId = :userId AND t.createdAt > :since")
    long countRecentForUser(@Param("userId") Long userId, @Param("since") LocalDateTime since);

    /** Invalidate any unused reset tokens for a user — called after a successful reset. */
    @Modifying
    @Query(
            "UPDATE PasswordResetToken t SET t.usedAt = :now WHERE t.userId = :userId"
                    + " AND t.usedAt IS NULL")
    int markAllUsedForUser(@Param("userId") Long userId, @Param("now") LocalDateTime now);
}
