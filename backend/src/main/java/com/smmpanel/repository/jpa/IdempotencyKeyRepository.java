package com.smmpanel.repository.jpa;

import com.smmpanel.entity.IdempotencyKey;
import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface IdempotencyKeyRepository extends JpaRepository<IdempotencyKey, Long> {

    Optional<IdempotencyKey> findByUserIdAndIdempotencyKeyAndOperation(
            Long userId, String idempotencyKey, String operation);

    /**
     * Periodic cleanup — expired records have already served their dedup purpose and just take up
     * space. Called by a scheduled job; the {@code idx_idem_expires_at} index keeps it fast even
     * with millions of historical rows.
     */
    @Modifying
    @Query("DELETE FROM IdempotencyKey k WHERE k.expiresAt < :now")
    int deleteExpired(@Param("now") LocalDateTime now);
}
