package com.smmpanel.repository.jpa;

import com.smmpanel.entity.Order;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * SPECIALIZED OPTIMIZED QUERIES
 *
 * <p>Additional repository methods designed to prevent N+1 queries for specific use cases and
 * complex reporting scenarios.
 *
 * <p>These methods are designed as separate interfaces to be mixed into repositories that need
 * specialized query optimizations.
 */
public interface OptimizedOrderQueries {

    // DASHBOARD AND REPORTING QUERIES

    /**
     * ADMIN DASHBOARD: Orders for admin overview with full context PREVENTS N+1: Fetches user,
     * service, and processing details
     */
    @Query(
            "SELECT DISTINCT o FROM Order o "
                    + "JOIN FETCH o.user u "
                    + "JOIN FETCH o.service s "
                    + "LEFT JOIN FETCH o.videoProcessing vp "
                    + "LEFT JOIN FETCH o.binomCampaigns bc "
                    + "WHERE o.createdAt >= :since "
                    + "ORDER BY o.createdAt DESC")
    List<Order> findRecentOrdersForDashboard(
            @Param("since") LocalDateTime since, Pageable pageable);

    /**
     * USER DASHBOARD: Recent orders for user with service info PREVENTS N+1: Optimized for user
     * dashboard displays
     */
    @Query(
            "SELECT o FROM Order o "
                    + "JOIN FETCH o.service s "
                    + "LEFT JOIN FETCH o.videoProcessing vp "
                    + "WHERE o.user.username = :username "
                    + "ORDER BY o.createdAt DESC")
    List<Order> findRecentOrdersForUser(@Param("username") String username, Pageable pageable);

    /**
     * PROCESSING QUEUE: Orders awaiting processing with priorities PREVENTS N+1: Used by automation
     * services for queue management
     */
    @Query(
            "SELECT o FROM Order o "
                    + "JOIN FETCH o.user u "
                    + "JOIN FETCH o.service s "
                    + "WHERE o.status = 'PENDING' "
                    + "AND (o.nextRetryAt IS NULL OR o.nextRetryAt <= :now) "
                    + "ORDER BY o.processingPriority DESC, o.createdAt ASC")
    List<Order> findOrdersReadyForProcessing(@Param("now") LocalDateTime now);

    /** MONITORING: Active orders for progress tracking PREVENTS N+1: Used by monitoring services */
    @Query(
            "SELECT o FROM Order o "
                    + "JOIN FETCH o.user u "
                    + "JOIN FETCH o.service s "
                    + "LEFT JOIN FETCH o.videoProcessing vp "
                    + "WHERE o.status IN ('ACTIVE', 'PROCESSING') "
                    + "AND o.updatedAt <= :staleThreshold")
    List<Order> findStaleActiveOrders(@Param("staleThreshold") LocalDateTime staleThreshold);

    // SERVICE-SPECIFIC QUERIES

    /**
     * SERVICE ANALYSIS: Orders by service type with user context PREVENTS N+1: For service
     * performance analysis
     */
    @Query(
            "SELECT o FROM Order o "
                    + "JOIN FETCH o.user u "
                    + "JOIN FETCH o.service s "
                    + "LEFT JOIN FETCH o.videoProcessing vp "
                    + "WHERE s.id = :serviceId "
                    + "AND o.createdAt >= :since "
                    + "ORDER BY o.createdAt DESC")
    List<Order> findOrdersByServiceWithDetails(
            @Param("serviceId") Long serviceId, @Param("since") LocalDateTime since);

    /**
     * SERVICE PERFORMANCE: Completed orders by service for metrics PREVENTS N+1: Used for service
     * success rate analysis
     */
    @Query(
            "SELECT o FROM Order o "
                    + "JOIN FETCH o.user u "
                    + "JOIN FETCH o.service s "
                    + "WHERE s.id = :serviceId "
                    + "AND o.status = 'COMPLETED' "
                    + "AND o.createdAt >= :since")
    List<Order> findCompletedOrdersByService(
            @Param("serviceId") Long serviceId, @Param("since") LocalDateTime since);

    // USER ANALYTICS QUERIES

    /**
     * USER ANALYTICS: Orders by user with complete details PREVENTS N+1: For user behavior analysis
     */
    @Query(
            "SELECT o FROM Order o "
                    + "JOIN FETCH o.user u "
                    + "JOIN FETCH o.service s "
                    + "LEFT JOIN FETCH o.videoProcessing vp "
                    + "LEFT JOIN FETCH o.binomCampaigns bc "
                    + "WHERE u.id = :userId "
                    + "AND o.createdAt BETWEEN :startDate AND :endDate "
                    + "ORDER BY o.createdAt DESC")
    List<Order> findUserOrdersInDateRange(
            @Param("userId") Long userId,
            @Param("startDate") LocalDateTime startDate,
            @Param("endDate") LocalDateTime endDate);

    /**
     * HIGH-VALUE USERS: Orders above value threshold with details PREVENTS N+1: For premium user
     * analysis
     */
    @Query(
            "SELECT o FROM Order o "
                    + "JOIN FETCH o.user u "
                    + "JOIN FETCH o.service s "
                    + "WHERE o.charge >= :minCharge "
                    + "AND o.createdAt >= :since "
                    + "ORDER BY o.charge DESC")
    List<Order> findHighValueOrders(
            @Param("minCharge") java.math.BigDecimal minCharge,
            @Param("since") LocalDateTime since);

    // OPERATIONAL QUERIES

    /**
     * BULK PROCESSING: Orders for bulk status updates PREVENTS N+1: Used by batch processing
     * operations
     */
    @Query(
            "SELECT o FROM Order o "
                    + "JOIN FETCH o.user u "
                    + "JOIN FETCH o.service s "
                    + "WHERE o.id IN :orderIds")
    List<Order> findOrdersByIdsWithDetails(@Param("orderIds") List<Long> orderIds);

    /**
     * YOUTUBE SPECIFIC: Orders with video processing details PREVENTS N+1: Specialized for YouTube
     * automation
     */
    @Query(
            "SELECT o FROM Order o "
                    + "JOIN FETCH o.user u "
                    + "JOIN FETCH o.service s "
                    + "JOIN FETCH o.videoProcessing vp "
                    + "WHERE o.youtubeVideoId IS NOT NULL "
                    + "AND o.status IN ('ACTIVE', 'PROCESSING') "
                    + "ORDER BY vp.createdAt ASC")
    List<Order> findYouTubeOrdersInProcessing();

    /**
     * YOUTUBE MONITORING: Orders with video analytics PREVENTS N+1: For YouTube progress tracking
     */
    @Query(
            "SELECT o FROM Order o "
                    + "JOIN FETCH o.user u "
                    + "JOIN FETCH o.service s "
                    + "JOIN FETCH o.videoProcessing vp "
                    + "WHERE o.youtubeVideoId = :videoId")
    Optional<Order> findOrderByYouTubeVideoId(@Param("videoId") String videoId);

    // FINANCIAL REPORTING QUERIES

    /**
     * REVENUE ANALYSIS: Orders with user and service for financial reports PREVENTS N+1: Used by
     * financial reporting services
     */
    @Query(
            "SELECT o FROM Order o "
                    + "JOIN FETCH o.user u "
                    + "JOIN FETCH o.service s "
                    + "WHERE o.createdAt BETWEEN :startDate AND :endDate "
                    + "AND o.charge > 0 "
                    + "ORDER BY o.charge DESC")
    List<Order> findRevenueOrdersInDateRange(
            @Param("startDate") LocalDateTime startDate, @Param("endDate") LocalDateTime endDate);

    /**
     * REFUND ANALYSIS: Orders that may require refunds PREVENTS N+1: For customer service and
     * refund processing
     */
    @Query(
            "SELECT o FROM Order o "
                    + "JOIN FETCH o.user u "
                    + "JOIN FETCH o.service s "
                    + "WHERE o.status IN ('HOLDING', 'CANCELLED') "
                    + "AND o.charge > 0 "
                    + "AND o.isManuallyFailed = true "
                    + "ORDER BY o.updatedAt DESC")
    List<Order> findOrdersEligibleForRefund();

    // PERFORMANCE MONITORING QUERIES

    /** SLOW ORDERS: Orders taking longer than expected PREVENTS N+1: For performance monitoring */
    @Query(
            "SELECT o FROM Order o "
                    + "JOIN FETCH o.user u "
                    + "JOIN FETCH o.service s "
                    + "LEFT JOIN FETCH o.videoProcessing vp "
                    + "WHERE o.status = 'PROCESSING' "
                    + "AND o.updatedAt < :slowThreshold "
                    + "ORDER BY o.updatedAt ASC")
    List<Order> findSlowProcessingOrders(@Param("slowThreshold") LocalDateTime slowThreshold);

    /**
     * COMPLETION ANALYSIS: Recently completed orders with timing PREVENTS N+1: For completion time
     * analysis
     */
    @Query(
            "SELECT o FROM Order o "
                    + "JOIN FETCH o.user u "
                    + "JOIN FETCH o.service s "
                    + "LEFT JOIN FETCH o.videoProcessing vp "
                    + "WHERE o.status = 'COMPLETED' "
                    + "AND o.updatedAt >= :since "
                    + "ORDER BY o.updatedAt DESC")
    List<Order> findRecentlyCompletedOrders(@Param("since") LocalDateTime since);
}

/**
 * QUERY OPTIMIZATION PATTERNS USED:
 *
 * <p>1. JOIN FETCH for required relationships (user, service) 2. LEFT JOIN FETCH for optional
 * relationships (videoProcessing, binomCampaigns) 3. DISTINCT when fetching collections to avoid
 * cartesian products 4. Proper ordering for predictable results 5. Parameterized queries for
 * security and performance 6. Specialized indexes support (see entity definitions)
 *
 * <p>PERFORMANCE BENEFITS: - Eliminates N+1 queries - Reduces database round trips from O(n) to
 * O(1) - Improves page load times significantly - Better memory utilization in application -
 * Predictable query performance
 *
 * <p>USAGE GUIDELINES: - Use appropriate method for specific use case - Consider pagination for
 * large result sets - Monitor query performance with metrics - Add indexes based on query patterns
 */
