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
                    + "LEFT JOIN FETCH o.binomCampaigns bc "
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
                    + "LEFT JOIN FETCH o.binomCampaigns bc "
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
}
