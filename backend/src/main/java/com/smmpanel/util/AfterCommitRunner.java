package com.smmpanel.util;

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Defers a side-effect (Telegram notification, bot HTTP call, ...) until <em>after</em> the
 * surrounding transaction commits. If no transaction is active the action runs immediately —
 * appropriate for callers from non-transactional contexts (schedulers, webhook handlers that
 * already opened their own short-lived txs and closed them before reaching this code).
 *
 * <p>Same primitive that Spring's {@code @TransactionalEventListener(phase = AFTER_COMMIT)}
 * uses internally — this is the public, supported {@link TransactionSynchronizationManager}
 * API rather than a workaround. We use the direct form (instead of an event/listener pair)
 * because the publishers and the receivers in this codebase live next door to each other and
 * the loose-coupling benefit of events doesn't pay for the extra indirection.
 *
 * <p>Why this matters: without this gate, an {@code @Async} notification can fire after the
 * service method returns but before the surrounding transaction commits. If that transaction
 * then rolls back (e.g. force-complete losing an optimistic-lock race against the bot's
 * webhook → {@code StaleStateException}), the panel-side state never changes but the
 * "✅ Заказ выполнен" Telegram message has already gone out. Real bug from prod logs.
 */
public final class AfterCommitRunner {

    private AfterCommitRunner() {}

    /**
     * Run {@code action} after the current transaction successfully commits. On rollback the
     * action is silently dropped (intentional — that's the whole point). If there is no
     * active transaction at all, run inline.
     *
     * <p>The action itself runs on the same thread that committed the outer transaction;
     * if it does heavy work, wrap it in {@code @Async} on the receiving end (the existing
     * {@code TelegramBotService} methods already do this).
     */
    public static void runAfterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            action.run();
                        }
                    });
        } else {
            action.run();
        }
    }
}
