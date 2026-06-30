package com.smmpanel.service.refill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.smmpanel.dto.refill.RefillBatchResponse;
import com.smmpanel.dto.refill.RefillRequestResponse;
import com.smmpanel.dto.refill.RefillResponse;
import com.smmpanel.entity.Order;
import com.smmpanel.entity.OrderStatus;
import com.smmpanel.entity.RefillCheck;
import com.smmpanel.entity.RefillRequest;
import com.smmpanel.entity.User;
import com.smmpanel.exception.ResourceNotFoundException;
import com.smmpanel.repository.jpa.OrderRepository;
import com.smmpanel.repository.jpa.RefillRequestRepository;
import com.smmpanel.repository.jpa.UserRepository;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Pure-Mockito coverage for {@link RefillRequestService}'s automatic-check flow: createRequest now
 * inserts a CHECKING row, the scheduler hooks (bindCheck / finalizeFromCheck / failRequest) drive
 * the state machine, and the admin approve/reject transitions still size the refill from the
 * bot-checked dropped amount.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RefillRequestServiceTest {

    private static final long ORDER_ID = 100L;
    private static final long REQUEST_ID = 200L;
    private static final long USER_ID = 7L;
    private static final long ADMIN_ID = 9L;

    @Mock private RefillRequestRepository refillRequestRepository;
    @Mock private OrderRepository orderRepository;
    @Mock private UserRepository userRepository;
    @Mock private OrderRefillService orderRefillService;
    @Mock private RefillCheckService refillCheckService;

    @InjectMocks private RefillRequestService service;

    private User user;
    private User admin;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "refillWindowDays", 30);
        user = User.builder().id(USER_ID).username("user").build();
        admin = User.builder().id(ADMIN_ID).username("admin").build();
        authenticateAs("user");
        when(userRepository.findByUsername("user")).thenReturn(Optional.of(user));
        // No in-flight request by default.
        when(refillRequestRepository.findFirstByOrderIdAndStatusInOrderByCreatedAtDesc(
                        anyLong(), any()))
                .thenReturn(Optional.empty());
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private static void authenticateAs(String username) {
        SecurityContextHolder.getContext()
                .setAuthentication(
                        new UsernamePasswordAuthenticationToken(
                                username,
                                "n/a",
                                Collections.singletonList(
                                        new SimpleGrantedAuthority("ROLE_USER"))));
    }

    private static Order completedOrder(long id, User owner) {
        Order o = new Order();
        o.setId(id);
        o.setUser(owner);
        o.setStatus(OrderStatus.COMPLETED);
        o.setUpdatedAt(LocalDateTime.now().minusDays(1));
        o.setCreatedAt(LocalDateTime.now().minusDays(2));
        return o;
    }

    private void stubEligibleOrder(Order order) {
        when(orderRepository.findByIdWithAllDetails(order.getId())).thenReturn(Optional.of(order));
        when(refillRequestRepository.existsByOrderIdAndStatus(
                        order.getId(), RefillRequest.Status.APPROVED))
                .thenReturn(false);
        when(refillRequestRepository.save(any(RefillRequest.class)))
                .thenAnswer(
                        inv -> {
                            RefillRequest r = inv.getArgument(0);
                            if (r.getId() == null) r.setId(REQUEST_ID);
                            return r;
                        });
    }

    // -----------------------------------------------------------------
    // createRequest — now inserts CHECKING (no bot call on the request path)
    // -----------------------------------------------------------------

    @Test
    @DisplayName("createRequest: happy path inserts a CHECKING request and returns it")
    void createRequest_happy_checking() {
        Order order = completedOrder(ORDER_ID, user);
        stubEligibleOrder(order);

        RefillRequestResponse resp = service.createRequest(ORDER_ID, "  ran low  ");

        assertThat(resp.getId()).isEqualTo(REQUEST_ID);
        assertThat(resp.getOrderId()).isEqualTo(ORDER_ID);
        assertThat(resp.getUserId()).isEqualTo(USER_ID);
        assertThat(resp.getStatus()).isEqualTo("CHECKING");
        assertThat(resp.getUserNote()).isEqualTo("ran low");
        verify(refillCheckService).assertSingleActionService(order);
        verify(refillRequestRepository, times(1)).save(any(RefillRequest.class));
    }

    @Test
    @DisplayName("createRequest: returns the existing in-flight request (idempotent on re-submit)")
    void createRequest_idempotent_existing_active() {
        Order order = completedOrder(ORDER_ID, user);
        when(orderRepository.findByIdWithAllDetails(ORDER_ID)).thenReturn(Optional.of(order));
        when(refillRequestRepository.existsByOrderIdAndStatus(
                        ORDER_ID, RefillRequest.Status.APPROVED))
                .thenReturn(false);
        RefillRequest existing =
                RefillRequest.builder()
                        .id(REQUEST_ID)
                        .orderId(ORDER_ID)
                        .userId(USER_ID)
                        .status(RefillRequest.Status.CHECKING)
                        .build();
        when(refillRequestRepository.findFirstByOrderIdAndStatusInOrderByCreatedAtDesc(
                        eq(ORDER_ID), any()))
                .thenReturn(Optional.of(existing));

        RefillRequestResponse resp = service.createRequest(ORDER_ID, null);

        assertThat(resp.getId()).isEqualTo(REQUEST_ID);
        assertThat(resp.getStatus()).isEqualTo("CHECKING");
        verify(refillRequestRepository, never()).save(any());
    }

    @Test
    @DisplayName("createRequest: 404 (not 403) when order belongs to another user")
    void createRequest_ownership_check_returns_notfound() {
        Order order = completedOrder(ORDER_ID, User.builder().id(USER_ID + 1).build());
        when(orderRepository.findByIdWithAllDetails(ORDER_ID)).thenReturn(Optional.of(order));

        assertThatThrownBy(() -> service.createRequest(ORDER_ID, null))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(refillRequestRepository, never()).save(any());
    }

    @Test
    @DisplayName("createRequest: rejects orders that are neither COMPLETED nor PARTIAL")
    void createRequest_rejects_inflight_orders() {
        Order order = completedOrder(ORDER_ID, user);
        order.setStatus(OrderStatus.IN_PROGRESS);
        when(orderRepository.findByIdWithAllDetails(ORDER_ID)).thenReturn(Optional.of(order));

        assertThatThrownBy(() -> service.createRequest(ORDER_ID, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("eligible for refill");
    }

    @Test
    @DisplayName("createRequest: rejects a refill-of-a-refill")
    void createRequest_rejects_refill_order() {
        Order order = completedOrder(ORDER_ID, user);
        order.setIsRefill(true);
        when(orderRepository.findByIdWithAllDetails(ORDER_ID)).thenReturn(Optional.of(order));

        assertThatThrownBy(() -> service.createRequest(ORDER_ID, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("refill order");
    }

    @Test
    @DisplayName("createRequest: rejects orders past the refill window (when a window is set)")
    void createRequest_rejects_outside_window() {
        Order order = completedOrder(ORDER_ID, user);
        order.setUpdatedAt(LocalDateTime.now().minusDays(31));
        when(orderRepository.findByIdWithAllDetails(ORDER_ID)).thenReturn(Optional.of(order));

        assertThatThrownBy(() -> service.createRequest(ORDER_ID, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Refill window expired");
    }

    @Test
    @DisplayName("createRequest: window-days = 0 → lifetime refill, even a very old order is accepted")
    void createRequest_unlimited_window_accepts_old_order() {
        ReflectionTestUtils.setField(service, "refillWindowDays", 0); // unlimited
        Order order = completedOrder(ORDER_ID, user);
        order.setUpdatedAt(LocalDateTime.now().minusDays(400)); // long past any 30-day window
        order.setCreatedAt(LocalDateTime.now().minusDays(420));
        stubEligibleOrder(order);

        RefillRequestResponse resp = service.createRequest(ORDER_ID, null);

        assertThat(resp.getStatus()).isEqualTo("CHECKING");
    }

    @Test
    @DisplayName("createRequest: rejects combo services (single-action only)")
    void createRequest_rejects_combo_service() {
        Order order = completedOrder(ORDER_ID, user);
        when(orderRepository.findByIdWithAllDetails(ORDER_ID)).thenReturn(Optional.of(order));
        doThrow(new IllegalStateException("Automatic refill is available only for single-action"))
                .when(refillCheckService)
                .assertSingleActionService(order);

        assertThatThrownBy(() -> service.createRequest(ORDER_ID, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("single-action");
        verify(refillRequestRepository, never()).save(any());
    }

    @Test
    @DisplayName("createRequest: rejects when an APPROVED refill already exists for the order")
    void createRequest_rejects_when_already_approved() {
        Order order = completedOrder(ORDER_ID, user);
        when(orderRepository.findByIdWithAllDetails(ORDER_ID)).thenReturn(Optional.of(order));
        when(refillRequestRepository.existsByOrderIdAndStatus(
                        ORDER_ID, RefillRequest.Status.APPROVED))
                .thenReturn(true);

        assertThatThrownBy(() -> service.createRequest(ORDER_ID, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already been approved");
    }

    @Test
    @DisplayName("createRequest: surfaces 409 when concurrent insert hits the active unique index")
    void createRequest_concurrent_insert_recovers() {
        Order order = completedOrder(ORDER_ID, user);
        when(orderRepository.findByIdWithAllDetails(ORDER_ID)).thenReturn(Optional.of(order));
        when(refillRequestRepository.existsByOrderIdAndStatus(
                        ORDER_ID, RefillRequest.Status.APPROVED))
                .thenReturn(false);
        when(refillRequestRepository.save(any(RefillRequest.class)))
                .thenThrow(new DataIntegrityViolationException("active unique"));

        assertThatThrownBy(() -> service.createRequest(ORDER_ID, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("just created");
    }

    @Test
    @DisplayName("createRequest: 404 when the order id doesn't exist")
    void createRequest_404_on_unknown_order() {
        when(orderRepository.findByIdWithAllDetails(ORDER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.createRequest(ORDER_ID, null))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("createRequest: AccessDenied when no auth context is set")
    void createRequest_unauthenticated_rejected() {
        SecurityContextHolder.clearContext();

        assertThatThrownBy(() -> service.createRequest(ORDER_ID, null))
                .isInstanceOf(AccessDeniedException.class);
    }

    // -----------------------------------------------------------------
    // createRequests — batch
    // -----------------------------------------------------------------

    @Test
    @DisplayName("createRequests: one bad id never fails the batch; per-order outcomes returned")
    void createRequests_batch_mixed() {
        Order ok = completedOrder(ORDER_ID, user);
        when(orderRepository.findByIdWithAllDetails(ORDER_ID)).thenReturn(Optional.of(ok));
        when(refillRequestRepository.existsByOrderIdAndStatus(
                        ORDER_ID, RefillRequest.Status.APPROVED))
                .thenReturn(false);
        when(orderRepository.findByIdWithAllDetails(404L)).thenReturn(Optional.empty());
        when(refillRequestRepository.save(any(RefillRequest.class)))
                .thenAnswer(
                        inv -> {
                            RefillRequest r = inv.getArgument(0);
                            if (r.getId() == null) r.setId(REQUEST_ID);
                            return r;
                        });

        RefillBatchResponse resp = service.createRequests(List.of(ORDER_ID, 404L, ORDER_ID), "note");

        // ORDER_ID de-duplicated → 2 distinct ids processed.
        assertThat(resp.getResults()).hasSize(2);
        assertThat(resp.getAccepted()).isEqualTo(1);
        assertThat(resp.getRejected()).isEqualTo(1);
        RefillBatchResponse.Item okItem =
                resp.getResults().stream()
                        .filter(i -> i.getOrderId().equals(ORDER_ID))
                        .findFirst()
                        .orElseThrow();
        assertThat(okItem.isAccepted()).isTrue();
        assertThat(okItem.getStatus()).isEqualTo("CHECKING");
        RefillBatchResponse.Item badItem =
                resp.getResults().stream()
                        .filter(i -> i.getOrderId().equals(404L))
                        .findFirst()
                        .orElseThrow();
        assertThat(badItem.isAccepted()).isFalse();
        assertThat(badItem.getMessage()).contains("not found");
    }

    // -----------------------------------------------------------------
    // scheduler hooks: bindCheck / finalizeFromCheck / failRequest
    // -----------------------------------------------------------------

    @Test
    @DisplayName("bindCheck: binds the check id and increments the attempt counter")
    void bindCheck_increments() {
        RefillRequest req =
                RefillRequest.builder()
                        .id(REQUEST_ID)
                        .orderId(ORDER_ID)
                        .status(RefillRequest.Status.CHECKING)
                        .checkAttempts(0)
                        .build();
        when(refillRequestRepository.findById(REQUEST_ID)).thenReturn(Optional.of(req));
        when(refillRequestRepository.save(any(RefillRequest.class)))
                .thenAnswer(i -> i.getArgument(0));

        service.bindCheck(REQUEST_ID, 55L);

        assertThat(req.getBotCheckId()).isEqualTo(55L);
        assertThat(req.getCheckAttempts()).isEqualTo(1);
    }

    @Test
    @DisplayName("bindCheck: no-op when the request already left CHECKING")
    void bindCheck_noop_when_not_checking() {
        RefillRequest req =
                RefillRequest.builder()
                        .id(REQUEST_ID)
                        .status(RefillRequest.Status.PENDING)
                        .build();
        when(refillRequestRepository.findById(REQUEST_ID)).thenReturn(Optional.of(req));

        service.bindCheck(REQUEST_ID, 55L);

        verify(refillRequestRepository, never()).save(any());
    }

    @Test
    @DisplayName("finalizeFromCheck: DONE check with drop > 0 → PENDING, snapshot copied")
    void finalize_drop_to_pending() {
        RefillRequest req =
                RefillRequest.builder()
                        .id(REQUEST_ID)
                        .orderId(ORDER_ID)
                        .status(RefillRequest.Status.CHECKING)
                        .botCheckId(55L)
                        .build();
        when(refillRequestRepository.findById(REQUEST_ID)).thenReturn(Optional.of(req));
        RefillCheck chk =
                RefillCheck.builder()
                        .id(55L)
                        .orderId(ORDER_ID)
                        .status(RefillCheck.Status.DONE)
                        .refillNeeded(215)
                        .dropped(215)
                        .dropRate(new java.math.BigDecimal("21.50"))
                        .currentCount(785)
                        .checkedAt(LocalDateTime.now())
                        .build();
        when(refillCheckService.findCheckById(55L)).thenReturn(Optional.of(chk));
        when(refillRequestRepository.save(any(RefillRequest.class)))
                .thenAnswer(i -> i.getArgument(0));

        service.finalizeFromCheck(REQUEST_ID);

        assertThat(req.getStatus()).isEqualTo(RefillRequest.Status.PENDING);
        assertThat(req.getBotRefillNeeded()).isEqualTo(215);
        assertThat(req.getBotDropRate()).isEqualByComparingTo("21.50");
        assertThat(req.getDecidedAt()).isNull(); // PENDING isn't a decision yet
    }

    @Test
    @DisplayName("finalizeFromCheck: DONE check with zero drop → NO_DROP (auto-closed)")
    void finalize_zero_drop_to_no_drop() {
        RefillRequest req =
                RefillRequest.builder()
                        .id(REQUEST_ID)
                        .orderId(ORDER_ID)
                        .status(RefillRequest.Status.CHECKING)
                        .botCheckId(56L)
                        .build();
        when(refillRequestRepository.findById(REQUEST_ID)).thenReturn(Optional.of(req));
        when(refillCheckService.findCheckById(56L))
                .thenReturn(
                        Optional.of(
                                RefillCheck.builder()
                                        .id(56L)
                                        .orderId(ORDER_ID)
                                        .status(RefillCheck.Status.DONE)
                                        .refillNeeded(0)
                                        .build()));
        when(refillRequestRepository.save(any(RefillRequest.class)))
                .thenAnswer(i -> i.getArgument(0));

        service.finalizeFromCheck(REQUEST_ID);

        assertThat(req.getStatus()).isEqualTo(RefillRequest.Status.NO_DROP);
        assertThat(req.getDecidedAt()).isNotNull();
    }

    @Test
    @DisplayName("finalizeFromCheck: bound check still RUNNING → no transition")
    void finalize_running_noop() {
        RefillRequest req =
                RefillRequest.builder()
                        .id(REQUEST_ID)
                        .status(RefillRequest.Status.CHECKING)
                        .botCheckId(57L)
                        .build();
        when(refillRequestRepository.findById(REQUEST_ID)).thenReturn(Optional.of(req));
        when(refillCheckService.findCheckById(57L))
                .thenReturn(
                        Optional.of(
                                RefillCheck.builder()
                                        .id(57L)
                                        .status(RefillCheck.Status.RUNNING)
                                        .build()));

        service.finalizeFromCheck(REQUEST_ID);

        assertThat(req.getStatus()).isEqualTo(RefillRequest.Status.CHECKING);
        verify(refillRequestRepository, never()).save(any());
    }

    @Test
    @DisplayName("failRequest: CHECKING → FAILED with reason")
    void failRequest_marks_failed() {
        RefillRequest req =
                RefillRequest.builder()
                        .id(REQUEST_ID)
                        .orderId(ORDER_ID)
                        .status(RefillRequest.Status.CHECKING)
                        .build();
        when(refillRequestRepository.findById(REQUEST_ID)).thenReturn(Optional.of(req));
        when(refillRequestRepository.save(any(RefillRequest.class)))
                .thenAnswer(i -> i.getArgument(0));

        service.failRequest(REQUEST_ID, "Couldn't verify the drop");

        assertThat(req.getStatus()).isEqualTo(RefillRequest.Status.FAILED);
        assertThat(req.getRejectionReason()).isEqualTo("Couldn't verify the drop");
        assertThat(req.getDecidedAt()).isNotNull();
    }

    // -----------------------------------------------------------------
    // approve / reject
    // -----------------------------------------------------------------

    @Test
    @DisplayName("approve: PENDING with drop amount → refills ONLY the dropped amount")
    void approve_uses_dropped_amount() {
        authenticateAs("admin");
        when(userRepository.findByUsername("admin")).thenReturn(Optional.of(admin));
        RefillRequest req =
                RefillRequest.builder()
                        .id(REQUEST_ID)
                        .orderId(ORDER_ID)
                        .userId(USER_ID)
                        .status(RefillRequest.Status.PENDING)
                        .botRefillNeeded(215)
                        .build();
        when(refillRequestRepository.findById(REQUEST_ID)).thenReturn(Optional.of(req));
        when(orderRefillService.createRefill(ORDER_ID, 215))
                .thenReturn(
                        RefillResponse.builder()
                                .refillId(50L)
                                .refillOrderId(500L)
                                .refillQuantity(215)
                                .build());
        when(refillRequestRepository.save(any(RefillRequest.class)))
                .thenAnswer(i -> i.getArgument(0));

        RefillRequestResponse resp = service.approve(REQUEST_ID);

        assertThat(resp.getStatus()).isEqualTo("APPROVED");
        assertThat(resp.getAdminId()).isEqualTo(ADMIN_ID);
        assertThat(resp.getRefillOrderId()).isEqualTo(500L);
        verify(orderRefillService, times(1)).createRefill(ORDER_ID, 215);
        verify(orderRefillService, never()).createRefill(ORDER_ID);
    }

    @Test
    @DisplayName("approve: legacy PENDING without a drop amount falls back to whole-order refill")
    void approve_legacy_whole_order() {
        authenticateAs("admin");
        when(userRepository.findByUsername("admin")).thenReturn(Optional.of(admin));
        RefillRequest req =
                RefillRequest.builder()
                        .id(REQUEST_ID)
                        .orderId(ORDER_ID)
                        .userId(USER_ID)
                        .status(RefillRequest.Status.PENDING)
                        .build();
        when(refillRequestRepository.findById(REQUEST_ID)).thenReturn(Optional.of(req));
        when(orderRefillService.createRefill(ORDER_ID))
                .thenReturn(RefillResponse.builder().refillId(50L).refillOrderId(500L).build());
        when(refillRequestRepository.save(any(RefillRequest.class)))
                .thenAnswer(i -> i.getArgument(0));

        RefillRequestResponse resp = service.approve(REQUEST_ID);

        assertThat(resp.getStatus()).isEqualTo("APPROVED");
        verify(orderRefillService, times(1)).createRefill(ORDER_ID);
    }

    @Test
    @DisplayName("approve: refuses anything not PENDING (CHECKING / NO_DROP / already decided)")
    void approve_only_pending() {
        authenticateAs("admin");
        when(userRepository.findByUsername("admin")).thenReturn(Optional.of(admin));
        RefillRequest req =
                RefillRequest.builder()
                        .id(REQUEST_ID)
                        .status(RefillRequest.Status.CHECKING)
                        .build();
        when(refillRequestRepository.findById(REQUEST_ID)).thenReturn(Optional.of(req));

        assertThatThrownBy(() -> service.approve(REQUEST_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already");
        verify(orderRefillService, never()).createRefill(anyLong());
    }

    @Test
    @DisplayName("approve: rolls back (no save) if createRefill blows up")
    void approve_rolls_back_on_refill_failure() {
        authenticateAs("admin");
        when(userRepository.findByUsername("admin")).thenReturn(Optional.of(admin));
        RefillRequest req =
                RefillRequest.builder()
                        .id(REQUEST_ID)
                        .orderId(ORDER_ID)
                        .status(RefillRequest.Status.PENDING)
                        .build();
        when(refillRequestRepository.findById(REQUEST_ID)).thenReturn(Optional.of(req));
        when(orderRefillService.createRefill(ORDER_ID))
                .thenThrow(new IllegalStateException("ineligible"));

        assertThatThrownBy(() -> service.approve(REQUEST_ID))
                .isInstanceOf(IllegalStateException.class);
        verify(refillRequestRepository, never()).save(any());
    }

    @Test
    @DisplayName("reject: PENDING → REJECTED, requires reason, records reason verbatim")
    void reject_records_reason() {
        authenticateAs("admin");
        when(userRepository.findByUsername("admin")).thenReturn(Optional.of(admin));
        RefillRequest req =
                RefillRequest.builder().id(REQUEST_ID).status(RefillRequest.Status.PENDING).build();
        when(refillRequestRepository.findById(REQUEST_ID)).thenReturn(Optional.of(req));
        when(refillRequestRepository.save(any(RefillRequest.class)))
                .thenAnswer(i -> i.getArgument(0));

        RefillRequestResponse resp = service.reject(REQUEST_ID, "  promo abuse  ");

        assertThat(resp.getStatus()).isEqualTo("REJECTED");
        assertThat(resp.getRejectionReason()).isEqualTo("promo abuse");
        assertThat(req.getAdminId()).isEqualTo(ADMIN_ID);
    }

    @Test
    @DisplayName("reject: blank reason is rejected")
    void reject_requires_reason() {
        assertThatThrownBy(() -> service.reject(REQUEST_ID, "   "))
                .isInstanceOf(IllegalArgumentException.class);
        verify(refillRequestRepository, never()).findById(eq(REQUEST_ID));
    }
}
