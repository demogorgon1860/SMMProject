package com.smmpanel.repository.jpa;

import com.smmpanel.entity.RefillRequest;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface RefillRequestRepository extends JpaRepository<RefillRequest, Long> {

    /** All requests ever made on this order, newest first. Used by admin order-detail history. */
    List<RefillRequest> findByOrderIdOrderByCreatedAtDesc(Long orderId);

    /**
     * Single PENDING request on an order, if any — there can be at most one (DB partial unique).
     */
    Optional<RefillRequest> findFirstByOrderIdAndStatus(Long orderId, RefillRequest.Status status);

    /**
     * Newest still-active request on an order. "Active" = CHECKING or PENDING — the active-set
     * partial unique index guarantees at most one such row, so this is the idempotency anchor for
     * {@code createRequest} (a re-submit returns the in-flight request instead of duplicating it).
     */
    Optional<RefillRequest> findFirstByOrderIdAndStatusInOrderByCreatedAtDesc(
            Long orderId, java.util.Collection<RefillRequest.Status> statuses);

    /** Has the order ever been approved-refilled through the request flow? */
    boolean existsByOrderIdAndStatus(Long orderId, RefillRequest.Status status);

    /** In-flight auto-check requests for the scheduler to advance (start check / finalize). */
    List<RefillRequest> findByStatusOrderByCreatedAtAsc(RefillRequest.Status status);

    /** User's own request history (newest first). */
    List<RefillRequest> findByUserIdOrderByCreatedAtDesc(Long userId);

    long countByUserId(Long userId);

    /**
     * Admin queue, optionally filtered by status. {@code null} returns everything (used by the "all
     * decisions" tab).
     */
    @Query(
            "SELECT r FROM RefillRequest r WHERE (:status IS NULL OR r.status = :status) ORDER BY"
                    + " CASE WHEN r.status = 'PENDING' THEN 0 ELSE 1 END, r.createdAt DESC")
    Page<RefillRequest> adminSearch(
            @Param("status") RefillRequest.Status status, Pageable pageable);

    long countByStatus(RefillRequest.Status status);
}
