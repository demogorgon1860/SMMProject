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
import com.smmpanel.service.integration.YouTubeService.VideoStatistics;
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

    /** Main synchronization job - Runs every 15 seconds for real-time click tracking */
    @Scheduled(fixedDelay = 15000)
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
                                                                            e.getMessage());
                                                                    errors.incrementAndGet();
                                                                    return false;
                                                                }
                                                            },
                                                            syncExecutor))
                                    .collect(Collectors.toList());

                    // Wait for all futures to complete
                    CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

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

        try {
            // Check which of campaigns 1, 3, 4 have this offer
            List<String> campaignsWithOffer = binomClient.getCampaignsWithOffer(offerId);

            if (campaignsWithOffer.isEmpty()) {
                log.debug(
                        "Offer {} not found in any campaign for order {}", offerId, order.getId());
                return false;
            }

            // Get offer statistics using the report API
            OfferClickStats offerStats = binomClient.getOfferClickStatistics(offerId);

            if (offerStats != null && offerStats.getClicks() > 0) {
                long totalClicks = offerStats.getClicks();

                log.info(
                        "Sync for order {} (offer {}): {} campaigns have offer, {} clicks",
                        order.getId(),
                        offerId,
                        campaignsWithOffer.size(),
                        totalClicks);

                // Update order stats with clicks and check for immediate removal
                updateOrderStats(order, totalClicks, BigDecimal.ZERO, campaignsWithOffer);
                return true;
            }

            log.debug("No click stats available for offer {} (order {})", offerId, order.getId());
            return false;

        } catch (Exception e) {
            log.error("Failed to sync offer stats for order {}: {}", order.getId(), e.getMessage());
            return false;
        }
    }

    /** Update order with aggregated stats */
    private void updateOrderStats(
            Order order, long totalClicks, BigDecimal totalCost, List<String> campaignsWithOffer) {
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

            // IMMEDIATELY remove offers from Binom campaigns to stop traffic
            boolean removalSuccessful = false;
            if (campaignsWithOffer != null && !campaignsWithOffer.isEmpty()) {
                try {
                    RemoveOfferResponse removeResponse =
                            binomClient.removeOfferFromCampaigns(
                                    order.getBinomOfferId(), campaignsWithOffer);
                    log.info(
                            "Successfully removed offer {} from campaigns for order {}: {}",
                            order.getBinomOfferId(),
                            order.getId(),
                            removeResponse.getMessage());
                    removalSuccessful = removeResponse.isSuccess();
                } catch (Exception e) {
                    log.error(
                            "Failed to remove offer {} from campaigns for order {}: {}",
                            order.getBinomOfferId(),
                            order.getId(),
                            e.getMessage());
                }
            }

            // After removal, check YouTube for final count (second startCount)
            if (removalSuccessful && order.getYoutubeVideoId() != null) {
                try {
                    // Wait a moment for YouTube to update
                    Thread.sleep(2000);

                    VideoStatistics stats =
                            youTubeService.getVideoStatistics(order.getYoutubeVideoId());
                    Long viewCount = stats != null ? stats.getViewCount() : null;
                    int secondStartCount = viewCount != null ? viewCount.intValue() : 0;
                    int firstStartCount = order.getStartCount() != null ? order.getStartCount() : 0;
                    int actualViewsDelivered = secondStartCount - firstStartCount;

                    log.info(
                            "Order {} completed - Final YouTube count: {} actual views delivered "
                                    + "(second count: {} - first count: {})",
                            order.getId(),
                            actualViewsDelivered,
                            secondStartCount,
                            firstStartCount);

                    // Store actual views delivered from YouTube
                    order.setViewsDelivered(actualViewsDelivered);
                } catch (Exception e) {
                    log.error(
                            "Failed to get final YouTube stats for order {}: {}",
                            order.getId(),
                            e.getMessage());
                }

                // CRITICAL: Only mark as COMPLETED if removal was successful
                order.setStatus(OrderStatus.COMPLETED);
                // Clear Redis cache when completed
                redisTemplate.delete("order:current:" + order.getId());
                redisTemplate.delete("order:monitoring:" + order.getId());

                log.info(
                        "Order {} marked as COMPLETED after successful offer removal",
                        order.getId());
            } else if (removalSuccessful && order.getYoutubeVideoId() == null) {
                // Removal successful but no YouTube video - still mark as completed
                order.setStatus(OrderStatus.COMPLETED);
                redisTemplate.delete("order:current:" + order.getId());
                redisTemplate.delete("order:monitoring:" + order.getId());

                log.info(
                        "Order {} marked as COMPLETED after successful offer removal (no YouTube"
                                + " video)",
                        order.getId());
            } else {
                // CRITICAL: Removal FAILED - keep order in IN_PROGRESS for retry
                log.error(
                        "Order {} offer removal FAILED - keeping order status as IN_PROGRESS for"
                                + " retry. Offer {} will be retried in next sync cycle.",
                        order.getId(),
                        order.getBinomOfferId());
                // Do NOT mark as COMPLETED - next sync cycle will retry removal
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
}
