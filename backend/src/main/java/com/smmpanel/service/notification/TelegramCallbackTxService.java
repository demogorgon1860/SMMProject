package com.smmpanel.service.notification;

import com.smmpanel.dto.telegram.CancelPendingDecision;
import com.smmpanel.entity.Order;
import com.smmpanel.entity.OrderStatus;
import com.smmpanel.repository.jpa.OrderRepository;
import com.smmpanel.service.integration.InstagramService;
import java.math.BigDecimal;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Transactional boundary for Telegram callback handlers. Kept as a separate bean so that
 * {@code @Transactional} is applied through Spring AOP proxy when invoked from {@link
 * TelegramUpdateHandler} — avoiding the self-invocation pitfall that silently disables
 * {@code @Transactional} on same-bean calls.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TelegramCallbackTxService {

    private final OrderRepository orderRepository;
    private final InstagramService instagramService;
    private final CancelDecisionService cancelDecisionService;

    /**
     * Outcome of {@link #performCancelTx}. {@code partial=true} means some items were already
     * delivered by the bot and only the undelivered portion was refunded; {@code partial=false}
     * means either nothing was delivered (full refund) or the call was a noop.
     *
     * @param refundedAmount amount credited back to the user
     * @param profitAmount amount retained on the order (i.e. order.charge after refund) — useful
     *     for partial-profit reporting; ZERO for full refund
     */
    public record CancelResult(
            boolean processed,
            boolean partial,
            BigDecimal refundedAmount,
            BigDecimal profitAmount,
            String reason) {}

    @Transactional(readOnly = true)
    public Optional<CancelPendingDecision> readPendingDecision(Long orderId) {
        return cancelDecisionService.getPendingDecision(orderId);
    }

    public void removePendingDecision(Long orderId) {
        cancelDecisionService.removePendingDecision(orderId);
    }

    /**
     * Refund + status transition in a single transaction. Routes to partial vs full refund based on
     * {@code completedCount}:
     *
     * <ul>
     *   <li>{@code completedCount > 0 && < quantity} → PARTIAL, pro-rata refund for undelivered
     *       portion, traffic_status = PARTIAL_CANCELLED_BY_ADMIN
     *   <li>otherwise → CANCELLED, full refund, traffic_status = CANCELLED_BY_ADMIN
     * </ul>
     *
     * DB-level idempotency guard ensures a retry after commit is a noop (e.g. if Redis lock expired
     * and Telegram re-delivered).
     */
    @Transactional
    public CancelResult performCancelTx(Long orderId, Integer completedCount) {
        Optional<Order> orderOpt = orderRepository.findById(orderId);
        if (orderOpt.isEmpty()) {
            return new CancelResult(
                    false, false, BigDecimal.ZERO, BigDecimal.ZERO, "заказ не найден");
        }

        Order order = orderOpt.get();
        if (order.getStatus() == OrderStatus.CANCELLED
                || order.getStatus() == OrderStatus.PARTIAL
                || order.getStatus() == OrderStatus.COMPLETED) {
            return new CancelResult(
                    false,
                    false,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    "уже " + order.getStatus().name().toLowerCase());
        }

        int completed = completedCount != null && completedCount > 0 ? completedCount : 0;
        int quantity = order.getQuantity() != null ? order.getQuantity() : 0;

        BigDecimal refunded;
        boolean isPartial;

        if (completed > 0 && completed < quantity) {
            String reason =
                    String.format(
                            "Partial refund for Instagram order #%d: %d/%d delivered (cancelled"
                                    + " by admin)",
                            orderId, completed, quantity);
            refunded = instagramService.processPartialRefund(order, completed, reason);
            order.setStatus(OrderStatus.PARTIAL);
            order.setTrafficStatus("PARTIAL_CANCELLED_BY_ADMIN");
            order.setViewsDelivered(completed);
            order.setRemains(quantity - completed);
            isPartial = true;
        } else {
            String reason =
                    String.format(
                            "Full refund for Instagram order #%d: no items delivered (cancelled"
                                    + " by admin)",
                            orderId);
            refunded = instagramService.processFullRefund(order, reason);
            order.setStatus(OrderStatus.CANCELLED);
            order.setTrafficStatus("CANCELLED_BY_ADMIN");
            order.setViewsDelivered(0);
            order.setRemains(quantity);
            isPartial = false;
        }

        BigDecimal profit = order.getCharge() != null ? order.getCharge() : BigDecimal.ZERO;

        orderRepository.save(order);
        cancelDecisionService.removePendingDecision(orderId);

        log.info(
                "Admin-cancel tx committed for order {} (partial={}, refund={}, profit={})",
                orderId,
                isPartial,
                refunded,
                profit);
        return new CancelResult(true, isPartial, refunded, profit, "ok");
    }
}
