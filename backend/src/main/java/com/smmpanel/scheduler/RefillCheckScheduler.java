package com.smmpanel.scheduler;

import com.smmpanel.client.InstagramBotClient;
import com.smmpanel.dto.instagram.RefillStatusOutcome;
import com.smmpanel.entity.RefillCheck;
import com.smmpanel.service.refill.RefillCheckService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Polls in-flight refill drop-checks against the bot and persists their results. Runs out-of-band
 * (never inside a user request or the admin approve() transaction) because a bot check can take
 * minutes. The bot HTTP call is made here, OUTSIDE the persistence transaction; {@link
 * RefillCheckService#applyPollResult} does the short write.
 *
 * <p>Single-runner, like {@code TelegramScheduler} — assumes one panel replica. If scaled out,
 * guard with ShedLock so two replicas don't double-poll.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RefillCheckScheduler {

    private final RefillCheckService refillCheckService;
    private final InstagramBotClient instagramBotClient;

    @Scheduled(fixedDelayString = "${app.refill.check.poll-interval-ms:10000}")
    public void pollRunningChecks() {
        List<RefillCheck> running;
        try {
            running = refillCheckService.listRunning();
        } catch (Exception e) {
            log.warn("Refill-check poll: listRunning failed: {}", e.getMessage());
            return;
        }
        if (running.isEmpty()) return;

        for (RefillCheck c : running) {
            try {
                RefillStatusOutcome outcome =
                        (c.getBotInstanceUrl() != null && c.getBotJobId() != null)
                                ? instagramBotClient.refillStatus(
                                        c.getBotInstanceUrl(), c.getBotJobId())
                                : RefillStatusOutcome.missing();
                refillCheckService.applyPollResult(c.getId(), outcome);
            } catch (Exception e) {
                log.warn(
                        "Refill-check poll failed for check {} (order {}): {}",
                        c.getId(),
                        c.getOrderId(),
                        e.getMessage());
            }
        }
    }
}
