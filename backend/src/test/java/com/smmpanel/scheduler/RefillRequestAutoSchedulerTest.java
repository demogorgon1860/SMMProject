package com.smmpanel.scheduler;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.smmpanel.entity.Order;
import com.smmpanel.entity.RefillCheck;
import com.smmpanel.entity.RefillRequest;
import com.smmpanel.exception.ApiException;
import com.smmpanel.repository.jpa.OrderRepository;
import com.smmpanel.service.refill.RefillCheckService;
import com.smmpanel.service.refill.RefillRequestService;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

/** Coverage for the auto-check scheduler's per-request decision logic. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RefillRequestAutoSchedulerTest {

    @Mock private RefillRequestService refillRequestService;
    @Mock private RefillCheckService refillCheckService;
    @Mock private OrderRepository orderRepository;

    @InjectMocks private RefillRequestAutoScheduler scheduler;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(scheduler, "maxStartsPerTick", 5);
        ReflectionTestUtils.setField(scheduler, "maxCheckAttempts", 3);
        ReflectionTestUtils.setField(scheduler, "maxAgeMinutes", 45);
    }

    private RefillRequest checking(long id, Long checkId, int attempts, int ageMinutes) {
        RefillRequest r =
                RefillRequest.builder()
                        .id(id)
                        .orderId(1000 + id)
                        .userId(7L)
                        .status(RefillRequest.Status.CHECKING)
                        .botCheckId(checkId)
                        .checkAttempts(attempts)
                        .build();
        r.setCreatedAt(LocalDateTime.now().minusMinutes(ageMinutes));
        return r;
    }

    @Test
    @DisplayName("bound check DONE → finalize, no new start")
    void done_finalizes() {
        RefillRequest req = checking(1L, 55L, 1, 2);
        when(refillRequestService.listChecking()).thenReturn(List.of(req));
        when(refillCheckService.findCheckById(55L))
                .thenReturn(Optional.of(check(RefillCheck.Status.DONE)));

        scheduler.advanceCheckingRequests();

        verify(refillRequestService).finalizeFromCheck(1L);
        verify(refillCheckService, never()).startCheckForAuto(any(), anyLong());
        verify(refillRequestService, never()).failRequest(anyLong(), any());
    }

    @Test
    @DisplayName("bound check RUNNING → wait (nothing happens)")
    void running_waits() {
        RefillRequest req = checking(1L, 55L, 1, 2);
        when(refillRequestService.listChecking()).thenReturn(List.of(req));
        when(refillCheckService.findCheckById(55L))
                .thenReturn(Optional.of(check(RefillCheck.Status.RUNNING)));

        scheduler.advanceCheckingRequests();

        verify(refillRequestService, never()).finalizeFromCheck(anyLong());
        verify(refillCheckService, never()).startCheckForAuto(any(), anyLong());
        verify(refillRequestService, never()).failRequest(anyLong(), any());
    }

    @Test
    @DisplayName("no bound check, young, budget left → starts a check and binds it")
    void no_check_starts_and_binds() {
        RefillRequest req = checking(1L, null, 0, 2);
        when(refillRequestService.listChecking()).thenReturn(List.of(req));
        Order order = new Order();
        order.setId(req.getOrderId());
        when(orderRepository.findByIdWithAllDetails(req.getOrderId()))
                .thenReturn(Optional.of(order));
        RefillCheck started = check(RefillCheck.Status.RUNNING);
        started.setId(99L);
        when(refillCheckService.startCheckForAuto(order, 7L)).thenReturn(started);

        scheduler.advanceCheckingRequests();

        verify(refillCheckService).startCheckForAuto(order, 7L);
        verify(refillRequestService).bindCheck(1L, 99L);
        verify(refillRequestService, never()).failRequest(anyLong(), any());
    }

    @Test
    @DisplayName("bound check FAILED, budget left → restarts a fresh check")
    void failed_check_restarts() {
        RefillRequest req = checking(1L, 55L, 1, 2);
        when(refillRequestService.listChecking()).thenReturn(List.of(req));
        when(refillCheckService.findCheckById(55L))
                .thenReturn(Optional.of(check(RefillCheck.Status.FAILED)));
        Order order = new Order();
        order.setId(req.getOrderId());
        when(orderRepository.findByIdWithAllDetails(req.getOrderId()))
                .thenReturn(Optional.of(order));
        RefillCheck started = check(RefillCheck.Status.RUNNING);
        started.setId(100L);
        when(refillCheckService.startCheckForAuto(order, 7L)).thenReturn(started);

        scheduler.advanceCheckingRequests();

        verify(refillRequestService).bindCheck(1L, 100L);
    }

    @Test
    @DisplayName("request older than the age ceiling → FAILED, no bot call")
    void over_age_fails() {
        RefillRequest req = checking(1L, null, 0, 46); // > maxAge 45
        when(refillRequestService.listChecking()).thenReturn(List.of(req));

        scheduler.advanceCheckingRequests();

        verify(refillRequestService).failRequest(eq(1L), contains("in time"));
        verify(refillCheckService, never()).startCheckForAuto(any(), anyLong());
    }

    @Test
    @DisplayName("attempt budget exhausted → FAILED, no bot call")
    void over_attempts_fails() {
        RefillRequest req = checking(1L, null, 3, 5); // attempts == maxCheckAttempts
        when(refillRequestService.listChecking()).thenReturn(List.of(req));

        scheduler.advanceCheckingRequests();

        verify(refillRequestService).failRequest(eq(1L), any());
        verify(refillCheckService, never()).startCheckForAuto(any(), anyLong());
    }

    @Test
    @DisplayName("permanent ineligibility from startCheckForAuto → FAILED")
    void permanent_failure_fails_request() {
        RefillRequest req = checking(1L, null, 0, 2);
        when(refillRequestService.listChecking()).thenReturn(List.of(req));
        Order order = new Order();
        order.setId(req.getOrderId());
        when(orderRepository.findByIdWithAllDetails(req.getOrderId()))
                .thenReturn(Optional.of(order));
        when(refillCheckService.startCheckForAuto(order, 7L))
                .thenThrow(new IllegalStateException("combo service"));

        scheduler.advanceCheckingRequests();

        verify(refillRequestService).failRequest(eq(1L), contains("combo"));
        verify(refillRequestService, never()).bindCheck(anyLong(), anyLong());
    }

    @Test
    @DisplayName("transient bot failure → leaves the request CHECKING (no fail, no bind)")
    void transient_failure_leaves_checking() {
        RefillRequest req = checking(1L, null, 0, 2);
        when(refillRequestService.listChecking()).thenReturn(List.of(req));
        Order order = new Order();
        order.setId(req.getOrderId());
        when(orderRepository.findByIdWithAllDetails(req.getOrderId()))
                .thenReturn(Optional.of(order));
        when(refillCheckService.startCheckForAuto(order, 7L))
                .thenThrow(new ApiException("bot down", HttpStatus.BAD_GATEWAY));

        scheduler.advanceCheckingRequests();

        verify(refillRequestService, never()).failRequest(anyLong(), any());
        verify(refillRequestService, never()).bindCheck(anyLong(), anyLong());
    }

    @Test
    @DisplayName("order vanished → FAILED")
    void order_missing_fails() {
        RefillRequest req = checking(1L, null, 0, 2);
        when(refillRequestService.listChecking()).thenReturn(List.of(req));
        when(orderRepository.findByIdWithAllDetails(req.getOrderId())).thenReturn(Optional.empty());

        scheduler.advanceCheckingRequests();

        verify(refillRequestService).failRequest(eq(1L), contains("no longer exists"));
    }

    @Test
    @DisplayName("per-tick start cap is honoured (only N starts even when more need one)")
    void start_cap_honoured() {
        ReflectionTestUtils.setField(scheduler, "maxStartsPerTick", 1);
        RefillRequest a = checking(1L, null, 0, 2);
        RefillRequest b = checking(2L, null, 0, 2);
        when(refillRequestService.listChecking()).thenReturn(List.of(a, b));
        Order oa = new Order();
        oa.setId(a.getOrderId());
        when(orderRepository.findByIdWithAllDetails(a.getOrderId())).thenReturn(Optional.of(oa));
        RefillCheck started = check(RefillCheck.Status.RUNNING);
        started.setId(99L);
        when(refillCheckService.startCheckForAuto(any(), anyLong())).thenReturn(started);

        scheduler.advanceCheckingRequests();

        // Only one start this tick; the second request is left for the next tick.
        verify(refillCheckService, times(1)).startCheckForAuto(any(), anyLong());
        verify(orderRepository, never()).findByIdWithAllDetails(b.getOrderId());
    }

    private static RefillCheck check(RefillCheck.Status status) {
        return RefillCheck.builder().id(1L).status(status).build();
    }
}
