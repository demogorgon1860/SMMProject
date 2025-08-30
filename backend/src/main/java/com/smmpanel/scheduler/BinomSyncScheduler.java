package com.smmpanel.scheduler;

import com.smmpanel.client.BinomClient;
import com.smmpanel.dto.binom.CampaignStatsResponse;
import com.smmpanel.entity.BinomCampaign;
import com.smmpanel.entity.Order;
import com.smmpanel.entity.OrderStatus;
import com.smmpanel.repository.jpa.BinomCampaignRepository;
import com.smmpanel.repository.jpa.OrderRepository;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
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
    private final BinomCampaignRepository binomCampaignRepository;
    private final JdbcTemplate jdbcTemplate;

    @Value("${app.scheduling.binom-sync.interval-minutes:5}")
    private int syncIntervalMinutes;

    @Value("${app.scheduling.binom-sync.batch-size:50}")
    private int batchSize;

    @Value("${app.scheduling.binom-sync.auto-pause-enabled:true}")
    private boolean autoPauseEnabled;

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    /** Main synchronization job Runs every N minutes (configurable) */
    @Scheduled(fixedDelayString = "${app.scheduling.binom-sync.interval-minutes:5}000")
    @Transactional(propagation = Propagation.REQUIRED)
    public void syncBinomCampaigns() {
        LocalDateTime startTime = LocalDateTime.now();
        Long jobId = createSyncJobRecord("BINOM_SYNC", "RUNNING");

        AtomicInteger ordersProcessed = new AtomicInteger(0);
        AtomicInteger campaignsProcessed = new AtomicInteger(0);
        AtomicInteger campaignsPaused = new AtomicInteger(0);
        AtomicInteger errors = new AtomicInteger(0);

        try {
            log.info("Starting Binom synchronization job");

            // Get all active orders with Binom campaigns
            List<Order> activeOrders =
                    orderRepository.findByStatusInAndBinomCampaignIdNotNull(
                            List.of(
                                    OrderStatus.PROCESSING,
                                    OrderStatus.ACTIVE,
                                    OrderStatus.IN_PROGRESS));

            log.info("Found {} active orders to sync", activeOrders.size());

            // Process orders in batches
            for (int i = 0; i < activeOrders.size(); i += batchSize) {
                int end = Math.min(i + batchSize, activeOrders.size());
                List<Order> batch = activeOrders.subList(i, end);

                for (Order order : batch) {
                    try {
                        syncOrderCampaigns(order, campaignsProcessed, campaignsPaused);
                        ordersProcessed.incrementAndGet();
                    } catch (Exception e) {
                        log.error("Error syncing order {}: {}", order.getId(), e.getMessage());
                        errors.incrementAndGet();
                    }
                }

                // Log progress
                log.debug("Processed {}/{} orders", ordersProcessed.get(), activeOrders.size());
            }

            // Update job record
            updateSyncJobRecord(
                    jobId,
                    "COMPLETED",
                    ordersProcessed.get(),
                    campaignsProcessed.get(),
                    campaignsPaused.get(),
                    errors.get(),
                    null);

            log.info(
                    "Binom sync completed: {} orders, {} campaigns processed, {} paused, {} errors",
                    ordersProcessed.get(),
                    campaignsProcessed.get(),
                    campaignsPaused.get(),
                    errors.get());

        } catch (Exception e) {
            log.error("Binom sync job failed: {}", e.getMessage(), e);
            updateSyncJobRecord(
                    jobId,
                    "FAILED",
                    ordersProcessed.get(),
                    campaignsProcessed.get(),
                    campaignsPaused.get(),
                    errors.get(),
                    e.getMessage());
        }
    }

    /** Sync campaigns for a single order */
    private void syncOrderCampaigns(
            Order order, AtomicInteger campaignsProcessed, AtomicInteger campaignsPaused) {
        // Get all campaigns for this order
        List<BinomCampaign> campaigns = binomCampaignRepository.findByOrderId(order.getId());

        if (campaigns.isEmpty()) {
            log.warn(
                    "No campaigns found for order {} despite binomCampaignId being set",
                    order.getId());
            return;
        }

        BigDecimal totalCost = BigDecimal.ZERO;
        long totalClicks = 0L;
        long totalViews = 0L;

        for (BinomCampaign campaign : campaigns) {
            try {
                // Skip already paused campaigns
                if ("PAUSED".equals(campaign.getStatus())) {
                    continue;
                }

                // Fetch stats from Binom
                CampaignStatsResponse stats =
                        binomClient.getDetailedStats(campaign.getCampaignId(), null, null);

                // Update campaign with latest stats
                campaign.setClicksDelivered(stats.getClicks().intValue());
                campaign.setConversions(
                        stats.getConversions() != null ? stats.getConversions().intValue() : 0);
                campaign.setCost(stats.getCost());
                campaign.setRevenue(stats.getRevenue());
                campaign.setLastStatsUpdate(LocalDateTime.now());

                // Accumulate totals
                totalCost =
                        totalCost.add(stats.getCost() != null ? stats.getCost() : BigDecimal.ZERO);
                totalClicks += stats.getClicks();

                // Calculate views based on coefficient
                if (order.getCoefficient() != null
                        && order.getCoefficient().compareTo(BigDecimal.ZERO) > 0) {
                    long campaignViews =
                            (long) (stats.getClicks() / order.getCoefficient().doubleValue());
                    totalViews += campaignViews;
                }

                // Check if campaign should be paused
                if (autoPauseEnabled && shouldPauseCampaign(campaign, stats, order)) {
                    pauseCampaign(campaign, order, stats);
                    campaignsPaused.incrementAndGet();
                }

                binomCampaignRepository.save(campaign);
                campaignsProcessed.incrementAndGet();

                log.debug(
                        "Synced campaign {} for order {}: {} clicks, cost: {}",
                        campaign.getCampaignId(),
                        order.getId(),
                        stats.getClicks(),
                        stats.getCost());

            } catch (Exception e) {
                log.error(
                        "Failed to sync campaign {} for order {}: {}",
                        campaign.getCampaignId(),
                        order.getId(),
                        e.getMessage());
            }
        }

        // Update order with aggregated stats
        updateOrderStats(order, totalViews, totalCost);
    }

    /** Check if campaign should be paused based on limits */
    private boolean shouldPauseCampaign(
            BinomCampaign campaign, CampaignStatsResponse stats, Order order) {
        // Check views delivered vs quantity
        if (order.getQuantity() != null && order.getCoefficient() != null) {
            long viewsDelivered = (long) (stats.getClicks() / order.getCoefficient().doubleValue());
            if (viewsDelivered >= order.getQuantity()) {
                campaign.setPauseReason(
                        String.format(
                                "Views target reached: %d >= %d",
                                viewsDelivered, order.getQuantity()));
                return true;
            }
        }

        // Check cost vs budget limit
        if (campaign.getBudgetLimit() != null && stats.getCost() != null) {
            if (stats.getCost().compareTo(campaign.getBudgetLimit()) >= 0) {
                campaign.setPauseReason(
                        String.format(
                                "Budget limit reached: %s >= %s",
                                stats.getCost(), campaign.getBudgetLimit()));
                return true;
            }
        }

        // Check order-level budget limit
        if (order.getBudgetLimit() != null && order.getCostIncurred() != null) {
            BigDecimal projectedCost =
                    order.getCostIncurred()
                            .add(stats.getCost() != null ? stats.getCost() : BigDecimal.ZERO);
            if (projectedCost.compareTo(order.getBudgetLimit()) >= 0) {
                campaign.setPauseReason(
                        String.format(
                                "Order budget limit reached: %s >= %s",
                                projectedCost, order.getBudgetLimit()));
                return true;
            }
        }

        return false;
    }

    /** Pause a campaign in Binom */
    private void pauseCampaign(BinomCampaign campaign, Order order, CampaignStatsResponse stats) {
        try {
            boolean paused = binomClient.pauseCampaign(campaign.getCampaignId());
            if (paused) {
                campaign.setStatus("PAUSED");
                campaign.setUpdatedAt(LocalDateTime.now());
                log.info(
                        "Auto-paused campaign {} for order {}: {}",
                        campaign.getCampaignId(),
                        order.getId(),
                        campaign.getPauseReason());
            } else {
                log.warn("Failed to pause campaign {} in Binom", campaign.getCampaignId());
            }
        } catch (Exception e) {
            log.error("Error pausing campaign {}: {}", campaign.getCampaignId(), e.getMessage());
        }
    }

    /** Update order with aggregated stats */
    private void updateOrderStats(Order order, long totalViews, BigDecimal totalCost) {
        order.setViewsDelivered((int) totalViews);
        order.setCostIncurred(totalCost);

        // Update traffic status based on progress
        if (order.getQuantity() != null && totalViews >= order.getQuantity()) {
            order.setTrafficStatus("DELIVERED");
            order.setStatus(OrderStatus.COMPLETED);
        } else if (totalViews > 0) {
            order.setTrafficStatus("RUNNING");
        }

        order.setUpdatedAt(LocalDateTime.now());
        orderRepository.save(order);

        log.debug(
                "Updated order {} stats: {} views delivered, cost: {}",
                order.getId(),
                totalViews,
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
