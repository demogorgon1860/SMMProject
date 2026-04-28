package com.smmpanel.service.profile;

import com.smmpanel.dto.profile.LifetimeStatsDto;
import com.smmpanel.entity.OrderStatus;
import com.smmpanel.entity.User;
import com.smmpanel.exception.UserNotFoundException;
import com.smmpanel.repository.jpa.OrderRepository;
import com.smmpanel.repository.jpa.RefillRequestRepository;
import com.smmpanel.repository.jpa.SupportTicketRepository;
import com.smmpanel.repository.jpa.UserRepository;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Backs {@code GET /v1/me/stats/lifetime}. Lives in its own bean (rather than a method on {@link
 * ProfileService}) so {@link Cacheable} actually fires — Spring's caching is implemented via an AOP
 * proxy and a self-call (this.compute()) bypasses it.
 *
 * <p>Cache key is the username and the entry TTL is 60s; we deliberately do NOT @CacheEvict on
 * order create/complete/refund. The Profile tile is a low-stakes view — eventual consistency at 60s
 * granularity is invisible to the user and saves coupling all order paths to a profile cache.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LifetimeStatsService {

    private static final List<OrderStatus> REALIZED_STATUSES =
            List.of(OrderStatus.COMPLETED, OrderStatus.PARTIAL);

    private final UserRepository userRepository;
    private final OrderRepository orderRepository;
    private final SupportTicketRepository supportTicketRepository;
    private final RefillRequestRepository refillRequestRepository;

    @Cacheable(value = "user-lifetime-stats", key = "#username")
    @Transactional(readOnly = true)
    public LifetimeStatsDto compute(String username) {
        User user =
                userRepository
                        .findByUsername(username)
                        .orElseThrow(() -> new UserNotFoundException("User not found"));

        long total = nz(orderRepository.countByUser_Username(username));
        long completed =
                nz(orderRepository.countByUser_UsernameAndStatus(username, OrderStatus.COMPLETED));
        long partial =
                nz(orderRepository.countByUser_UsernameAndStatus(username, OrderStatus.PARTIAL));
        long cancelled =
                nz(orderRepository.countByUser_UsernameAndStatus(username, OrderStatus.CANCELLED));

        BigDecimal totalSpent =
                orderRepository.sumChargeByUsernameAndStatuses(username, REALIZED_STATUSES);
        if (totalSpent == null) totalSpent = BigDecimal.ZERO;

        long ticketsTotal = supportTicketRepository.countByUserId(user.getId());
        long refillsRequested = refillRequestRepository.countByUserId(user.getId());

        Object[] firstLast = orderRepository.firstAndLastOrderAtForUsername(username);
        LocalDateTime firstAt = null;
        LocalDateTime lastAt = null;
        // Spring Data hands back a single-row Object[][] when the SELECT has > 1 column, so the
        // outer array is the row and inner is the columns. Older Hibernate versions return the
        // inner array directly — handle both shapes defensively.
        Object[] row = firstLast;
        if (firstLast != null && firstLast.length == 1 && firstLast[0] instanceof Object[] inner) {
            row = inner;
        }
        if (row != null && row.length >= 2) {
            firstAt = row[0] instanceof LocalDateTime f ? f : null;
            lastAt = row[1] instanceof LocalDateTime l ? l : null;
        }

        return LifetimeStatsDto.builder()
                .ordersTotal(total)
                .ordersCompleted(completed)
                .ordersPartial(partial)
                .ordersCancelled(cancelled)
                .totalSpent(totalSpent)
                .ticketsTotal(ticketsTotal)
                .refillsRequested(refillsRequested)
                .memberSince(user.getCreatedAt())
                .firstOrderAt(firstAt)
                .lastOrderAt(lastAt)
                .build();
    }

    private static long nz(Long v) {
        return v == null ? 0L : v;
    }
}
