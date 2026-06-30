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
     * Refill-only listing for the user-facing {@code /orders} "Refill" tab, with optional
     * search-by-id / search-by-link filters baked into the same query — when the operator types
     * into the search bar on the Refill tab the count + page must honour it (otherwise "No orders
     * match" never shows and pagination lies). Same shape as {@link #adminSearchInStatuses}: every
     * search filter is null-tolerant, JOIN FETCH on user/service keeps the list page N+1-free.
     */
    @Query(
            value =
                    "SELECT o FROM Order o JOIN FETCH o.user u JOIN FETCH o.service s "
                            + "WHERE o.user = :user AND o.isRefill = true "
                            + "AND (:searchId IS NULL OR o.id = :searchId) "
                            + "AND (:searchLink IS NULL OR LOWER(o.link) LIKE :searchLink)",
            countQuery =
                    "SELECT COUNT(o) FROM Order o "
                            + "WHERE o.user = :user AND o.isRefill = true "
                            + "AND (:searchId IS NULL OR o.id = :searchId) "
                            + "AND (:searchLink IS NULL OR LOWER(o.link) LIKE :searchLink)")
    Page<Order> searchUserAndIsRefillTrue(
            @Param("user") User user,
            @Param("searchId") Long searchId,
            @Param("searchLink") String searchLink,
            Pageable pageable);

    /**
     * Admin "Refill" bucket — same shape as {@link #adminSearchInStatuses} but pinned to {@code
     * is_refill = true}. Status is intentionally not a filter (the operator's bucket choice is
     * "refill", and refill rows go through PENDING → IN_PROGRESS → COMPLETED/PARTIAL over their
     * lifetime). Date range + id + username + link work as the operator expects.
     */
    @Query(
            value =
                    "SELECT o FROM Order o JOIN FETCH o.user u JOIN FETCH o.service s "
                            + "WHERE o.isRefill = true "
                            + "AND (CAST(:fromDate AS string) IS NULL OR o.createdAt >= :fromDate)"
                            + " AND (CAST(:toDate AS string) IS NULL OR o.createdAt <= :toDate) "
                            + "AND (:searchId IS NULL OR o.id = :searchId) "
                            + "AND (:searchUsername IS NULL OR LOWER(u.username) LIKE"
                            + " :searchUsername) "
                            + "AND (:searchLink IS NULL OR LOWER(o.link) LIKE :searchLink)",
            countQuery =
                    "SELECT COUNT(o) FROM Order o JOIN o.user u "
                            + "WHERE o.isRefill = true "
                            + "AND (CAST(:fromDate AS string) IS NULL OR o.createdAt >= :fromDate)"
                            + " AND (CAST(:toDate AS string) IS NULL OR o.createdAt <= :toDate) "
                            + "AND (:searchId IS NULL OR o.id = :searchId) "
                            + "AND (:searchUsername IS NULL OR LOWER(u.username) LIKE"
                            + " :searchUsername) "
                            + "AND (:searchLink IS NULL OR LOWER(o.link) LIKE :searchLink)")
    Page<Order> adminSearchRefillOnly(
            @Param("fromDate") LocalDateTime fromDate,
            @Param("toDate") LocalDateTime toDate,
            @Param("searchId") Long searchId,
            @Param("searchUsername") String searchUsername,
            @Param("searchLink") String searchLink,
            Pageable pageable);

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
     * Find orders by user and a set of statuses (for filter coalescing). Used by the user-facing
     * Orders page when the "In progress" filter must match BOTH {@code IN_PROGRESS} and {@code
     * PROCESSING} (we hide PROCESSING from the UI; an order in PROCESSING must still appear when
     * the operator clicks "In progress").
     */
    @Query(
            "SELECT o FROM Order o JOIN FETCH o.user JOIN FETCH o.service "
                    + "WHERE o.user = :user AND o.status IN :statuses")
    Page<Order> findByUserAndStatusIn(
            @Param("user") User user,
            @Param("statuses") java.util.Collection<OrderStatus> statuses,
            Pageable pageable);

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
     * all related entities INCLUDES: user, service
     */
    @Query(
            "SELECT DISTINCT o FROM Order o "
                    + "JOIN FETCH o.user u "
                    + "JOIN FETCH o.service s "
                    + "WHERE u.id = :userId "
                    + "ORDER BY o.createdAt DESC")
    List<Order> findOrdersWithDetailsByUserId(@Param("userId") Long userId);

    /**
     * Batch lookup for the Perfect Panel {@code action=statuses} endpoint. Resellers poll this with
     * 25–50 order IDs every few seconds; the previous implementation loaded ALL of the user's
     * historical orders (could be thousands) and filtered in memory, which dominated the endpoint's
     * latency and turned a fast batch read into an O(allUserOrders) scan.
     *
     * <p>This query restricts the row set to {@code id IN (:ids) AND user = :userId} at the SQL
     * level, with JOIN FETCH on user and service so the response mapper reads both lazy
     * associations without follow-up queries. Authorization is enforced inline (filter by user_id)
     * so a malicious caller can't probe other users' order IDs.
     */
    @Query(
            "SELECT o FROM Order o "
                    + "JOIN FETCH o.user u "
                    + "JOIN FETCH o.service s "
                    + "WHERE o.id IN :ids AND u.id = :userId")
    List<Order> findByIdInAndUserId(
            @Param("ids") java.util.Collection<Long> ids, @Param("userId") Long userId);

    /**
     * SPECIALIZED QUERY: Find orders with details by user ID with pagination PREVENTS N+1: Single
     * query with all related entities
     */
    @Query(
            value =
                    "SELECT DISTINCT o FROM Order o "
                            + "JOIN FETCH o.user u "
                            + "JOIN FETCH o.service s "
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

    /**
     * Per-user order counts for the admin Users table. Single GROUP BY query against an indexed
     * column avoids the N+1 we'd otherwise have if the listing called countByUserId per row.
     * Returns rows of [userId, count] for users with at least one order.
     */
    @Query("SELECT o.user.id, COUNT(o) FROM Order o WHERE o.user.id IN :userIds GROUP BY o.user.id")
    List<Object[]> countOrdersByUserIds(@Param("userIds") List<Long> userIds);

    @Query("SELECT SUM(o.charge) FROM Order o WHERE o.createdAt >= :date")
    Double sumRevenueAfter(@Param("date") LocalDateTime date);

    /**
     * Fulfilled-only revenue: SUM(charge) over orders that actually completed (or partially
     * completed). Excludes CANCELLED, FAILED, REFUND and in-flight statuses. PARTIAL orders already
     * have their {@code charge} shrunk to the delivered fraction by {@code
     * OrderService.markPartialCompletion}, so summing it here yields net profit without a separate
     * refund subtraction. Powers the admin dashboard "last 24h / 7d / 30d" cards — the unfiltered
     * {@link #sumRevenueAfter(LocalDateTime)} above includes pending/cancelled orders and
     * overstates earnings.
     */
    @Query(
            "SELECT COALESCE(SUM(o.charge), 0) FROM Order o WHERE o.createdAt >= :date AND"
                    + " o.status IN (com.smmpanel.entity.OrderStatus.COMPLETED,"
                    + " com.smmpanel.entity.OrderStatus.PARTIAL)")
    Double sumFulfilledRevenueAfter(@Param("date") LocalDateTime date);

    /** Same idea as {@link #sumFulfilledRevenueAfter} but counting orders. */
    @Query(
            "SELECT COUNT(o) FROM Order o WHERE o.createdAt >= :date AND"
                    + " o.status IN (com.smmpanel.entity.OrderStatus.COMPLETED,"
                    + " com.smmpanel.entity.OrderStatus.PARTIAL)")
    Long countFulfilledOrdersAfter(@Param("date") LocalDateTime date);

    @Query("SELECT COUNT(o) FROM Order o WHERE o.status = :status AND o.createdAt >= :date")
    Long countByStatusAndCreatedAtAfter(
            @Param("status") OrderStatus status, @Param("date") LocalDateTime date);

    @Query(
            "SELECT DATE(o.createdAt) as date, SUM(o.charge) as revenue "
                    + "FROM Order o WHERE o.createdAt >= :startDate "
                    + "GROUP BY DATE(o.createdAt) ORDER BY DATE(o.createdAt)")
    List<Object[]> getDailyRevenue(@Param("startDate") LocalDateTime startDate);

    /**
     * Per-day breakdown for the admin dashboard charts. Returns one row per (day, status) tuple so
     * the frontend can render stacked bars (completed / partial / cancelled) and a profit line
     * without inventing data via Math.sin. Cheaper than fetching every order: a single GROUP BY on
     * indexed (created_at, status).
     */
    @Query(
            "SELECT DATE(o.createdAt) as date, o.status as status,"
                    + " COUNT(o) as cnt, COALESCE(SUM(o.charge), 0) as revenue "
                    + "FROM Order o WHERE o.createdAt >= :startDate "
                    + "GROUP BY DATE(o.createdAt), o.status "
                    + "ORDER BY DATE(o.createdAt)")
    List<Object[]> getDailyOrderBreakdown(@Param("startDate") LocalDateTime startDate);

    /**
     * Per-day stats for a single user (the wallet-card sparkline + dashboard KPI deltas on
     * /dashboard). Same shape as getDailyOrderBreakdown but scoped to one userId.
     */
    @Query(
            "SELECT DATE(o.createdAt) as date, o.status as status,"
                    + " COUNT(o) as cnt, COALESCE(SUM(o.charge), 0) as revenue "
                    + "FROM Order o WHERE o.user.id = :userId AND o.createdAt >= :startDate "
                    + "GROUP BY DATE(o.createdAt), o.status "
                    + "ORDER BY DATE(o.createdAt)")
    List<Object[]> getDailyOrderBreakdownForUser(
            @Param("userId") Long userId, @Param("startDate") LocalDateTime startDate);

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
     * Sum of "consumed" quantity for a <em>quota group</em> (a set of service ids) on a link within
     * a time window. Used for the per-URL quota check on order creation. For COMPLETED/PARTIAL
     * orders we count what was actually delivered ({@code quantity - remains}); for active orders
     * the slot is reserved, so we count the full {@code quantity}. Caller passes the list of
     * statuses to consider — terminal statuses (CANCELLED/FAILED/ERROR/REFILL/SUSPENDED) must be
     * excluded so freed slots are not counted.
     *
     * <p>The service ids are the siblings sharing the same action+gender (geo variants and the
     * duplicate id-space 1-25 / 26-50 collapse into one group) — see {@code
     * OrderService.enforceUrlQuota}. The bot's account pool for an action+gender is shared across
     * those services, so ordering the same target through any of them competes for the same
     * accounts; counting them separately let resellers bypass the cap and pile repeat orders onto a
     * link until the bot ran out of fresh accounts and returned PARTIAL.
     */
    @Query(
            "SELECT COALESCE(SUM(CASE WHEN o.status IN (com.smmpanel.entity.OrderStatus.COMPLETED,"
                + " com.smmpanel.entity.OrderStatus.PARTIAL) THEN o.quantity - COALESCE(o.remains,"
                + " 0) ELSE o.quantity END), 0) FROM Order o WHERE o.service.id IN :serviceIds AND"
                + " o.link = :link AND o.status IN :statuses AND o.createdAt >= :cutoff")
    Long sumConsumedQuantityByServiceIdsAndLink(
            @Param("serviceIds") java.util.Collection<Long> serviceIds,
            @Param("link") String link,
            @Param("statuses") List<OrderStatus> statuses,
            @Param("cutoff") LocalDateTime cutoff);

    /**
     * Acquire a transaction-scoped PostgreSQL advisory lock on (quota group, link). Released
     * automatically on commit or rollback. Serializes concurrent createOrder calls on the same link
     * within the same quota group so the aggregate cannot be read-skewed across simultaneous
     * requests. The lock key is the group key (action+gender), NOT the service id — two orders on
     * the same link through different geo/duplicate services of the same group must take the same
     * lock, otherwise they could both read a stale consumed value and jointly overshoot the cap.
     *
     * <p>{@code hashtext} returns {@code int}, matching the two-int form of {@code
     * pg_advisory_xact_lock}. Returns {@code Object} (not a typed value) because the function
     * returns SQL {@code void}, which Hibernate hands back as a {@link
     * org.postgresql.util.PGobject} — casting that to {@code Integer} throws {@link
     * ClassCastException}. We discard the result; the lock is acquired by side effect.
     */
    @Query(
            value = "SELECT pg_advisory_xact_lock(hashtext(:link), hashtext(:groupKey))",
            nativeQuery = true)
    Object acquireQuotaLock(@Param("groupKey") String groupKey, @Param("link") String link);

    @Query("SELECT o FROM Order o WHERE o.user.id = :userId AND o.createdAt > :createdAt")
    List<Order> findByUserIdAndCreatedAtAfter(
            @Param("userId") Long userId, @Param("createdAt") LocalDateTime createdAt);

    // ============ Per-URL order serialization (OrderSerializationService) ============

    /**
     * Transaction-scoped advisory lock keyed ONLY on the link (single-bigint form — a DISJOINT lock
     * space from the two-int {@link #acquireQuotaLock}, so the two can never deadlock). Serializes
     * the "is this URL busy? → dispatch the next" critical section across concurrent pumps.
     * Released on commit/rollback. Returns {@code Object} for the same reason as {@link
     * #acquireQuotaLock} (the void function comes back as a {@link org.postgresql.util.PGobject};
     * do not cast it).
     */
    @Query(value = "SELECT pg_advisory_xact_lock(hashtext(:link))", nativeQuery = true)
    Object acquireUrlSerializationLock(@Param("link") String link);

    /** True if any order for this link currently occupies the URL (status in the active set). */
    boolean existsByLinkAndStatusIn(String link, java.util.Collection<OrderStatus> statuses);

    /**
     * Waiting orders for a link in a given status, oldest first (id ASC = FIFO: 30000 → 30001 → …).
     * JOIN FETCH user+service so the dispatch path has them without a lazy round-trip. Call with a
     * limit-1 {@code Pageable} to get only the next one.
     */
    @Query(
            "SELECT o FROM Order o JOIN FETCH o.user JOIN FETCH o.service"
                    + " WHERE o.link = :link AND o.status = :status ORDER BY o.id ASC")
    List<Order> findOrdersByLinkAndStatusOrderById(
            @Param("link") String link, @Param("status") OrderStatus status, Pageable pageable);

    /**
     * Sweeper backstop — links that have ≥1 PENDING order but NO order currently occupying the URL.
     * These need a pump (the active order reached terminal via a path that did not fire a pump, or
     * a pump was missed across a restart). Ordered/limited by the caller's {@code Pageable}.
     */
    @Query(
            "SELECT DISTINCT o.link FROM Order o"
                    + " WHERE o.status = com.smmpanel.entity.OrderStatus.PENDING"
                    + " AND o.link NOT IN"
                    + " (SELECT o2.link FROM Order o2 WHERE o2.status IN :activeStatuses)")
    List<String> findLinksWithPendingAndNoActive(
            @Param("activeStatuses") java.util.Collection<OrderStatus> activeStatuses,
            Pageable pageable);

    /**
     * Sweeper alert — links whose occupying order has been stuck (its {@code updatedAt} is older
     * than {@code cutoff}) while PENDING orders wait behind it. Alert-only candidates; the sweeper
     * never auto-releases these (preserves start-count correctness — operator resolves manually).
     */
    @Query(
            "SELECT DISTINCT o.link FROM Order o"
                    + " WHERE o.status IN :activeStatuses AND o.updatedAt < :cutoff"
                    + " AND o.link IN"
                    + " (SELECT o2.link FROM Order o2"
                    + " WHERE o2.status = com.smmpanel.entity.OrderStatus.PENDING)")
    List<String> findStuckActiveLinks(
            @Param("activeStatuses") java.util.Collection<OrderStatus> activeStatuses,
            @Param("cutoff") LocalDateTime cutoff,
            Pageable pageable);

    // User-specific queries
    Page<Order> findByUser_UsernameOrderByCreatedAtDesc(String username, Pageable pageable);

    Optional<Order> findByIdAndUser_Username(Long id, String username);

    Long countByUser_Username(String username);

    Long countByUser_UsernameAndStatus(String username, OrderStatus status);

    @Query(
            "SELECT MIN(o.createdAt), MAX(o.createdAt) FROM Order o WHERE o.user.username ="
                    + " :username")
    Object[] firstAndLastOrderAtForUsername(@Param("username") String username);

    @Query(
            "SELECT COALESCE(SUM(o.charge), 0) FROM Order o WHERE o.user.username = :username AND"
                    + " o.status IN :statuses")
    BigDecimal sumChargeByUsernameAndStatuses(
            @Param("username") String username, @Param("statuses") List<OrderStatus> statuses);

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

    // --- User search by ID(s) ---
    /**
     * Multi-id search for the user-facing Orders search box ("29931, 29932, …"). Explicit ids
     * intentionally ignore the status tab — the customer asked for these specific orders, so we
     * return them whatever their state. {@code ids} is always non-empty (the caller falls back to
     * link search otherwise), so the {@code IN} clause is never given a null/empty collection.
     */
    @Query(
            value =
                    "SELECT o FROM Order o JOIN FETCH o.user u JOIN FETCH o.service s "
                            + "WHERE o.user = :user AND o.id IN :ids",
            countQuery =
                    "SELECT COUNT(o) FROM Order o WHERE o.user = :user AND o.id IN :ids")
    Page<Order> searchByUserAndIdIn(
            @Param("user") User user,
            @Param("ids") java.util.Collection<Long> ids,
            Pageable pageable);

    /** Multi-id search pinned to the user's refill bucket (the /orders "Refill" tab). */
    @Query(
            value =
                    "SELECT o FROM Order o JOIN FETCH o.user u JOIN FETCH o.service s "
                            + "WHERE o.user = :user AND o.isRefill = true AND o.id IN :ids",
            countQuery =
                    "SELECT COUNT(o) FROM Order o "
                            + "WHERE o.user = :user AND o.isRefill = true AND o.id IN :ids")
    Page<Order> searchUserAndIsRefillTrueIdIn(
            @Param("user") User user,
            @Param("ids") java.util.Collection<Long> ids,
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

    /**
     * Admin search variant that accepts a SET of statuses instead of a single one. Powers the "In
     * progress" filter chip which (per the UI hide of PROCESSING) must match orders in either
     * {@code IN_PROGRESS} or {@code PROCESSING}.
     */
    @Query(
            value =
                    "SELECT o FROM Order o JOIN FETCH o.user u JOIN FETCH o.service s WHERE"
                        + " (:statuses IS NULL OR o.status IN :statuses) AND (CAST(:fromDate AS"
                        + " string) IS NULL OR o.createdAt >= :fromDate) AND (CAST(:toDate AS"
                        + " string) IS NULL OR o.createdAt <= :toDate) AND (:searchId IS NULL OR"
                        + " o.id = :searchId) AND (:searchUsername IS NULL OR LOWER(u.username)"
                        + " LIKE :searchUsername) AND (:searchLink IS NULL OR LOWER(o.link) LIKE"
                        + " :searchLink)",
            countQuery =
                    "SELECT COUNT(o) FROM Order o JOIN o.user u WHERE (:statuses IS NULL OR"
                        + " o.status IN :statuses) AND (CAST(:fromDate AS string) IS NULL OR"
                        + " o.createdAt >= :fromDate) AND (CAST(:toDate AS string) IS NULL OR"
                        + " o.createdAt <= :toDate) AND (:searchId IS NULL OR o.id = :searchId) AND"
                        + " (:searchUsername IS NULL OR LOWER(o.user.username) LIKE"
                        + " :searchUsername) AND (:searchLink IS NULL OR LOWER(o.link) LIKE"
                        + " :searchLink)")
    Page<Order> adminSearchInStatuses(
            @Param("statuses") java.util.Collection<OrderStatus> statuses,
            @Param("fromDate") LocalDateTime fromDate,
            @Param("toDate") LocalDateTime toDate,
            @Param("searchId") Long searchId,
            @Param("searchUsername") String searchUsername,
            @Param("searchLink") String searchLink,
            Pageable pageable);

    /**
     * Admin multi-id search ("29931, 29932, …"). Explicit ids ignore the status chip (the operator
     * asked for these orders specifically) but still respect the date range. {@code ids} is always
     * non-empty (caller falls back to the heuristic search otherwise).
     */
    @Query(
            value =
                    "SELECT o FROM Order o JOIN FETCH o.user u JOIN FETCH o.service s "
                            + "WHERE o.id IN :ids "
                            + "AND (CAST(:fromDate AS string) IS NULL OR o.createdAt >= :fromDate) "
                            + "AND (CAST(:toDate AS string) IS NULL OR o.createdAt <= :toDate)",
            countQuery =
                    "SELECT COUNT(o) FROM Order o WHERE o.id IN :ids "
                            + "AND (CAST(:fromDate AS string) IS NULL OR o.createdAt >= :fromDate) "
                            + "AND (CAST(:toDate AS string) IS NULL OR o.createdAt <= :toDate)")
    Page<Order> adminSearchByIdIn(
            @Param("fromDate") LocalDateTime fromDate,
            @Param("toDate") LocalDateTime toDate,
            @Param("ids") java.util.Collection<Long> ids,
            Pageable pageable);

    /** Admin multi-id search pinned to the refill bucket (the "Refill" chip). */
    @Query(
            value =
                    "SELECT o FROM Order o JOIN FETCH o.user u JOIN FETCH o.service s "
                            + "WHERE o.isRefill = true AND o.id IN :ids "
                            + "AND (CAST(:fromDate AS string) IS NULL OR o.createdAt >= :fromDate) "
                            + "AND (CAST(:toDate AS string) IS NULL OR o.createdAt <= :toDate)",
            countQuery =
                    "SELECT COUNT(o) FROM Order o WHERE o.isRefill = true AND o.id IN :ids "
                            + "AND (CAST(:fromDate AS string) IS NULL OR o.createdAt >= :fromDate) "
                            + "AND (CAST(:toDate AS string) IS NULL OR o.createdAt <= :toDate)")
    Page<Order> adminSearchRefillOnlyByIdIn(
            @Param("fromDate") LocalDateTime fromDate,
            @Param("toDate") LocalDateTime toDate,
            @Param("ids") java.util.Collection<Long> ids,
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

    /**
     * Count of this user's orders that are still "in flight" — used as a hard guard before account
     * deletion. The user has to cancel or wait for these to finish before erasure can proceed.
     */
    @Query("SELECT COUNT(o) FROM Order o WHERE o.user.id = :userId AND o.status IN :statuses")
    long countByUserIdAndStatusIn(
            @Param("userId") Long userId, @Param("statuses") List<OrderStatus> statuses);
}
