package com.smmpanel.service;

import com.smmpanel.dto.admin.*;
import com.smmpanel.entity.*;
import com.smmpanel.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class AdminService {

    private final OrderRepository orderRepository;
    private final UserRepository userRepository;
    private final TrafficSourceRepository trafficSourceRepository;
    private final ConversionCoefficientRepository coefficientRepository;
    private final YouTubeAccountRepository youTubeAccountRepository;
    private final OperatorLogRepository operatorLogRepository;
    private final BalanceService balanceService;
    private final SeleniumService seleniumService;
    private final BinomService binomService;
    private final YouTubeService youTubeService;

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
                .activeOrders(orderRepository.findByStatusIn(Arrays.asList(
                        OrderStatus.ACTIVE, OrderStatus.PROCESSING, OrderStatus.IN_PROGRESS)).size())
                .pendingOrders(orderRepository.findByStatus(OrderStatus.PENDING).size())
                .completedOrders(orderRepository.findByStatus(OrderStatus.COMPLETED).size())
                .totalUsers(userRepository.count())
                .activeYouTubeAccounts(youTubeAccountRepository.findByStatus(YouTubeAccountStatus.ACTIVE).size())
                .activeTrafficSources(trafficSourceRepository.findByActiveTrue().size())
                .build();
    }

    public Map<String, Object> getAllOrders(String status, String username, String dateFrom, String dateTo, Pageable pageable) {
        Specification<Order> spec = Specification.where(null);

        if (status != null && !status.isEmpty()) {
            OrderStatus orderStatus = OrderStatus.valueOf(status.toUpperCase());
            spec = spec.and((root, query, cb) -> cb.equal(root.get("status"), orderStatus));
        }

        if (username != null && !username.isEmpty()) {
            spec = spec.and((root, query, cb) -> 
                cb.like(cb.lower(root.get("user").get("username")), "%" + username.toLowerCase() + "%"));
        }

        if (dateFrom != null && !dateFrom.isEmpty()) {
            LocalDateTime from = LocalDate.parse(dateFrom).atStartOfDay();
            spec = spec.and((root, query, cb) -> cb.greaterThanOrEqualTo(root.get("createdAt"), from));
        }

        if (dateTo != null && !dateTo.isEmpty()) {
            LocalDateTime to = LocalDate.parse(dateTo).atTime(23, 59, 59);
            spec = spec.and((root, query, cb) -> cb.lessThanOrEqualTo(root.get("createdAt"), to));
        }

        Page<Order> orders = orderRepository.findAll(spec, pageable);

        List<AdminOrderDto> orderDtos = orders.getContent().stream()
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
                    logOperatorAction(operator, request.getAction(), "ORDER", orderId, 
                            Map.of("reason", request.getReason() != null ? request.getReason() : ""));
                }

                processed++;
            } catch (Exception e) {
                log.error("Failed to perform bulk action {} on order {}: {}", 
                        request.getAction(), orderId, e.getMessage());
            }
        }

        log.info("Performed bulk action {} on {} orders by {}", 
                request.getAction(), processed, operatorUsername);

        return processed;
    }

    @Transactional
    public void cancelOrder(Long orderId, String reason) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new IllegalArgumentException("Order not found: " + orderId));

        // Stop Binom campaigns if any
        List<BinomCampaign> campaigns = binomService.getActiveCampaignsForOrder(orderId);
        for (BinomCampaign campaign : campaigns) {
            binomService.stopCampaign(campaign.getCampaignId());
        }

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
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new IllegalArgumentException("Order not found: " + orderId));

        int oldStartCount = order.getStartCount();
        order.setStartCount(newStartCount);
        
        // Recalculate remains
        int currentViews = getCurrentViewCount(order);
        int viewsGained = currentViews - newStartCount;
        order.setRemains(Math.max(0, order.getQuantity() - viewsGained));
        
        orderRepository.save(order);

        log.info("Updated start count for order {} from {} to {}", orderId, oldStartCount, newStartCount);
    }

    @Transactional
    public void completeOrder(Long orderId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new IllegalArgumentException("Order not found: " + orderId));

        // Stop all campaigns
        List<BinomCampaign> campaigns = binomService.getActiveCampaignsForOrder(orderId);
        for (BinomCampaign campaign : campaigns) {
            binomService.stopCampaign(campaign.getCampaignId());
        }

        order.setStatus(OrderStatus.COMPLETED);
        order.setRemains(0);
        orderRepository.save(order);

        log.info("Manually completed order {}", orderId);
    }

    public List<TrafficSourceDto> getTrafficSources() {
        return trafficSourceRepository.findAll().stream()
                .map(this::mapToTrafficSourceDto)
                .collect(Collectors.toList());
    }

    @Transactional
    public TrafficSourceDto createTrafficSource(TrafficSourceDto request) {
        TrafficSource source = new TrafficSource();
        source.setName(request.getName());
        source.setSourceId(request.getSourceId());
        source.setWeight(request.getWeight());
        source.setDailyLimit(request.getDailyLimit());
        source.setGeoTargeting(request.getGeoTargeting());
        source.setActive(request.getActive());

        source = trafficSourceRepository.save(source);

        log.info("Created traffic source: {}", source.getName());
        return mapToTrafficSourceDto(source);
    }

    @Transactional
    public TrafficSourceDto updateTrafficSource(Long id, TrafficSourceDto request) {
        TrafficSource source = trafficSourceRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Traffic source not found: " + id));

        source.setName(request.getName());
        source.setWeight(request.getWeight());
        source.setDailyLimit(request.getDailyLimit());
        source.setGeoTargeting(request.getGeoTargeting());
        source.setActive(request.getActive());

        source = trafficSourceRepository.save(source);

        log.info("Updated traffic source: {}", source.getName());
        return mapToTrafficSourceDto(source);
    }

    @Transactional
    public void deleteTrafficSource(Long id) {
        TrafficSource source = trafficSourceRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Traffic source not found: " + id));

        trafficSourceRepository.delete(source);
        log.info("Deleted traffic source: {}", source.getName());
    }

    public List<CoefficientDto> getConversionCoefficients() {
        return coefficientRepository.findAll().stream()
                .map(this::mapToCoefficientDto)
                .collect(Collectors.toList());
    }

    @Transactional
    public CoefficientDto updateConversionCoefficient(Long serviceId, CoefficientUpdateRequest request) {
        String operatorUsername = SecurityContextHolder.getContext().getAuthentication().getName();
        User operator = userRepository.findByUsername(operatorUsername).orElse(null);

        ConversionCoefficient coefficient = coefficientRepository.findByServiceId(serviceId)
                .orElse(new ConversionCoefficient());

        coefficient.setServiceId(serviceId);
        coefficient.setWithClip(request.getWithClip());
        coefficient.setWithoutClip(request.getWithoutClip());
        coefficient.setUpdatedBy(operator);

        coefficient = coefficientRepository.save(coefficient);

        log.info("Updated conversion coefficients for service {}: with clip={}, without clip={}", 
                serviceId, request.getWithClip(), request.getWithoutClip());

        return mapToCoefficientDto(coefficient);
    }

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
        YouTubeAccount account = youTubeAccountRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("YouTube account not found: " + id));

        account.setDailyClipsCount(0);
        account.setLastClipDate(LocalDate.now());
        youTubeAccountRepository.save(account);

        log.info("Reset daily limit for YouTube account: {}", account.getUsername());
    }

    @Transactional
    public void updateYouTubeAccountStatus(Long id, String status) {
        YouTubeAccount account = youTubeAccountRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("YouTube account not found: " + id));

        YouTubeAccountStatus newStatus = YouTubeAccountStatus.valueOf(status.toUpperCase());
        account.setStatus(newStatus);
        youTubeAccountRepository.save(account);

        log.info("Updated YouTube account {} status to {}", account.getUsername(), status);
    }

    public Map<String, Object> getSystemHealth() {
        Map<String, Object> health = new HashMap<>();

        // Check Selenium connection
        health.put("selenium", seleniumService.testConnection());

        // Check database
        health.put("database", true); // If we're here, DB is working

        // Count active components
        health.put("activeYouTubeAccounts", youTubeAccountRepository.findByStatus(YouTubeAccountStatus.ACTIVE).size());
        health.put("activeTrafficSources", trafficSourceRepository.findByActiveTrue().size());
        health.put("pendingOrders", orderRepository.findByStatus(OrderStatus.PENDING).size());
        health.put("processingOrders", orderRepository.findByStatus(OrderStatus.PROCESSING).size());

        return health;
    }

    public Map<String, Object> getOperatorLogs(String operatorUsername, String action, 
                                               String dateFrom, String dateTo, Pageable pageable) {
        Specification<OperatorLog> spec = Specification.where(null);

        if (operatorUsername != null && !operatorUsername.isEmpty()) {
            spec = spec.and((root, query, cb) -> 
                cb.like(cb.lower(root.get("operator").get("username")), "%" + operatorUsername.toLowerCase() + "%"));
        }

        if (action != null && !action.isEmpty()) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("action"), action.toUpperCase()));
        }

        if (dateFrom != null && !dateFrom.isEmpty()) {
            LocalDateTime from = LocalDate.parse(dateFrom).atStartOfDay();
            spec = spec.and((root, query, cb) -> cb.greaterThanOrEqualTo(root.get("createdAt"), from));
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

    public Map<String, Object> getUsers(String search, String role, Pageable pageable) {
        Specification<User> spec = Specification.where(null);

        if (search != null && !search.isEmpty()) {
            spec = spec.and((root, query, cb) -> 
                cb.or(
                    cb.like(cb.lower(root.get("username")), "%" + search.toLowerCase() + "%"),
                    cb.like(cb.lower(root.get("email")), "%" + search.toLowerCase() + "%")
                ));
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
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + userId));

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

        log.info("Adjusted balance for user {} by ${} - reason: {}", user.getUsername(), amount, reason);
    }

    @Transactional
    public void updateUserRole(Long userId, String role) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + userId));

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

    private void resumeOrder(Long orderId) {
        // Implementation moved to OrderProcessingService
        throw new UnsupportedOperationException("Use OrderProcessingService.resumeOrder()");
    }

    private BigDecimal calculateRefundAmount(Order order) {
        // Calculate refund based on work completed
        if (order.getStatus() == OrderStatus.PENDING || order.getStatus() == OrderStatus.IN_PROGRESS) {
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
            log.warn("Could not get current view count for order {}: {}", order.getId(), e.getMessage());
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

    private void logOperatorAction(User operator, String action, String targetType, Long targetId, Map<String, Object> details) {
        OperatorLog log = new OperatorLog();
        log.setOperator(operator);
        log.setAction(action.toUpperCase());
        log.setTargetType(targetType);
        log.setTargetId(targetId);
        log.setDetails(details.toString()); // In real implementation, use JSON serialization
        
        operatorLogRepository.save(log);
    }

    private AdminOrderDto mapToAdminOrderDto(Order order) {
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
                .build();
    }

    private TrafficSourceDto mapToTrafficSourceDto(TrafficSource source) {
        return TrafficSourceDto.builder()
                .id(source.getId())
                .name(source.getName())
                .sourceId(source.getSourceId())
                .weight(source.getWeight())
                .dailyLimit(source.getDailyLimit())
                .clicksUsedToday(source.getClicksUsedToday())
                .geoTargeting(source.getGeoTargeting())
                .active(source.getActive())
                .performanceScore(source.getPerformanceScore())
                .build();
    }

    private CoefficientDto mapToCoefficientDto(ConversionCoefficient coefficient) {
        return CoefficientDto.builder()
                .id(coefficient.getId())
                .serviceId(coefficient.getServiceId())
                .withClip(coefficient.getWithClip())
                .withoutClip(coefficient.getWithoutClip())
                .updatedBy(coefficient.getUpdatedBy() != null ? coefficient.getUpdatedBy().getUsername() : null)
                .updatedAt(coefficient.getUpdatedAt())
                .build();
    }
}