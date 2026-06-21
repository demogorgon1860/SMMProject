package com.smmpanel.service.refill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.smmpanel.dto.refill.RefillRequestResponse;
import com.smmpanel.dto.refill.RefillResponse;
import com.smmpanel.entity.Order;
import com.smmpanel.entity.OrderStatus;
import com.smmpanel.entity.RefillCheck;
import com.smmpanel.entity.RefillRequest;
import com.smmpanel.entity.User;
import com.smmpanel.exception.ResourceNotFoundException;
import com.smmpanel.repository.jpa.OrderRepository;
import com.smmpanel.repository.jpa.RefillCheckRepository;
import com.smmpanel.repository.jpa.RefillRequestRepository;
import com.smmpanel.repository.jpa.UserRepository;
import java.time.LocalDateTime;
import java.util.Collections;
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
 * Pure-Mockito coverage for {@link RefillRequestService}. Verifies the user-side state machine
 * (eligibility window, ownership checks, double-submit idempotency) and the admin-side approve /
 * reject transitions, plus the concurrent-insert recovery branch.
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
    @Mock private RefillCheckRepository refillCheckRepository;

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
        // No prior drop-check by default → legacy whole-order refill path (LENIENT: ignored when
        // a test returns before the snapshot-binding step).
        when(refillCheckRepository.findFirstByOrderIdAndStatusOrderByRequestedAtDesc(
                        anyLong(), any(RefillCheck.Status.class)))
                .thenReturn(Optional.empty());
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private static void authenticateAs(String username) {
        // 3-arg ctor sets isAuthenticated = true; the 2-arg one does not.
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

    // -----------------------------------------------------------------
    // createRequest
    // -----------------------------------------------------------------

    @Test
    @DisplayName("createRequest: happy path inserts PENDING and returns it")
    void createRequest_happy() {
        Order order = completedOrder(ORDER_ID, user);
        when(orderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order));
        when(refillRequestRepository.existsByOrderIdAndStatus(
                        ORDER_ID, RefillRequest.Status.APPROVED))
                .thenReturn(false);
        when(refillRequestRepository.findFirstByOrderIdAndStatus(
                        ORDER_ID, RefillRequest.Status.PENDING))
                .thenReturn(Optional.empty());
        when(refillRequestRepository.save(any(RefillRequest.class)))
                .thenAnswer(
                        inv -> {
                            RefillRequest r = inv.getArgument(0);
                            r.setId(REQUEST_ID);
                            return r;
                        });

        RefillRequestResponse resp = service.createRequest(ORDER_ID, "  ran low  ");

        assertThat(resp.getId()).isEqualTo(REQUEST_ID);
        assertThat(resp.getOrderId()).isEqualTo(ORDER_ID);
        assertThat(resp.getUserId()).isEqualTo(USER_ID);
        assertThat(resp.getStatus()).isEqualTo("PENDING");
        assertThat(resp.getUserNote()).isEqualTo("ran low");
        verify(refillRequestRepository, times(1)).save(any(RefillRequest.class));
    }

    @Test
    @DisplayName("createRequest: returns the existing PENDING (idempotent on user double-click)")
    void createRequest_idempotent_existingPending() {
        Order order = completedOrder(ORDER_ID, user);
        when(orderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order));
        when(refillRequestRepository.existsByOrderIdAndStatus(
                        ORDER_ID, RefillRequest.Status.APPROVED))
                .thenReturn(false);
        RefillRequest existing =
                RefillRequest.builder()
                        .id(REQUEST_ID)
                        .orderId(ORDER_ID)
                        .userId(USER_ID)
                        .status(RefillRequest.Status.PENDING)
                        .build();
        when(refillRequestRepository.findFirstByOrderIdAndStatus(
                        ORDER_ID, RefillRequest.Status.PENDING))
                .thenReturn(Optional.of(existing));

        RefillRequestResponse resp = service.createRequest(ORDER_ID, null);

        assertThat(resp.getId()).isEqualTo(REQUEST_ID);
        assertThat(resp.getStatus()).isEqualTo("PENDING");
        verify(refillRequestRepository, never()).save(any());
    }

    @Test
    @DisplayName("createRequest: 404 (not 403) when order belongs to another user")
    void createRequest_ownership_check_returns_notfound() {
        Order order = completedOrder(ORDER_ID, User.builder().id(USER_ID + 1).build());
        when(orderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order));

        assertThatThrownBy(() -> service.createRequest(ORDER_ID, null))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(refillRequestRepository, never()).save(any());
    }

    @Test
    @DisplayName("createRequest: rejects orders that are neither COMPLETED nor PARTIAL")
    void createRequest_rejects_inflight_orders() {
        Order order = completedOrder(ORDER_ID, user);
        order.setStatus(OrderStatus.IN_PROGRESS);
        when(orderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order));

        assertThatThrownBy(() -> service.createRequest(ORDER_ID, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("eligible for refill");
    }

    @Test
    @DisplayName("createRequest: rejects orders past the refill window")
    void createRequest_rejects_outside_window() {
        Order order = completedOrder(ORDER_ID, user);
        order.setUpdatedAt(LocalDateTime.now().minusDays(31));
        when(orderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order));

        assertThatThrownBy(() -> service.createRequest(ORDER_ID, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Refill window expired");
    }

    @Test
    @DisplayName("createRequest: rejects when an APPROVED refill already exists for the order")
    void createRequest_rejects_when_already_approved() {
        Order order = completedOrder(ORDER_ID, user);
        when(orderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order));
        when(refillRequestRepository.existsByOrderIdAndStatus(
                        ORDER_ID, RefillRequest.Status.APPROVED))
                .thenReturn(true);

        assertThatThrownBy(() -> service.createRequest(ORDER_ID, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already been approved");
    }

    @Test
    @DisplayName("createRequest: surfaces 409 when concurrent insert hits the partial unique index")
    void createRequest_concurrent_insert_recovers() {
        Order order = completedOrder(ORDER_ID, user);
        when(orderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order));
        when(refillRequestRepository.existsByOrderIdAndStatus(
                        ORDER_ID, RefillRequest.Status.APPROVED))
                .thenReturn(false);
        when(refillRequestRepository.findFirstByOrderIdAndStatus(
                        ORDER_ID, RefillRequest.Status.PENDING))
                .thenReturn(Optional.empty());
        when(refillRequestRepository.save(any(RefillRequest.class)))
                .thenThrow(new DataIntegrityViolationException("partial unique"));

        assertThatThrownBy(() -> service.createRequest(ORDER_ID, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("just created");
    }

    @Test
    @DisplayName("createRequest: 404 when the order id doesn't exist")
    void createRequest_404_on_unknown_order() {
        when(orderRepository.findById(ORDER_ID)).thenReturn(Optional.empty());

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
    // approve / reject
    // -----------------------------------------------------------------

    @Test
    @DisplayName("approve: PENDING → APPROVED, attaches refill order id, records admin id")
    void approve_pending_request() {
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
        assertThat(resp.getAdminId()).isEqualTo(ADMIN_ID);
        assertThat(resp.getRefillOrderId()).isEqualTo(500L);
        assertThat(req.getRefillId()).isEqualTo(50L);
        assertThat(req.getDecidedAt()).isNotNull();
    }

    @Test
    @DisplayName("approve: refuses to re-approve an already-approved request")
    void approve_idempotency_guard() {
        authenticateAs("admin");
        when(userRepository.findByUsername("admin")).thenReturn(Optional.of(admin));
        RefillRequest req =
                RefillRequest.builder()
                        .id(REQUEST_ID)
                        .status(RefillRequest.Status.APPROVED)
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

    // -----------------------------------------------------------------
    // drop-based refill (the core behavior change)
    // -----------------------------------------------------------------

    @Test
    @DisplayName(
            "createRequest binds the latest DONE drop-check; approve refills ONLY the dropped"
                    + " amount, not the whole order")
    void createRequest_binds_dropCheck_and_approve_uses_it() {
        Order order = completedOrder(ORDER_ID, user);
        when(orderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order));
        when(refillRequestRepository.existsByOrderIdAndStatus(
                        ORDER_ID, RefillRequest.Status.APPROVED))
                .thenReturn(false);
        when(refillRequestRepository.findFirstByOrderIdAndStatus(
                        ORDER_ID, RefillRequest.Status.PENDING))
                .thenReturn(Optional.empty());
        RefillCheck chk =
                RefillCheck.builder()
                        .id(77L)
                        .orderId(ORDER_ID)
                        .status(RefillCheck.Status.DONE)
                        .refillNeeded(215)
                        .dropped(215)
                        .dropRate(new java.math.BigDecimal("21.50"))
                        .currentCount(785)
                        .checkedAt(LocalDateTime.now())
                        .build();
        when(refillCheckRepository.findFirstByOrderIdAndStatusOrderByRequestedAtDesc(
                        ORDER_ID, RefillCheck.Status.DONE))
                .thenReturn(Optional.of(chk));
        when(refillRequestRepository.save(any(RefillRequest.class)))
                .thenAnswer(
                        inv -> {
                            RefillRequest r = inv.getArgument(0);
                            if (r.getId() == null) r.setId(REQUEST_ID);
                            return r;
                        });

        RefillRequestResponse created = service.createRequest(ORDER_ID, null);
        assertThat(created.getRefillNeeded()).isEqualTo(215);
        assertThat(created.getDropRate()).isEqualByComparingTo("21.50");

        // Approve: must size the refill to 215 (the dropped amount), NOT the whole order.
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

        RefillRequestResponse approved = service.approve(REQUEST_ID);

        assertThat(approved.getStatus()).isEqualTo("APPROVED");
        verify(orderRefillService, times(1)).createRefill(ORDER_ID, 215);
        verify(orderRefillService, never()).createRefill(ORDER_ID);
    }

    @Test
    @DisplayName("createRequest rejects when the bound drop-check found nothing dropped")
    void createRequest_rejects_zeroDrop() {
        Order order = completedOrder(ORDER_ID, user);
        when(orderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order));
        when(refillRequestRepository.existsByOrderIdAndStatus(
                        ORDER_ID, RefillRequest.Status.APPROVED))
                .thenReturn(false);
        when(refillRequestRepository.findFirstByOrderIdAndStatus(
                        ORDER_ID, RefillRequest.Status.PENDING))
                .thenReturn(Optional.empty());
        when(refillCheckRepository.findFirstByOrderIdAndStatusOrderByRequestedAtDesc(
                        ORDER_ID, RefillCheck.Status.DONE))
                .thenReturn(
                        Optional.of(
                                RefillCheck.builder()
                                        .id(78L)
                                        .orderId(ORDER_ID)
                                        .status(RefillCheck.Status.DONE)
                                        .refillNeeded(0)
                                        .build()));

        assertThatThrownBy(() -> service.createRequest(ORDER_ID, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No drop detected");
        verify(refillRequestRepository, never()).save(any());
    }
}
