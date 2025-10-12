package com.smmpanel.service.admin;

import com.smmpanel.dto.admin.*;
import com.smmpanel.entity.*;
import com.smmpanel.entity.YouTubeAccountStatus;
import com.smmpanel.repository.jpa.*;
import com.smmpanel.service.balance.BalanceService;
import com.smmpanel.service.core.AuditService;
import com.smmpanel.service.integration.BinomService;
import com.smmpanel.service.integration.SeleniumService;
import com.smmpanel.service.integration.YouTubeService;
import com.smmpanel.service.order.state.OrderStateManager;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class AdminService {

    private final OrderRepository orderRepository;
    private final UserRepository userRepository;
    private final ServiceRepository serviceRepository;
    private final ConversionCoefficientRepository coefficientRepository;
    private final YouTubeAccountRepository youTubeAccountRepository;
    private final OperatorLogRepository operatorLogRepository;
    private final BalanceService balanceService;
    private final SeleniumService seleniumService;
    private final BinomService binomService;
    private final YouTubeService youTubeService;
    private final OrderStateManager orderStateManager;
    private final AuditService auditService;

    @Transactional(readOnly = true)
    public DashboardStats getDashboardStats() {
        LocalDateTime last24Hours = LocalDateTime.now().minusHours(24);
        LocalDateTime last7Days = LocalDateTime.now().minusDays(7);
        LocalDateTime last30Days = LocalDateTime.now().minusDays(30);

        return DashboardStats.builder()
                .totalOrders(orderRepository.count())
                .ordersLast24h(orderRepository.countOrdersCreatedAfter(last24Hours))
                .ordersLast7Days(orderRepository.countOrdersCreatedAfter(last7Days))
                .ordersLast30Days(orderRepository.countOrdersCreatedAfter(last30Days))
                .totalRevenue(orderRepository.sumRevenueAfter(LocalDateTime.now().minusYears(1)))
                .revenueLast24h(orderRepository.sumRevenueAfter(last24Hours))
                .revenueLast7Days(orderRepository.sumRevenueAfter(last7Days))
                .revenueLast30Days(orderRepository.sumRevenueAfter(last30Days))
                .activeOrders(
                        orderRepository
                                .findByStatusIn(
                                        Arrays.asList(
                                                OrderStatus.ACTIVE,
                                                OrderStatus.PROCESSING,
                                                OrderStatus.IN_PROGRESS))
                                .size())
                .pendingOrders(orderRepository.findByStatus(OrderStatus.PENDING).size())
                .completedOrders(orderRepository.findByStatus(OrderStatus.COMPLETED).size())
                .totalUsers(userRepository.count())
                .activeYouTubeAccounts(
                        youTubeAccountRepository.findByStatus(YouTubeAccountStatus.ACTIVE).size())
                .build();
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getAllOrders(
            String status, String username, String dateFrom, String dateTo, Pageable pageable) {
        Specification<Order> spec = Specification.where(null);

        if (status != null && !status.isEmpty()) {
            OrderStatus orderStatus = OrderStatus.valueOf(status.toUpperCase());
            spec = spec.and((root, query, cb) -> cb.equal(root.get("status"), orderStatus));
        }

        if (username != null && !username.isEmpty()) {
            spec =
                    spec.and(
                            (root, query, cb) ->
                                    cb.like(
                                            cb.lower(root.get("user").get("username")),
                                            "%" + username.toLowerCase() + "%"));
        }

        if (dateFrom != null && !dateFrom.isEmpty()) {
            LocalDateTime from = LocalDate.parse(dateFrom).atStartOfDay();
            spec =
                    spec.and(
                            (root, query, cb) ->
                                    cb.greaterThanOrEqualTo(root.get("createdAt"), from));
        }

        if (dateTo != null && !dateTo.isEmpty()) {
            LocalDateTime to = LocalDate.parse(dateTo).atTime(23, 59, 59);
            spec = spec.and((root, query, cb) -> cb.lessThanOrEqualTo(root.get("createdAt"), to));
        }

        Page<Order> orders = orderRepository.findAll(spec, pageable);

        List<AdminOrderDto> orderDtos =
                orders.getContent().stream()
                        .map(this::mapToAdminOrderDto)
                        .collect(Collectors.toList());

        Map<String, Object> response = new HashMap<>();
        response.put("orders", orderDtos);
        response.put("totalPages", orders.getTotalPages());
        response.put("totalElements", orders.getTotalElements());
        response.put("currentPage", orders.getNumber());
        response.put("pageSize", orders.getSize());

        return response;
    }

    @Transactional
    public int performBulkAction(BulkActionRequest request) {
        String operatorUsername = SecurityContextHolder.getContext().getAuthentication().getName();
        User operator = userRepository.findByUsername(operatorUsername).orElse(null);

        int processed = 0;
        for (Long orderId : request.getOrderIds()) {
            try {
                switch (request.getAction().toLowerCase()) {
                    case "stop":
                        stopOrder(orderId, request.getReason());
                        break;
                    case "resume":
                        resumeOrder(orderId);
                        break;
                    case "cancel":
                        cancelOrder(orderId, request.getReason());
                        break;
                    case "complete":
                        completeOrder(orderId);
                        break;
                    default:
                        continue;
                }

                // Log the action
                if (operator != null) {
                    logOperatorAction(
                            operator,
                            request.getAction(),
                            "ORDER",
                            orderId,
                            Map.of(
                                    "reason",
                                    request.getReason() != null ? request.getReason() : ""));
                }

                processed++;
            } catch (Exception e) {
                log.error(
                        "Failed to perform bulk action {} on order {}: {}",
                        request.getAction(),
                        orderId,
                        e.getMessage());
            }
        }

        log.info(
                "Performed bulk action {} on {} orders by {}",
                request.getAction(),
                processed,
                operatorUsername);

        return processed;
    }

    @Transactional
    public void cancelOrder(Long orderId, String reason) {
        Order order =
                orderRepository
                        .findById(orderId)
                        .orElseThrow(
                                () -> new IllegalArgumentException("Order not found: " + orderId));

        // Campaign stopping removed - no longer needed

        // Calculate refund amount
        BigDecimal refundAmount = calculateRefundAmount(order);

        if (refundAmount.compareTo(BigDecimal.ZERO) > 0) {
            balanceService.refund(order.getUser(), refundAmount, order, reason);
        }

        order.setStatus(OrderStatus.CANCELLED);
        order.setErrorMessage(reason);
        orderRepository.save(order);

        log.info("Cancelled order {} with refund ${} - reason: {}", orderId, refundAmount, reason);
    }

    @Transactional
    public void updateStartCount(Long orderId, Integer newStartCount) {
        Order order =
                orderRepository
                        .findById(orderId)
                        .orElseThrow(
                                () -> new IllegalArgumentException("Order not found: " + orderId));

        int oldStartCount = order.getStartCount();
        order.setStartCount(newStartCount);

        // Recalculate remains
        int currentViews = getCurrentViewCount(order);
        int viewsGained = currentViews - newStartCount;
        order.setRemains(Math.max(0, order.getQuantity() - viewsGained));

        orderRepository.save(order);

        log.info(
                "Updated start count for order {} from {} to {}",
                orderId,
                oldStartCount,
                newStartCount);
    }

    @Transactional
    public void completeOrder(Long orderId) {
        Order order =
                orderRepository
                        .findById(orderId)
                        .orElseThrow(
                                () -> new IllegalArgumentException("Order not found: " + orderId));

        // Campaign stopping removed - no longer needed

        order.setStatus(OrderStatus.COMPLETED);
        order.setRemains(0);
        orderRepository.save(order);

        log.info("Manually completed order {}", orderId);
    }

    @Transactional
    public void pauseOrder(Long orderId, String reason) {
        Order order =
                orderRepository
                        .findById(orderId)
                        .orElseThrow(
                                () -> new IllegalArgumentException("Order not found: " + orderId));

        // Only active orders can be paused
        if (order.getStatus() != OrderStatus.ACTIVE) {
            throw new IllegalStateException(
                    "Order must be ACTIVE to pause. Current status: " + order.getStatus());
        }

        order.setStatus(OrderStatus.PAUSED);
        orderRepository.save(order);

        log.info("Paused order {} with reason: {}", orderId, reason);
    }

    @Transactional
    public void resumeOrder(Long orderId) {
        Order order =
                orderRepository
                        .findById(orderId)
                        .orElseThrow(
                                () -> new IllegalArgumentException("Order not found: " + orderId));

        // Only resume PAUSED orders
        // PENDING orders are processed automatically
        if (order.getStatus() != OrderStatus.PAUSED) {
            throw new IllegalStateException(
                    "Order must be PAUSED to resume. Current status: " + order.getStatus());
        }

        order.setStatus(OrderStatus.ACTIVE);
        orderRepository.save(order);

        log.info("Resumed order {}", orderId);
    }

    @Transactional
    public void refillOrder(Long orderId, Integer newQuantity) {
        Order order =
                orderRepository
                        .findById(orderId)
                        .orElseThrow(
                                () -> new IllegalArgumentException("Order not found: " + orderId));

        // Only completed or partial orders can be refilled
        if (order.getStatus() != OrderStatus.COMPLETED
                && order.getStatus() != OrderStatus.PARTIAL) {
            throw new IllegalStateException(
                    "Order must be COMPLETED or PARTIAL to refill. Current status: "
                            + order.getStatus());
        }

        // Update quantity if provided
        if (newQuantity != null && newQuantity > 0) {
            int additionalQuantity = newQuantity;
            order.setQuantity(order.getQuantity() + additionalQuantity);
            order.setRemains(order.getRemains() + additionalQuantity);
        } else {
            // Refill with original quantity
            order.setRemains(order.getQuantity());
        }

        order.setStatus(OrderStatus.ACTIVE);
        orderRepository.save(order);

        log.info("Refilled order {} with quantity: {}", orderId, newQuantity);
    }

    @Transactional(readOnly = true)
    public List<CoefficientDto> getConversionCoefficients() {
        return coefficientRepository.findAll().stream()
                .map(this::mapToCoefficientDto)
                .collect(Collectors.toList());
    }

    @Transactional
    public CoefficientDto updateConversionCoefficient(
            Long serviceId, CoefficientUpdateRequest request) {
        String currentUsername = SecurityContextHolder.getContext().getAuthentication().getName();
        ConversionCoefficient coefficient =
                coefficientRepository
                        .findByServiceId(serviceId)
                        .orElseThrow(
                                () ->
                                        new IllegalArgumentException(
                                                "Coefficient not found for service: " + serviceId));
        coefficient.setWithClip(request.getWithClip());
        coefficient.setWithoutClip(request.getWithoutClip());
        coefficient.setUpdatedBy(currentUsername);
        coefficient.setUpdatedAt(LocalDateTime.now());
        coefficient = coefficientRepository.save(coefficient);
        return mapToCoefficientDto(coefficient);
    }

    @Transactional
    public ConversionCoefficient createConversionCoefficient(
            Long serviceId, BigDecimal withClip, BigDecimal withoutClip) {
        String currentUsername = SecurityContextHolder.getContext().getAuthentication().getName();
        com.smmpanel.entity.Service service =
                serviceRepository
                        .findById(serviceId)
                        .orElseThrow(
                                () ->
                                        new IllegalArgumentException(
                                                "Service not found: " + serviceId));
        ConversionCoefficient coefficient =
                ConversionCoefficient.builder()
                        .service(service)
                        .withClip(withClip)
                        .withoutClip(withoutClip)
                        .updatedBy(currentUsername)
                        .createdAt(LocalDateTime.now())
                        .updatedAt(LocalDateTime.now())
                        .build();
        return coefficientRepository.save(coefficient);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getYouTubeAccounts(Pageable pageable) {
        Page<YouTubeAccount> accounts = youTubeAccountRepository.findAll(pageable);

        Map<String, Object> response = new HashMap<>();
        response.put("accounts", accounts.getContent());
        response.put("totalPages", accounts.getTotalPages());
        response.put("totalElements", accounts.getTotalElements());
        response.put("currentPage", accounts.getNumber());
        response.put("pageSize", accounts.getSize());

        return response;
    }

    @Transactional
    public void resetYouTubeAccountDailyLimit(Long id) {
        // Daily limits have been removed - this method is now a no-op for backward compatibility
        log.info(
                "resetYouTubeAccountDailyLimit called for account {} but daily limits have been"
                        + " removed",
                id);
    }

    @Transactional
    public void updateYouTubeAccountStatus(Long id, String status) {
        YouTubeAccount account =
                youTubeAccountRepository
                        .findById(id)
                        .orElseThrow(
                                () ->
                                        new IllegalArgumentException(
                                                "YouTube account not found: " + id));

        YouTubeAccountStatus newStatus = YouTubeAccountStatus.valueOf(status.toUpperCase());
        account.setStatus(newStatus);
        youTubeAccountRepository.save(account);

        log.info("Updated YouTube account {} status to {}", account.getEmail(), status);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getSystemHealth() {
        Map<String, Object> health = new HashMap<>();

        // Check Selenium connection
        health.put("selenium", seleniumService.testConnection());

        // Check database
        health.put("database", true); // If we're here, DB is working

        // Count active components
        health.put(
                "activeYouTubeAccounts",
                youTubeAccountRepository.findByStatus(YouTubeAccountStatus.ACTIVE).size());
        health.put("pendingOrders", orderRepository.findByStatus(OrderStatus.PENDING).size());
        health.put("processingOrders", orderRepository.findByStatus(OrderStatus.PROCESSING).size());

        return health;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getOperatorLogs(
            String operatorUsername,
            String action,
            String dateFrom,
            String dateTo,
            Pageable pageable) {
        Specification<OperatorLog> spec = Specification.where(null);

        if (operatorUsername != null && !operatorUsername.isEmpty()) {
            spec =
                    spec.and(
                            (root, query, cb) ->
                                    cb.like(
                                            cb.lower(root.get("operator").get("username")),
                                            "%" + operatorUsername.toLowerCase() + "%"));
        }

        if (action != null && !action.isEmpty()) {
            spec =
                    spec.and(
                            (root, query, cb) ->
                                    cb.equal(root.get("action"), action.toUpperCase()));
        }

        if (dateFrom != null && !dateFrom.isEmpty()) {
            LocalDateTime from = LocalDate.parse(dateFrom).atStartOfDay();
            spec =
                    spec.and(
                            (root, query, cb) ->
                                    cb.greaterThanOrEqualTo(root.get("createdAt"), from));
        }

        if (dateTo != null && !dateTo.isEmpty()) {
            LocalDateTime to = LocalDate.parse(dateTo).atTime(23, 59, 59);
            spec = spec.and((root, query, cb) -> cb.lessThanOrEqualTo(root.get("createdAt"), to));
        }

        Page<OperatorLog> logs = operatorLogRepository.findAll(spec, pageable);

        Map<String, Object> response = new HashMap<>();
        response.put("logs", logs.getContent());
        response.put("totalPages", logs.getTotalPages());
        response.put("totalElements", logs.getTotalElements());
        response.put("currentPage", logs.getNumber());
        response.put("pageSize", logs.getSize());

        return response;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getRevenueStats(int days) {
        LocalDateTime startDate = LocalDateTime.now().minusDays(days);

        // Get daily revenue for the period
        List<Object[]> dailyRevenue = orderRepository.getDailyRevenue(startDate);

        Map<String, Object> stats = new HashMap<>();
        stats.put("totalRevenue", orderRepository.sumRevenueAfter(startDate));
        stats.put("dailyRevenue", dailyRevenue);
        stats.put("period", days + " days");

        return stats;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getOrderStats(int days) {
        LocalDateTime startDate = LocalDateTime.now().minusDays(days);

        // Get order counts by status
        Map<String, Long> statusCounts = new HashMap<>();
        for (OrderStatus status : OrderStatus.values()) {
            Long count = orderRepository.countByStatusAndCreatedAtAfter(status, startDate);
            statusCounts.put(status.name(), count);
        }

        Map<String, Object> stats = new HashMap<>();
        stats.put("totalOrders", orderRepository.countOrdersCreatedAfter(startDate));
        stats.put("statusCounts", statusCounts);
        stats.put("period", days + " days");

        return stats;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getUsers(String search, String role, Pageable pageable) {
        Specification<User> spec = Specification.where(null);

        if (search != null && !search.isEmpty()) {
            spec =
                    spec.and(
                            (root, query, cb) ->
                                    cb.or(
                                            cb.like(
                                                    cb.lower(root.get("username")),
                                                    "%" + search.toLowerCase() + "%"),
                                            cb.like(
                                                    cb.lower(root.get("email")),
                                                    "%" + search.toLowerCase() + "%")));
        }

        if (role != null && !role.isEmpty()) {
            UserRole userRole = UserRole.valueOf(role.toUpperCase());
            spec = spec.and((root, query, cb) -> cb.equal(root.get("role"), userRole));
        }

        Page<User> users = userRepository.findAll(spec, pageable);

        Map<String, Object> response = new HashMap<>();
        response.put("users", users.getContent());
        response.put("totalPages", users.getTotalPages());
        response.put("totalElements", users.getTotalElements());
        response.put("currentPage", users.getNumber());
        response.put("pageSize", users.getSize());

        return response;
    }

    @Transactional
    public void adjustUserBalance(Long userId, Double amount, String reason) {
        User user =
                userRepository
                        .findById(userId)
                        .orElseThrow(
                                () -> new IllegalArgumentException("User not found: " + userId));

        BigDecimal adjustmentAmount = BigDecimal.valueOf(amount);

        if (adjustmentAmount.compareTo(BigDecimal.ZERO) > 0) {
            balanceService.addBalance(user, adjustmentAmount, null, reason);
        } else {
            // For negative adjustments, we need to handle differently
            BigDecimal currentBalance = user.getBalance();
            BigDecimal newBalance = currentBalance.add(adjustmentAmount);

            if (newBalance.compareTo(BigDecimal.ZERO) < 0) {
                throw new IllegalArgumentException("Adjustment would result in negative balance");
            }

            user.setBalance(newBalance);
            userRepository.save(user);
        }

        log.info(
                "Adjusted balance for user {} by ${} - reason: {}",
                user.getUsername(),
                amount,
                reason);
    }

    @Transactional
    public void updateUserRole(Long userId, String role) {
        User user =
                userRepository
                        .findById(userId)
                        .orElseThrow(
                                () -> new IllegalArgumentException("User not found: " + userId));

        UserRole newRole = UserRole.valueOf(role.toUpperCase());
        UserRole oldRole = user.getRole();

        user.setRole(newRole);
        userRepository.save(user);

        log.info("Updated user {} role from {} to {}", user.getUsername(), oldRole, newRole);
    }

    private void stopOrder(Long orderId, String reason) {
        // Implementation moved to OrderProcessingService
        throw new UnsupportedOperationException("Use OrderProcessingService.stopOrder()");
    }

    private BigDecimal calculateRefundAmount(Order order) {
        // Calculate refund based on work completed
        if (order.getStatus() == OrderStatus.PENDING
                || order.getStatus() == OrderStatus.IN_PROGRESS) {
            return order.getCharge(); // Full refund
        }

        int currentViews = getCurrentViewCount(order);
        int viewsDelivered = currentViews - order.getStartCount();

        if (viewsDelivered <= 0) {
            return order.getCharge(); // Full refund if no views delivered
        }

        if (viewsDelivered >= order.getQuantity()) {
            return BigDecimal.ZERO; // No refund if target reached
        }

        // Partial refund based on remaining views
        double deliveryPercentage = (double) viewsDelivered / order.getQuantity();
        double refundPercentage = 1.0 - deliveryPercentage;

        return order.getCharge().multiply(BigDecimal.valueOf(refundPercentage));
    }

    private int getCurrentViewCount(Order order) {
        try {
            String videoId = order.getYoutubeVideoId();
            if (videoId == null) {
                // Extract from URL if not stored
                videoId = extractVideoIdFromUrl(order.getLink());
            }

            Long viewCount = youTubeService.getViewCount(videoId);
            return viewCount.intValue();
        } catch (Exception e) {
            log.warn(
                    "Could not get current view count for order {}: {}",
                    order.getId(),
                    e.getMessage());
            return order.getStartCount();
        }
    }

    private String extractVideoIdFromUrl(String url) {
        // Basic extraction - should use YouTubeService.extractVideoId()
        if (url.contains("youtube.com/watch?v=")) {
            return url.split("v=")[1].split("&")[0];
        } else if (url.contains("youtu.be/")) {
            return url.split("youtu.be/")[1].split("\\?")[0];
        }
        throw new IllegalArgumentException("Invalid YouTube URL");
    }

    private void logOperatorAction(
            User operator,
            String action,
            String targetType,
            Long targetId,
            Map<String, Object> details) {
        OperatorLog log = new OperatorLog();
        log.setOperator(operator);
        log.setAction(action.toUpperCase());
        log.setTargetType(targetType);
        log.setTargetId(targetId);
        log.setDetails(details.toString()); // In real implementation, use JSON serialization

        operatorLogRepository.save(log);
    }

    private AdminOrderDto mapToAdminOrderDto(Order order) {
        // Get Binom offer ID if available
        String binomOfferId = null;
        try {
            List<Map<String, Object>> campaigns =
                    binomService.getActiveCampaignsForOrder(order.getId());
            if (!campaigns.isEmpty()) {
                Object offerId = campaigns.get(0).get("offerId");
                if (offerId != null) {
                    binomOfferId = String.valueOf(offerId);
                }
            }
        } catch (Exception e) {
            log.debug(
                    "Could not get Binom offer ID for order {}: {}", order.getId(), e.getMessage());
        }

        // Get YouTube video title as order name
        String orderName = "N/A";
        try {
            if (order.getLink() != null) {
                String videoId = youTubeService.extractVideoId(order.getLink());
                if (videoId != null) {
                    YouTubeService.VideoDetails details = youTubeService.getVideoDetails(videoId);
                    if (details != null && details.getTitle() != null) {
                        orderName = details.getTitle();
                    }
                }
            }
        } catch (Exception e) {
            log.debug(
                    "Could not get YouTube video title for order {}: {}",
                    order.getId(),
                    e.getMessage());
            // Fallback to link if we can't get the title
            if (order.getLink() != null) {
                orderName = order.getLink();
            }
        }

        return AdminOrderDto.builder()
                .id(order.getId())
                .username(order.getUser().getUsername())
                .serviceId(order.getService().getId())
                .serviceName(order.getService().getName())
                .link(order.getLink())
                .quantity(order.getQuantity())
                .charge(order.getCharge())
                .startCount(order.getStartCount())
                .remains(order.getRemains())
                .status(order.getStatus().name())
                .createdAt(order.getCreatedAt())
                .updatedAt(order.getUpdatedAt())
                .orderName(orderName)
                .binomOfferId(binomOfferId != null ? binomOfferId : "No offer")
                .youtubeVideoId(order.getYoutubeVideoId())
                .build();
    }

    private CoefficientDto mapToCoefficientDto(ConversionCoefficient coefficient) {
        return CoefficientDto.builder()
                .id(coefficient.getId())
                .serviceId(coefficient.getServiceId())
                .withClip(coefficient.getWithClip())
                .withoutClip(coefficient.getWithoutClip())
                .updatedBy(coefficient.getUpdatedBy())
                .updatedAt(coefficient.getUpdatedAt())
                .build();
    }

    // Test Service Methods for Service Testing Page

    public Map<String, Object> testBinomConnection() {
        Map<String, Object> response = new HashMap<>();
        try {
            // Test connection by getting campaigns
            boolean isConnected = binomService.testConnection();
            if (isConnected) {
                response.put("status", "success");
                response.put("message", "Successfully connected to Binom");
                response.put("connected", true);
            } else {
                response.put("status", "error");
                response.put("error", "Connection failed");
                response.put("connected", false);
            }
        } catch (Exception e) {
            log.error("Binom connection test failed", e);
            response.put("status", "error");
            response.put("error", "Connection failed: " + e.getMessage());
            response.put("connected", false);
        }
        return response;
    }

    public Map<String, Object> syncBinomCampaigns() {
        Map<String, Object> response = new HashMap<>();
        try {
            int syncedCount = binomService.syncAllCampaigns();
            response.put("status", "success");
            response.put("message", "Successfully synced " + syncedCount + " campaigns");
            response.put("syncedCount", syncedCount);
        } catch (Exception e) {
            log.error("Binom sync failed", e);
            response.put("status", "error");
            response.put("error", "Sync failed: " + e.getMessage());
        }
        return response;
    }

    public Map<String, Object> checkYouTubeViews(String videoUrl) {
        Map<String, Object> response = new HashMap<>();
        try {
            if (videoUrl == null || videoUrl.isEmpty()) {
                response.put("status", "error");
                response.put("error", "Video URL is required");
                return response;
            }

            // Extract video ID from URL
            String videoId = extractVideoId(videoUrl);
            if (videoId == null) {
                response.put("status", "error");
                response.put("error", "Invalid YouTube URL");
                return response;
            }

            // Get complete video statistics using the new method
            YouTubeService.VideoStatistics stats = youTubeService.getVideoStatistics(videoId);

            // Get video details for title and duration
            YouTubeService.VideoDetails details = youTubeService.getVideoDetails(videoId);

            response.put("status", "success");
            response.put("videoId", videoId);
            response.put("title", details.getTitle());
            response.put("channelTitle", details.getChannelTitle());

            // Add all statistics fields
            response.put("viewCount", stats.getViewCount());
            response.put("likeCount", stats.getLikeCount());
            response.put("dislikeCount", stats.getDislikeCount()); // Will be 0 (deprecated)
            response.put("favoriteCount", stats.getFavoriteCount()); // Will be 0 (deprecated)
            response.put("commentCount", stats.getCommentCount());

            // Add quota usage info
            response.put(
                    "quotaUsage",
                    String.format("%.2f%%", youTubeService.getQuotaUsagePercentage()));

        } catch (Exception e) {
            log.error("YouTube check failed for URL: " + videoUrl, e);
            response.put("status", "error");
            response.put("error", "Failed to check video: " + e.getMessage());
        }
        return response;
    }

    public Map<String, Object> createSeleniumClip(
            String videoUrl, Integer startTime, Integer endTime) {
        Map<String, Object> response = new HashMap<>();
        try {
            if (videoUrl == null || videoUrl.isEmpty()) {
                response.put("status", "error");
                response.put("error", "Video URL is required");
                return response;
            }

            // Default times if not provided
            if (startTime == null) startTime = 0;
            if (endTime == null) endTime = 15;

            if (startTime >= endTime) {
                response.put("status", "error");
                response.put("error", "Start time must be less than end time");
                return response;
            }

            // For testing, try to get a real YouTube account
            YouTubeAccount testAccount = getTestYouTubeAccount();
            if (testAccount == null) {
                // If no account available, use async mock for demo
                log.warn("No YouTube account available, using mock async clip creation");
                Map<String, Object> jobResult =
                        seleniumService.createClipAsync(videoUrl, startTime, endTime);
                response.put("status", "success");
                response.put("message", "Clip creation job started (mock)");
                response.put("jobId", jobResult.get("jobId"));
                response.put("progress", jobResult.get("progress"));
                response.put("mock", true);
                return response;
            }

            // Start async clip creation with real Selenium
            String jobId = UUID.randomUUID().toString();
            response.put("status", "success");
            response.put("message", "Clip creation job started");
            response.put("jobId", jobId);
            response.put("progress", 0);
            response.put("videoUrl", videoUrl);

            // Store initial job status
            Map<String, Object> jobStatus = new ConcurrentHashMap<>();
            jobStatus.put("jobId", jobId);
            jobStatus.put("status", "IN_PROGRESS");
            jobStatus.put("progress", 0);
            jobStatus.put("videoUrl", videoUrl);
            jobStatus.put("createdAt", System.currentTimeMillis());
            seleniumJobTracker.put(jobId, jobStatus);

            // Execute clip creation asynchronously
            CompletableFuture.runAsync(
                    () -> {
                        try {
                            log.info("Starting real Selenium clip creation for job: {}", jobId);
                            updateSeleniumJobStatus(
                                    jobId, "IN_PROGRESS", 10, "Initializing Selenium");

                            // Generate clip title
                            String clipTitle =
                                    "Test Clip - "
                                            + LocalDateTime.now()
                                                    .format(
                                                            DateTimeFormatter.ofPattern(
                                                                    "yyyy-MM-dd HH:mm"));

                            updateSeleniumJobStatus(
                                    jobId, "IN_PROGRESS", 30, "Navigating to video");

                            // Create the clip using real Selenium automation
                            String clipUrl =
                                    seleniumService.createClip(videoUrl, testAccount, clipTitle);

                            if (clipUrl != null) {
                                updateSeleniumJobStatus(
                                        jobId, "COMPLETED", 100, "Clip created successfully");
                                jobStatus.put("clipUrl", clipUrl);
                                jobStatus.put("completedAt", System.currentTimeMillis());
                                log.info(
                                        "Successfully created clip for job {}: {}", jobId, clipUrl);
                            } else {
                                updateSeleniumJobStatus(
                                        jobId, "FAILED", -1, "Failed to create clip");
                                jobStatus.put("error", "Clip creation returned null");
                            }

                        } catch (Exception e) {
                            log.error(
                                    "Selenium clip creation failed for job {}: {}",
                                    jobId,
                                    e.getMessage(),
                                    e);
                            updateSeleniumJobStatus(
                                    jobId, "FAILED", -1, "Error: " + e.getMessage());
                            jobStatus.put("error", e.getMessage());
                        }
                    });

        } catch (Exception e) {
            log.error("Selenium clip creation failed", e);
            response.put("status", "error");
            response.put("error", "Failed to create clip: " + e.getMessage());
        }
        return response;
    }

    public Map<String, Object> getClipJobStatus(String jobId) {
        Map<String, Object> response = new HashMap<>();
        try {
            // First check our real job tracker
            Map<String, Object> trackedJob = seleniumJobTracker.get(jobId);
            if (trackedJob != null) {
                response.putAll(trackedJob);
                return response;
            }

            // Fall back to mock job status if not found in tracker
            Map<String, Object> status = seleniumService.getJobStatus(jobId);
            response.putAll(status);
        } catch (Exception e) {
            log.error("Failed to get clip job status for jobId: " + jobId, e);
            response.put("status", "error");
            response.put("error", "Failed to get job status: " + e.getMessage());
        }
        return response;
    }

    // Job tracker for Selenium operations
    private final Map<String, Map<String, Object>> seleniumJobTracker = new ConcurrentHashMap<>();

    private void updateSeleniumJobStatus(
            String jobId, String status, int progress, String message) {
        Map<String, Object> jobStatus = seleniumJobTracker.get(jobId);
        if (jobStatus != null) {
            jobStatus.put("status", status);
            jobStatus.put("progress", progress);
            jobStatus.put("message", message);
            jobStatus.put("updatedAt", System.currentTimeMillis());
            log.debug("Updated job {} status: {} ({}%)", jobId, status, progress);
        }
    }

    private YouTubeAccount getTestYouTubeAccount() {
        try {
            // Get YouTube account from the repository
            List<YouTubeAccount> accounts = youTubeAccountRepository.findAll();
            if (!accounts.isEmpty()) {
                // Return the first active account
                YouTubeAccount account =
                        accounts.stream()
                                .filter(acc -> acc.getStatus() == YouTubeAccountStatus.ACTIVE)
                                .findFirst()
                                .orElse(accounts.get(0));

                log.info("Using YouTube account from database: {}", account.getEmail());
                return account;
            }

            // No accounts found in database
            log.error(
                    "No YouTube accounts found in database. Please add an account to the"
                            + " youtube_accounts table.");
            return null;
        } catch (Exception e) {
            log.error("Failed to get YouTube account from database: {}", e.getMessage());
            return null;
        }
    }

    /** Test Selenium connection and WebDriver availability */
    public Map<String, Object> testSeleniumConnection() {
        return testSeleniumConnection(null);
    }

    /** Test Selenium connection with custom URL for noVNC viewing */
    public Map<String, Object> testSeleniumConnection(String testUrl) {
        Map<String, Object> response = new HashMap<>();
        try {
            boolean isConnected =
                    seleniumService.testConnection(
                            testUrl != null ? testUrl : "https://www.youtube.com");
            response.put("status", isConnected ? "success" : "error");
            response.put("connected", isConnected);
            response.put(
                    "message",
                    isConnected
                            ? "Selenium Grid is connected and operational. Watch live at"
                                    + " http://localhost:7900"
                            : "Failed to connect to Selenium Grid");
            response.put("hubUrl", seleniumService.getSeleniumHubUrl());
            response.put("noVncUrl", "http://localhost:7900");
            response.put("testUrl", testUrl != null ? testUrl : "https://www.youtube.com");
            response.put("viewDuration", "60 seconds");
            response.put("timestamp", System.currentTimeMillis());

            // Add browser capabilities info
            Map<String, Object> capabilities = new HashMap<>();
            capabilities.put("browser", "Chrome");
            capabilities.put("headless", false);
            capabilities.put("incognito", true);
            response.put("capabilities", capabilities);

        } catch (Exception e) {
            log.error("Selenium connection test failed", e);
            response.put("status", "error");
            response.put("connected", false);
            response.put("error", "Connection test failed: " + e.getMessage());
        }
        return response;
    }

    /** Get available YouTube accounts for testing */
    public List<Map<String, Object>> getAvailableYouTubeAccounts() {
        List<Map<String, Object>> accounts = new ArrayList<>();
        try {
            List<YouTubeAccount> ytAccounts = youTubeAccountRepository.findAll();
            for (YouTubeAccount account : ytAccounts) {
                Map<String, Object> accountInfo = new HashMap<>();
                accountInfo.put("id", account.getId());
                accountInfo.put("email", account.getEmail());
                // Username field removed - only email and password needed
                accountInfo.put("status", account.getStatus());
                accountInfo.put("active", account.getStatus() == YouTubeAccountStatus.ACTIVE);
                accountInfo.put("totalClipsCreated", account.getTotalClipsCreated());
                accountInfo.put("createdAt", account.getCreatedAt());
                accounts.add(accountInfo);
            }

            // If no accounts, add a mock test account info
            if (accounts.isEmpty()) {
                Map<String, Object> testAccount = new HashMap<>();
                testAccount.put("id", 0L);
                testAccount.put("email", "bastardofedderdstark@gmail.com");
                testAccount.put("username", "testuser");
                testAccount.put("channelId", "test-channel");
                testAccount.put("active", true);
                testAccount.put("creditsAvailable", 100);
                testAccount.put("note", "Test account (not in database)");
                accounts.add(testAccount);
            }

        } catch (Exception e) {
            log.error("Failed to get YouTube accounts", e);
            Map<String, Object> errorAccount = new HashMap<>();
            errorAccount.put("error", "Failed to retrieve accounts: " + e.getMessage());
            accounts.add(errorAccount);
        }
        return accounts;
    }

    /**
     * Test YouTube API statistics retrieval - comprehensive test Tests the new getVideoStatistics
     * method with all fields
     */
    public Map<String, Object> testYouTubeStatistics(String videoUrl) {
        Map<String, Object> response = new HashMap<>();
        try {
            if (videoUrl == null || videoUrl.isEmpty()) {
                response.put("status", "error");
                response.put("error", "Video URL is required");
                return response;
            }

            // Extract video ID
            String videoId = youTubeService.extractVideoId(videoUrl);

            // Test 1: Get view count only (cached, efficient)
            long startTime = System.currentTimeMillis();
            Long viewCount = youTubeService.getViewCount(videoId);
            long viewCountTime = System.currentTimeMillis() - startTime;

            // Test 2: Get complete statistics
            startTime = System.currentTimeMillis();
            YouTubeService.VideoStatistics stats = youTubeService.getVideoStatistics(videoId);
            long statsTime = System.currentTimeMillis() - startTime;

            // Test 3: Get video details
            startTime = System.currentTimeMillis();
            YouTubeService.VideoDetails details = youTubeService.getVideoDetails(videoId);
            long detailsTime = System.currentTimeMillis() - startTime;

            // Build response
            response.put("status", "success");
            response.put("videoId", videoId);

            // Video information
            Map<String, Object> videoInfo = new HashMap<>();
            videoInfo.put("title", details.getTitle());
            videoInfo.put("channelTitle", details.getChannelTitle());
            response.put("videoInfo", videoInfo);

            // Statistics (all fields from YouTube API v3)
            Map<String, Object> statistics = new HashMap<>();
            statistics.put("viewCount", stats.getViewCount());
            statistics.put("likeCount", stats.getLikeCount());
            statistics.put("dislikeCount", stats.getDislikeCount());
            statistics.put("favoriteCount", stats.getFavoriteCount());
            statistics.put("commentCount", stats.getCommentCount());
            response.put("statistics", statistics);

            // Performance metrics
            Map<String, Object> performance = new HashMap<>();
            performance.put("viewCountApiTime", viewCountTime + "ms");
            performance.put("statisticsApiTime", statsTime + "ms");
            performance.put("detailsApiTime", detailsTime + "ms");
            performance.put("totalTime", (viewCountTime + statsTime + detailsTime) + "ms");
            response.put("performance", performance);

            // API quota information
            Map<String, Object> quotaInfo = new HashMap<>();
            quotaInfo.put(
                    "quotaUsagePercentage",
                    String.format("%.2f%%", youTubeService.getQuotaUsagePercentage()));
            quotaInfo.put("quotaCost", "3 units (1 per API call)");
            response.put("quotaInfo", quotaInfo);

            // Cache status
            Map<String, Object> cacheInfo = new HashMap<>();
            cacheInfo.put("viewCountCached", viewCountTime < 50 ? "Yes" : "No");
            cacheInfo.put("cacheTTL", "600 seconds for views, 300 seconds for stats");
            response.put("cacheInfo", cacheInfo);

        } catch (Exception e) {
            log.error("YouTube statistics test failed for URL: " + videoUrl, e);
            response.put("status", "error");
            response.put("error", "Failed to get statistics: " + e.getMessage());
            response.put("errorType", e.getClass().getSimpleName());
        }
        return response;
    }

    /** Get orders requiring attention (errors, long processing times, etc.) */
    public Page<AdminOrderDto> getOrdersRequiringAttention(Pageable pageable) {
        LocalDateTime threshold = LocalDateTime.now().minusHours(24);

        Specification<Order> spec =
                Specification.where(
                        (root, query, cb) ->
                                cb.or(
                                        // Orders stuck in processing for too long
                                        cb.and(
                                                cb.equal(
                                                        root.get("status"), OrderStatus.PROCESSING),
                                                cb.lessThan(root.get("createdAt"), threshold)),
                                        // Orders with error messages
                                        cb.isNotNull(root.get("errorMessage")),
                                        // Orders in holding status
                                        cb.equal(root.get("status"), OrderStatus.HOLDING)));

        Page<Order> orders = orderRepository.findAll(spec, pageable);
        return orders.map(this::mapToAdminOrderDto);
    }

    // Private helper methods

    private Specification<Order> buildOrderSpecification(
            OrderStatus status, Long userId, String search) {
        return (root, query, cb) -> {
            var predicates = new java.util.ArrayList<jakarta.persistence.criteria.Predicate>();

            if (status != null) {
                predicates.add(cb.equal(root.get("status"), status));
            }

            if (userId != null) {
                predicates.add(cb.equal(root.get("user").get("id"), userId));
            }

            if (search != null && !search.trim().isEmpty()) {
                String searchPattern = "%" + search.toLowerCase() + "%";
                predicates.add(
                        cb.or(
                                cb.like(cb.lower(root.get("link")), searchPattern),
                                cb.like(cb.lower(root.get("user").get("username")), searchPattern),
                                cb.like(cb.lower(root.get("service").get("name")), searchPattern)));
            }

            return cb.and(predicates.toArray(new jakarta.persistence.criteria.Predicate[0]));
        };
    }

    private String extractVideoId(String url) {
        if (url == null || url.isEmpty()) {
            return null;
        }

        String videoId = null;

        if (url.contains("youtube.com/watch?v=")) {
            String[] parts = url.split("v=");
            if (parts.length > 1) {
                videoId = parts[1].split("&")[0];
            }
        } else if (url.contains("youtu.be/")) {
            String[] parts = url.split("youtu.be/");
            if (parts.length > 1) {
                videoId = parts[1].split("\\?")[0];
            }
        }

        return videoId;
    }
}
