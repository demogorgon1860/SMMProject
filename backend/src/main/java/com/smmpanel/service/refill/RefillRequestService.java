package com.smmpanel.service.refill;

import com.smmpanel.dto.refill.RefillBatchResponse;
import com.smmpanel.dto.refill.RefillRequestResponse;
import com.smmpanel.dto.refill.RefillResponse;
import com.smmpanel.entity.Order;
import com.smmpanel.entity.OrderStatus;
import com.smmpanel.entity.RefillCheck;
import com.smmpanel.entity.RefillRequest;
import com.smmpanel.entity.User;
import com.smmpanel.exception.ResourceNotFoundException;
import com.smmpanel.exception.UserNotFoundException;
import com.smmpanel.repository.jpa.OrderRepository;
import com.smmpanel.repository.jpa.RefillRequestRepository;
import com.smmpanel.repository.jpa.UserRepository;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Automatic, user-initiated refill flow with admin approval.
 *
 * <p>The customer submits one or more order numbers on the Refill page; the panel takes over from
 * there:
 *
 * <ol>
 *   <li>{@link #createRequest} / {@link #createRequests} validate ownership / status / window /
 *       single-action service / no-duplicates and insert a {@link RefillRequest.Status#CHECKING}
 *       row — no bot call on the request path.
 *   <li>{@code RefillRequestAutoScheduler} runs the bot drop-check out-of-band and finalizes the
 *       request via {@link #finalizeFromCheck}:
 *       <ul>
 *         <li>drop &gt; 0 → {@link RefillRequest.Status#PENDING}, carrying the real dropped amount.
 *         <li>drop == 0 → {@link RefillRequest.Status#NO_DROP} (auto-closed).
 *         <li>check couldn't complete → {@link RefillRequest.Status#FAILED} (user may resubmit).
 *       </ul>
 *   <li>Admin sees only PENDING requests and either {@link #approve} (re-delivers exactly the
 *       dropped amount via {@link OrderRefillService}) or {@link #reject}.
 * </ol>
 *
 * <p>Concurrency: an active-set partial-unique index on {@code (order_id) WHERE status IN
 * ('CHECKING','PENDING')} blocks two concurrent in-flight requests on the same order; the service
 * catches the resulting integrity violation, so the user-side double-click is naturally idempotent.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RefillRequestService {

    private final RefillRequestRepository refillRequestRepository;
    private final OrderRepository orderRepository;
    private final UserRepository userRepository;
    private final OrderRefillService orderRefillService;
    private final RefillCheckService refillCheckService;

    /** A request is "active" (occupies the order's one slot) while CHECKING or PENDING. */
    private static final List<RefillRequest.Status> ACTIVE_STATUSES =
            List.of(RefillRequest.Status.CHECKING, RefillRequest.Status.PENDING);

    /** Refill window measured from {@code Order.updatedAt} (= order completion time). */
    @Value("${app.refill.window-days:30}")
    private int refillWindowDays;

    // ---------------------------------------------------------------------
    // User-facing
    // ---------------------------------------------------------------------

    /** Submit a single order for an automatic refill check (Orders drawer / single endpoint). */
    @Transactional
    public RefillRequestResponse createRequest(Long orderId, String userNote) {
        return RefillRequestResponse.from(createOne(currentUser(), orderId, userNote));
    }

    /**
     * Submit several orders at once (the Refill page paste-list). Each order is validated and
     * inserted independently so one bad id never fails the batch. Ids are de-duplicated preserving
     * input order.
     *
     * <p>Deliberately NOT {@code @Transactional}: {@code createOne}'s insert runs in its own
     * repository-level transaction per order. If we wrapped the whole batch in one transaction, a
     * single concurrent-race constraint violation (rare, but possible) would poison the Postgres
     * transaction — every subsequent order's read would then throw "current transaction is aborted"
     * and the already-created CHECKING rows would roll back, turning one flaky order into a failed
     * batch. Per-order isolation keeps partial success genuinely partial.
     */
    public RefillBatchResponse createRequests(List<Long> orderIds, String sharedNote) {
        User user = currentUser();
        List<RefillBatchResponse.Item> items = new ArrayList<>();
        int accepted = 0;
        int rejected = 0;
        for (Long orderId : new LinkedHashSet<>(orderIds)) {
            if (orderId == null || orderId <= 0) {
                items.add(
                        RefillBatchResponse.Item.builder()
                                .orderId(orderId)
                                .accepted(false)
                                .message("Invalid order number")
                                .build());
                rejected++;
                continue;
            }
            try {
                RefillRequest req = createOne(user, orderId, sharedNote);
                items.add(
                        RefillBatchResponse.Item.builder()
                                .orderId(orderId)
                                .accepted(true)
                                .status(req.getStatus().name())
                                .requestId(req.getId())
                                .build());
                accepted++;
            } catch (ResourceNotFoundException e) {
                items.add(rejectedItem(orderId, "Order not found"));
                rejected++;
            } catch (IllegalStateException e) {
                items.add(rejectedItem(orderId, e.getMessage()));
                rejected++;
            }
        }
        return RefillBatchResponse.builder()
                .results(items)
                .accepted(accepted)
                .rejected(rejected)
                .build();
    }

    private static RefillBatchResponse.Item rejectedItem(Long orderId, String message) {
        return RefillBatchResponse.Item.builder()
                .orderId(orderId)
                .accepted(false)
                .message(message)
                .build();
    }

    /**
     * Validate one order and insert a CHECKING request for it (or return the existing in-flight
     * request idempotently). Throws {@link ResourceNotFoundException} (404) for unknown / not-owned
     * orders and {@link IllegalStateException} for every other ineligibility so the batch caller can
     * surface a per-order reason.
     */
    private RefillRequest createOne(User user, Long orderId, String userNote) {
        // Eager-fetch user + service: the batch path runs without a surrounding transaction (for
        // per-order isolation) and open-in-view is disabled, so a lazy order.getService() /
        // order.getUser() would throw LazyInitializationException. findByIdWithAllDetails JOIN
        // FETCHes both.
        Order order =
                orderRepository
                        .findByIdWithAllDetails(orderId)
                        .orElseThrow(() -> new ResourceNotFoundException("Order not found"));

        // 1) Ownership — return 404 not 403, so we don't leak that the order exists.
        if (order.getUser() == null || !order.getUser().getId().equals(user.getId())) {
            throw new ResourceNotFoundException("Order not found");
        }

        // 2) Can't refill a refill — point the user at the original.
        if (Boolean.TRUE.equals(order.getIsRefill())) {
            throw new IllegalStateException(
                    "This is a refill order — request a refill on the original order instead.");
        }

        // 3) Eligibility window — only completed / partial orders make sense to refill.
        OrderStatus status = order.getStatus();
        if (status != OrderStatus.COMPLETED && status != OrderStatus.PARTIAL) {
            throw new IllegalStateException(
                    "Only completed or partial orders are eligible for refill (current: "
                            + status
                            + ")");
        }

        // 4) Time window from completion. Using updatedAt as a proxy for "completed at" — Order
        //    doesn't track a separate completion timestamp, but updatedAt was bumped on the
        //    transition to COMPLETED/PARTIAL.
        LocalDateTime referencePoint =
                order.getUpdatedAt() != null ? order.getUpdatedAt() : order.getCreatedAt();
        if (referencePoint == null
                || LocalDateTime.now()
                        .isAfter(referencePoint.plus(Duration.ofDays(refillWindowDays)))) {
            throw new IllegalStateException(
                    "Refill window expired (orders are eligible within "
                            + refillWindowDays
                            + " days of completion)");
        }

        // 5) Service must be drop-checkable (single action). Reject combos at submit time so the
        //    user gets instant feedback instead of a later FAILED in their history.
        refillCheckService.assertSingleActionService(order);

        // 6) Already approved? No double-refill through this flow.
        if (refillRequestRepository.existsByOrderIdAndStatus(
                orderId, RefillRequest.Status.APPROVED)) {
            throw new IllegalStateException(
                    "A refill has already been approved for this order. Open a support ticket if"
                            + " more dropped.");
        }

        // 7) Existing in-flight (CHECKING or PENDING)? Idempotent — return it rather than 409.
        Optional<RefillRequest> existing =
                refillRequestRepository.findFirstByOrderIdAndStatusInOrderByCreatedAtDesc(
                        orderId, ACTIVE_STATUSES);
        if (existing.isPresent()) {
            return existing.get();
        }

        // 8) Insert a CHECKING request. The auto-scheduler picks it up and runs the bot drop-check.
        //    The active-set partial unique index can still race us between findFirst and save under
        //    extreme concurrency — surface a 409 and let the client retry (the IF-check above wins
        //    immediately on retry).
        RefillRequest req =
                RefillRequest.builder()
                        .orderId(orderId)
                        .userId(user.getId())
                        .status(RefillRequest.Status.CHECKING)
                        .userNote(trimToNull(userNote))
                        .build();
        try {
            req = refillRequestRepository.save(req);
        } catch (DataIntegrityViolationException e) {
            log.info("Concurrent refill request on order {} — caller should retry", orderId);
            throw new IllegalStateException(
                    "A refill request was just created for this order — refresh and try again");
        }

        log.info(
                "Refill request created (CHECKING): order={}, user={}, request={}",
                orderId,
                user.getId(),
                req.getId());
        return req;
    }

    /** Latest non-terminal request (or the most recent terminal one) for the given order. */
    @Transactional(readOnly = true)
    public Optional<RefillRequestResponse> getMyForOrder(Long orderId) {
        User user = currentUser();
        List<RefillRequest> rows =
                refillRequestRepository.findByOrderIdOrderByCreatedAtDesc(orderId);
        return rows.stream()
                .filter(r -> r.getUserId().equals(user.getId()))
                .findFirst()
                .map(RefillRequestResponse::from);
    }

    @Transactional(readOnly = true)
    public List<RefillRequestResponse> listMy() {
        User user = currentUser();
        return refillRequestRepository.findByUserIdOrderByCreatedAtDesc(user.getId()).stream()
                .map(RefillRequestResponse::from)
                .toList();
    }

    // ---------------------------------------------------------------------
    // Auto-check orchestration (called by RefillRequestAutoScheduler)
    // ---------------------------------------------------------------------

    /** In-flight CHECKING requests for the scheduler to advance, oldest first. */
    @Transactional(readOnly = true)
    public List<RefillRequest> listChecking() {
        return refillRequestRepository.findByStatusOrderByCreatedAtAsc(
                RefillRequest.Status.CHECKING);
    }

    /** Bind a freshly-started drop-check to a CHECKING request and count the attempt. No-op if the
     * request is no longer CHECKING (race with another tick / admin action). */
    @Transactional
    public void bindCheck(Long requestId, Long checkId) {
        RefillRequest req = refillRequestRepository.findById(requestId).orElse(null);
        if (req == null || req.getStatus() != RefillRequest.Status.CHECKING) return;
        req.setBotCheckId(checkId);
        req.setCheckAttempts((req.getCheckAttempts() == null ? 0 : req.getCheckAttempts()) + 1);
        refillRequestRepository.save(req);
    }

    /**
     * Finalize a CHECKING request from its bound, completed drop-check. Idempotent / safe to call
     * speculatively: no-op unless the request is still CHECKING and its bound check is DONE.
     */
    @Transactional
    public void finalizeFromCheck(Long requestId) {
        RefillRequest req = refillRequestRepository.findById(requestId).orElse(null);
        if (req == null || req.getStatus() != RefillRequest.Status.CHECKING) return;
        if (req.getBotCheckId() == null) return;
        RefillCheck c = refillCheckService.findCheckById(req.getBotCheckId()).orElse(null);
        if (c == null || c.getStatus() != RefillCheck.Status.DONE) return;

        int needed = c.getRefillNeeded() == null ? 0 : Math.max(0, c.getRefillNeeded());
        req.setBotRefillNeeded(needed);
        req.setBotDropped(c.getDropped());
        req.setBotDropRate(c.getDropRate());
        req.setBotCurrentCount(c.getCurrentCount());
        req.setBotEarlyStopped(c.getEarlyStopped());
        req.setBotCheckedAt(c.getCheckedAt());

        if (needed > 0) {
            req.setStatus(RefillRequest.Status.PENDING);
            refillRequestRepository.save(req);
            log.info(
                    "Refill request {} (order {}) → PENDING — drop {} ({}%)",
                    requestId, req.getOrderId(), needed, c.getDropRate());
        } else {
            req.setStatus(RefillRequest.Status.NO_DROP);
            req.setDecidedAt(LocalDateTime.now());
            refillRequestRepository.save(req);
            log.info(
                    "Refill request {} (order {}) → NO_DROP — nothing dropped",
                    requestId, req.getOrderId());
        }
    }

    /** Terminate a CHECKING request that couldn't be auto-checked. No-op if it already moved on. */
    @Transactional
    public void failRequest(Long requestId, String reason) {
        RefillRequest req = refillRequestRepository.findById(requestId).orElse(null);
        if (req == null || req.getStatus() != RefillRequest.Status.CHECKING) return;
        req.setStatus(RefillRequest.Status.FAILED);
        req.setRejectionReason(trimToNull(reason));
        req.setDecidedAt(LocalDateTime.now());
        refillRequestRepository.save(req);
        log.info("Refill request {} (order {}) → FAILED — {}", requestId, req.getOrderId(), reason);
    }

    // ---------------------------------------------------------------------
    // Admin-facing — controllers must enforce role gates
    // ---------------------------------------------------------------------

    @Transactional(readOnly = true)
    public org.springframework.data.domain.Page<RefillRequestResponse> adminList(
            RefillRequest.Status status, org.springframework.data.domain.Pageable pageable) {
        return refillRequestRepository
                .adminSearch(status, pageable)
                .map(RefillRequestResponse::from);
    }

    @Transactional(readOnly = true)
    public RefillRequestResponse adminGet(Long requestId) {
        return RefillRequestResponse.from(
                refillRequestRepository
                        .findById(requestId)
                        .orElseThrow(() -> new ResourceNotFoundException("Request not found")));
    }

    @Transactional(readOnly = true)
    public long countPending() {
        return refillRequestRepository.countByStatus(RefillRequest.Status.PENDING);
    }

    /**
     * Admin approves a pending request. Delegates to {@link OrderRefillService#createRefill}; if
     * that throws (order ineligible, view count error, etc.) the whole transaction rolls back and
     * the request stays PENDING — admin can retry after fixing the underlying state.
     */
    @Transactional
    public RefillRequestResponse approve(Long requestId) {
        User admin = currentUser();
        RefillRequest req =
                refillRequestRepository
                        .findById(requestId)
                        .orElseThrow(() -> new ResourceNotFoundException("Request not found"));

        if (req.getStatus() != RefillRequest.Status.PENDING) {
            throw new IllegalStateException("Request already " + req.getStatus());
        }

        // Drop-based: when the request carries a bot-checked drop amount, re-deliver exactly that
        // (not the whole order). Falls back to the legacy quantity when no check was bound.
        RefillResponse refill =
                req.getBotRefillNeeded() != null
                        ? orderRefillService.createRefill(
                                req.getOrderId(), req.getBotRefillNeeded())
                        : orderRefillService.createRefill(req.getOrderId());

        req.setStatus(RefillRequest.Status.APPROVED);
        req.setAdminId(admin.getId());
        req.setDecidedAt(LocalDateTime.now());
        req.setRefillId(refill.getRefillId());
        req.setRefillOrderId(refill.getRefillOrderId());
        refillRequestRepository.save(req);

        log.info(
                "Refill request {} APPROVED by admin {} → refill_order={}",
                requestId,
                admin.getId(),
                refill.getRefillOrderId());
        return RefillRequestResponse.from(req);
    }

    @Transactional
    public RefillRequestResponse reject(Long requestId, String reason) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("Rejection reason is required");
        }
        User admin = currentUser();
        RefillRequest req =
                refillRequestRepository
                        .findById(requestId)
                        .orElseThrow(() -> new ResourceNotFoundException("Request not found"));

        if (req.getStatus() != RefillRequest.Status.PENDING) {
            throw new IllegalStateException("Request already " + req.getStatus());
        }

        req.setStatus(RefillRequest.Status.REJECTED);
        req.setRejectionReason(reason.trim());
        req.setAdminId(admin.getId());
        req.setDecidedAt(LocalDateTime.now());
        refillRequestRepository.save(req);

        log.info("Refill request {} REJECTED by admin {}", requestId, admin.getId());
        return RefillRequestResponse.from(req);
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    private User currentUser() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth.getName() == null) {
            throw new AccessDeniedException("Not authenticated");
        }
        return userRepository
                .findByUsername(auth.getName())
                .orElseThrow(() -> new UserNotFoundException("User not found"));
    }

    private static String trimToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
