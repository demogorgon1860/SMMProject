package com.smmpanel.service.stats;

import com.smmpanel.entity.Order;
import com.smmpanel.entity.OrderStatus;
import com.smmpanel.repository.jpa.OrderRepository;
import com.smmpanel.repository.jpa.ServiceRepository;
import com.smmpanel.repository.jpa.UserRepository;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Aggregates landing-page metrics. Lives in its own bean (NOT inside the controller) so {@link
 * Cacheable} actually works — Spring's caching is implemented via an AOP proxy, and a controller
 * that calls its own {@code @Cacheable} method through {@code this} bypasses the proxy entirely
 * (the call never goes through the proxy boundary, so the annotation is silently ignored).
 *
 * <p>Cache name {@code public-stats} is configured with a 60s TTL in {@link
 * com.smmpanel.config.RedisConfig}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PublicStatsService {

    private final OrderRepository orderRepository;
    private final UserRepository userRepository;
    private final ServiceRepository serviceRepository;

    @Cacheable("public-stats")
    @Transactional(readOnly = true)
    public Map<String, Object> compute() {
        // Only count delivered work. "Fulfilled" should not include cancelled or failed orders —
        // that would be a marketing lie that any auditor would catch in five minutes.
        long fulfilled =
                orderRepository.countByStatus(OrderStatus.COMPLETED)
                        + orderRepository.countByStatus(OrderStatus.PARTIAL);
        long fulfilledLast24h =
                Math.max(
                        0,
                        orderRepository.countOrdersCreatedAfter(
                                LocalDateTime.now().minusHours(24)));
        long usersTotal = userRepository.count();

        // Customer-facing service stats. The DB has paired entries with identical names but
        // different price tiers (the "fast" cheap variant + the "real-quality" premium one);
        // we surface only the premium one to the public catalog (frontend dedupes the same way
        // in pages/public/ServicesList.tsx#dedupeInstagram). Filtering to the same set here keeps
        // the landing-page "X services · min $Y/1k" line honest:
        //   - count  = number of UNIQUE service names in the customer-visible catalog
        //   - min    = cheapest price among those (the kept-premium entries)
        // Limited to Instagram because that's the only live platform today.
        var allActive = serviceRepository.findByActiveOrderByIdAsc(Boolean.TRUE);
        java.util.Map<String, java.math.BigDecimal> premiumPriceByName = new java.util.HashMap<>();
        for (var s : allActive) {
            String cat = s.getCategory() == null ? "" : s.getCategory().toUpperCase();
            if (!cat.contains("INSTAGRAM")) continue;
            String name = s.getName() == null ? "" : s.getName().trim();
            if (name.isEmpty()) continue;
            java.math.BigDecimal price = s.getPricePer1000();
            if (price == null) continue;
            // Keep the HIGHER price for each duplicate-named pair (the premium / non-fast variant).
            premiumPriceByName.merge(name, price, java.math.BigDecimal::max);
        }
        int serviceCount = premiumPriceByName.size();
        java.math.BigDecimal minPrice =
                premiumPriceByName.values().stream()
                        .min(java.math.BigDecimal::compareTo)
                        .orElse(null);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ordersFulfilled", fulfilled);
        body.put("ordersLast24h", fulfilledLast24h);
        body.put("serviceCount", serviceCount);
        body.put("usersTotal", usersTotal);
        // Cheapest active service price per 1k units, serialized as a string to preserve
        // precision through JSON (matches the DailyProfit pattern). Null if no services are
        // active — the frontend hides the "min $X/1k" line in that case rather than showing $0.
        body.put("minPricePer1k", minPrice == null ? null : minPrice.toPlainString());
        return body;
    }

    /**
     * Statuses worth surfacing on the landing-page ticker (anything in motion or finished cleanly).
     */
    private static final List<OrderStatus> WATCHABLE_STATUSES =
            List.of(
                    OrderStatus.COMPLETED,
                    OrderStatus.PARTIAL,
                    OrderStatus.IN_PROGRESS,
                    OrderStatus.PROCESSING,
                    OrderStatus.ACTIVE);

    /**
     * Recent orders for the public landing ticker. Each row contains only:
     *
     * <ul>
     *   <li>{@code id} — the panel order ID (sequential int, low info-leak risk)
     *   <li>{@code quantity} — how many were/are being delivered
     *   <li>{@code service} — short service name (e.g. "Likes", "Followers", "Comments"); the full
     *       DB name is sanitized — no audience tags, no geos, no internal labels
     *   <li>{@code status} — lowercase enum name (completed / partial / in_progress / …)
     *   <li>{@code ageSeconds} — seconds since creation, for client-side "12s ago" rendering
     * </ul>
     *
     * <p>NEVER includes: username, target URL, charge, IP, anything user-identifying. Cached for
     * 10s in Redis so the landing handles spike traffic without hammering the orders table.
     */
    @Cacheable("public-recent-orders")
    @Transactional(readOnly = true)
    public List<Map<String, Object>> recentOrders() {
        int limit = 12;
        List<Order> rows =
                orderRepository.findRecentInStatusesWithService(
                        WATCHABLE_STATUSES, PageRequest.of(0, limit));
        LocalDateTime now = LocalDateTime.now();
        List<Map<String, Object>> out = new java.util.ArrayList<>(rows.size());
        for (Order o : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", o.getId());
            m.put("quantity", o.getQuantity() == null ? 0 : o.getQuantity());
            m.put(
                    "service",
                    shortServiceName(o.getService() == null ? null : o.getService().getName()));
            m.put("status", o.getStatus() == null ? "unknown" : o.getStatus().name().toLowerCase());
            long ageSec =
                    o.getCreatedAt() == null
                            ? 0
                            : Math.max(0, Duration.between(o.getCreatedAt(), now).getSeconds());
            m.put("ageSeconds", ageSec);
            out.add(m);
        }
        return out;
    }

    /**
     * "Instagram Likes [Mix Gender] [USA/Europe]" -> "Likes". Strips the platform prefix and any
     * bracketed audience/geo qualifiers; keeps only the human-readable action word(s). Falls back
     * to "Service" for null/empty input.
     */
    private static String shortServiceName(String fullName) {
        if (fullName == null || fullName.isBlank()) return "Service";
        String n = fullName.replaceAll("(?i)^Instagram\\s+", "").trim();
        int bracket = n.indexOf('[');
        if (bracket > 0) n = n.substring(0, bracket).trim();
        return n.isEmpty() ? "Service" : n;
    }
}
