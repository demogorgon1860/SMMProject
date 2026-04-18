package com.smmpanel.service.notification;

import com.smmpanel.dto.telegram.CancelPendingDecision;
import com.smmpanel.entity.Order;
import com.smmpanel.entity.OrderStatus;
import com.smmpanel.repository.jpa.OrderRepository;
import com.smmpanel.service.balance.BalanceService;
import java.math.BigDecimal;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Transactional boundary for Telegram callback handlers. Kept as a separate bean so that {@code @Transactional}
 * is applied through Spring AOP proxy when invoked from {@link TelegramUpdateHandler} — avoiding the
 * self-invocation pitfall that silently disables {@code @Transactional} on same-bean calls.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TelegramCallbackTxService {

    private final OrderRepository orderRepository;
    private final BalanceService balanceService;
    private final CancelDecisionService cancelDecisionService;

    public record CancelResult(boolean processed, BigDecimal refundedAmount, String reason) {}

    @Transactional(readOnly = true)
    public Optional<String> readBotOrderIdIfPending(Long orderId) {
        return cancelDecisionService
                .getPendingDecision(orderId)
                .map(CancelPendingDecision::getBotOrderId);
    }

    public void removePendingDecision(Long orderId) {
        cancelDecisionService.removePendingDecision(orderId);
    }

    /**
     * Full refund + status transition in a single transaction. DB-level idempotency guard ensures
     * a second invocation (e.g. after a Telegram retry that slipped past the Redis lock) is a noop.
     */
    @Transactional
    public CancelResult performCancelTx(Long orderId) {
        Optional<Order> orderOpt = orderRepository.findById(orderId);
        if (orderOpt.isEmpty()) {
            return new CancelResult(false, BigDecimal.ZERO, "заказ не найден");
        }

        Order order = orderOpt.get();
        if (order.getStatus() == OrderStatus.CANCELLED) {
            return new CancelResult(false, BigDecimal.ZERO, "уже отменён");
        }

        BigDecimal refunded = BigDecimal.ZERO;
        if (order.getCharge() != null
                && order.getCharge().compareTo(BigDecimal.ZERO) > 0
                && order.getUser() != null) {
            refunded = order.getCharge();
            balanceService.refund(
                    order.getUser(),
                    refunded,
                    order,
                    "Full refund for Instagram order #" + orderId + ": cancelled by admin");
            order.setCharge(BigDecimal.ZERO);
        }

        order.setStatus(OrderStatus.CANCELLED);
        order.setTrafficStatus("CANCELLED_BY_ADMIN");
        orderRepository.save(order);

        cancelDecisionService.removePendingDecision(orderId);
        log.info("Admin-cancel tx committed for order {} (refund={})", orderId, refunded);
        return new CancelResult(true, refunded, "ok");
    }
}
