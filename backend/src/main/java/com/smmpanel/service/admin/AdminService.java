package com.smmpanel.service.admin;

import com.smmpanel.client.InstagramBotClient;
import com.smmpanel.dto.admin.*;
import com.smmpanel.entity.*;
import com.smmpanel.repository.jpa.*;
import com.smmpanel.service.balance.BalanceService;
import com.smmpanel.service.core.AuditService;
import com.smmpanel.service.notification.DailyProfitService;
import com.smmpanel.service.notification.TelegramNotificationService;
import com.smmpanel.service.order.state.OrderStateManager;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
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
    private final OperatorLogRepository operatorLogRepository;
    private final BalanceService balanceService;
    private final OrderStateManager orderStateManager;
    private final AuditService auditService;
    private final BalanceDepositRepository balanceDepositRepository;
    private final org.springframework.data.redis.core.RedisTemplate<String, Object> redisTemplate;
    private final InstagramBotClient instagramBotClient;
    private final DailyProfitService dailyProfitService;
    private final TelegramNotificationService telegramNotificationService;
    private final com.smmpanel.service.integration.InstagramService instagramService;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    @Transactional(readOnly = true)
    @Cacheable("dashboard-stats")
    public DashboardStats getDashboardStats() {
        LocalDateTime last24Hours = LocalDateTime.now().minusHours(24);
        LocalDateTime last7Days = LocalDateTime.now().minusDays(7);
        LocalDateTime last30Days = LocalDateTime.now().minusDays(30);

        // Dashboard counts and revenue use the *fulfilled* variant — only COMPLETED + PARTIAL —
        // so cancelled / refunded / in-flight orders don't inflate the cards. PARTIAL.charge is
        // already shrunk to the delivered fraction by markPartialCompletion, so SUM is net profit.
        return DashboardStats.builder()
                .totalOrders(orderRepository.count())
                .ordersLast24h(orderRepository.countFulfilledOrdersAfter(last24Hours))
                .ordersLast7Days(orderRepository.countFulfilledOrdersAfter(last7Days))
                .ordersLast30Days(orderRepository.countFulfilledOrdersAfter(last30Days))
                .totalRevenue(
                        orderRepository.sumFulfilledRevenueAfter(
                                LocalDateTime.now().minusYears(1)))
                .revenueLast24h(orderRepository.sumFulfilledRevenueAfter(last24Hours))
                .revenueLast7Days(orderRepository.sumFulfilledRevenueAfter(last7Days))
                .revenueLast30Days(orderRepository.sumFulfilledRevenueAfter(last30Days))
                .activeOrders(
                        (int)
                                orderRepository.countByStatusIn(
                                        Arrays.asList(
                                                OrderStatus.ACTIVE,
                                                OrderStatus.PROCESSING,
                                                OrderStatus.IN_PROGRESS)))
                .pendingOrders((int) orderRepository.countByStatus(OrderStatus.PENDING))
                .completedOrders((int) orderRepository.countByStatus(OrderStatus.COMPLETED))
                .totalUsers(userRepository.count())
                .build();
    }

    /**
     * Daily order/revenue series for the admin dashboard charts. Returns exactly {@code days}
     * entries — one per calendar day from (today - days + 1) through today, inclusive — so the
     * frontend can render a contiguous N-day series. Days with no orders come back as zero rows
     * rather than gaps.
     */
    @Transactional(readOnly = true)
    @Cacheable(value = "daily-stats", key = "#days")
    public List<com.smmpanel.dto.admin.DailyStatPoint> getDailyStats(int days) {
        java.time.LocalDate today = java.time.LocalDate.now();
        java.time.LocalDate startDate = today.minusDays(days - 1L);
        java.time.LocalDateTime startDateTime = startDate.atStartOfDay();

        List<Object[]> rows = orderRepository.getDailyOrderBreakdown(startDateTime);

        java.util.Map<java.time.LocalDate, com.smmpanel.dto.admin.DailyStatPoint> byDay =
                new java.util.HashMap<>();
        for (java.time.LocalDate d = startDate; !d.isAfter(today); d = d.plusDays(1)) {
            byDay.put(
                    d,
                    com.smmpanel.dto.admin.DailyStatPoint.builder()
                            .date(d)
                            .total(0L)
                            .completed(0L)
                            .partial(0L)
                            .cancelled(0L)
                            .revenue(java.math.BigDecimal.ZERO)
                            .build());
        }

        for (Object[] row : rows) {
            // row: [DATE date, OrderStatus status, long count, BigDecimal revenue]
            java.time.LocalDate d = toLocalDate(row[0]);
            if (d == null || d.isBefore(startDate) || d.isAfter(today)) continue;
            com.smmpanel.dto.admin.DailyStatPoint p = byDay.get(d);
            if (p == null) continue;

            OrderStatus status = (OrderStatus) row[1];
            long count = ((Number) row[2]).longValue();
            java.math.BigDecimal revenue =
                    row[3] == null ? java.math.BigDecimal.ZERO : (java.math.BigDecimal) row[3];

            p.setTotal(p.getTotal() + count);
            switch (status) {
                case COMPLETED -> p.setCompleted(p.getCompleted() + count);
                case PARTIAL -> p.setPartial(p.getPartial() + count);
                case CANCELLED, ERROR -> p.setCancelled(p.getCancelled() + count);
                default -> {
                    /* in-progress / pending / etc. counted only in `total` */
                }
            }
            // Revenue: only count completed + partial as realized; matches DailyProfitService.
            if (status == OrderStatus.COMPLETED || status == OrderStatus.PARTIAL) {
                p.setRevenue(p.getRevenue().add(revenue));
            }
        }

        return byDay.values().stream()
                .sorted(
                        java.util.Comparator.comparing(
                                com.smmpanel.dto.admin.DailyStatPoint::getDate))
                .toList();
    }

    /** Same daily breakdown, scoped to a single user. */
    @Transactional(readOnly = true)
    public List<com.smmpanel.dto.admin.DailyStatPoint> getDailyStatsForUser(Long userId, int days) {
        java.time.LocalDate today = java.time.LocalDate.now();
        java.time.LocalDate startDate = today.minusDays(days - 1L);
        java.time.LocalDateTime startDateTime = startDate.atStartOfDay();

        List<Object[]> rows = orderRepository.getDailyOrderBreakdownForUser(userId, startDateTime);

        java.util.Map<java.time.LocalDate, com.smmpanel.dto.admin.DailyStatPoint> byDay =
                new java.util.HashMap<>();
        for (java.time.LocalDate d = startDate; !d.isAfter(today); d = d.plusDays(1)) {
            byDay.put(
                    d,
                    com.smmpanel.dto.admin.DailyStatPoint.builder()
                            .date(d)
                            .total(0L)
                            .completed(0L)
                            .partial(0L)
                            .cancelled(0L)
                            .revenue(java.math.BigDecimal.ZERO)
                            .build());
        }

        for (Object[] row : rows) {
            java.time.LocalDate d = toLocalDate(row[0]);
            if (d == null || d.isBefore(startDate) || d.isAfter(today)) continue;
            com.smmpanel.dto.admin.DailyStatPoint p = byDay.get(d);
            if (p == null) continue;

            OrderStatus status = (OrderStatus) row[1];
            long count = ((Number) row[2]).longValue();
            java.math.BigDecimal revenue =
                    row[3] == null ? java.math.BigDecimal.ZERO : (java.math.BigDecimal) row[3];

            p.setTotal(p.getTotal() + count);
            switch (status) {
                case COMPLETED -> p.setCompleted(p.getCompleted() + count);
                case PARTIAL -> p.setPartial(p.getPartial() + count);
                case CANCELLED, ERROR -> p.setCancelled(p.getCancelled() + count);
                default -> {
                    /* nothing extra */
                }
            }
            // From the user's perspective, "spent" = charge they were billed for completed/partial.
            if (status == OrderStatus.COMPLETED || status == OrderStatus.PARTIAL) {
                p.setRevenue(p.getRevenue().add(revenue));
            }
        }

        return byDay.values().stream()
                .sorted(
                        java.util.Comparator.comparing(
                                com.smmpanel.dto.admin.DailyStatPoint::getDate))
                .toList();
    }

    /**
     * Coerce whatever the DATE() JPQL function gives back (java.sql.Date on Postgres, sometimes
     * java.time.LocalDate via Hibernate dialect) into a LocalDate.
     */
    private static java.time.LocalDate toLocalDate(Object raw) {
        if (raw == null) return null;
        if (raw instanceof java.time.LocalDate ld) return ld;
        if (raw instanceof java.sql.Date sd) return sd.toLocalDate();
        if (raw instanceof java.util.Date ud)
            return ud.toInstant().atZone(java.time.ZoneId.systemDefault()).toLocalDate();
        return java.time.LocalDate.parse(raw.toString());
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getAllOrders(
            String status, String search, String dateFrom, String dateTo, Pageable pageable) {
        return getAllOrders(status, search, null, dateFrom, dateTo, pageable);
    }

    /**
     * Server-side admin order list. Two search levers, applied together:
     *
     * <ul>
     *   <li>{@code search} — the legacy disambiguating field. Numeric → exact id; contains {@code
     *       "/"} or {@code "instagram"} → URL substring; otherwise → username substring.
     *   <li>{@code urlSearch} — explicit URL substring. Use this for short URL fragments like
     *       "/p/ABC123" that the heuristic would otherwise mis-route to the username column. When
     *       supplied, it overrides whatever URL guess {@code search} would have made.
     * </ul>
     *
     * <p>The previous frontend filter was client-side-only on the current page (Task {@code
     * 14d0b976}); useless across the production 6k+ orders. Pushing it to the server lets the
     * {@code orders.link} index do the work and lets the search match across pages.
     */
    public Map<String, Object> getAllOrders(
            String status,
            String search,
            String urlSearch,
            String dateFrom,
            String dateTo,
            Pageable pageable) {

        // Default sort by ID descending if no sort specified
        if (pageable.getSort().isUnsorted()) {
            pageable =
                    PageRequest.of(
                            pageable.getPageNumber(),
                            pageable.getPageSize(),
                            Sort.by(Sort.Direction.DESC, "id"));
        }

        // Accept the status filter case-insensitively and tolerate either underscored
        // ("IN_PROGRESS") or human-spelled ("in progress") input. An unrecognized value
        // resolves to null, which the rest of the method treats as "no filter" rather
        // than blowing up with IllegalArgumentException -> 500.
        OrderStatus orderStatus = null;
        if (status != null && !status.isBlank()) {
            try {
                orderStatus = OrderStatus.valueOf(status.trim().toUpperCase().replace(' ', '_'));
            } catch (IllegalArgumentException ignored) {
                log.warn("Unknown status filter on /v2/admin/orders: '{}'", status);
            }
        }

        // Parse date filters
        LocalDateTime fromDateTime = null;
        LocalDateTime toDateTime = null;
        if (dateFrom != null && !dateFrom.isEmpty()) {
            fromDateTime = LocalDate.parse(dateFrom).atStartOfDay();
        }
        if (dateTo != null && !dateTo.isEmpty()) {
            toDateTime = LocalDate.parse(dateTo).atTime(23, 59, 59);
        }

        // Resolve search parameters
        Long searchId = null;
        String searchUsername = null;
        String searchLink = null;
        if (search != null && !search.isEmpty()) {
            String term = search.trim().toLowerCase();
            try {
                searchId = Long.parseLong(term);
            } catch (NumberFormatException ignored) {
                if (term.contains("/") || term.contains("instagram")) {
                    searchLink = "%" + term + "%";
                } else {
                    searchUsername = "%" + term + "%";
                }
            }
        }
        // Explicit URL search beats the heuristic. Common case: admin pastes "/p/ABC123" —
        // doesn't contain "instagram", but it's clearly a URL fragment.
        if (urlSearch != null && !urlSearch.isBlank()) {
            searchLink = "%" + urlSearch.trim().toLowerCase() + "%";
        }

        // Filter coalescing: PROCESSING is hidden from the UI and rendered as IN_PROGRESS,
        // so a click on the "In progress" chip must surface orders in EITHER status. Without
        // this, the operator would filter to "in progress" and PROCESSING orders silently
        // disappear from the list.
        java.util.Collection<OrderStatus> statuses =
                orderStatus == null ? null : expandInProgressFilter(orderStatus);
        Page<Order> orders =
                orderRepository.adminSearchInStatuses(
                        statuses,
                        fromDateTime,
                        toDateTime,
                        searchId,
                        searchUsername,
                        searchLink,
                        pageable);

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
        List<String> errors = new ArrayList<>();

        for (Long orderId : request.getOrderIds()) {
            try {
                // CRITICAL FIX: Use separate transaction for each action
                // This prevents partial bulk operation inconsistencies
                performSingleBulkAction(orderId, request, operator);
                processed++;
            } catch (Exception e) {
                String errorMsg =
                        String.format(
                                "Order %d: %s",
                                orderId,
                                e.getMessage() != null
                                        ? e.getMessage()
                                        : e.getClass().getSimpleName());
                errors.add(errorMsg);
                log.error(
                        "Failed to perform bulk action {} on order {}: {}",
                        request.getAction(),
                        orderId,
                        e.getMessage(),
                        e);
            }
        }

        log.info(
                "Performed bulk action {} on {} orders by {} (success: {}, failed: {})",
                request.getAction(),
                request.getOrderIds().size(),
                operatorUsername,
                processed,
                errors.size());

        // CRITICAL FIX: If ANY failures occurred, log them for admin visibility
        if (!errors.isEmpty()) {
            log.warn(
                    "Bulk action {} had {} failures: {}",
                    request.getAction(),
                    errors.size(),
                    String.join("; ", errors));
        }

        return processed;
    }

    @Transactional(
            propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    private void performSingleBulkAction(Long orderId, BulkActionRequest request, User operator) {
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
            case "partial":
                if (request.getRemains() != null) {
                    // Admin provided custom remains value
                    markOrderAsPartialWithRemains(
                            orderId, request.getReason(), request.getRemains());
                } else {
                    // Use existing behavior (calculate from current state)
                    markOrderAsPartial(orderId, request.getReason());
                }
                break;
            default:
                throw new IllegalArgumentException("Unknown action: " + request.getAction());
        }

        // Log the action
        if (operator != null) {
            logOperatorAction(
                    operator,
                    request.getAction(),
                    "ORDER",
                    orderId,
                    Map.of("reason", request.getReason() != null ? request.getReason() : ""));
        }
    }

    @Transactional
    public void cancelOrder(Long orderId, String reason) {
        Order order =
                orderRepository
                        .findById(orderId)
                        .orElseThrow(
                                () -> new IllegalArgumentException("Order not found: " + orderId));

        rejectIfAlreadyRefunded(order, "cancel");

        // 1. Tell the bot to release the slot BEFORE we mutate panel state — otherwise it would
        //    keep dispatching while the user already has a refund in their wallet. Best-effort:
        //    DB state below is the source of truth either way.
        stopBotForOrder(order, "cancel");

        // 2. Set remains to full quantity (nothing delivered, full refund expected). This ensures
        //    the v2 API returns correct remains for cancelled orders.
        order.setRemains(order.getQuantity());

        // 3. Calculate full refund (cancellation = full refund).
        BigDecimal refundAmount = calculateRefundAmount(order);

        if (refundAmount.compareTo(BigDecimal.ZERO) > 0) {
            balanceService.refund(order.getUser(), refundAmount, order, reason);
        }

        // 4. Zero the charge after full refund (cancelled = nothing paid).
        order.setCharge(BigDecimal.ZERO);

        order.setStatus(OrderStatus.CANCELLED);
        order.setErrorMessage(reason);
        orderRepository.save(order);

        log.info(
                "Cancelled order {} with refund ${} - reason: {}, remains set to {}",
                orderId,
                refundAmount,
                reason,
                order.getQuantity());
    }

    /**
     * Best-effort signal to the Instagram bot: stop dispatching this order.
     *
     * <p>The bot exposes only {@code /api/orders/cancel} — it does not differentiate "partial" from
     * "cancel" semantically; both mean "release the slot, free profile pool". So we use the same
     * {@link InstagramBotClient#cancelOrderFast} for cancel, mark-partial, and force-complete.
     *
     * <p>Failure is non-fatal: we log and continue. Panel-side state already represents the truth,
     * and the bot will eventually time out / be reaped via heartbeat regardless.
     */
    private void stopBotForOrder(Order order, String panelAction) {
        if (order == null) return;
        String botOrderId = order.getInstagramBotOrderId();
        if (botOrderId == null || botOrderId.isBlank()) return;
        final Long orderId = order.getId();

        // Defer the bot signal until our DB transition commits. Running it inline lets the
        // bot's response webhook race the panel-side save: bot replies "cancelled,
        // completed=N", InstagramResultConsumer commits its own update, our @Version then
        // mismatches → StaleStateException → rollback → panel state lost. This was the prod
        // failure mode admin saw on order #8086. Deferring guarantees the webhook (if any)
        // arrives after we've already committed a terminal status, and the consumer's
        // terminal-state guard skips it.
        com.smmpanel.util.AfterCommitRunner.runAfterCommit(
                () -> {
                    try {
                        boolean ok = instagramBotClient.cancelOrderFast(botOrderId);
                        if (!ok) {
                            log.info(
                                    "{}: bot rejected cancel for order {} (bot_order_id={}) —"
                                            + " likely already finished",
                                    panelAction,
                                    orderId,
                                    botOrderId);
                        }
                    } catch (Exception e) {
                        log.warn(
                                "{}: bot cancel call failed for order {} (bot_order_id={}): {}",
                                panelAction,
                                orderId,
                                botOrderId,
                                e.getMessage());
                    }
                });
    }

    /**
     * Single source of truth for "refund already happened" guard. {@link OrderStatus#PARTIAL} and
     * {@link OrderStatus#CANCELLED} both mean balance was already credited back; running cancel /
     * markPartial again would issue a second refund off the already-reduced charge — i.e. real
     * money loss to the merchant.
     *
     * @throws IllegalStateException if the order is in a terminal refund state.
     */
    private void rejectIfAlreadyRefunded(Order order, String panelAction) {
        OrderStatus s = order.getStatus();
        if (s == OrderStatus.PARTIAL || s == OrderStatus.CANCELLED) {
            throw new IllegalStateException(
                    panelAction
                            + ": order "
                            + order.getId()
                            + " already in terminal refund state ("
                            + s
                            + "). Refunding again would double-charge the merchant.");
        }
    }

    @Transactional
    public void deleteOrder(Long orderId) {
        Order order =
                orderRepository
                        .findById(orderId)
                        .orElseThrow(
                                () -> new IllegalArgumentException("Order not found: " + orderId));

        log.warn(
                "HARD DELETE initiated for order {}: status={}, user={}",
                orderId,
                order.getStatus(),
                order.getUser().getUsername());

        // 1. Clear Redis cache keys related to this order
        try {
            // Clear clip URL cache (by order ID)
            String orderClipKey = "order:clip:" + orderId;
            redisTemplate.delete(orderClipKey);

            // Clear secondStartCount cache
            String secondCountKey = "order:secondStartCount:" + orderId;
            redisTemplate.delete(secondCountKey);

            // Clear any clip URL cache (by video URL if exists)
            if (order.getLink() != null) {
                String clipUrlCacheKey = "clip:url:" + order.getLink();
                redisTemplate.delete(clipUrlCacheKey);
            }

            log.info("Cleared Redis cache keys for order {}", orderId);
        } catch (Exception e) {
            log.warn("Failed to clear Redis keys for order {}: {}", orderId, e.getMessage());
        }

        // 2. Hard delete the order from database
        orderRepository.delete(order);

        log.warn("HARD DELETE completed for order {}", orderId);
    }

    @Transactional
    public void updateStartCount(Long orderId, Integer newStartCount) {
        // CRITICAL FIX: Validate newStartCount before applying
        if (newStartCount == null) {
            throw new IllegalArgumentException("New start count cannot be null");
        }
        if (newStartCount < 0) {
            throw new IllegalArgumentException(
                    "New start count cannot be negative. Provided: " + newStartCount);
        }

        Order order =
                orderRepository
                        .findById(orderId)
                        .orElseThrow(
                                () -> new IllegalArgumentException("Order not found: " + orderId));

        // CRITICAL FIX: Handle null oldStartCount safely
        Integer oldStartCount = order.getStartCount();
        if (oldStartCount == null) {
            oldStartCount = 0;
            log.warn("Order {} had null startCount, treating as 0", orderId);
        }

        order.setStartCount(newStartCount);
        // Remains is maintained by the Instagram bot via webhook callbacks; we no longer
        // recompute it here from a live view count (YouTube path removed).
        orderRepository.save(order);

        log.info(
                "Updated start count for order {} from {} to {}",
                orderId,
                oldStartCount,
                newStartCount);
    }

    @Transactional
    public void completeOrder(Long orderId) {
        // Legacy thin form kept for callers (e.g. bulk action) that just want the DB flip.
        Order order =
                orderRepository
                        .findById(orderId)
                        .orElseThrow(
                                () -> new IllegalArgumentException("Order not found: " + orderId));

        order.setStatus(OrderStatus.COMPLETED);
        order.setRemains(0);
        orderRepository.save(order);

        log.info("Manually completed order {}", orderId);
    }

    /**
     * Admin force-complete: marks an order as fully delivered <strong>regardless of current
     * state</strong>, signals the bot to stop work, records daily profit, and sends a Telegram
     * notification.
     *
     * <p>Use cases:
     *
     * <ul>
     *   <li>Bot got stuck mid-run but the action actually succeeded on Instagram side
     *   <li>External delivery confirmation arrived through ops channels
     *   <li>Operator wants to close out an order that's been sitting in PENDING / IN_PROGRESS
     * </ul>
     *
     * <p>Idempotent: calling on an already-COMPLETED order is a no-op (still triggers bot stop in
     * case the previous transition was incomplete).
     */
    @Transactional
    public void forceCompleteOrder(Long orderId, String reason, User operator) {
        Order order =
                orderRepository
                        .findById(orderId)
                        .orElseThrow(
                                () -> new IllegalArgumentException("Order not found: " + orderId));

        OrderStatus previousStatus = order.getStatus();
        boolean alreadyCompleted = previousStatus == OrderStatus.COMPLETED;

        // Refusing to "force-complete" an order that's already been refunded prevents the panel
        // from booking phantom profit on a zero-charge order and from sending a Telegram
        // "completed" notification for something the customer already got their money back on.
        if (previousStatus == OrderStatus.CANCELLED || previousStatus == OrderStatus.PARTIAL) {
            throw new IllegalStateException(
                    "Cannot force-complete an order in terminal refund state: " + previousStatus);
        }

        // 1. Tell the bot to release the slot. Best-effort — DB state below is the source of truth.
        stopBotForOrder(order, "force_complete");

        // 2. Flip panel-side state. Mark fully delivered (remains=0 so qty-remains=qty), clear
        // any error. Order does not carry a separate "completed" column; delivered count is
        // derived as quantity - remains by API mappers.
        Integer qty = order.getQuantity();
        int completedCount = qty == null ? 0 : qty;
        order.setStatus(OrderStatus.COMPLETED);
        order.setRemains(0);
        order.setErrorMessage(null);
        // Distinguish admin-driven completion from organic completion in audits.
        if (order.getTrafficStatus() == null
                || !order.getTrafficStatus().startsWith("FORCE_COMPLETED")) {
            order.setTrafficStatus("FORCE_COMPLETED_BY_ADMIN");
        }
        orderRepository.save(order);

        // 3. Record profit + Telegram only on the first transition into COMPLETED. Multiple
        // force-completes shouldn't double-count daily profit. Both side-effects are deferred
        // until AFTER COMMIT — Redis HINCRBY is non-transactional, so an inline call followed
        // by rollback would leak phantom profit into the daily counter (this was the source of
        // the May 2 over-count: $244.51 in Redis vs $154.21 in DB).
        if (!alreadyCompleted) {
            final BigDecimal profitAmount = order.getCharge();
            com.smmpanel.util.AfterCommitRunner.runAfterCommit(
                    () -> {
                        try {
                            dailyProfitService.recordProfit(profitAmount, OrderStatus.COMPLETED);
                        } catch (Exception e) {
                            log.warn(
                                    "force_complete: profit recording failed for order {}: {}",
                                    orderId,
                                    e.getMessage());
                        }
                    });
            try {
                telegramNotificationService.notifyOrderCompleted(order, completedCount);
            } catch (Exception e) {
                log.warn(
                        "force_complete: telegram notification failed for order {}: {}",
                        orderId,
                        e.getMessage());
            }
        }

        // 4. Operator audit log — high-impact action, always recorded.
        if (operator != null) {
            Map<String, Object> meta = new java.util.HashMap<>();
            meta.put("previousStatus", previousStatus == null ? "null" : previousStatus.name());
            meta.put("reason", reason == null ? "" : reason);
            meta.put("alreadyCompleted", alreadyCompleted);
            logOperatorAction(operator, "force_complete", "ORDER", orderId, meta);
        }

        log.info(
                "Admin force_complete order={} previousStatus={} alreadyCompleted={} reason={}",
                orderId,
                previousStatus,
                alreadyCompleted,
                reason);
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

    /**
     * Two callers, both wired through the frontend's "Retry" button (action=start /
     * action=resume on POST /v2/admin/orders/{id}/actions):
     *
     * <ul>
     *   <li><b>PAUSED</b> — bot was paused (admin or circuit-breaker); flip back to ACTIVE
     *       so the existing dispatch resumes from where it stopped.
     *   <li><b>PENDING</b> — order was created but never picked up by the bot fleet (or
     *       the dispatch silently failed). Re-dispatch via {@link InstagramService}; if a
     *       stale {@code instagramBotOrderId} exists, cancel it on the bot side first to
     *       avoid double-execution.
     * </ul>
     *
     * Any other status throws — admin should pick the right action (cancel / mark-partial
     * / force-complete) for terminal or in-progress orders.
     */
    @Transactional
    public void resumeOrder(Long orderId) {
        Order order =
                orderRepository
                        .findById(orderId)
                        .orElseThrow(
                                () -> new IllegalArgumentException("Order not found: " + orderId));

        OrderStatus status = order.getStatus();

        if (status == OrderStatus.PAUSED) {
            order.setStatus(OrderStatus.ACTIVE);
            orderRepository.save(order);
            log.info("Resumed PAUSED order {}", orderId);
            return;
        }

        if (status == OrderStatus.PENDING) {
            // Stale botOrderId: order was once dispatched but never made progress.
            // Cancel the bot-side record (best-effort, ignore failures) before
            // re-dispatching so we don't duplicate work on the bot fleet.
            String existingBotId = order.getInstagramBotOrderId();
            if (existingBotId != null && !existingBotId.isBlank()) {
                try {
                    instagramBotClient.cancelOrderFast(existingBotId);
                } catch (Exception e) {
                    log.warn(
                            "Stale bot-cancel for order {} failed (continuing with"
                                    + " re-dispatch): {}",
                            orderId,
                            e.getMessage());
                }
                order.setInstagramBotOrderId(null);
            }

            com.smmpanel.dto.instagram.InstagramOrderResponse resp =
                    instagramService.createInstagramOrder(order);
            if (resp == null || !resp.isSuccess()) {
                String msg =
                        resp == null ? "no response from Instagram service" : resp.getError();
                throw new IllegalStateException(
                        "Re-dispatch to bot fleet failed: " + msg);
            }

            log.info(
                    "Re-dispatched PENDING order {} to bot fleet (botOrderId={})",
                    orderId,
                    resp.getId());
            return;
        }

        throw new IllegalStateException(
                "Order must be PAUSED or PENDING to resume / retry. Current status: " + status);
    }

    /**
     * Manually mark order as PARTIAL Works on order statuses that haven't already been refunded:
     * COMPLETED, PENDING, IN_PROGRESS, ACTIVE, PROCESSING, PAUSED. Automatically calculates and
     * refunds proportional amount based on delivered views. Stops order processing and deletes
     * Binom offer if exists.
     *
     * <p>Idempotency guard: rejects orders that are already in a terminal refund state (PARTIAL or
     * CANCELLED). Without this guard, calling twice would refund twice — the refund repository has
     * no per-order de-duplication, and the second call would compute a refund off the
     * already-reduced charge.
     */
    @Transactional
    public void markOrderAsPartial(Long orderId, String reason) {
        Order order =
                orderRepository
                        .findById(orderId)
                        .orElseThrow(
                                () -> new IllegalArgumentException("Order not found: " + orderId));

        rejectIfAlreadyRefunded(order, "mark_partial");

        // For the bot, partial == cancel — both mean "stop dispatching, release the slot".
        // Send the cancel signal first so we don't keep delivering while the user has a refund.
        stopBotForOrder(order, "mark_partial");

        // Get current status for logging
        OrderStatus oldStatus = order.getStatus();

        // Calculate refund amount based on what was delivered
        BigDecimal refundAmount = calculatePartialRefund(order);

        log.info(
                "Marking order {} as PARTIAL. Old status: {}, Refund amount: ${}",
                orderId,
                oldStatus,
                refundAmount);

        // Issue refund to user if any work was not completed
        if (refundAmount.compareTo(BigDecimal.ZERO) > 0) {
            try {
                balanceService.refund(
                        order.getUser(),
                        refundAmount,
                        order,
                        "Partial refund - " + (reason != null ? reason : "Manual admin action"));
                log.info(
                        "Refunded ${} to user {} for partial order {}",
                        refundAmount,
                        order.getUser().getUsername(),
                        orderId);

                // CRITICAL: Update charge to reflect only the delivered portion
                // charge = originalCharge - refundAmount (user only pays for what was delivered)
                order.setCharge(order.getCharge().subtract(refundAmount));
            } catch (Exception e) {
                log.error("Failed to refund partial order {}: {}", orderId, e.getMessage());
                throw new RuntimeException("Refund failed: " + e.getMessage());
            }
        }

        // Mark order as PARTIAL
        order.setStatus(OrderStatus.PARTIAL);

        // Set error message with reason
        String errorMsg = "Manually marked as PARTIAL by admin";
        if (reason != null && !reason.trim().isEmpty()) {
            errorMsg += ": " + reason;
        }
        order.setErrorMessage(errorMsg);

        orderRepository.save(order);

        log.info("Successfully marked order {} as PARTIAL (was {})", orderId, oldStatus);
    }

    /**
     * Mark order as PARTIAL with admin-specified remains value. Automatically calculates and
     * processes refund based on the provided remains value. This method allows admins to manually
     * specify exactly how much of the order was not delivered.
     *
     * @param orderId The order ID to mark as partial
     * @param reason Optional reason for the partial status
     * @param customRemains Admin-specified remaining quantity (must be 0 <= remains <= quantity)
     */
    @Transactional
    public void markOrderAsPartialWithRemains(Long orderId, String reason, Integer customRemains) {
        Order order =
                orderRepository
                        .findById(orderId)
                        .orElseThrow(
                                () -> new IllegalArgumentException("Order not found: " + orderId));

        rejectIfAlreadyRefunded(order, "mark_partial_with_remains");

        // For the bot, partial == cancel — stop dispatching now so we don't deliver more after
        // the operator-set remains value is locked in.
        stopBotForOrder(order, "mark_partial_with_remains");

        // Validate remains value
        if (customRemains == null) {
            throw new IllegalArgumentException("Remains value is required for partial action");
        }
        if (customRemains < 0) {
            throw new IllegalArgumentException(
                    "Remains cannot be negative. Provided: " + customRemains);
        }
        if (customRemains > order.getQuantity()) {
            throw new IllegalArgumentException(
                    String.format(
                            "Remains (%d) cannot exceed quantity (%d)",
                            customRemains, order.getQuantity()));
        }

        OrderStatus oldStatus = order.getStatus();

        // Set the admin-provided remains value BEFORE calculating refund
        order.setRemains(customRemains);

        // Calculate refund based on the new remains value
        BigDecimal refundAmount = calculatePartialRefund(order);

        log.info(
                "Marking order {} as PARTIAL. Old status: {}, Admin-set remains: {}, Refund: ${}",
                orderId,
                oldStatus,
                customRemains,
                refundAmount);

        // Issue refund if any work was not completed
        if (refundAmount.compareTo(BigDecimal.ZERO) > 0) {
            try {
                balanceService.refund(
                        order.getUser(),
                        refundAmount,
                        order,
                        "Partial refund - " + (reason != null ? reason : "Manual admin action"));
                log.info(
                        "Refunded ${} to user {} for partial order {}",
                        refundAmount,
                        order.getUser().getUsername(),
                        orderId);

                // CRITICAL: Update charge to reflect only the delivered portion
                // charge = originalCharge - refundAmount (user only pays for what was delivered)
                order.setCharge(order.getCharge().subtract(refundAmount));
            } catch (Exception e) {
                log.error("Failed to refund partial order {}: {}", orderId, e.getMessage());
                throw new RuntimeException("Refund failed: " + e.getMessage());
            }
        }

        // Mark order as PARTIAL
        order.setStatus(OrderStatus.PARTIAL);

        // Set error message with reason
        String errorMsg = "Manually marked as PARTIAL by admin";
        if (reason != null && !reason.trim().isEmpty()) {
            errorMsg += ": " + reason;
        }
        errorMsg += " (remains: " + customRemains + ")";
        order.setErrorMessage(errorMsg);

        orderRepository.save(order);

        log.info(
                "Successfully marked order {} as PARTIAL (was {}), remains={}",
                orderId,
                oldStatus,
                customRemains);
    }

    /**
     * Calculate refund amount for partial order based on remains field. Uses the 'remains' field
     * which is set by the bot or can be manually adjusted in the database.
     */
    private BigDecimal calculatePartialRefund(Order order) {
        // Full refund for pending or in-progress orders (nothing delivered yet)
        if (order.getStatus() == OrderStatus.PENDING
                || order.getStatus() == OrderStatus.IN_PROGRESS) {
            return order.getCharge();
        }

        // Use the 'remains' field for calculation
        Integer remains = order.getRemains();
        Integer quantity = order.getQuantity();

        // If still no valid remains, default to full refund
        if (remains == null) {
            log.warn("Order {} has no remains set, defaulting to full refund", order.getId());
            return order.getCharge();
        }

        if (remains <= 0) {
            // All items delivered - no refund
            log.info("Order {} fully delivered (remains=0), no refund needed", order.getId());
            return BigDecimal.ZERO;
        }

        if (remains >= quantity) {
            // Nothing delivered - full refund
            log.info(
                    "Order {} nothing delivered (remains={}), full refund", order.getId(), remains);
            return order.getCharge();
        }

        // Partial refund based on remains: refund = charge * (remains / quantity)
        BigDecimal refund =
                order.getCharge()
                        .multiply(BigDecimal.valueOf(remains))
                        .divide(BigDecimal.valueOf(quantity), 4, java.math.RoundingMode.HALF_UP);

        int delivered = quantity - remains;
        double deliveredPercentage = (double) delivered / quantity * 100;

        log.info(
                "Order {}: {}/{} delivered ({:.1f}%), remains={}, refund=${}",
                order.getId(), delivered, quantity, deliveredPercentage, remains, refund);

        return refund;
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
    @Cacheable(value = "coefficients", key = "'all'")
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
        List<User> content = users.getContent();

        // Batch the per-user order count so the listing is a single extra query, not N+1.
        java.util.Map<Long, Long> ordersCountByUser;
        if (content.isEmpty()) {
            ordersCountByUser = java.util.Collections.emptyMap();
        } else {
            List<Long> ids = content.stream().map(User::getId).toList();
            ordersCountByUser =
                    orderRepository.countOrdersByUserIds(ids).stream()
                            .collect(
                                    java.util.stream.Collectors.toMap(
                                            row -> ((Number) row[0]).longValue(),
                                            row -> ((Number) row[1]).longValue()));
        }

        List<com.smmpanel.dto.admin.UserAdminDto> dtos =
                content.stream()
                        .map(
                                u ->
                                        com.smmpanel.dto.admin.UserAdminDto.builder()
                                                .id(u.getId())
                                                .username(u.getUsername())
                                                .email(u.getEmail())
                                                .balance(u.getBalance())
                                                .totalSpent(u.getTotalSpent())
                                                .role(
                                                        u.getRole() == null
                                                                ? null
                                                                : u.getRole().name())
                                                // "active" matches the entity's @Column boolean.
                                                // Anything else (locked, deactivated by admin)
                                                // shows as "suspended".
                                                .status(u.isActive() ? "active" : "suspended")
                                                .emailVerified(u.isEmailVerified())
                                                .twoFactorEnabled(u.isTwoFactorEnabled())
                                                .apiKeyConfigured(u.getApiKeyHash() != null)
                                                .ordersCount(
                                                        ordersCountByUser.getOrDefault(
                                                                u.getId(), 0L))
                                                .createdAt(u.getCreatedAt())
                                                .lastLoginAt(u.getLastLoginAt())
                                                .build())
                        .toList();

        Map<String, Object> response = new HashMap<>();
        response.put("users", dtos);
        response.put("totalPages", users.getTotalPages());
        response.put("totalElements", users.getTotalElements());
        response.put("currentPage", users.getNumber());
        response.put("pageSize", users.getSize());

        return response;
    }

    @Transactional
    public void adjustUserBalance(Long userId, BigDecimal amount, String reason) {
        if (amount == null) {
            throw new IllegalArgumentException("Adjustment amount is required");
        }
        if (amount.compareTo(BigDecimal.ZERO) == 0) {
            throw new IllegalArgumentException("Adjustment amount cannot be zero");
        }

        User user =
                userRepository
                        .findById(userId)
                        .orElseThrow(
                                () -> new IllegalArgumentException("User not found: " + userId));

        BigDecimal adjustmentAmount = amount.setScale(2, java.math.RoundingMode.HALF_UP);

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
                "Adjusted balance for user {} by {} - reason: {}",
                user.getUsername(),
                adjustmentAmount,
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

        Integer remains = order.getRemains();
        Integer quantity = order.getQuantity();

        // No remains tracked yet — assume nothing delivered, full refund.
        if (remains == null || quantity == null || quantity <= 0) {
            return order.getCharge();
        }

        if (remains <= 0) {
            return BigDecimal.ZERO; // Fully delivered, no refund
        }

        if (remains >= quantity) {
            return order.getCharge(); // Nothing delivered, full refund
        }

        // Partial refund proportional to undelivered remains.
        return order.getCharge()
                .multiply(BigDecimal.valueOf(remains))
                .divide(BigDecimal.valueOf(quantity), 4, java.math.RoundingMode.HALF_UP);
    }

    private void logOperatorAction(
            User operator,
            String action,
            String targetType,
            Long targetId,
            Map<String, Object> details) {
        OperatorLog entry = new OperatorLog();
        entry.setOperator(operator);
        entry.setAction(action.toUpperCase());
        entry.setTargetType(targetType);
        entry.setTargetId(targetId);

        // operator_logs.details is Postgres JSONB — Map.toString() produces "{k=v}" which is
        // NOT valid JSON, so the INSERT failed with "invalid input syntax for type json".
        // Serialize through Jackson so the column gets a real JSON document.
        String json;
        try {
            json = objectMapper.writeValueAsString(details == null ? Map.of() : details);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            log.warn(
                    "Failed to serialize operator-log details for action {} on {}#{}: {}",
                    action,
                    targetType,
                    targetId,
                    e.getMessage());
            json = "{}";
        }
        entry.setDetails(json);

        operatorLogRepository.save(entry);
    }

    /**
     * UI-coalescing: the panel hides {@link OrderStatus#PROCESSING} and renders it as {@code
     * IN_PROGRESS}. Status filters must follow suit — clicking either label returns orders in
     * either real DB status. Other statuses pass through as-is.
     */
    private static java.util.Collection<OrderStatus> expandInProgressFilter(OrderStatus s) {
        if (s == OrderStatus.IN_PROGRESS || s == OrderStatus.PROCESSING) {
            return java.util.List.of(OrderStatus.IN_PROGRESS, OrderStatus.PROCESSING);
        }
        return java.util.List.of(s);
    }

    private AdminOrderDto mapToAdminOrderDto(Order order) {
        String orderName = order.getLink() != null ? order.getLink() : "N/A";

        return AdminOrderDto.builder()
                .id(order.getId())
                .username(order.getUser().getUsername())
                .serviceId(order.getService().getId())
                .serviceName(order.getService().getName())
                .link(order.getLink())
                .quantity(order.getQuantity())
                .charge(calculateEffectiveCharge(order))
                .startCount(order.getStartCount())
                .remains(order.getRemains())
                .status(order.getStatus().name())
                .createdAt(order.getCreatedAt())
                .updatedAt(order.getUpdatedAt())
                .orderName(orderName)
                .isRefill(Boolean.TRUE.equals(order.getIsRefill()))
                .refillParentId(order.getRefillParentId())
                .build();
    }

    private BigDecimal calculateEffectiveCharge(Order order) {
        if (order.getCharge() == null) {
            return BigDecimal.ZERO;
        }
        if (order.getStatus() == OrderStatus.CANCELLED) {
            return BigDecimal.ZERO;
        }
        // For PARTIAL orders, charge is already proportional (set by markPartialCompletion)
        return order.getCharge();
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

    /**
     * Get all deposits/payments for admin
     *
     * @param status Filter by payment status (optional)
     * @param username Filter by username (optional)
     * @param dateFrom Filter by start date (optional)
     * @param dateTo Filter by end date (optional)
     * @param pageable Pagination parameters
     * @return Map containing deposit list and pagination info
     */
    @Transactional(readOnly = true)
    public Map<String, Object> getAllDeposits(
            String status, String username, String dateFrom, String dateTo, Pageable pageable) {

        Page<BalanceDeposit> deposits =
                balanceDepositRepository.findAllByOrderByCreatedAtDesc(pageable);

        // Convert deposits to DTO format with user information
        List<Map<String, Object>> depositList =
                deposits.getContent().stream()
                        .map(
                                deposit -> {
                                    Map<String, Object> depositMap = new HashMap<>();
                                    depositMap.put("id", deposit.getId());
                                    depositMap.put("orderId", deposit.getOrderId());
                                    depositMap.put(
                                            "username",
                                            deposit.getUser() != null
                                                    ? deposit.getUser().getUsername()
                                                    : "Unknown");
                                    depositMap.put(
                                            "userId",
                                            deposit.getUser() != null
                                                    ? deposit.getUser().getId()
                                                    : null);
                                    depositMap.put("amountUsdt", deposit.getAmountUsdt());
                                    depositMap.put("cryptoAmount", deposit.getCryptoAmount());
                                    depositMap.put("status", deposit.getStatus().name());
                                    depositMap.put("paymentUrl", deposit.getPaymentUrl());
                                    depositMap.put(
                                            "cryptomusPaymentId", deposit.getCryptomusPaymentId());
                                    depositMap.put("createdAt", deposit.getCreatedAt());
                                    depositMap.put("confirmedAt", deposit.getConfirmedAt());
                                    depositMap.put("expiresAt", deposit.getExpiresAt());
                                    return depositMap;
                                })
                        .collect(Collectors.toList());

        Map<String, Object> response = new HashMap<>();
        response.put("deposits", depositList);
        response.put("currentPage", deposits.getNumber());
        response.put("totalPages", deposits.getTotalPages());
        response.put("totalElements", deposits.getTotalElements());
        response.put("pageSize", deposits.getSize());

        return response;
    }
}
