package com.smmpanel.scheduler;

import com.smmpanel.config.OrderSerializationProperties;
import com.smmpanel.repository.jpa.OrderRepository;
import com.smmpanel.service.notification.TelegramBotService;
import com.smmpanel.service.order.OrderSerializationService;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Authoritative backstop for {@link OrderSerializationService}. The per-URL pump is also fired
 * directly on order creation and completion, but those are best-effort optimizations — order
 * terminal-state transitions happen in ~7 different places (admin cancel, timeout cancel, force
 * complete, …) and hooking each is fragile. This sweeper guarantees correctness regardless:
 *
 * <ul>
 *   <li><b>Orphan release</b>: any link with PENDING orders waiting but NO order occupying the URL
 *       gets pumped (covers an unhooked terminal path, a rolled-back/missed pump, or a panel
 *       restart between the completion commit and the pump).
 *   <li><b>Stuck alert</b>: any link whose occupying order is stuck (not terminal, {@code
 *       updatedAt} older than the threshold) while PENDING orders wait → a System Health Telegram
 *       alert (per-link cooldown). Per the product decision the queue is NOT auto-released — an
 *       operator resolves the stuck order manually so start-count correctness is preserved.
 * </ul>
 *
 * Uses {@code fixedDelay} (not {@code fixedRate}) so a slow pass can't pile up; per-link pumps are
 * idempotent under the advisory lock so overlap with the direct triggers is harmless.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderSerializationSweeper {

    private static final String STUCK_ALERT_KEY_PREFIX = "order:serialization:stuck_alert:";

    private final OrderRepository orderRepository;
    private final OrderSerializationService orderSerializationService;
    private final OrderSerializationProperties props;
    private final TelegramBotService telegramBotService;
    private final StringRedisTemplate stringRedisTemplate;

    @Scheduled(fixedDelayString = "${app.order.serialization.sweep-interval-ms:60000}")
    public void sweep() {
        if (!props.isEnabled()) {
            return;
        }
        try {
            releaseOrphans();
        } catch (Exception e) {
            log.warn("Per-URL serialization sweep (orphan release) failed: {}", e.getMessage());
        }
        try {
            alertStuck();
        } catch (Exception e) {
            log.warn("Per-URL serialization sweep (stuck alert) failed: {}", e.getMessage());
        }
    }

    /** Pump every link that has PENDING orders waiting but no order occupying the URL. */
    private void releaseOrphans() {
        List<String> links =
                orderRepository.findLinksWithPendingAndNoActive(
                        props.getActiveStatuses(), PageRequest.of(0, props.getSweepBatchSize()));
        if (links.isEmpty()) {
            return;
        }
        log.info(
                "Per-URL sweep: {} link(s) with waiting orders and a free URL — pumping",
                links.size());
        for (String link : links) {
            // Fire async (asyncExecutor pool) so a backlog burst doesn't pin a size-4 scheduler
            // thread on sequential bot calls; each pump still serializes per-link under the lock.
            try {
                orderSerializationService.pumpUrlAsync(link);
            } catch (Exception e) {
                log.warn("Sweep pump dispatch failed for link {}: {}", link, e.getMessage());
            }
        }
    }

    /** Alert (once per cooldown) on links wedged by a stuck occupying order. No auto-release. */
    private void alertStuck() {
        LocalDateTime cutoff = LocalDateTime.now().minusHours(props.getStuckActiveHours());
        List<String> links =
                orderRepository.findStuckActiveLinks(
                        props.getActiveStatuses(),
                        cutoff,
                        PageRequest.of(0, props.getSweepBatchSize()));
        for (String link : links) {
            if (!acquireAlertCooldown(link)) {
                continue; // already alerted recently
            }
            String text =
                    String.format(
                            "⚠️ Очередь по ссылке заблокирована%nАктивный заказ завис (нет"
                                    + " завершения > %dч), а следующие ждут в PENDING.%nСсылка:"
                                    + " %s%nНужно вручную завершить/отменить зависший заказ.",
                            props.getStuckActiveHours(), link);
            telegramBotService.sendToHealthChat(text);
            log.warn(
                    "Per-URL serialization: link {} wedged by a stuck active order — alerted",
                    link);
        }
    }

    /** SETNX-with-TTL per link so a wedged URL doesn't re-alert every sweep. */
    private boolean acquireAlertCooldown(String link) {
        try {
            // Raw link in the key (Redis keys are binary-safe and tolerate the length) — a 32-bit
            // hashCode could collide and silently suppress a genuine stuck-queue alert.
            String key = STUCK_ALERT_KEY_PREFIX + link;
            Boolean ok =
                    stringRedisTemplate
                            .opsForValue()
                            .setIfAbsent(
                                    key,
                                    "1",
                                    props.getStuckAlertCooldownMinutes(),
                                    TimeUnit.MINUTES);
            return Boolean.TRUE.equals(ok);
        } catch (Exception e) {
            // Redis hiccup must not suppress a genuine alert nor crash the sweep — err toward
            // alerting.
            log.warn("Stuck-alert cooldown check failed for {}: {}", link, e.getMessage());
            return true;
        }
    }
}
