package com.smmpanel.consumer;

import com.smmpanel.entity.Order;
import com.smmpanel.entity.OrderStatus;
import com.smmpanel.event.OrderCreatedEvent;
import com.smmpanel.event.OrderStatusChangedEvent;
import com.smmpanel.producer.OrderEventProducer;
import com.smmpanel.repository.OrderRepository;
import com.smmpanel.service.BinomService;
import com.smmpanel.service.YouTubeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * Order Event Consumer
 * Processes order events from Kafka asynchronously
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderEventConsumer {

    private final OrderRepository orderRepository;
    private final YouTubeService youTubeService;
    private final BinomService binomService;
    private final OrderEventProducer orderEventProducer;

    /**
     * Process order created events
     */
    @KafkaListener(
        topics = "smm.order.processing",
        groupId = "order-processing-group",
        containerFactory = "kafkaListenerContainerFactory"
    )
    @Transactional
    public void processOrderCreatedEvent(
            @Payload OrderCreatedEvent event,
            @Header(KafkaHeaders.RECEIVED_TOPIC) String topic,
            @Header(KafkaHeaders.RECEIVED_PARTITION) int partition,
            @Header(KafkaHeaders.OFFSET) long offset) {
        
        log.info("Processing order created event: orderId={}, userId={}, topic={}, partition={}, offset={}", 
                event.getOrderId(), event.getUserId(), topic, partition, offset);
        
        try {
            Optional<Order> orderOpt = orderRepository.findById(event.getOrderId());
            if (orderOpt.isEmpty()) {
                log.error("Order not found: orderId={}", event.getOrderId());
                return;
            }
            
            Order order = orderOpt.get();
            
            // Step 1: Verify YouTube video exists and get start_count
            processYouTubeVerification(order);
            
            // Step 2: Update order status to PROCESSING
            updateOrderStatus(order, OrderStatus.PROCESSING);
            
            // Step 3: Create Binom campaign
            processBinomCampaignCreation(order);
            
            // Step 4: Update order status to ACTIVE
            updateOrderStatus(order, OrderStatus.ACTIVE);
            
            log.info("Successfully processed order: orderId={}", event.getOrderId());
            
        } catch (Exception e) {
            log.error("Failed to process order created event: orderId={}", event.getOrderId(), e);
            throw e; // Let Kafka retry or send to DLQ
        }
    }

    /**
     * Process order status changed events
     */
    @KafkaListener(
        topics = "smm.order.state.updates",
        groupId = "order-status-group",
        containerFactory = "kafkaListenerContainerFactory"
    )
    @Transactional
    public void processOrderStatusChangedEvent(
            @Payload OrderStatusChangedEvent event,
            @Header(KafkaHeaders.RECEIVED_TOPIC) String topic,
            @Header(KafkaHeaders.RECEIVED_PARTITION) int partition,
            @Header(KafkaHeaders.OFFSET) long offset) {
        
        log.info("Processing order status changed event: orderId={}, oldStatus={}, newStatus={}, topic={}, partition={}, offset={}", 
                event.getOrder().getId(), event.getOldStatus(), event.getNewStatus(), topic, partition, offset);
        
        try {
            // Handle status-specific logic
            switch (event.getNewStatus()) {
                case ACTIVE:
                    handleOrderActivated(event.getOrder());
                    break;
                case COMPLETED:
                    handleOrderCompleted(event.getOrder());
                    break;
                case CANCELLED:
                    handleOrderCancelled(event.getOrder());
                    break;
                default:
                    log.debug("No specific handling for status: {}", event.getNewStatus());
            }
            
        } catch (Exception e) {
            log.error("Failed to process order status changed event: orderId={}", event.getOrder().getId(), e);
            throw e; // Let Kafka retry or send to DLQ
        }
    }

    /**
     * Process YouTube verification
     */
    private void processYouTubeVerification(Order order) {
        try {
            if (order.getLink() != null && order.getLink().contains("youtube")) {
                String videoId = youTubeService.extractVideoId(order.getLink());
                Long viewCount = youTubeService.getViewCount(videoId);
                
                order.setYoutubeVideoId(videoId);
                order.setStartCount(viewCount.intValue());
                orderRepository.save(order);
                
                log.info("YouTube verification completed: orderId={}, videoId={}, startCount={}", 
                        order.getId(), videoId, viewCount);
            }
        } catch (Exception e) {
            log.error("YouTube verification failed: orderId={}", order.getId(), e);
            // Don't fail the entire process, continue with default values
        }
    }

    /**
     * Process Binom campaign creation
     */
    private void processBinomCampaignCreation(Order order) {
        try {
            // Create Binom campaign
            binomService.createCampaign(order, order.getLink(), false);
            log.info("Binom campaign created: orderId={}", order.getId());
        } catch (Exception e) {
            log.error("Binom campaign creation failed: orderId={}", order.getId(), e);
            // Update order with error message
            order.setErrorMessage("Failed to create Binom campaign: " + e.getMessage());
            orderRepository.save(order);
            throw e; // Let Kafka retry
        }
    }

    /**
     * Update order status
     */
    private void updateOrderStatus(Order order, OrderStatus newStatus) {
        OrderStatus oldStatus = order.getStatus();
        order.setStatus(newStatus);
        orderRepository.save(order);
        
        log.info("Order status updated: orderId={}, oldStatus={}, newStatus={}", 
                order.getId(), oldStatus, newStatus);
    }

    /**
     * Handle order activated
     */
    private void handleOrderActivated(Order order) {
        log.info("Order activated: orderId={}", order.getId());
        // Additional logic for activated orders
    }

    /**
     * Handle order completed
     */
    private void handleOrderCompleted(Order order) {
        log.info("Order completed: orderId={}", order.getId());
        // Additional logic for completed orders
    }

    /**
     * Handle order cancelled
     */
    private void handleOrderCancelled(Order order) {
        log.info("Order cancelled: orderId={}", order.getId());
        // Additional logic for cancelled orders
    }
} 