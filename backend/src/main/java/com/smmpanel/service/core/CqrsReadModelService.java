package com.smmpanel.service.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smmpanel.entity.Order;
import com.smmpanel.entity.OrderEvent;
import com.smmpanel.entity.OrderStatus;
import com.smmpanel.entity.Service;
import com.smmpanel.entity.User;
import com.smmpanel.model.OrderReadModel;
import com.smmpanel.repository.jpa.OrderRepository;
import com.smmpanel.repository.jpa.ServiceRepository;
import com.smmpanel.repository.jpa.UserRepository;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Page;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Component
@RequiredArgsConstructor
public class CqrsReadModelService {

    private final RedisTemplate<String, Object> redisTemplate;
    private final OrderRepository orderRepository;
    private final UserRepository userRepository;
    private final ServiceRepository serviceRepository;
    private final EventSourcingService eventSourcingService;
    private final ObjectMapper objectMapper;

    private static final String ORDER_READ_MODEL_KEY = "order:read:";
    private static final String ORDER_BY_USER_KEY = "orders:user:";
    private static final String ORDER_BY_STATUS_KEY = "orders:status:";
    private static final String ORDER_BY_SERVICE_KEY = "orders:service:";
    private static final String RECENT_ORDERS_KEY = "orders:recent";
    private static final String ORDER_STATS_KEY = "stats:orders:";

    @Transactional
    public void updateOrderReadModel(Order order) {
        // Update the order read model in Redis or other storage
        String key = ORDER_READ_MODEL_KEY + order.getId();
        redisTemplate.opsForValue().set(key, order, 1, TimeUnit.HOURS);

        // Update related indexes
        updateOrderIndexes(order);
    }

    private void updateOrderIndexes(Order order) {
        // Update user index
        String userKey = ORDER_BY_USER_KEY + order.getUser().getId();
        redisTemplate
                .opsForZSet()
                .add(userKey, order.getId(), order.getCreatedAt().toEpochSecond(ZoneOffset.UTC));

        // Update status index
        String statusKey = ORDER_BY_STATUS_KEY + order.getStatus().name();
        redisTemplate.opsForSet().add(statusKey, order.getId());

        // Update service index
        String serviceKey = ORDER_BY_SERVICE_KEY + order.getService().getId();
        redisTemplate.opsForSet().add(serviceKey, order.getId());
    }

    @Transactional
    public OrderReadModel buildAndCacheReadModel(Long orderId) {
        log.info("Building read model for order: {}", orderId);

        Order order =
                orderRepository
                        .findById(orderId)
                        .orElseThrow(() -> new RuntimeException("Order not found: " + orderId));

        OrderReadModel readModel = buildReadModel(order);

        // Cache the read model
        cacheReadModel(readModel);

        // Update indexes
        updateIndexes(readModel);

        return readModel;
    }

    @Async
    @KafkaListener(
            topics = {"smm.order.processing", "smm.order.state.updates"},
            groupId = "cqrs-read-model-group")
    public void handleOrderEvent(OrderEvent event) {
        try {
            log.debug("Updating read model for order event: {}", event.getEventId());

            // Get or create read model
            OrderReadModel readModel = getReadModel(event.getOrderId());
            if (readModel == null) {
                readModel = buildAndCacheReadModel(event.getOrderId());
            }

            // Update read model based on event
            updateReadModelFromEvent(readModel, event);

            // Recache
            cacheReadModel(readModel);

            // Update indexes
            updateIndexes(readModel);

        } catch (Exception e) {
            log.error("Failed to update read model for event: {}", event.getEventId(), e);
        }
    }

    @Cacheable(value = "orderReadModel", key = "#orderId")
    public OrderReadModel getReadModel(Long orderId) {
        String key = ORDER_READ_MODEL_KEY + orderId;
        Object cached = redisTemplate.opsForValue().get(key);

        if (cached != null) {
            return objectMapper.convertValue(cached, OrderReadModel.class);
        }

        // Build from database if not cached
        return buildAndCacheReadModel(orderId);
    }

    public List<OrderReadModel> getUserOrders(Long userId, int page, int size) {
        String key = ORDER_BY_USER_KEY + userId;

        // Get from sorted set (sorted by creation time)
        Set<Object> orderIds =
                redisTemplate.opsForZSet().reverseRange(key, page * size, (page + 1) * size - 1);

        if (orderIds == null || orderIds.isEmpty()) {
            // Rebuild from database
            return rebuildUserOrdersIndex(userId, page, size);
        }

        return orderIds.stream()
                .map(id -> getReadModel(Long.valueOf(id.toString())))
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    public List<OrderReadModel> getOrdersByStatus(String status, int limit) {
        String key = ORDER_BY_STATUS_KEY + status;

        Set<Object> orderIds = redisTemplate.opsForZSet().reverseRange(key, 0, limit - 1);

        if (orderIds == null || orderIds.isEmpty()) {
            return Collections.emptyList();
        }

        return orderIds.stream()
                .map(id -> getReadModel(Long.valueOf(id.toString())))
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    public List<OrderReadModel> getRecentOrders(int limit) {
        Set<Object> orderIds =
                redisTemplate.opsForZSet().reverseRange(RECENT_ORDERS_KEY, 0, limit - 1);

        if (orderIds == null || orderIds.isEmpty()) {
            return Collections.emptyList();
        }

        return orderIds.stream()
                .map(id -> getReadModel(Long.valueOf(id.toString())))
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    public Map<String, Object> getOrderStatistics(Long userId) {
        String key = ORDER_STATS_KEY + (userId != null ? userId : "global");

        Map<Object, Object> stats = redisTemplate.opsForHash().entries(key);

        if (stats.isEmpty()) {
            return calculateAndCacheStatistics(userId);
        }

        Map<String, Object> result = new HashMap<>();
        stats.forEach((k, v) -> result.put(k.toString(), v));
        return result;
    }

    @CacheEvict(value = "orderReadModel", key = "#orderId")
    public void invalidateReadModel(Long orderId) {
        String key = ORDER_READ_MODEL_KEY + orderId;
        redisTemplate.delete(key);
        log.info("Invalidated read model for order: {}", orderId);
    }

    @Async
    public void rebuildAllReadModels() {
        log.info("Starting rebuild of all read models");

        List<Order> orders = orderRepository.findAll();
        int count = 0;

        for (Order order : orders) {
            try {
                buildAndCacheReadModel(order.getId());
                count++;

                if (count % 100 == 0) {
                    log.info("Rebuilt {} read models", count);
                }
            } catch (Exception e) {
                log.error("Failed to rebuild read model for order: {}", order.getId(), e);
            }
        }

        log.info("Completed rebuilding {} read models", count);
    }

    private OrderReadModel buildReadModel(Order order) {
        User user = order.getUser();
        Service service = order.getService();

        OrderReadModel readModel =
                OrderReadModel.builder()
                        .id(OrderReadModel.generateId(order.getId()))
                        .orderId(order.getId())
                        .userId(user.getId())
                        .username(user.getUsername())
                        .status(order.getStatus().name())
                        .serviceId(service.getId())
                        .serviceName(service.getName())
                        .link(order.getLink())
                        .quantity(order.getQuantity())
                        .startCount(order.getStartCount())
                        .remains(order.getRemains())
                        .charge(order.getCharge())
                        .rate(service.getPricePer1000())
                        // binomCampaignId removed - using direct campaign connection
                        .binomOfferId(order.getBinomOfferId())
                        .youtubeVideoId(order.getYoutubeVideoId())
                        .createdAt(order.getCreatedAt())
                        .updatedAt(order.getUpdatedAt())
                        .build();

        // Add service details
        readModel.setServiceCategory(service.getCategory());
        readModel.setServiceIsActive(service.getActive());
        readModel.setServiceMinQuantity(service.getMinOrder());
        readModel.setServiceMaxQuantity(service.getMaxOrder());

        // Add customer metrics
        long customerOrderCount = orderRepository.countByUser_Username(user.getUsername());
        readModel.setCustomerOrderCount((int) customerOrderCount);

        BigDecimal lifetimeValue = orderRepository.sumChargeByUser_Username(user.getUsername());
        readModel.setCustomerLifetimeValue(lifetimeValue);

        // Add event statistics
        Map<String, Object> eventStats = eventSourcingService.getEventStatistics(order.getId());
        readModel.setTotalEvents((Integer) eventStats.get("totalEvents"));
        readModel.setProcessedEvents(((Long) eventStats.get("processedEvents")).intValue());
        readModel.setFailedEvents(((Long) eventStats.get("failedEvents")).intValue());

        @SuppressWarnings("unchecked")
        Map<String, Long> eventTypeCounts = (Map<String, Long>) eventStats.get("eventTypeCounts");
        if (eventTypeCounts != null) {
            readModel.setEventTypeCounts(
                    eventTypeCounts.entrySet().stream()
                            .collect(
                                    Collectors.toMap(
                                            Map.Entry::getKey, e -> e.getValue().intValue())));
        }

        // Calculate metrics
        readModel.calculateMetrics();

        return readModel;
    }

    private void cacheReadModel(OrderReadModel readModel) {
        String key = ORDER_READ_MODEL_KEY + readModel.getOrderId();
        redisTemplate.opsForValue().set(key, readModel, 1, TimeUnit.HOURS);
    }

    private void updateIndexes(OrderReadModel readModel) {
        // Update user orders index
        String userKey = ORDER_BY_USER_KEY + readModel.getUserId();
        double score = readModel.getCreatedAt().toEpochSecond(ZoneOffset.UTC);
        redisTemplate.opsForZSet().add(userKey, readModel.getOrderId(), score);
        redisTemplate.expire(userKey, 24, TimeUnit.HOURS);

        // Update status index
        String statusKey = ORDER_BY_STATUS_KEY + readModel.getStatus();
        redisTemplate.opsForZSet().add(statusKey, readModel.getOrderId(), score);
        redisTemplate.expire(statusKey, 1, TimeUnit.HOURS);

        // Update service index
        String serviceKey = ORDER_BY_SERVICE_KEY + readModel.getServiceId();
        redisTemplate.opsForZSet().add(serviceKey, readModel.getOrderId(), score);
        redisTemplate.expire(serviceKey, 1, TimeUnit.HOURS);

        // Update recent orders
        redisTemplate.opsForZSet().add(RECENT_ORDERS_KEY, readModel.getOrderId(), score);

        // Keep only last 1000 recent orders
        Long size = redisTemplate.opsForZSet().size(RECENT_ORDERS_KEY);
        if (size != null && size > 1000) {
            redisTemplate.opsForZSet().removeRange(RECENT_ORDERS_KEY, 0, size - 1001);
        }
    }

    private void updateReadModelFromEvent(OrderReadModel readModel, OrderEvent event) {
        readModel.updateFromEvent(event.getEventType());

        // Update specific fields based on event type
        Map<String, Object> eventData = event.getEventData();

        switch (event.getEventType()) {
            case OrderEvent.ORDER_STATUS_CHANGED:
                readModel.setStatus((String) eventData.get("newStatus"));
                readModel.setUpdatedAt(LocalDateTime.now());
                break;

            case OrderEvent.ORDER_COMPLETED:
                readModel.setStatus("COMPLETED");
                readModel.setCompletedAt(LocalDateTime.now());
                readModel.setRemains(0);
                break;

            case OrderEvent.ORDER_CANCELLED:
                readModel.setStatus("CANCELLED");
                break;

            case OrderEvent.ORDER_REFUNDED:
                readModel.setStatus("REFUNDED");
                break;
        }

        // Recalculate metrics
        readModel.calculateMetrics();
    }

    private List<OrderReadModel> rebuildUserOrdersIndex(Long userId, int page, int size) {
        User user = userRepository.findById(userId).orElse(null);
        if (user == null) {
            return Collections.emptyList();
        }

        Page<Order> orderPage =
                orderRepository.findByUser(
                        user, org.springframework.data.domain.PageRequest.of(page, size));
        List<Order> orders = orderPage.getContent();

        List<OrderReadModel> readModels = new ArrayList<>();

        for (Order order : orders) {
            OrderReadModel readModel = buildReadModel(order);
            cacheReadModel(readModel);
            updateIndexes(readModel);
            readModels.add(readModel);
        }

        return readModels;
    }

    private Map<String, Object> calculateAndCacheStatistics(Long userId) {
        Map<String, Object> stats = new HashMap<>();

        if (userId != null) {
            // User-specific statistics
            User user = userRepository.findById(userId).orElse(null);
            if (user != null) {
                stats.put("totalOrders", orderRepository.countByUser_Username(user.getUsername()));
                stats.put(
                        "completedOrders",
                        orderRepository.countByUser_UsernameAndStatus(
                                user.getUsername(), OrderStatus.COMPLETED));
                stats.put(
                        "totalSpent", orderRepository.sumChargeByUser_Username(user.getUsername()));
            }
        } else {
            // Global statistics
            stats.put("totalOrders", orderRepository.count());
            stats.put("activeOrders", orderRepository.countByStatus(OrderStatus.ACTIVE));
            stats.put(
                    "todayOrders",
                    orderRepository.countByCreatedAtAfter(
                            LocalDateTime.now().toLocalDate().atStartOfDay()));
        }

        // Cache statistics
        String key = ORDER_STATS_KEY + (userId != null ? userId : "global");
        redisTemplate.opsForHash().putAll(key, stats);
        redisTemplate.expire(key, 5, TimeUnit.MINUTES);

        return stats;
    }
}
