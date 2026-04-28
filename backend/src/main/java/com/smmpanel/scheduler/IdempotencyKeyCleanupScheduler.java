package com.smmpanel.scheduler;

import com.smmpanel.service.core.IdempotencyService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Prunes expired {@code idempotency_keys} rows so the table doesn't grow without bound. Default TTL
 * is 5 minutes per record, so an hourly sweep is more than enough to keep the table small.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IdempotencyKeyCleanupScheduler {

    private final IdempotencyService idempotencyService;

    /** Hourly at :15 — off-peak relative to other hourly jobs that fire at :00. */
    @Scheduled(cron = "${app.idempotency.cleanup-cron:0 15 * * * *}")
    public void cleanup() {
        try {
            idempotencyService.deleteExpired();
        } catch (Exception e) {
            // Cleanup is best-effort — table grows for one cycle, next sweep tries again.
            log.warn("Idempotency-key cleanup failed: {}", e.toString());
        }
    }
}
