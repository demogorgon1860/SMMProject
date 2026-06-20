package com.smmpanel.service.order;

import com.smmpanel.config.OrderSerializationProperties;
import com.smmpanel.dto.instagram.InstagramOrderResponse;
import com.smmpanel.entity.Order;
import com.smmpanel.entity.OrderStatus;
import com.smmpanel.repository.jpa.OrderRepository;
import com.smmpanel.service.balance.BalanceService;
import com.smmpanel.service.integration.InstagramService;
import java.math.BigDecimal;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Per-URL serialization of order dispatch. Orders that target the SAME normalized {@code link} are
 * handed to the bot one at a time (FIFO by id); the rest wait in PENDING until the active one
 * reaches a terminal state. See {@link OrderSerializationProperties} for the why.
 *
 * <p>The single critical section is {@link #pumpUrl(String)}: under a per-URL Postgres advisory
 * lock it asks "is this URL busy?" and, if not, dispatches the lowest-id PENDING order. It is
 * called from three places — the creation Kafka consumer (immediate dispatch when the URL is free),
 * the order completion paths (release the next when the active one finishes, via {@link
 * #pumpUrlAsync}), and {@code OrderSerializationSweeper} (the authoritative backstop). All three
 * are idempotent under the lock, so duplicate/overlapping calls can only no-op, never
 * double-dispatch.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderSerializationService {

    /**
     * Hard cap on dispatch attempts within one pump. A pump normally dispatches one order and
     * stops; it only loops when a dispatch hard-fails (bot business error → CANCELLED+refund, never
     * occupied the URL) and we move straight to the next waiter. The cap is a runaway guard — each
     * iteration moves an order out of PENDING (the same-tx flush makes the next query skip it), so
     * the loop is already bounded, but a cap turns any unexpected non-progress into a logged
     * back-off, not a spin.
     */
    private static final int MAX_DISPATCH_ATTEMPTS = 100;

    private final OrderRepository orderRepository;
    private final InstagramService instagramService;
    private final BalanceService balanceService;
    private final OrderSerializationProperties props;

    /**
     * Self-reference (lazy, no cycle) so {@link #pumpUrlAsync} re-enters {@link #pumpUrl} through
     * the proxy — a plain {@code this.pumpUrl(...)} would bypass the {@code @Transactional} advice
     * and the transaction-scoped advisory lock would be released immediately, breaking
     * serialization.
     */
    private final ObjectProvider<OrderSerializationService> self;

    public boolean isEnabled() {
        return props.isEnabled();
    }

    /**
     * The gate. Under a per-URL advisory lock: if the URL is already occupied, do nothing;
     * otherwise dispatch the lowest-id PENDING order for that link. Must run in a transaction (the
     * advisory lock is transaction-scoped) — callers reach it through the Spring proxy, never via
     * {@code this}.
     */
    @Transactional
    public void pumpUrl(String link) {
        if (!props.isEnabled() || link == null || link.isBlank()) {
            return;
        }
        // Serialize the whole check-and-dispatch against any other pump for the same link.
        orderRepository.acquireUrlSerializationLock(link);

        // Re-read committed state under the lock (never trust a passed-in entity).
        if (orderRepository.existsByLinkAndStatusIn(link, props.getActiveStatuses())) {
            return; // URL busy — the active order will trigger the next pump when it finishes.
        }

        for (int attempt = 0; attempt < MAX_DISPATCH_ATTEMPTS; attempt++) {
            List<Order> next =
                    orderRepository.findOrdersByLinkAndStatusOrderById(
                            link, OrderStatus.PENDING, PageRequest.of(0, 1));
            if (next.isEmpty()) {
                return; // nothing waiting
            }
            Order order = next.get(0);
            if (dispatchOrderToBot(order)) {
                return; // order now occupies the URL — done
            }
            // Hard dispatch failure cancelled the order without occupying the URL; try the next
            // one.
        }
        log.warn(
                "pumpUrl({}) hit max dispatch attempts ({}) — backing off; sweeper will retry",
                link,
                MAX_DISPATCH_ATTEMPTS);
    }

    /**
     * Async wrapper used by the order-completion hooks so the bot dispatch (network call under the
     * advisory lock) never runs on the RabbitMQ/webhook listener thread. Re-enters {@link #pumpUrl}
     * through the proxy so the transaction + lock apply on the async thread.
     */
    @Async("asyncExecutor")
    public void pumpUrlAsync(String link) {
        try {
            self.getObject().pumpUrl(link);
        } catch (Exception e) {
            log.warn("Async per-URL pump failed for link {}: {}", link, e.getMessage());
        }
    }

    /**
     * Dispatch one specific order to the bot. Extracted verbatim from the old {@code
     * OrderEventConsumer.processInstagramOrder} so behavior (and Kafka-retry semantics) are
     * preserved. Runs inside the caller's transaction (so the advisory lock from {@link #pumpUrl}
     * is held across the dispatch) — deliberately NOT {@code @Transactional}.
     *
     * <p>Delivery to the bot is <b>at-least-once</b>, not exactly-once: the bot send happens inside
     * this transaction, so a commit failure (or pod kill) strictly after the bot already received
     * the order rolls the panel row back to PENDING while the bot has it, and a Kafka retry would
     * re-send. This matches the pre-serialization behavior — the advisory lock serializes
     * concurrent pumps but gives no exactly-once guarantee across a tx that aborts after an
     * external side effect. The bot de-duplicates creates by {@code external_id} (the panel order
     * id), which closes the window.
     *
     * @return {@code true} if the order now occupies the URL (IN_PROGRESS); {@code false} if the
     *     bot rejected it (CANCELLED + full refund — it never occupied the URL). A bot business
     *     error returns normally; only an infrastructure exception propagates (so Kafka can retry).
     */
    public boolean dispatchOrderToBot(Order order) {
        log.info("Dispatching Instagram order {} to bot (link={})", order.getId(), order.getLink());
        InstagramOrderResponse response = instagramService.createInstagramOrder(order);

        if (response.isSuccess()) {
            order.setInstagramBotOrderId(response.getId());
            order.setStatus(OrderStatus.IN_PROGRESS);
            orderRepository.save(order);
            log.info(
                    "Instagram order {} sent to bot, botOrderId={}",
                    order.getId(),
                    response.getId());
            return true;
        }

        log.error(
                "Instagram bot returned error for order {}: {} — cancelling + full refund",
                order.getId(),
                response.getError());
        order.setStatus(OrderStatus.CANCELLED);
        order.setErrorMessage("Instagram bot error: " + response.getError());
        order.setRemains(order.getQuantity());
        BigDecimal refundAmount = order.getCharge();
        balanceService.refund(
                order.getUser(),
                refundAmount,
                order,
                "Refund for failed Instagram order #" + order.getId());
        order.setCharge(BigDecimal.ZERO);
        orderRepository.save(order);
        log.info("Refunded {} for failed Instagram order {}", refundAmount, order.getId());
        return false;
    }
}
