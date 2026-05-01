package com.smmpanel.service.notification;

import com.smmpanel.entity.Order;
import com.smmpanel.util.AfterCommitRunner;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Facade over {@link TelegramBotService}. Public API unchanged — every existing caller
 * (OrderService, AdminService, InstagramService, InstagramResultConsumer, ...) keeps working
 * without edits.
 *
 * <p>What changed: each {@code notify*} call is now <strong>deferred until the surrounding
 * transaction commits</strong> via {@link AfterCommitRunner#runAfterCommit}. The historical
 * failure mode was that the underlying {@link TelegramBotService} methods are {@code @Async}
 * — they hand off to the executor immediately, so a Telegram message could go out even when
 * the database transaction that triggered the notification later rolled back. Concrete
 * example from prod (order #8086): {@code AdminService.forceCompleteOrder} hit an optimistic
 * lock conflict against the bot's webhook, the {@code @Transactional} method rolled back,
 * but the "✅ Заказ выполнен" message had already been queued and sent. Admin saw an error
 * toast yet the user got a "completed" alert.
 *
 * <p>Bonus: lazy associations on {@link Order} (user, service) are pre-loaded here while we're
 * still inside the surrounding transaction's persistence context. Without that, the deferred
 * action — which runs after the context closes — would hit
 * {@code LazyInitializationException} the first time it touches {@code order.getUser()}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TelegramNotificationService {

    private final TelegramBotService telegramBotService;

    public void notifyNewOrder(Order order) {
        primeAssociations(order);
        AfterCommitRunner.runAfterCommit(() -> telegramBotService.notifyNewOrder(order));
    }

    public void notifyOrderCompleted(Order order, Integer completed) {
        primeAssociations(order);
        AfterCommitRunner.runAfterCommit(
                () -> telegramBotService.notifyOrderCompleted(order, completed));
    }

    public void notifyOrderPartial(Order order, Integer completed) {
        primeAssociations(order);
        AfterCommitRunner.runAfterCommit(
                () -> telegramBotService.notifyOrderPartial(order, completed));
    }

    public void notifyOrderFailed(Order order, Integer completed) {
        primeAssociations(order);
        AfterCommitRunner.runAfterCommit(
                () -> telegramBotService.notifyOrderFailed(order, completed));
    }

    public void notifyOrderCancelledPending(Order order) {
        primeAssociations(order);
        AfterCommitRunner.runAfterCommit(
                () -> telegramBotService.notifyOrderCancelledPending(order, null));
    }

    public void notifyOrderCancelledPending(Order order, Integer completedCount) {
        primeAssociations(order);
        AfterCommitRunner.runAfterCommit(
                () -> telegramBotService.notifyOrderCancelledPending(order, completedCount));
    }

    /**
     * Force-init lazy {@code @ManyToOne} proxies on the entity so the deferred action
     * (which runs after the persistence context closes) can read them without
     * {@code LazyInitializationException}. A no-op if the proxies are already initialized
     * or if the field is null. We intentionally call only the public getters, not
     * {@code Hibernate.initialize}, to keep this code Hibernate-version-agnostic.
     */
    private static void primeAssociations(Order order) {
        if (order == null) return;
        if (order.getUser() != null) {
            order.getUser().getUsername();
        }
        if (order.getService() != null) {
            order.getService().getName();
        }
    }
}
