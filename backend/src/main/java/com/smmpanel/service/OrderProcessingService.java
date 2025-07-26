package com.smmpanel.service;

import com.smmpanel.entity.*;
import com.smmpanel.repository.OrderRepository;
import com.smmpanel.repository.ViewStatsRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class OrderProcessingService {

    private final OrderRepository orderRepository;
    private final ViewStatsRepository viewStatsRepository;
    private final YouTubeService youTubeService;
    private final VideoProcessingService videoProcessingService;
    private final BinomService binomService;
    private final KafkaTemplate<String, Object> kafkaTemplate;

    @KafkaListener(topics = "order-processing")
    @Transactional
    public void processNewOrder(Long orderId) {
        try {
            Order order = orderRepository.findById(orderId)
                    .orElseThrow(() -> new IllegalArgumentException("Order not found: " + orderId));

            if (order.getStatus() != OrderStatus.PENDING) {
                log.info("Order {} already processed, skipping", orderId);
                return;
            }

            log.info("Processing new order {}", orderId);

            // Update status to IN_PROGRESS
            order.setStatus(OrderStatus.IN_PROGRESS);
            orderRepository.save(order);

            // Get start count from YouTube
            try {
                String videoId = youTubeService.extractVideoId(order.getLink());
                Long startCount = youTubeService.getViewCount(videoId);
                
                order.setStartCount(startCount.intValue());
                order.setRemains(order.getQuantity());
                orderRepository.save(order);

                log.info("Got start count {} for order {}", startCount, orderId);

            } catch (Exception e) {
                log.error("Failed to get start count for order {}: {}", orderId, e.getMessage());
                order.setStatus(OrderStatus.CANCELLED);
                order.setErrorMessage("Failed to get video start count: " + e.getMessage());
                orderRepository.save(order);
                
                // Refund the user
                kafkaTemplate.send("order-refund", orderId);
                return;
            }

            // Update status to PROCESSING
            order.setStatus(OrderStatus.PROCESSING);
            orderRepository.save(order);

            // Create video processing record
            videoProcessingService.createVideoProcessing(order);

        } catch (Exception e) {
            log.error("Failed to process order {}: {}", orderId, e.getMessage(), e);
            
            Order order = orderRepository.findById(orderId).orElse(null);
            if (order != null) {
                order.setStatus(OrderStatus.CANCELLED);
                order.setErrorMessage("Processing failed: " + e.getMessage());
                orderRepository.save(order);
                
                kafkaTemplate.send("order-refund", orderId);
            }
        }
    }

    @KafkaListener(topics = "binom-campaign-creation")
    @Transactional
    public void createBinomCampaign(Map<String, Object> data) {
        try {
            Long orderId = Long.valueOf(data.get("orderId").toString());
            String targetUrl = data.get("targetUrl").toString();
            Boolean hasClip = (Boolean) data.get("hasClip");

            Order order = orderRepository.findById(orderId)
                    .orElseThrow(() -> new IllegalArgumentException("Order not found: " + orderId));

            log.info("Creating Binom campaign for order {} with URL: {}", orderId, targetUrl);

            // Create campaign in Binom
            BinomCampaign campaign = binomService.createCampaign(order, targetUrl, hasClip);

            // Update order status to ACTIVE
            order.setStatus(OrderStatus.ACTIVE);
            orderRepository.save(order);

            // Create view stats record for monitoring
            createViewStatsRecord(order, campaign);

            log.info("Order {} is now ACTIVE with Binom campaign {}", orderId, campaign.getCampaignId());

        } catch (Exception e) {
            log.error("Failed to create Binom campaign: {}", e.getMessage(), e);
            
            Long orderId = Long.valueOf(data.get("orderId").toString());
            Order order = orderRepository.findById(orderId).orElse(null);
            if (order != null) {
                order.setStatus(OrderStatus.CANCELLED);
                order.setErrorMessage("Failed to create campaign: " + e.getMessage());
                orderRepository.save(order);
                
                kafkaTemplate.send("order-refund", orderId);
            }
        }
    }

    @KafkaListener(topics = "video-processing-retry")
    @Transactional
    public void retryVideoProcessing(Long processingId) {
        try {
            // Add delay before retry
            Thread.sleep(30000); // 30 seconds delay
            
            videoProcessingService.retryProcessing(processingId);
            
        } catch (Exception e) {
            log.error("Failed to retry video processing {}: {}", processingId, e.getMessage(), e);
        }
    }

    @KafkaListener(topics = "order-refund")
    @Transactional
    public void processRefund(Long orderId) {
        try {
            Order order = orderRepository.findById(orderId)
                    .orElseThrow(() -> new IllegalArgumentException("Order not found: " + orderId));

            // Refund logic will be handled by BalanceService
            log.info("Processing refund for cancelled order {}", orderId);
            
            // Additional refund processing can be added here
            
        } catch (Exception e) {
            log.error("Failed to process refund for order {}: {}", orderId, e.getMessage(), e);
        }
    }

    @Scheduled(fixedRate = 1800000) // Every 30 minutes
    @Transactional
    public void monitorActiveOrders() {
        try {
            List<Order> activeOrders = orderRepository.findByStatusIn(List.of(
                    OrderStatus.ACTIVE, 
                    OrderStatus.HOLDING
            ));

            log.debug("Monitoring {} active orders", activeOrders.size());

            for (Order order : activeOrders) {
                try {
                    monitorSingleOrder(order);
                } catch (Exception e) {
                    log.error("Failed to monitor order {}: {}", order.getId(), e.getMessage(), e);
                }
            }

        } catch (Exception e) {
            log.error("Failed to monitor active orders: {}", e.getMessage(), e);
        }
    }

    private void monitorSingleOrder(Order order) {
        try {
            // Get current view count
            String videoId = youTubeService.extractVideoId(order.getLink());
            Long currentViews = youTubeService.getViewCount(videoId);
            
            int viewsGained = currentViews.intValue() - order.getStartCount();
            int remainingViews = order.getQuantity() - viewsGained;
            
            // Update remains
            order.setRemains(Math.max(0, remainingViews));
            order.setUpdatedAt(LocalDateTime.now());
            orderRepository.save(order);

            // Update view stats
            updateViewStats(order, currentViews.intValue());

            // Update Binom campaign stats
            List<BinomCampaign> campaigns = binomService.getActiveCampaignsForOrder(order.getId());
            for (BinomCampaign campaign : campaigns) {
                binomService.updateCampaignStats(campaign.getCampaignId());
            }

            // Check if target reached
            if (viewsGained >= order.getQuantity()) {
                if (order.getStatus() == OrderStatus.ACTIVE) {
                    // Move to HOLDING status for monitoring
                    order.setStatus(OrderStatus.HOLDING);
                    orderRepository.save(order);
                    
                    // Stop Binom campaigns
                    for (BinomCampaign campaign : campaigns) {
                        binomService.stopCampaign(campaign.getCampaignId());
                    }
                    
                    log.info("Order {} reached target, moved to HOLDING", order.getId());
                }
            } else if (order.getStatus() == OrderStatus.HOLDING) {
                // Check if views dropped significantly
                double dropPercentage = (double) remainingViews / order.getQuantity();
                
                if (dropPercentage > 0.1) { // More than 10% drop
                    log.warn("Order {} views dropped by {}%, may need refill", 
                            order.getId(), (int)(dropPercentage * 100));
                    
                    // Operator will decide on refill action
                }
            }

            log.debug("Monitored order {}: {} views gained, {} remaining", 
                    order.getId(), viewsGained, remainingViews);

        } catch (Exception e) {
            log.error("Failed to monitor order {}: {}", order.getId(), e.getMessage(), e);
            
            if (e.getMessage().contains("Video unavailable") || e.getMessage().contains("deleted")) {
                // Video was deleted or made private - complete the order
                order.setStatus(OrderStatus.COMPLETED);
                order.setRemains(0);
                orderRepository.save(order);
                
                log.info("Order {} completed due to video unavailability", order.getId());
            }
        }
    }

    private void createViewStatsRecord(Order order, BinomCampaign campaign) {
        ViewStats stats = new ViewStats();
        stats.setOrder(order);
        stats.setTargetViews(order.getQuantity());
        stats.setCurrentViews(order.getStartCount());
        stats.setLastChecked(LocalDateTime.now());
        stats.setCheckInterval(1800); // 30 minutes
        stats.setCheckCount(0);
        
        viewStatsRepository.save(stats);
    }

    private void updateViewStats(Order order, int currentViews) {
        viewStatsRepository.findByOrderId(order.getId()).ifPresent(stats -> {
            stats.setCurrentViews(currentViews);
            stats.setLastChecked(LocalDateTime.now());
            stats.setCheckCount(stats.getCheckCount() + 1);
            
            // Calculate velocity (views per hour)
            if (stats.getCheckCount() > 1) {
                long hoursSinceCreation = java.time.Duration.between(stats.getCreatedAt(), LocalDateTime.now()).toHours();
                if (hoursSinceCreation > 0) {
                    int viewsGained = currentViews - order.getStartCount();
                    stats.setViewsVelocity(java.math.BigDecimal.valueOf((double) viewsGained / hoursSinceCreation));
                }
            }
            
            viewStatsRepository.save(stats);
        });
    }

    private BigDecimal calculateViews(Order order, ConversionCoefficient coefficient) {
        return BigDecimal.valueOf(order.getQuantity()).multiply(coefficient.getWithClip());
    }

    // Manual operator actions
    @Transactional
    public void stopOrder(Long orderId, String reason) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new IllegalArgumentException("Order not found: " + orderId));

        // Stop all Binom campaigns
        List<BinomCampaign> campaigns = binomService.getActiveCampaignsForOrder(orderId);
        for (BinomCampaign campaign : campaigns) {
            binomService.stopCampaign(campaign.getCampaignId());
        }

        order.setStatus(OrderStatus.PAUSED);
        order.setErrorMessage(reason);
        orderRepository.save(order);

        log.info("Manually stopped order {} - reason: {}", orderId, reason);
    }

    @Transactional
    public void resumeOrder(Long orderId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new IllegalArgumentException("Order not found: " + orderId));

        // Resume all campaigns
        List<BinomCampaign> campaigns = binomService.getActiveCampaignsForOrder(orderId);
        for (BinomCampaign campaign : campaigns) {
            binomService.resumeCampaign(campaign.getCampaignId());
        }

        order.setStatus(OrderStatus.ACTIVE);
        order.setErrorMessage(null);
        orderRepository.save(order);

        log.info("Manually resumed order {}", orderId);
    }

    @Transactional
    public void refillOrder(Long orderId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new IllegalArgumentException("Order not found: " + orderId));

        // Calculate remaining views needed
        String videoId = youTubeService.extractVideoId(order.getLink());
        Long currentViews = youTubeService.getViewCount(videoId);
        int viewsGained = currentViews.intValue() - order.getStartCount();
        int remainingViews = order.getQuantity() - viewsGained;

        if (remainingViews <= 0) {
            log.info("Order {} doesn't need refill, already completed", orderId);
            return;
        }

        // Get target URL (check if we have a clip)
        String targetUrl = order.getLink();
        boolean hasClip = false;
        
        videoProcessingService.findByOrderId(orderId).ifPresent(processing -> {
            if (processing.getClipCreated() && processing.getClipUrl() != null) {
                targetUrl = processing.getClipUrl();
                hasClip = true;
            }
        });

        // Create refill campaign
        binomService.createRefillCampaign(order, targetUrl, remainingViews, hasClip);

        order.setStatus(OrderStatus.REFILL);
        orderRepository.save(order);

        log.info("Created refill campaign for order {} with {} remaining views", orderId, remainingViews);
    }
}