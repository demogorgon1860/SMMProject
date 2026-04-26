package com.smmpanel.service.refill;

import com.smmpanel.dto.refill.RefillRequestResponse;
import com.smmpanel.dto.refill.RefillResponse;
import com.smmpanel.entity.Order;
import com.smmpanel.entity.OrderStatus;
import com.smmpanel.entity.RefillRequest;
import com.smmpanel.entity.User;
import com.smmpanel.exception.ResourceNotFoundException;
import com.smmpanel.exception.UserNotFoundException;
import com.smmpanel.repository.jpa.OrderRepository;
import com.smmpanel.repository.jpa.RefillRequestRepository;
import com.smmpanel.repository.jpa.UserRepository;
import java.time.Duration;
import java.time.LocalDateTime;
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
 * User-initiated refill request flow with admin approval.
 *
 * <p>The flow:
 *
 * <ol>
 *   <li>User clicks "Request refill" on a COMPLETED or PARTIAL order within the refill window.
 *   <li>{@link #createRequest} validates ownership / status / window / no-duplicates and inserts a
 *       PENDING row.
 *   <li>Admin sees the queue at {@code /admin/refill-requests}, then either:
 *       <ul>
 *         <li>{@link #approve} → delegates to {@link OrderRefillService#createRefill} (which has
 *             its own pessimistic lock, max-refill cap and idempotency window). On success the
 *             request transitions to APPROVED with a back-reference to the new refill order.
 *         <li>{@link #reject} → records a customer-facing reason and transitions to REJECTED. The
 *             user can request again later (admin's discretion).
 *       </ul>
 * </ol>
 *
 * <p>Concurrency: a partial-unique index on {@code (order_id) WHERE status='PENDING'} blocks two
 * concurrent inserts on the same order; the service catches the resulting integrity violation and
 * returns the existing PENDING row, so the user-side double-click is naturally idempotent.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RefillRequestService {

    private final RefillRequestRepository refillRequestRepository;
    private final OrderRepository orderRepository;
    private final UserRepository userRepository;
    private final OrderRefillService orderRefillService;

    /** Refill window measured from {@code Order.updatedAt} (= order completion time). */
    @Value("${app.refill.window-days:30}")
    private int refillWindowDays;

    // ---------------------------------------------------------------------
    // User-facing
    // ---------------------------------------------------------------------

    @Transactional
    public RefillRequestResponse createRequest(Long orderId, String userNote) {
        User user = currentUser();

        Order order =
                orderRepository
                        .findById(orderId)
                        .orElseThrow(() -> new ResourceNotFoundException("Order not found"));

        // 1) Ownership — return 404 not 403, so we don't leak that the order exists.
        if (order.getUser() == null || !order.getUser().getId().equals(user.getId())) {
            throw new ResourceNotFoundException("Order not found");
        }

        // 2) Eligibility window — only completed / partial orders make sense to refill.
        OrderStatus status = order.getStatus();
        if (status != OrderStatus.COMPLETED && status != OrderStatus.PARTIAL) {
            throw new IllegalStateException(
                    "Only completed or partial orders are eligible for refill (current: "
                            + status
                            + ")");
        }

        // 3) Time window from completion. Using updatedAt as a proxy for "completed at" — Order
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

        // 4) Already approved? No double-refill through this flow — admin can still create a
        //    direct refill via /api/v2/admin/orders/{id}/refill if needed.
        if (refillRequestRepository.existsByOrderIdAndStatus(
                orderId, RefillRequest.Status.APPROVED)) {
            throw new IllegalStateException(
                    "A refill has already been approved for this order. Open a support ticket if"
                            + " more views dropped.");
        }

        // 5) Existing pending? Idempotent — return the already-pending request rather than 409.
        Optional<RefillRequest> existing =
                refillRequestRepository.findFirstByOrderIdAndStatus(
                        orderId, RefillRequest.Status.PENDING);
        if (existing.isPresent()) {
            return RefillRequestResponse.from(existing.get());
        }

        // 6) Insert. The partial unique index can still race us between findFirst and save under
        //    extreme concurrency. We do NOT attempt to recover inside this @Transactional —
        //    Postgres marks the whole tx as aborted after a constraint violation, so any
        //    follow-up read in the same tx would itself error out. Instead, surface a 409 and
        //    let the client retry. On retry the IF-check above wins immediately and returns
        //    the existing PENDING idempotently.
        RefillRequest req =
                RefillRequest.builder()
                        .orderId(orderId)
                        .userId(user.getId())
                        .status(RefillRequest.Status.PENDING)
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
                "Refill request created: order={}, user={}, request={}",
                orderId,
                user.getId(),
                req.getId());
        return RefillRequestResponse.from(req);
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

        RefillResponse refill = orderRefillService.createRefill(req.getOrderId());

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
