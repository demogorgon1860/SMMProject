package com.smmpanel.scheduler;

import com.smmpanel.entity.Order;
import com.smmpanel.entity.RefillCheck;
import com.smmpanel.entity.RefillRequest;
import com.smmpanel.repository.jpa.OrderRepository;
import com.smmpanel.service.refill.RefillCheckService;
import com.smmpanel.service.refill.RefillRequestService;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Drives the automatic refill flow: turns user-submitted {@link RefillRequest.Status#CHECKING}
 * requests into admin-actionable {@link RefillRequest.Status#PENDING} ones (or auto-closes them as
 * {@code NO_DROP} / {@code FAILED}) by running the bot drop-check out-of-band.
 *
 * <p>Per tick, for each CHECKING request it inspects the bound drop-check:
 *
 * <ul>
 *   <li><b>DONE</b> → finalize (PENDING if drop &gt; 0, else NO_DROP).
 *   <li><b>RUNNING</b> → wait ({@code RefillCheckScheduler} is polling it).
 *   <li><b>none / FAILED</b> → (re)start a check, subject to the per-tick start cap, the per-request
 *       attempt budget, and an overall age ceiling. When the budget/age is exhausted the request is
 *       FAILED so the customer can resubmit.
 * </ul>
 *
 * <p>Like {@code RefillCheckScheduler} / {@code TelegramScheduler} this is a single-runner (one
 * panel replica). The bot HTTP call (start check) is made here, OUTSIDE the request-finalizing
 * transactions — the service methods are short writes only.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RefillRequestAutoScheduler {

    private final RefillRequestService refillRequestService;
    private final RefillCheckService refillCheckService;
    private final OrderRepository orderRepository;

    /** Max NEW bot checks kicked off per tick — bounds the load a big paste-list puts on the bot. */
    @Value("${app.refill.auto.max-starts-per-tick:5}")
    private int maxStartsPerTick;

    /** Max bot checks started for one request before we give up (each started check counts once). */
    @Value("${app.refill.auto.max-check-attempts:3}")
    private int maxCheckAttempts;

    /** Overall ceiling: a request still CHECKING past this is FAILED (bot likely unreachable). */
    @Value("${app.refill.auto.max-age-minutes:45}")
    private int maxAgeMinutes;

    @Scheduled(fixedDelayString = "${app.refill.auto.poll-interval-ms:12000}")
    public void advanceCheckingRequests() {
        List<RefillRequest> checking;
        try {
            checking = refillRequestService.listChecking();
        } catch (Exception e) {
            log.warn("Refill auto-check: listChecking failed: {}", e.getMessage());
            return;
        }
        if (checking.isEmpty()) return;

        int startsThisTick = 0;
        for (RefillRequest req : checking) {
            try {
                Optional<RefillCheck> bound =
                        req.getBotCheckId() == null
                                ? Optional.empty()
                                : refillCheckService.findCheckById(req.getBotCheckId());

                RefillCheck.Status checkStatus = bound.map(RefillCheck::getStatus).orElse(null);

                if (checkStatus == RefillCheck.Status.RUNNING) {
                    continue; // RefillCheckScheduler is polling it; nothing to do yet.
                }
                if (checkStatus == RefillCheck.Status.DONE) {
                    refillRequestService.finalizeFromCheck(req.getId());
                    continue;
                }

                // No bound check yet, or the bound check FAILED → (re)start, within the budgets.
                if (olderThan(req, maxAgeMinutes)) {
                    refillRequestService.failRequest(
                            req.getId(), "Couldn't verify the drop in time — please try again.");
                    continue;
                }
                int attempts = req.getCheckAttempts() == null ? 0 : req.getCheckAttempts();
                if (attempts >= maxCheckAttempts) {
                    refillRequestService.failRequest(
                            req.getId(), "Couldn't verify the drop — please try again later.");
                    continue;
                }
                if (startsThisTick >= maxStartsPerTick) {
                    continue; // hand off to the next tick to spread bot load
                }

                Order order = orderRepository.findByIdWithAllDetails(req.getOrderId()).orElse(null);
                if (order == null) {
                    refillRequestService.failRequest(req.getId(), "Order no longer exists.");
                    continue;
                }

                try {
                    RefillCheck check = refillCheckService.startCheckForAuto(order, req.getUserId());
                    refillRequestService.bindCheck(req.getId(), check.getId());
                    startsThisTick++;
                } catch (IllegalStateException permanent) {
                    // Ineligible (combo / status / refill-of-refill) — no point retrying.
                    refillRequestService.failRequest(req.getId(), permanent.getMessage());
                } catch (RuntimeException transientErr) {
                    // Bot unavailable (ApiException et al.) — leave CHECKING; retry next tick until
                    // the age ceiling.
                    log.warn(
                            "Refill auto-check: start failed for request {} (order {}): {}",
                            req.getId(),
                            req.getOrderId(),
                            transientErr.getMessage());
                }
            } catch (Exception e) {
                log.warn(
                        "Refill auto-check: advancing request {} (order {}) failed: {}",
                        req.getId(),
                        req.getOrderId(),
                        e.getMessage());
            }
        }
    }

    private static boolean olderThan(RefillRequest req, int minutes) {
        return req.getCreatedAt() != null
                && req.getCreatedAt().isBefore(LocalDateTime.now().minus(Duration.ofMinutes(minutes)));
    }
}
