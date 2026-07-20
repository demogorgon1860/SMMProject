package com.smmpanel.service.order;

import com.smmpanel.config.OrderSerializationProperties;
import com.smmpanel.dto.instagram.InstagramOrderResponse;
import com.smmpanel.dto.instagram.InstagramOrderType;
import com.smmpanel.entity.Order;
import com.smmpanel.entity.OrderStatus;
import com.smmpanel.repository.jpa.OrderRepository;
import com.smmpanel.service.balance.BalanceService;
import com.smmpanel.service.integration.InstagramService;
import java.math.BigDecimal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Metric-aware per-URL serialization of order dispatch. Orders that target the SAME normalized
 * {@code link} serialize only when their affected-metric sets OVERLAP: two likes orders wait for
 * each other, but a likes order and a comments order on the same link run in parallel (their
 * start-count baselines — like vs comment counts — are independent). See {@link
 * OrderSerializationProperties} for the why.
 *
 * <p>The single critical section is {@link #pumpUrl(String)}: under a per-URL Postgres advisory
 * lock it computes the link's occupied metrics and dispatches every FIFO-by-id PENDING waiter whose
 * metrics don't overlap them. It is called from three places — the creation Kafka consumer, the
 * order completion paths (via {@link #pumpUrlAsync}), and {@code OrderSerializationSweeper} (the
 * authoritative backstop). All three are idempotent under the lock, so duplicate/overlapping calls
 * can only no-op, never double-dispatch a conflicting metric.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderSerializationService {

    /**
     * Runaway guard: max actual bot-dispatch attempts within one pump. Conflicting waiters are
     * skipped without a bot call and don't count; successful dispatches are naturally bounded to 3
     * (one per metric). Hitting this cap therefore means a storm of hard-failing orders on the link
     * — back off and let the sweeper retry rather than making hundreds of sequential bot calls
     * while holding the advisory lock.
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
     * The gate. Under a per-URL advisory lock: compute the URL's currently-occupied metrics (the
     * bitwise-OR of the masks of the orders occupying it), then dispatch every lowest-id-first
     * PENDING waiter whose metrics do NOT overlap an occupied metric. Independent metrics (e.g.
     * likes vs comments on the same link) dispatch together; same-metric orders serialize so the
     * bot's start-count scout never reads a baseline another order is mutating. A conflicting
     * waiter is left PENDING and re-pumped when the occupying order finishes. Must run in a
     * transaction (the advisory lock is transaction-scoped) — callers reach it through the Spring
     * proxy, never via {@code this}.
     */
    @Transactional
    public void pumpUrl(String link) {
        if (!props.isEnabled() || link == null || link.isBlank()) {
            return;
        }
        // Serialize the whole check-and-dispatch against any other pump for the same link.
        orderRepository.acquireUrlSerializationLock(link);

        // Occupied metrics = OR of the masks of the orders occupying this URL. Re-read committed
        // state under the lock (never trust a passed-in entity).
        int occupied = 0;
        for (Integer mask :
                orderRepository.findMetricMasksByLinkAndStatusIn(link, props.getActiveStatuses())) {
            occupied |= effectiveMask(mask);
        }
        if (occupied == InstagramOrderType.METRIC_ALL) {
            return; // every metric busy — no waiter can dispatch; an active order re-pumps on
            // finish.
        }

        // Dispatch each waiting order whose metrics don't overlap an occupied metric, FIFO by id.
        // The scan is bounded (dispatchScanLimit); it also stops early once every metric is busy.
        int attempts = 0;
        for (Order order :
                orderRepository.findOrdersByLinkAndStatusOrderById(
                        link,
                        OrderStatus.PENDING,
                        PageRequest.of(0, props.getDispatchScanLimit()))) {
            int mask = effectiveMask(order.getMetricMask());
            if ((mask & occupied) != 0) {
                continue; // conflicts with an active order on a shared metric — keep waiting
            }
            if (++attempts > MAX_DISPATCH_ATTEMPTS) {
                log.warn(
                        "pumpUrl({}) hit {} dispatch attempts — backing off; sweeper will retry",
                        link,
                        MAX_DISPATCH_ATTEMPTS);
                return;
            }
            if (dispatchOrderToBot(order)) {
                occupied |= mask; // now occupies its metrics
                if (occupied == InstagramOrderType.METRIC_ALL) {
                    return; // nothing else can dispatch
                }
            }
            // Hard dispatch failure cancelled the order without occupying the URL — try the next.
        }
    }

    /**
     * A 0 (or legacy-null) mask means the order's affected metrics are unknown — treat it as ALL so
     * it serializes conservatively (against everything) rather than "conflicts with nothing".
     */
    private static int effectiveMask(Integer mask) {
        return (mask == null || mask == 0) ? InstagramOrderType.METRIC_ALL : mask;
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
