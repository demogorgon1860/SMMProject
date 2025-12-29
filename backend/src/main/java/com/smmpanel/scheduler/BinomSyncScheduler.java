package com.smmpanel.scheduler;

import com.smmpanel.client.BinomClient;
import com.smmpanel.client.BinomClient.OfferClickStats;
import com.smmpanel.client.BinomClient.RemoveOfferResponse;
// BinomCampaign removed - using dynamic campaign connections
import com.smmpanel.entity.Order;
import com.smmpanel.entity.OrderStatus;
// BinomCampaignRepository removed - using dynamic campaign connections
import com.smmpanel.repository.jpa.OrderRepository;
import com.smmpanel.service.integration.YouTubeService;
import jakarta.annotation.PreDestroy;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Scheduled job for synchronizing Binom campaign data Runs periodically to fetch stats from Binom
 * and update local database Implements automatic campaign pause based on limits
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
        name = "app.scheduling.binom-sync.enabled",
        havingValue = "true",
        matchIfMissing = true)
public class BinomSyncScheduler {

    private final BinomClient binomClient;
    private final OrderRepository orderRepository;
    // BinomCampaignRepository removed - using dynamic campaign connections
    private final JdbcTemplate jdbcTemplate;
    private final YouTubeService youTubeService;
    private final RedisTemplate<String, Object> redisTemplate;
    private final com.smmpanel.service.order.OrderService orderService;

    // Executor service for parallel order processing
    // Thread pool sized to work within Resilience4j bulkhead limits (25 concurrent API calls)
    // With caching, most orders hit cache after first in batch, so 15 threads is optimal
    private final ExecutorService syncExecutor =
            Executors.newFixedThreadPool(
                    15, // Parallel threads - sized below bulkhead limit to avoid blocking
                    r -> {
                        Thread t = new Thread(r, "binom-sync-worker");
                        t.setDaemon(true);
                        return t;
                    });

    // Distributed lock settings for preventing race conditions
    private static final String LOCK_PREFIX = "lock:order:binom:sync:";
    private static final long LOCK_TIMEOUT_SECONDS = 30; // Max time to hold lock
    private static final long LOCK_ACQUIRE_TIMEOUT_SECONDS = 2; // How long to wait for lock

    @Value("${app.scheduling.binom-sync.interval-minutes:5}")
    private int syncIntervalMinutes;

    @Value("${app.scheduling.binom-sync.batch-size:50}")
    private int batchSize;

    @Value("${app.scheduling.binom-sync.auto-pause-enabled:true}")
    private boolean autoPauseEnabled;

    @Value("${app.scheduling.binom-sync.parallel-enabled:true}")
    private boolean parallelProcessingEnabled;

    @Value("${app.scheduling.binom-sync.early-pull-threshold:0.95}")
    private double earlyPullThreshold;

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    /**
     * Gracefully shutdown the executor service on application shutdown Prevents thread leaks during
     * redeployment and application restarts
     */
    @PreDestroy
    public void shutdown() {
        log.info("Shutting down Binom sync executor service...");
        syncExecutor.shutdown(); // Disable new tasks from being submitted
        try {
            // Wait up to 30 seconds for currently executing tasks to finish
            if (!syncExecutor.awaitTermination(30, TimeUnit.SECONDS)) {
                log.warn("Executor did not terminate in 30 seconds, forcing shutdown...");
                syncExecutor.shutdownNow(); // Cancel currently executing tasks
                // Wait a bit for tasks to respond to being cancelled
                if (!syncExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                    log.error("Executor did not terminate after forced shutdown");
                }
            }
            log.info("Binom sync executor service shut down successfully");
        } catch (InterruptedException e) {
            log.error("Shutdown interrupted, forcing immediate shutdown", e);
            syncExecutor.shutdownNow(); // (Re-)Cancel if current thread also interrupted
            Thread.currentThread().interrupt(); // Preserve interrupt status
        }
    }

    /** Main synchronization job - Runs every 5 seconds for real-time click tracking */
    @Scheduled(fixedDelay = 5000)
    public void syncBinomCampaigns() { // NO @Transactional here
        try {
            // Check if database is accessible before running sync
            if (!isDatabaseAccessible()) {
                log.warn("Database not accessible, skipping Binom sync");
                return;
            }
            processBinomSync();
        } catch (Exception e) {
            log.error("Error in Binom sync: ", e);
        }
    }

    private boolean isDatabaseAccessible() {
        try {
            jdbcTemplate.queryForObject("SELECT 1", Integer.class);
            return true;
        } catch (Exception e) {
            log.debug("Database connectivity check failed: {}", e.getMessage());
            return false;
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW) // Isolated transaction
    protected void processBinomSync() {
        LocalDateTime startTime = LocalDateTime.now();
        Long jobId = createSyncJobRecord("BINOM_SYNC", "RUNNING");

        AtomicInteger ordersProcessed = new AtomicInteger(0);
        AtomicInteger offersTracked = new AtomicInteger(0);
        AtomicInteger errors = new AtomicInteger(0);

        try {
            log.info("Starting Binom synchronization job");

            // Get all active orders with Binom offer ID (direct campaign connection)
            // Changed: Now using binomOfferId instead of binomCampaignId since campaigns are
            // connected directly
            List<Order> activeOrders =
                    orderRepository.findByStatusInAndBinomOfferIdNotNull(
                            List.of(
                                    OrderStatus.PROCESSING,
                                    OrderStatus.ACTIVE,
                                    OrderStatus.IN_PROGRESS));

            log.info("Found {} active orders to sync", activeOrders.size());

            // Process orders in batches with optional parallel processing
            for (int i = 0; i < activeOrders.size(); i += batchSize) {
                int end = Math.min(i + batchSize, activeOrders.size());
                List<Order> batch = activeOrders.subList(i, end);

                if (parallelProcessingEnabled) {
                    // PARALLEL PROCESSING - Process all orders in batch concurrently
                    log.info(
                            "Processing batch {}-{} of {} orders IN PARALLEL",
                            i,
                            end,
                            activeOrders.size());

                    List<CompletableFuture<Boolean>> futures =
                            batch.stream()
                                    .map(
                                            order ->
                                                    CompletableFuture.supplyAsync(
                                                            () -> {
                                                                try {
                                                                    boolean tracked =
                                                                            syncOrderOfferStats(
                                                                                    order);
                                                                    ordersProcessed
                                                                            .incrementAndGet();
                                                                    if (tracked) {
                                                                        offersTracked
                                                                                .incrementAndGet();
                                                                    }
                                                                    return tracked;
                                                                } catch (Exception e) {
                                                                    log.error(
                                                                            "Error syncing order"
                                                                                    + " {}: {}",
                                                                            order.getId(),
                                                                            e.getMessage(),
                                                                            e);
                                                                    errors.incrementAndGet();
                                                                    return false;
                                                                }
                                                            },
                                                            syncExecutor))
                                    .collect(Collectors.toList());

                    // CRITICAL FIX: Wait for all futures and check for exceptions
                    try {
                        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

                        // Check if any futures completed exceptionally
                        long exceptionallyCompleted =
                                futures.stream()
                                        .filter(CompletableFuture::isCompletedExceptionally)
                                        .count();

                        if (exceptionallyCompleted > 0) {
                            log.warn(
                                    "Batch {}-{}: {} futures completed exceptionally",
                                    i,
                                    end,
                                    exceptionallyCompleted);
                        }
                    } catch (Exception e) {
                        log.error(
                                "Parallel processing failed for batch {}-{}: {}",
                                i,
                                end,
                                e.getMessage(),
                                e);
                        // Continue with next batch rather than failing entire job
                    }

                } else {
                    // SEQUENTIAL PROCESSING - Original behavior
                    log.info(
                            "Processing batch {}-{} of {} orders SEQUENTIALLY",
                            i,
                            end,
                            activeOrders.size());

                    for (Order order : batch) {
                        try {
                            boolean tracked = syncOrderOfferStats(order);
                            if (tracked) {
                                offersTracked.incrementAndGet();
                            }
                            ordersProcessed.incrementAndGet();
                        } catch (Exception e) {
                            log.error("Error syncing order {}: {}", order.getId(), e.getMessage());
                            errors.incrementAndGet();
                        }
                    }
                }

                // Log progress
                log.info("Processed {}/{} orders", ordersProcessed.get(), activeOrders.size());
            }

            // Update job record
            updateSyncJobRecord(
                    jobId,
                    "COMPLETED",
                    ordersProcessed.get(),
                    offersTracked.get(),
                    0, // No campaign pausing anymore
                    errors.get(),
                    null);

            log.info(
                    "Binom sync completed: {} orders processed, {} offers tracked, {} errors",
                    ordersProcessed.get(),
                    offersTracked.get(),
                    errors.get());

        } catch (Exception e) {
            log.error("Binom sync job failed: {}", e.getMessage(), e);
            updateSyncJobRecord(
                    jobId,
                    "FAILED",
                    ordersProcessed.get(),
                    offersTracked.get(),
                    0,
                    errors.get(),
                    e.getMessage());
        }
    }

    /** Sync offer stats for a single order */
    private boolean syncOrderOfferStats(Order order) {
        String offerId = order.getBinomOfferId();

        if (offerId == null) {
            log.debug("Order {} has no offer ID, skipping sync", order.getId());
            return false;
        }

        // CRITICAL: Skip orders that already had their offers removed
        // If secondStartCount exists in Redis, it means offer was removed and view monitoring
        // started
        // This prevents unnecessary Binom API calls for orders in the view-monitoring phase
        String secondCountKey = "order:secondStartCount:" + order.getId();
        Boolean hasSecondCount = redisTemplate.hasKey(secondCountKey);
        if (Boolean.TRUE.equals(hasSecondCount)) {
            log.debug(
                    "Order {} - Offer already removed, view monitoring active. Skipping Binom"
                            + " sync.",
                    order.getId());
            return false;
        }

        // EXPONENTIAL BACKOFF CHECK: Skip orders in backoff period after failed removal attempts
        // Prevents hammering Binom API with rapid retry attempts
        String backoffKey = "order:offerRemoval:nextRetry:" + order.getId();
        String nextRetryTimeStr = (String) redisTemplate.opsForValue().get(backoffKey);
        if (nextRetryTimeStr != null) {
            try {
                LocalDateTime nextRetryTime = LocalDateTime.parse(nextRetryTimeStr);
                if (LocalDateTime.now().isBefore(nextRetryTime)) {
                    log.debug(
                            "Order {} - In backoff period, skipping sync until {}",
                            order.getId(),
                            nextRetryTime.format(
                                    java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss")));
                    return false;
                } else {
                    // Backoff period elapsed, remove the key to allow retry
                    redisTemplate.delete(backoffKey);
                    log.info(
                            "Order {} - Backoff period elapsed, attempting offer removal retry",
                            order.getId());
                }
            } catch (Exception e) {
                log.warn(
                        "Failed to parse backoff time for order {}: {}. Proceeding with sync.",
                        order.getId(),
                        e.getMessage());
                // If parsing fails, delete the corrupt key and proceed
                redisTemplate.delete(backoffKey);
            }
        }

        // DISTRIBUTED LOCK: Prevent race conditions
        // Multiple threads/instances could process same order simultaneously
        // Lock ensures only ONE thread processes order at a time
        if (!acquireOrderLock(order.getId())) {
            log.debug(
                    "Order {} - Lock not acquired, another thread is processing. Skipping.",
                    order.getId());
            return false;
        }

        try {
            // FIX #1: Use hardcoded campaign list to avoid eventual consistency issues
            // Binom's query API may lag, causing getCampaignsWithOffer() to return empty
            // even when offer IS in campaigns 1, 3, 4. Use fixed list instead.
            List<String> FIXED_CAMPAIGNS = java.util.Arrays.asList("1", "3", "4");

            // Get offer statistics using the report API
            OfferClickStats offerStats = binomClient.getOfferClickStatistics(offerId);

            if (offerStats != null && offerStats.getClicks() > 0) {
                long totalClicks = offerStats.getClicks();

                log.info(
                        "Sync for order {} (offer {}): {} clicks from campaigns [1,3,4]",
                        order.getId(),
                        offerId,
                        totalClicks);

                // Update order stats with clicks and check for immediate removal
                // Pass FIXED_CAMPAIGNS instead of queried campaigns
                updateOrderStats(order, totalClicks, BigDecimal.ZERO, FIXED_CAMPAIGNS);
                return true;
            }

            log.debug("No click stats available for offer {} (order {})", offerId, order.getId());
            return false;

        } catch (Exception e) {
            log.error("Failed to sync offer stats for order {}: {}", order.getId(), e.getMessage());
            return false;
        } finally {
            // ALWAYS release lock, even if exception occurs
            releaseOrderLock(order.getId());
        }
    }

    /**
     * Update order with aggregated stats Uses retry logic to handle concurrent modification
     * conflicts
     */
    private void updateOrderStats(
            Order order, long totalClicks, BigDecimal totalCost, List<String> campaignsWithOffer) {
        // FIX: Retry logic with exponential backoff for OptimisticLockingFailureException
        int maxRetries = 3;
        int retryDelay = 100; // Start with 100ms

        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                updateOrderStatsWithRetry(
                        order.getId(), totalClicks, totalCost, campaignsWithOffer);
                return; // Success - exit retry loop
            } catch (org.springframework.orm.ObjectOptimisticLockingFailureException e) {
                if (attempt < maxRetries) {
                    log.warn(
                            "OptimisticLockingFailureException for order {} (attempt {}/{}). "
                                    + "Retrying after {}ms...",
                            order.getId(),
                            attempt,
                            maxRetries,
                            retryDelay);
                    try {
                        Thread.sleep(retryDelay);
                        retryDelay *= 2; // Exponential backoff: 100ms, 200ms, 400ms
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        log.error(
                                "Retry interrupted for order {}: {}",
                                order.getId(),
                                ie.getMessage());
                        return;
                    }
                } else {
                    log.error(
                            "Failed to sync offer stats for order {} after {} attempts: {}",
                            order.getId(),
                            maxRetries,
                            e.getMessage());
                }
            } catch (Exception e) {
                log.error(
                        "Unexpected error updating order {} stats: {}",
                        order.getId(),
                        e.getMessage());
                return;
            }
        }
    }

    /**
     * Actual update logic in a separate transaction This ensures fresh entity retrieval and proper
     * version handling
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    protected void updateOrderStatsWithRetry(
            Long orderId, long totalClicks, BigDecimal totalCost, List<String> campaignsWithOffer) {
        // FIX: Fetch fresh order entity within new transaction to get latest version
        Order order =
                orderRepository
                        .findById(orderId)
                        .orElseThrow(
                                () -> new IllegalStateException("Order " + orderId + " not found"));

        // Track clicks directly - no conversion to views during tracking

        // Calculate target clicks needed
        long targetClicks = 0;
        if (order.getQuantity() != null
                && order.getCoefficient() != null
                && order.getCoefficient().compareTo(BigDecimal.ZERO) > 0) {
            targetClicks = Math.round(order.getQuantity() * order.getCoefficient().doubleValue());
        }

        log.info(
                "Order {} tracking: {} clicks received (target: {} clicks for {} views with"
                        + " coefficient {})",
                order.getId(),
                totalClicks,
                targetClicks,
                order.getQuantity(),
                order.getCoefficient());

        // Store clicks count temporarily (for tracking purposes)
        order.setViewsDelivered((int) (totalClicks / order.getCoefficient().doubleValue()));
        order.setCostIncurred(totalCost);

        // Calculate early pull threshold to prevent overshoot
        // Configurable threshold (default 90%) to account for clicks during sync and removal delay
        long earlyPullThresholdClicks = (long) (targetClicks * earlyPullThreshold);
        double currentProgress = targetClicks > 0 ? (double) totalClicks / targetClicks : 0;

        // Check if we've reached the early pull threshold
        if (targetClicks > 0 && totalClicks >= earlyPullThresholdClicks) {
            // Threshold reached - immediately remove offers from campaigns
            log.info(
                    "Order {} reached early pull threshold ({} of target): {} clicks (target: {},"
                            + " progress: {}) - removing offer {} from campaigns immediately",
                    order.getId(),
                    String.format("%.0f%%", earlyPullThreshold * 100),
                    totalClicks,
                    targetClicks,
                    String.format("%.1f%%", currentProgress * 100),
                    order.getBinomOfferId());

            // FIX #1: IMMEDIATELY remove offers from Binom campaigns to stop traffic
            // Use hardcoded campaigns [1,3,4] instead of querying Binom to avoid eventual
            // consistency issues
            List<String> FIXED_CAMPAIGNS_FOR_REMOVAL = java.util.Arrays.asList("1", "3", "4");
            boolean removalSuccessful = false;
            try {
                RemoveOfferResponse removeResponse =
                        binomClient.removeOfferFromCampaigns(
                                order.getBinomOfferId(), FIXED_CAMPAIGNS_FOR_REMOVAL);
                log.info(
                        "Successfully removed offer {} from campaigns [1,3,4] for order {}: {}",
                        order.getBinomOfferId(),
                        order.getId(),
                        removeResponse.getMessage());
                removalSuccessful = removeResponse.isSuccess();
            } catch (Exception e) {
                log.error(
                        "Failed to remove offer {} from campaigns [1,3,4] for order {}: {}",
                        order.getBinomOfferId(),
                        order.getId(),
                        e.getMessage());
            }

            // FIX #2: After successful offer removal, keep order IN_PROGRESS
            // CRITICAL: scheduleInitialViewCheck() sets Redis key that STOPS future Binom syncs
            // ONLY call it AFTER successful removal to prevent offers from never being removed
            if (removalSuccessful) {
                // Keep order IN_PROGRESS while monitoring YouTube views
                // Do NOT change status - order is still "in progress" until views are verified
                log.info(
                        "Order {} - Offer successfully removed from campaigns [1,3,4]. "
                                + "Order remains IN_PROGRESS while monitoring YouTube views.",
                        order.getId());

                // Reset retry count on successful removal
                order.setRetryCount(0);
                order.setLastErrorType(null);
                order.setFailedPhase(null);

                // Clean up backoff key since removal succeeded
                String backoffKey = "order:offerRemoval:nextRetry:" + order.getId();
                redisTemplate.delete(backoffKey);

                // CRITICAL FIX: Idempotency check to prevent double-processing
                // Use Redis SETNX (SET if Not eXists) for atomic idempotency
                String viewCheckScheduledKey = "order:viewCheckScheduled:" + order.getId();
                Boolean alreadyScheduled =
                        redisTemplate.opsForValue().setIfAbsent(viewCheckScheduledKey, "true");

                if (Boolean.TRUE.equals(alreadyScheduled)) {
                    // FIX #2: Schedule initial view check ONLY after successful removal
                    // This prevents Redis key from blocking future removal attempts
                    try {
                        orderService.scheduleInitialViewCheck(order);
                        log.info(
                                "Order {} - Scheduled YouTube view monitoring (every 30 minutes). "
                                        + "Redis key 'order:secondStartCount:{}' now SET - "
                                        + "order will be SKIPPED from future Binom syncs.",
                                order.getId(),
                                order.getId());
                    } catch (Exception e) {
                        log.error(
                                "CRITICAL: Failed to schedule view check for order {}. "
                                        + "Order may be stuck in limbo: {}",
                                order.getId(),
                                e.getMessage());
                        // Remove idempotency key to allow retry
                        redisTemplate.delete(viewCheckScheduledKey);
                    }
                } else {
                    log.info(
                            "Order {} - View check already scheduled by another thread. Skipping.",
                            order.getId());
                }
            } else {
                // CRITICAL FIX: Removal FAILED - Track retry attempts with exponential backoff
                Integer currentRetryCount =
                        order.getRetryCount() != null ? order.getRetryCount() : 0;
                Integer maxRetries = order.getMaxRetries() != null ? order.getMaxRetries() : 10;

                currentRetryCount++;
                order.setRetryCount(currentRetryCount);
                order.setLastRetryAt(LocalDateTime.now());
                order.setLastErrorType("BINOM_OFFER_REMOVAL_FAILED");
                order.setFailedPhase("BINOM_OFFER_REMOVAL");

                if (currentRetryCount >= maxRetries) {
                    // MAX RETRIES EXCEEDED - Log critical error for manual intervention
                    // DO NOT change status - Let admin manually review and handle via UI
                    log.error(
                            "CRITICAL: Order {} - Binom offer removal FAILED after {} attempts"
                                    + " (max: {}). Manual intervention required. Offer: {}. Order"
                                    + " remains IN_PROGRESS for admin review.",
                            order.getId(),
                            currentRetryCount,
                            maxRetries,
                            order.getBinomOfferId());

                    // Set error message for admin visibility, but DO NOT change status
                    // Admin can manually transition to HOLDING via UI if needed
                    order.setErrorMessage(
                            String.format(
                                    "CRITICAL: Binom offer removal failed after %d attempts. Offer:"
                                        + " %s. Manual intervention required - Admin should review"
                                        + " and manually remove offer or mark order as HOLDING.",
                                    currentRetryCount, order.getBinomOfferId()));

                    // Clean up backoff key since max retries exceeded - no more retries
                    String maxRetriesBackoffKey = "order:offerRemoval:nextRetry:" + order.getId();
                    redisTemplate.delete(maxRetriesBackoffKey);

                    // TODO: Future enhancement - Publish OfferRemovalFailedEvent for automated
                    // handling
                    // via OrderStateManagementService.transitionToHolding()
                } else {
                    // EXPONENTIAL BACKOFF: Calculate next retry delay
                    // Attempt 1: 30 seconds, 2: 1 min, 3: 2 min, 4: 5 min, 5+: 10 min
                    long backoffMinutes;
                    switch (currentRetryCount) {
                        case 1:
                            backoffMinutes = 0; // Immediate retry (next 5s cycle)
                            break;
                        case 2:
                            backoffMinutes = 1; // 1 minute
                            break;
                        case 3:
                            backoffMinutes = 2; // 2 minutes
                            break;
                        case 4:
                            backoffMinutes = 5; // 5 minutes
                            break;
                        default:
                            backoffMinutes = 10; // 10 minutes for subsequent retries
                    }

                    LocalDateTime nextRetryTime = LocalDateTime.now().plusMinutes(backoffMinutes);

                    // Store next retry time in Redis to implement backoff
                    String backoffKey = "order:offerRemoval:nextRetry:" + order.getId();
                    redisTemplate
                            .opsForValue()
                            .set(
                                    backoffKey,
                                    nextRetryTime.toString(),
                                    java.time.Duration.ofHours(1));

                    log.warn(
                            "Order {} offer removal FAILED (attempt {}/{}). Offer {} will be"
                                + " retried after {} minute(s) backoff (at {}). Redis backoff key"
                                + " set.",
                            order.getId(),
                            currentRetryCount,
                            maxRetries,
                            order.getBinomOfferId(),
                            backoffMinutes,
                            nextRetryTime.format(
                                    java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss")));
                    // Do NOT change status - retry will happen after backoff period
                    // Do NOT call scheduleInitialViewCheck() - would block future removal attempts
                }
            }
        }
        // Note: Removed traffic_status updates as they're not meaningful

        order.setUpdatedAt(LocalDateTime.now());
        orderRepository.save(order);

        log.debug(
                "Updated order {} stats: {} views delivered from Binom, cost: {}",
                order.getId(),
                order.getViewsDelivered(),
                totalCost);
    }

    /** Create sync job record in database */
    private Long createSyncJobRecord(String jobType, String status) {
        String sql =
                "INSERT INTO binom_sync_jobs (job_type, status, started_at, created_at) VALUES (?,"
                        + " ?, ?, ?) RETURNING id";
        return jdbcTemplate.queryForObject(
                sql, Long.class, jobType, status, LocalDateTime.now(), LocalDateTime.now());
    }

    /** Update sync job record */
    private void updateSyncJobRecord(
            Long jobId,
            String status,
            int ordersSync,
            int campaignsSync,
            int campaignsPaused,
            int errors,
            String errorMessage) {
        String sql =
                "UPDATE binom_sync_jobs SET status = ?, completed_at = ?, orders_synced = ?,"
                    + " campaigns_synced = ?, campaigns_paused = ?, error_count = ?, error_message"
                    + " = ? WHERE id = ?";
        jdbcTemplate.update(
                sql,
                status,
                LocalDateTime.now(),
                ordersSync,
                campaignsSync,
                campaignsPaused,
                errors,
                errorMessage,
                jobId);
    }

    /** Daily cleanup job - runs at 2 AM Cleans up old sync job records */
    @Scheduled(cron = "0 0 2 * * *")
    public void cleanupOldSyncJobs() {
        try {
            String sql = "DELETE FROM binom_sync_jobs WHERE created_at < ?";
            int deleted = jdbcTemplate.update(sql, LocalDateTime.now().minusDays(30));
            log.info("Cleaned up {} old sync job records", deleted);
        } catch (Exception e) {
            log.error("Error cleaning up sync jobs: {}", e.getMessage());
        }
    }

    // ========================================
    // DISTRIBUTED LOCKING METHODS
    // ========================================

    /**
     * Acquire distributed lock for order processing Prevents race conditions when multiple threads
     * or instances process the same order
     *
     * @param orderId Order ID to lock
     * @return true if lock acquired, false if order is already being processed
     */
    private boolean acquireOrderLock(Long orderId) {
        String lockKey = LOCK_PREFIX + orderId;
        String lockValue = Thread.currentThread().getName() + "-" + System.currentTimeMillis();

        long startTime = System.currentTimeMillis();
        long timeoutMillis = LOCK_ACQUIRE_TIMEOUT_SECONDS * 1000;

        // Try to acquire lock with retry
        while (System.currentTimeMillis() - startTime < timeoutMillis) {
            try {
                // Use SETNX (SET if Not eXists) for atomic lock acquisition
                Boolean acquired =
                        redisTemplate
                                .opsForValue()
                                .setIfAbsent(
                                        lockKey,
                                        lockValue,
                                        java.time.Duration.ofSeconds(LOCK_TIMEOUT_SECONDS));

                if (Boolean.TRUE.equals(acquired)) {
                    log.debug(
                            "Lock ACQUIRED for order {} by thread {}",
                            orderId,
                            Thread.currentThread().getName());
                    return true;
                }

                // Lock held by another thread, wait and retry
                Thread.sleep(50); // 50ms retry interval

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Lock acquisition interrupted for order {}", orderId);
                return false;
            } catch (Exception e) {
                log.error("Error acquiring lock for order {}: {}", orderId, e.getMessage());
                return false;
            }
        }

        // Timeout reached without acquiring lock
        log.debug(
                "Lock acquisition TIMEOUT for order {} after {}s - another thread is processing",
                orderId,
                LOCK_ACQUIRE_TIMEOUT_SECONDS);
        return false;
    }

    /**
     * Release distributed lock for order processing
     *
     * @param orderId Order ID to unlock
     */
    private void releaseOrderLock(Long orderId) {
        String lockKey = LOCK_PREFIX + orderId;

        try {
            redisTemplate.delete(lockKey);
            log.debug("Lock RELEASED for order {}", orderId);
        } catch (Exception e) {
            log.error("Error releasing lock for order {}: {}", orderId, e.getMessage());
        }
    }
}
