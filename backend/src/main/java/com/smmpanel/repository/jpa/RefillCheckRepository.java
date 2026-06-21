package com.smmpanel.repository.jpa;

import com.smmpanel.entity.RefillCheck;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface RefillCheckRepository extends JpaRepository<RefillCheck, Long> {

    /** Most recent check for an order — the Refill page polls / renders this. */
    Optional<RefillCheck> findFirstByOrderIdOrderByRequestedAtDesc(Long orderId);

    /** In-flight check for an order, if any (idempotency: reuse instead of re-queueing). */
    Optional<RefillCheck> findFirstByOrderIdAndStatusOrderByRequestedAtDesc(
            Long orderId, RefillCheck.Status status);

    /** All checks in a given state — the scheduler polls RUNNING rows. */
    List<RefillCheck> findByStatus(RefillCheck.Status status);

    /** Per-user check count within a window — used for rate limiting the expensive bot check. */
    long countByUserIdAndRequestedAtAfter(Long userId, LocalDateTime cutoff);
}
