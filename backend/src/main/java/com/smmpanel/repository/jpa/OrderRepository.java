// Add these methods to existing OrderRepository.java
package com.smmpanel.repository.jpa;

import com.smmpanel.entity.Order;
import com.smmpanel.entity.OrderStatus;
import com.smmpanel.entity.User;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface OrderRepository
        extends JpaRepository<Order, Long>, JpaSpecificationExecutor<Order>, OptimizedOrderQueries {

    // OPTIMIZED QUERIES WITH JOIN FETCH TO PREVENT N+1 PROBLEMS

    /**
     * Find orders by user with all related entities fetched PREVENTS N+1: Fetches user and service
     * in single query
     */
    @EntityGraph(attributePaths = {"user", "service"})
    Page<Order> findByUser(User user, Pageable pageable);

    /**
     * Find orders by user and status with related entities PREVENTS N+1: Fetches user and service
     * in single query
     */
    @Query(
            "SELECT o FROM Order o JOIN FETCH o.user JOIN FETCH o.service "
                    + "WHERE o.user = :user AND o.status = :status")
    Page<Order> findByUserAndStatus(
            @Param("user") User user, @Param("status") OrderStatus status, Pageable pageable);

    /**
     * Find single order by ID and user with related entities PREVENTS N+1: Fetches user and service
     * in single query
     */
    @Query(
            "SELECT o FROM Order o JOIN FETCH o.user JOIN FETCH o.service "
                    + "WHERE o.id = :id AND o.user = :user")
    Optional<Order> findByIdAndUser(@Param("id") Long id, @Param("user") User user);

    /**
     * Find orders by status with related entities for admin views PREVENTS N+1: Fetches user and
     * service for each order
     */
    @Query(
            "SELECT o FROM Order o JOIN FETCH o.user JOIN FETCH o.service "
                    + "WHERE o.status = :status")
    List<Order> findByStatus(@Param("status") OrderStatus status);

    /**
     * Find orders by multiple statuses with related entities PREVENTS N+1: Fetches user and service
     * for admin dashboards
     */
    @Query(
            "SELECT o FROM Order o JOIN FETCH o.user JOIN FETCH o.service "
                    + "WHERE o.status IN :statuses")
    List<Order> findByStatusIn(@Param("statuses") List<OrderStatus> statuses);

    /**
     * Find orders created after date with all details PREVENTS N+1: Fetches user and service for
     * reporting
     */
    @Query(
            "SELECT o FROM Order o JOIN FETCH o.user JOIN FETCH o.service "
                    + "WHERE o.createdAt >= :date")
    List<Order> findOrdersCreatedAfter(@Param("date") LocalDateTime date);

    /**
     * SPECIALIZED QUERY: Find orders with full details by user ID PREVENTS N+1: Single query with
     * all related entities INCLUDES: user, service, videoProcessing, binomCampaigns
     */
    @Query(
            "SELECT DISTINCT o FROM Order o "
                    + "JOIN FETCH o.user u "
                    + "JOIN FETCH o.service s "
                    + "LEFT JOIN FETCH o.videoProcessing vp "
                    + "WHERE u.id = :userId "
                    + "ORDER BY o.createdAt DESC")
    List<Order> findOrdersWithDetailsByUserId(@Param("userId") Long userId);

    /**
     * SPECIALIZED QUERY: Find orders with details by user ID with pagination PREVENTS N+1: Single
     * query with all related entities
     */
    @Query(
            value =
                    "SELECT DISTINCT o FROM Order o "
                            + "JOIN FETCH o.user u "
                            + "JOIN FETCH o.service s "
                            + "LEFT JOIN FETCH o.videoProcessing vp "
                            + "WHERE u.id = :userId "
                            + "ORDER BY o.createdAt DESC",
            countQuery = "SELECT COUNT(o) FROM Order o WHERE o.user.id = :userId")
    Page<Order> findOrdersWithDetailsByUserId(@Param("userId") Long userId, Pageable pageable);

    /**
     * OPTIMIZED: Single order by ID with all details PREVENTS N+1: Fetches all related entities in
     * one query
     */
    @Query(
            "SELECT o FROM Order o "
                    + "JOIN FETCH o.user u "
                    + "JOIN FETCH o.service s "
                    + "LEFT JOIN FETCH o.videoProcessing vp "
                    + "WHERE o.id = :id")
    Optional<Order> findByIdWithAllDetails(@Param("id") Long id);

    /**
     * OPTIMIZED: Find active orders for processing with details PREVENTS N+1: Used by processing
     * services
     */
    @Query(
            "SELECT o FROM Order o "
                    + "JOIN FETCH o.user u "
                    + "JOIN FETCH o.service s "
                    + "LEFT JOIN FETCH o.videoProcessing vp "
                    + "WHERE o.status IN ('ACTIVE', 'PROCESSING') "
                    + "ORDER BY o.processingPriority DESC, o.createdAt ASC")
    List<Order> findActiveOrdersWithDetails();

    /**
     * OPTIMIZED: Find pending orders for queue processing PREVENTS N+1: Used by automation services
     */
    @Query(
            "SELECT o FROM Order o "
                    + "JOIN FETCH o.user u "
                    + "JOIN FETCH o.service s "
                    + "WHERE o.status = 'PENDING' "
                    + "ORDER BY o.processingPriority DESC, o.createdAt ASC")
    List<Order> findPendingOrdersWithDetails();

    @Query("SELECT COUNT(o) FROM Order o WHERE o.createdAt >= :date")
    Long countOrdersCreatedAfter(@Param("date") LocalDateTime date);

    @Query("SELECT SUM(o.charge) FROM Order o WHERE o.createdAt >= :date")
    Double sumRevenueAfter(@Param("date") LocalDateTime date);

    @Query("SELECT COUNT(o) FROM Order o WHERE o.status = :status AND o.createdAt >= :date")
    Long countByStatusAndCreatedAtAfter(
            @Param("status") OrderStatus status, @Param("date") LocalDateTime date);

    @Query(
            "SELECT DATE(o.createdAt) as date, SUM(o.charge) as revenue "
                    + "FROM Order o WHERE o.createdAt >= :startDate "
                    + "GROUP BY DATE(o.createdAt) ORDER BY DATE(o.createdAt)")
    List<Object[]> getDailyRevenue(@Param("startDate") LocalDateTime startDate);

    long countByStatusIn(List<OrderStatus> statuses);

    long countByStatus(OrderStatus status);

    /**
     * Recent orders in "watchable" statuses for the public landing-page ticker. JOIN FETCH on
     * service avoids N+1 when the caller serializes service.name. Caller must pass a Pageable with
     * a size limit (typically 12–20).
     */
    @Query(
            "SELECT o FROM Order o JOIN FETCH o.service"
                    + " WHERE o.status IN :statuses"
                    + " ORDER BY o.createdAt DESC")
    List<Order> findRecentInStatusesWithService(
            @Param("statuses") List<OrderStatus> statuses, Pageable pageable);

    @Query("SELECT AVG(o.charge) FROM Order o")
    Double calculateAverageOrderValue();

    @EntityGraph(attributePaths = {"user", "service"})
    @Query("SELECT o FROM Order o WHERE o.id = :id")
    Optional<Order> findByIdWithDetails(@Param("id") Long id);

    @Query(
            "SELECT COUNT(o) FROM Order o WHERE o.user.id = :userId AND o.link = :link AND"
                    + " o.createdAt > :createdAt")
    long countByUserIdAndLinkAndCreatedAtAfter(
            @Param("userId") Long userId,
            @Param("link") String link,
            @Param("createdAt") LocalDateTime createdAt);

    /**
     * Sum of "consumed" quantity per (service, link) within a time window. Used for the per-URL
     * quota check on order creation. For COMPLETED/PARTIAL orders we count what was actually
     * delivered ({@code quantity - remains}); for active orders the slot is reserved, so we count
     * the full {@code quantity}. Caller passes the list of statuses to consider — terminal statuses
     * (CANCELLED/FAILED/ERROR/REFILL/SUSPENDED) must be excluded so freed slots are not counted.
     */
    @Query(
            "SELECT COALESCE(SUM(CASE WHEN o.status IN (com.smmpanel.entity.OrderStatus.COMPLETED,"
                    + " com.smmpanel.entity.OrderStatus.PARTIAL) THEN o.quantity -"
                    + " COALESCE(o.remains, 0) ELSE o.quantity END), 0) FROM Order o WHERE"
                    + " o.service.id = :serviceId AND o.link = :link AND o.status IN :statuses AND"
                    + " o.createdAt >= :cutoff")
    Long sumConsumedQuantityByServiceAndLink(
            @Param("serviceId") Long serviceId,
            @Param("link") String link,
            @Param("statuses") List<OrderStatus> statuses,
            @Param("cutoff") LocalDateTime cutoff);

    /**
     * Acquire a transaction-scoped PostgreSQL advisory lock on (serviceId, link). Released
     * automatically on commit or rollback. Serializes concurrent createOrder calls on the same
     * URL+service so the quota aggregate cannot be read-skewed across simultaneous requests.
     *
     * <p>Returns {@code Object} (not a typed value) because {@code pg_advisory_xact_lock} returns
     * SQL {@code void}, which Hibernate hands back as a {@link org.postgresql.util.PGobject} —
     * trying to cast that to {@code Integer} throws {@link ClassCastException}. We discard the
     * result; the lock is acquired by side effect.
     */
    @Query(
            value = "SELECT pg_advisory_xact_lock(hashtext(:link), CAST(:serviceId AS int))",
            nativeQuery = true)
    Object acquireQuotaLock(@Param("serviceId") Long serviceId, @Param("link") String link);

    @Query("SELECT o FROM Order o WHERE o.user.id = :userId AND o.createdAt > :createdAt")
    List<Order> findByUserIdAndCreatedAtAfter(
            @Param("userId") Long userId, @Param("createdAt") LocalDateTime createdAt);

    // User-specific queries
    Page<Order> findByUser_UsernameOrderByCreatedAtDesc(String username, Pageable pageable);

    Optional<Order> findByIdAndUser_Username(Long id, String username);

    Long countByUser_Username(String username);

    Long countByUser_UsernameAndStatus(String username, OrderStatus status);

    @Query("SELECT COALESCE(SUM(o.charge), 0) FROM Order o WHERE o.user.username = :username")
    BigDecimal sumChargeByUser_Username(@Param("username") String username);

    @Query(
            "SELECT COALESCE(SUM(o.quantity - COALESCE(o.remains, 0)), 0) FROM Order o WHERE"
                    + " o.user.username = :username AND o.status IN :statuses")
    Long sumDeliveredByUserAndStatuses(
            @Param("username") String username, @Param("statuses") List<OrderStatus> statuses);

    @Query(
            "SELECT COALESCE(SUM(o.charge), 0) FROM Order o WHERE o.user.username = :username AND"
                    + " o.createdAt >= :date")
    BigDecimal sumChargeByUser_UsernameAndCreatedAtAfter(
            @Param("username") String username, @Param("date") LocalDateTime date);

    @Query("SELECT o FROM Order o WHERE o.status = :status AND o.createdAt < :date")
    List<Order> findByStatusAndCreatedAtBefore(
            @Param("status") OrderStatus status, @Param("date") LocalDateTime date);

    List<Order> findByStatusInAndCreatedAtBefore(
            List<OrderStatus> statuses, LocalDateTime dateTime);

    long countByCreatedAtAfter(LocalDateTime dateTime);

    long countByUserIdAndCreatedAtAfter(Long userId, LocalDateTime dateTime);

    // ERROR RECOVERY QUERIES

    /**
     * Find orders ready for retry with details PREVENTS N+1: Includes user and service for
     * processing context
     */
    @Query(
            "SELECT o FROM Order o "
                    + "JOIN FETCH o.user u "
                    + "JOIN FETCH o.service s "
                    + "WHERE o.nextRetryAt IS NOT NULL AND o.nextRetryAt <= :now "
                    + "AND o.isManuallyFailed = false AND o.retryCount < o.maxRetries "
                    + "ORDER BY o.nextRetryAt ASC")
    Page<Order> findOrdersReadyForRetry(@Param("now") LocalDateTime now, Pageable pageable);

    /**
     * Find orders in dead letter queue with details PREVENTS N+1: Includes user and service for
     * operator review
     */
    @Query(
            "SELECT o FROM Order o "
                    + "JOIN FETCH o.user u "
                    + "JOIN FETCH o.service s "
                    + "WHERE (o.isManuallyFailed = true OR o.retryCount >= o.maxRetries) "
                    + "AND o.status = 'HOLDING' ORDER BY o.updatedAt DESC")
    Page<Order> findDeadLetterQueueOrders(Pageable pageable);

    /** Count failed orders */
    @Query("SELECT COUNT(o) FROM Order o WHERE o.errorMessage IS NOT NULL")
    long countFailedOrders();

    /** Count failed orders since given date */
    @Query(
            "SELECT COUNT(o) FROM Order o WHERE o.errorMessage IS NOT NULL AND o.updatedAt >="
                    + " :since")
    long countFailedOrdersSince(@Param("since") LocalDateTime since);

    /** Count orders in dead letter queue */
    @Query(
            "SELECT COUNT(o) FROM Order o WHERE (o.isManuallyFailed = true OR o.retryCount >="
                    + " o.maxRetries) AND o.status = 'HOLDING'")
    long countDeadLetterQueueOrders();

    /** Count orders pending retry */
    @Query(
            "SELECT COUNT(o) FROM Order o WHERE o.nextRetryAt IS NOT NULL AND o.nextRetryAt > :now "
                    + "AND o.isManuallyFailed = false AND o.retryCount < o.maxRetries")
    long countOrdersPendingRetry(@Param("now") LocalDateTime now);

    /** Get error type statistics */
    @Query(
            "SELECT o.lastErrorType as errorType, COUNT(o) as count "
                    + "FROM Order o WHERE o.lastErrorType IS NOT NULL "
                    + "GROUP BY o.lastErrorType ORDER BY COUNT(o) DESC")
    List<Object[]> getErrorTypeStatistics();

    /**
     * Find orders with specific error type with details PREVENTS N+1: Includes user and service for
     * error analysis
     */
    @Query(
            "SELECT o FROM Order o "
                    + "JOIN FETCH o.user u "
                    + "JOIN FETCH o.service s "
                    + "WHERE o.lastErrorType = :errorType ORDER BY o.updatedAt DESC")
    Page<Order> findOrdersByErrorType(@Param("errorType") String errorType, Pageable pageable);

    /**
     * Find orders that failed in specific phase with details PREVENTS N+1: Includes user and
     * service for phase analysis
     */
    @Query(
            "SELECT o FROM Order o "
                    + "JOIN FETCH o.user u "
                    + "JOIN FETCH o.service s "
                    + "WHERE o.failedPhase = :phase ORDER BY o.updatedAt DESC")
    Page<Order> findOrdersByFailedPhase(@Param("phase") String phase, Pageable pageable);

    /**
     * Find orders with high retry count with details PREVENTS N+1: Includes user and service for
     * retry analysis
     */
    @Query(
            "SELECT o FROM Order o JOIN FETCH o.user u JOIN FETCH o.service s WHERE o.retryCount >="
                    + " :minRetries ORDER BY o.retryCount DESC, o.updatedAt DESC")
    Page<Order> findOrdersWithHighRetryCount(
            @Param("minRetries") int minRetries, Pageable pageable);

    /**
     * Get orders for manual operator review with details PREVENTS N+1: Includes user and service
     * for operator dashboard
     */
    @Query(
            "SELECT o FROM Order o "
                    + "JOIN FETCH o.user u "
                    + "JOIN FETCH o.service s "
                    + "WHERE o.isManuallyFailed = true OR "
                    + "(o.retryCount >= o.maxRetries AND o.status = 'HOLDING') "
                    + "ORDER BY o.updatedAt DESC")
    Page<Order> findOrdersForManualReview(Pageable pageable);

    // Removed findByStatusInAndBinomCampaignIdNotNull - using binomOfferId instead

    /**
     * Find orders by status with Binom offer ID set - for direct campaign connection OPTIMIZED:
     * JOIN FETCH video_processing to prevent N+1 queries in BinomSyncScheduler Schema analysis
     * showed 767,674 sequential scans before index - now uses JOIN for efficiency
     */
    @Query(
            "SELECT DISTINCT o FROM Order o "
                    + "LEFT JOIN FETCH o.videoProcessing vp "
                    + "WHERE o.status IN :statuses "
                    + "AND o.binomOfferId IS NOT NULL")
    List<Order> findByStatusInAndBinomOfferIdNotNull(@Param("statuses") List<OrderStatus> statuses);

    /**
     * OPTIMIZED QUERY WITH INDEX HINT: Find orders using status index Forces use of
     * idx_order_status index for better performance
     */
    @Query(
            value =
                    "SELECT /*+ INDEX(o idx_order_status) */ o.* FROM orders o "
                            + "WHERE o.status = :status AND o.created_at >= :date "
                            + "ORDER BY o.created_at DESC",
            nativeQuery = true)
    List<Order> findOrdersWithIndexHint(
            @Param("status") String status, @Param("date") LocalDateTime date);

    /**
     * BATCH UPDATE: Update status for multiple orders efficiently Prevents multiple individual
     * updates
     */
    @Query(
            "UPDATE Order o SET o.status = :newStatus, o.updatedAt = :updatedAt "
                    + "WHERE o.id IN :orderIds")
    int batchUpdateStatus(
            @Param("orderIds") List<Long> orderIds,
            @Param("newStatus") OrderStatus newStatus,
            @Param("updatedAt") LocalDateTime updatedAt);

    /**
     * BATCH UPDATE: Mark orders as processed with completion data Used for bulk processing
     * completion
     */
    @Query(
            "UPDATE Order o SET o.status = 'COMPLETED', "
                    + "o.updatedAt = :updatedAt, "
                    + "o.remains = 0 "
                    + "WHERE o.id IN :orderIds")
    int batchMarkAsCompleted(
            @Param("orderIds") List<Long> orderIds, @Param("updatedAt") LocalDateTime updatedAt);

    /**
     * OPTIMIZED: Find orders ready for processing with limit Uses native query with LIMIT for
     * better performance
     */
    @Query(
            value =
                    "SELECT o.* FROM orders o "
                            + "WHERE o.status = 'PENDING' "
                            + "AND o.processing_priority >= :minPriority "
                            + "ORDER BY o.processing_priority DESC, o.created_at ASC "
                            + "LIMIT :limit",
            nativeQuery = true)
    List<Order> findTopPendingOrdersForProcessing(
            @Param("minPriority") int minPriority, @Param("limit") int limit);

    /** BATCH DELETE: Remove old completed orders Used for data cleanup operations */
    @Query("DELETE FROM Order o WHERE o.status = 'COMPLETED' " + "AND o.updatedAt < :beforeDate")
    int deleteOldCompletedOrders(@Param("beforeDate") LocalDateTime beforeDate);

    // ==================== SEARCH QUERIES (partition-safe JPQL) ====================

    // --- User search by ID ---
    @Query(
            value =
                    "SELECT o FROM Order o JOIN FETCH o.user u JOIN FETCH o.service s "
                            + "WHERE o.user = :user AND o.id = :orderId",
            countQuery = "SELECT COUNT(o) FROM Order o WHERE o.user = :user AND o.id = :orderId")
    Page<Order> searchByUserAndId(
            @Param("user") User user, @Param("orderId") Long orderId, Pageable pageable);

    @Query(
            value =
                    "SELECT o FROM Order o JOIN FETCH o.user u JOIN FETCH o.service s "
                            + "WHERE o.user = :user AND o.id = :orderId AND o.status = :status",
            countQuery =
                    "SELECT COUNT(o) FROM Order o "
                            + "WHERE o.user = :user AND o.id = :orderId AND o.status = :status")
    Page<Order> searchByUserAndIdAndStatus(
            @Param("user") User user,
            @Param("orderId") Long orderId,
            @Param("status") OrderStatus status,
            Pageable pageable);

    // --- User search by link ---
    @Query(
            value =
                    "SELECT o FROM Order o JOIN FETCH o.user u JOIN FETCH o.service s "
                            + "WHERE o.user = :user AND LOWER(o.link) LIKE :link",
            countQuery =
                    "SELECT COUNT(o) FROM Order o WHERE o.user = :user AND LOWER(o.link) LIKE"
                            + " :link")
    Page<Order> searchByUserAndLink(
            @Param("user") User user, @Param("link") String link, Pageable pageable);

    @Query(
            value =
                    "SELECT o FROM Order o JOIN FETCH o.user u JOIN FETCH o.service s "
                            + "WHERE o.user = :user AND LOWER(o.link) LIKE :link AND o.status ="
                            + " :status",
            countQuery =
                    "SELECT COUNT(o) FROM Order o "
                            + "WHERE o.user = :user AND LOWER(o.link) LIKE :link AND o.status ="
                            + " :status")
    Page<Order> searchByUserAndLinkAndStatus(
            @Param("user") User user,
            @Param("link") String link,
            @Param("status") OrderStatus status,
            Pageable pageable);

    // --- Admin: flexible query with optional filters ---
    // Uses CAST to avoid PostgreSQL "could not determine data type of parameter" error
    @Query(
            value =
                    "SELECT o FROM Order o JOIN FETCH o.user u JOIN FETCH o.service s WHERE"
                        + " (CAST(:status AS string) IS NULL OR o.status = :status) AND"
                        + " (CAST(:fromDate AS string) IS NULL OR o.createdAt >= :fromDate) AND"
                        + " (CAST(:toDate AS string) IS NULL OR o.createdAt <= :toDate) AND"
                        + " (:searchId IS NULL OR o.id = :searchId) AND (:searchUsername IS NULL OR"
                        + " LOWER(u.username) LIKE :searchUsername) AND (:searchLink IS NULL OR"
                        + " LOWER(o.link) LIKE :searchLink)",
            countQuery =
                    "SELECT COUNT(o) FROM Order o JOIN o.user u WHERE (CAST(:status AS string) IS"
                        + " NULL OR o.status = :status) AND (CAST(:fromDate AS string) IS NULL OR"
                        + " o.createdAt >= :fromDate) AND (CAST(:toDate AS string) IS NULL OR"
                        + " o.createdAt <= :toDate) AND (:searchId IS NULL OR o.id = :searchId) AND"
                        + " (:searchUsername IS NULL OR LOWER(o.user.username) LIKE"
                        + " :searchUsername) AND (:searchLink IS NULL OR LOWER(o.link) LIKE"
                        + " :searchLink)")
    Page<Order> adminSearch(
            @Param("status") OrderStatus status,
            @Param("fromDate") LocalDateTime fromDate,
            @Param("toDate") LocalDateTime toDate,
            @Param("searchId") Long searchId,
            @Param("searchUsername") String searchUsername,
            @Param("searchLink") String searchLink,
            Pageable pageable);

    // ==================== REFILL OPERATIONS ====================

    /**
     * Find order by ID with PESSIMISTIC WRITE lock Prevents concurrent refill creation for the same
     * order Used during refill creation to ensure atomicity
     */
    @Query(
            "SELECT o FROM Order o LEFT JOIN FETCH o.service LEFT JOIN FETCH o.user WHERE o.id ="
                    + " :id")
    @org.springframework.data.jpa.repository.Lock(
            jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    Optional<Order> findByIdWithLock(@Param("id") Long id);

    /**
     * Count pending refill orders for a given parent order Prevents duplicate refills when one is
     * already pending
     */
    long countByRefillParentIdAndStatus(Long refillParentId, OrderStatus status);

    /**
     * Find all refill orders for a parent order regardless of status Used for checking refill
     * history and limits
     */
    List<Order> findByRefillParentId(Long refillParentId);

    /**
     * Get the maximum user_order_number for a given user Used when creating new orders to assign
     * sequential order numbers per user
     */
    @Query("SELECT COALESCE(MAX(o.userOrderNumber), 0) FROM Order o WHERE o.user.id = :userId")
    Integer findMaxUserOrderNumberByUserId(@Param("userId") Long userId);
}
