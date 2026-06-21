package com.smmpanel.service.refill;

import com.smmpanel.client.InstagramBotClient;
import com.smmpanel.dto.instagram.InstagramOrderType;
import com.smmpanel.dto.instagram.RefillCheckResult;
import com.smmpanel.dto.instagram.RefillJobDto;
import com.smmpanel.dto.instagram.RefillReportDto;
import com.smmpanel.dto.instagram.RefillStatusOutcome;
import com.smmpanel.dto.refill.RefillCheckResponse;
import com.smmpanel.entity.Order;
import com.smmpanel.entity.OrderStatus;
import com.smmpanel.entity.RefillCheck;
import com.smmpanel.entity.User;
import com.smmpanel.exception.ApiException;
import com.smmpanel.exception.ResourceNotFoundException;
import com.smmpanel.exception.UserNotFoundException;
import com.smmpanel.repository.jpa.OrderRepository;
import com.smmpanel.repository.jpa.RefillCheckRepository;
import com.smmpanel.repository.jpa.UserRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Live "drop check" against the Instagram bot. The user enters an order number on the Refill page;
 * we kick off the bot's {@code POST /api/refill/check}, persist a {@link RefillCheck} (RUNNING),
 * and let {@code RefillCheckScheduler} poll {@code GET /api/refill/status} out-of-band — the bot
 * enumeration can take minutes, so nothing here blocks on the result.
 *
 * <p>v1 supports single-action services only (like / follow / comment); combos return one report
 * per component and are rejected with a clear message. The drop rate is computed panel-side as
 * {@code refill_needed / ordered_count * 100}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RefillCheckService {

    private final OrderRepository orderRepository;
    private final RefillCheckRepository refillCheckRepository;
    private final UserRepository userRepository;
    private final InstagramBotClient instagramBotClient;

    /**
     * Reuse an in-flight RUNNING check newer than this instead of queueing a duplicate. Kept below
     * {@link #lostThresholdMinutes} so we never hand back a RUNNING check that the scheduler is
     * about to FAIL as lost.
     */
    @Value("${app.refill.check.reuse-window-minutes:3}")
    private int reuseWindowMinutes;

    /** Per-user check budget within {@link #userRateWindowMinutes}. */
    @Value("${app.refill.check.user-rate-limit:20}")
    private int userRateLimit;

    @Value("${app.refill.check.user-rate-window-minutes:60}")
    private int userRateWindowMinutes;

    /**
     * If the bot job vanishes (404 / restart / eviction) and the check is older than this, FAIL.
     */
    @Value("${app.refill.check.lost-threshold-minutes:4}")
    private int lostThresholdMinutes;

    /** Overall ceiling: a check still not done past this is FAILED (bot TTL is ~25 min). */
    @Value("${app.refill.check.max-age-minutes:30}")
    private int maxAgeMinutes;

    // ---------------------------------------------------------------------
    // User-facing (ownership-scoped)
    // ---------------------------------------------------------------------

    /** Start a drop check for one of the current user's orders. */
    @Transactional
    public RefillCheckResponse startCheckForUser(Long orderId) {
        User user = currentUser();
        Order order =
                orderRepository
                        .findByIdAndUser(orderId, user)
                        .orElseThrow(() -> new ResourceNotFoundException("Order not found"));
        return startCheck(order, user.getId());
    }

    /** Latest check for one of the current user's orders (Refill page polls this). */
    @Transactional(readOnly = true)
    public Optional<RefillCheckResponse> getLatestForUser(Long orderId) {
        User user = currentUser();
        // Ownership: 404 (not 403) so we don't leak that the order exists.
        orderRepository
                .findByIdAndUser(orderId, user)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found"));
        return refillCheckRepository
                .findFirstByOrderIdOrderByRequestedAtDesc(orderId)
                .map(RefillCheckResponse::from);
    }

    // ---------------------------------------------------------------------
    // Admin-facing (any order; controllers role-gate by URL)
    // ---------------------------------------------------------------------

    @Transactional
    public RefillCheckResponse startCheckForAdmin(Long orderId) {
        User admin = currentUser();
        Order order =
                orderRepository
                        .findByIdWithAllDetails(orderId)
                        .orElseThrow(() -> new ResourceNotFoundException("Order not found"));
        return startCheck(order, admin.getId());
    }

    @Transactional(readOnly = true)
    public Optional<RefillCheckResponse> getLatestForAdmin(Long orderId) {
        return refillCheckRepository
                .findFirstByOrderIdOrderByRequestedAtDesc(orderId)
                .map(RefillCheckResponse::from);
    }

    // ---------------------------------------------------------------------
    // Core
    // ---------------------------------------------------------------------

    private RefillCheckResponse startCheck(Order order, Long requesterUserId) {
        if (Boolean.TRUE.equals(order.getIsRefill())) {
            throw new IllegalStateException("Cannot check a refill order — check the original.");
        }
        OrderStatus status = order.getStatus();
        if (status != OrderStatus.COMPLETED && status != OrderStatus.PARTIAL) {
            throw new IllegalStateException(
                    "Drop check is available only for completed or partial orders (current: "
                            + status
                            + ").");
        }

        InstagramOrderType type = resolveType(order);
        if (!isSingleAction(type)) {
            throw new IllegalStateException(
                    "Проверка дропа доступна только для одиночных услуг (лайки, подписки или"
                            + " комментарии). Combo-услуги — в следующей версии.");
        }

        // Idempotency: reuse a recent in-flight check rather than spawning a duplicate expensive
        // job.
        Optional<RefillCheck> running =
                refillCheckRepository.findFirstByOrderIdAndStatusOrderByRequestedAtDesc(
                        order.getId(), RefillCheck.Status.RUNNING);
        if (running.isPresent()
                && running.get().getRequestedAt() != null
                && running.get()
                        .getRequestedAt()
                        .isAfter(LocalDateTime.now().minusMinutes(reuseWindowMinutes))) {
            return RefillCheckResponse.from(running.get());
        }

        // Rate limit the expensive checker per requester.
        long recent =
                refillCheckRepository.countByUserIdAndRequestedAtAfter(
                        requesterUserId, LocalDateTime.now().minusMinutes(userRateWindowMinutes));
        if (recent >= userRateLimit) {
            throw new IllegalStateException(
                    "Слишком много проверок за последний час. Попробуйте позже.");
        }

        RefillCheckResult result = instagramBotClient.refillCheck(String.valueOf(order.getId()));
        if (!result.isAccepted()) {
            throw new ApiException(
                    "Не удалось запустить проверку дропа: "
                            + (result.getError() == null ? "бот недоступен" : result.getError()),
                    HttpStatus.BAD_GATEWAY);
        }

        RefillCheck check =
                RefillCheck.builder()
                        .orderId(order.getId())
                        .userId(requesterUserId)
                        .botJobId(result.getJobId())
                        .botInstanceUrl(result.getInstanceUrl())
                        .actionType(type.getValue())
                        .status(RefillCheck.Status.RUNNING)
                        .orderedCount(order.getQuantity())
                        .build();
        check = refillCheckRepository.save(check);
        log.info(
                "[REFILL-CHECK] order={} job={} instance={} type={} → RUNNING",
                order.getId(),
                result.getJobId(),
                result.getInstanceUrl(),
                type.getValue());
        return RefillCheckResponse.from(check);
    }

    // ---------------------------------------------------------------------
    // Scheduler hooks
    // ---------------------------------------------------------------------

    /** RUNNING checks for the scheduler to poll. */
    @Transactional(readOnly = true)
    public List<RefillCheck> listRunning() {
        return refillCheckRepository.findByStatus(RefillCheck.Status.RUNNING);
    }

    /**
     * Apply a polled job snapshot to a RUNNING check. {@code job == null} means the bot returned no
     * job (transient error, or the job was lost to a restart / LRU eviction). Called by the
     * scheduler (separate bean) so the {@code @Transactional} proxy applies; the bot HTTP call is
     * done by the scheduler OUTSIDE this transaction.
     */
    @Transactional
    public void applyPollResult(Long checkId, RefillStatusOutcome outcome) {
        RefillCheck c = refillCheckRepository.findById(checkId).orElse(null);
        if (c == null || c.getStatus() != RefillCheck.Status.RUNNING) {
            return; // already finalized or gone (race with another tick)
        }
        LocalDateTime now = LocalDateTime.now();
        RefillJobDto job = outcome.getJob();

        if (job == null) {
            if (outcome.isJobMissing()) {
                // Definitive 404 — the bot lost the job (restart / eviction). FAIL after the
                // short lost-job grace so the UI can offer a re-check.
                if (olderThan(c, now, lostThresholdMinutes)) {
                    fail(c, now, "Бот потерял задачу проверки — запустите проверку ещё раз.");
                }
            } else {
                // Transient failure (network / breaker) — NOT proof the job is gone. Keep polling
                // a genuinely-running (minutes-long) enumeration until the overall ceiling.
                if (olderThan(c, now, maxAgeMinutes)) {
                    fail(c, now, "Проверка превысила лимит времени — попробуйте ещё раз.");
                }
            }
            return;
        }

        String jobStatus = job.getStatus() == null ? "" : job.getStatus().toLowerCase();
        if (!"done".equals(jobStatus)) {
            if (olderThan(c, now, maxAgeMinutes)) {
                fail(c, now, "Проверка превысила лимит времени — попробуйте ещё раз.");
            }
            return; // queued / running
        }

        RefillReportDto rep =
                (job.getReports() == null || job.getReports().isEmpty())
                        ? null
                        : job.getReports().get(0);
        if (rep == null) {
            fail(c, now, "Бот вернул пустой результат.");
            return;
        }
        if (!"done".equalsIgnoreCase(rep.getStatus())) {
            // error / unsupported — surface the bot's reason; do NOT treat as drop=0.
            c.setStatus(RefillCheck.Status.FAILED);
            c.setError(
                    rep.getError() != null
                            ? rep.getError()
                            : ("Проверка недоступна (" + rep.getStatus() + ")"));
            c.setNote(rep.getNote());
            c.setEarlyStopped(Boolean.TRUE.equals(rep.getEarlyStopped()));
            c.setCheckedAt(now);
            refillCheckRepository.save(c);
            return;
        }

        // Success — persist the snapshot and compute the drop rate.
        int ordered =
                rep.getCount() != null && rep.getCount() > 0
                        ? rep.getCount()
                        : (c.getOrderedCount() != null ? c.getOrderedCount() : 0);
        int refillNeeded = rep.getRefillNeeded() != null ? Math.max(0, rep.getRefillNeeded()) : 0;
        c.setOrderedCount(ordered);
        c.setDelivered(rep.getDelivered());
        c.setMatchable(rep.getMatchable());
        c.setPresent(rep.getPresent());
        c.setDropped(rep.getDropped());
        c.setRefillNeeded(refillNeeded);
        c.setCurrentCount(rep.getCurrentCount());
        c.setEarlyStopped(Boolean.TRUE.equals(rep.getEarlyStopped()));
        c.setNote(rep.getNote());
        c.setDropRate(computeDropRate(refillNeeded, ordered));
        c.setStatus(RefillCheck.Status.DONE);
        c.setCheckedAt(now);
        refillCheckRepository.save(c);
        log.info(
                "[REFILL-CHECK] order={} DONE — refillNeeded={}/{} dropRate={}% earlyStopped={}",
                c.getOrderId(), refillNeeded, ordered, c.getDropRate(), c.getEarlyStopped());
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    /** drop rate % = refill_needed / ordered_count * 100, HALF_UP to 2 dp. */
    static BigDecimal computeDropRate(int refillNeeded, int orderedCount) {
        if (orderedCount <= 0) return BigDecimal.ZERO;
        return BigDecimal.valueOf(refillNeeded)
                .multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(orderedCount), 2, RoundingMode.HALF_UP);
    }

    private static boolean olderThan(RefillCheck c, LocalDateTime now, int minutes) {
        return c.getRequestedAt() != null && c.getRequestedAt().isBefore(now.minusMinutes(minutes));
    }

    private void fail(RefillCheck c, LocalDateTime now, String message) {
        c.setStatus(RefillCheck.Status.FAILED);
        c.setError(message);
        c.setCheckedAt(now);
        refillCheckRepository.save(c);
        log.info("[REFILL-CHECK] order={} FAILED — {}", c.getOrderId(), message);
    }

    private InstagramOrderType resolveType(Order order) {
        if (order.getService() == null || order.getService().getCategory() == null) {
            throw new IllegalStateException("Order has no service category — cannot check drop.");
        }
        try {
            return InstagramOrderType.fromServiceCategory(order.getService().getCategory());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("Unsupported service for drop check.");
        }
    }

    private static boolean isSingleAction(InstagramOrderType type) {
        return type == InstagramOrderType.LIKE
                || type == InstagramOrderType.FOLLOW
                || type == InstagramOrderType.COMMENT;
    }

    private User currentUser() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth.getName() == null) {
            throw new AccessDeniedException("Not authenticated");
        }
        return userRepository
                .findByUsername(auth.getName())
                .orElseThrow(() -> new UserNotFoundException("User not found"));
    }
}
