package com.smmpanel.service.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.smmpanel.config.OrderSerializationProperties;
import com.smmpanel.dto.instagram.InstagramOrderResponse;
import com.smmpanel.entity.Order;
import com.smmpanel.entity.OrderStatus;
import com.smmpanel.entity.User;
import com.smmpanel.repository.jpa.OrderRepository;
import com.smmpanel.service.balance.BalanceService;
import com.smmpanel.service.integration.InstagramService;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/** Unit tests for the per-URL dispatch gate {@link OrderSerializationService}. */
class OrderSerializationServiceTest {

    private static final String URL = "https://www.instagram.com/p/ABC1234567/";

    private OrderRepository repo;
    private InstagramService instagram;
    private BalanceService balance;
    private OrderSerializationProperties props;
    private OrderSerializationService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        repo = mock(OrderRepository.class);
        instagram = mock(InstagramService.class);
        balance = mock(BalanceService.class);
        props = new OrderSerializationProperties(); // enabled=true, default active statuses
        ObjectProvider<OrderSerializationService> self = mock(ObjectProvider.class);
        service = new OrderSerializationService(repo, instagram, balance, props, self);
    }

    private Order order(long id) {
        Order o = mock(Order.class);
        when(o.getId()).thenReturn(id);
        when(o.getLink()).thenReturn(URL);
        when(o.getQuantity()).thenReturn(100);
        when(o.getCharge()).thenReturn(new BigDecimal("5.00"));
        when(o.getUser()).thenReturn(mock(User.class));
        return o;
    }

    private static InstagramOrderResponse ok(String botId) {
        return InstagramOrderResponse.builder().success(true).id(botId).build();
    }

    private static InstagramOrderResponse fail(String error) {
        return InstagramOrderResponse.builder().success(false).error(error).build();
    }

    @Test
    void disabledIsANoOp() {
        props.setEnabled(false);
        service.pumpUrl(URL);
        verifyNoInteractions(repo, instagram, balance);
    }

    @Test
    void blankLinkIsANoOp() {
        service.pumpUrl("   ");
        verifyNoInteractions(repo, instagram, balance);
    }

    @Test
    void busyUrlTakesLockButDoesNotDispatch() {
        when(repo.existsByLinkAndStatusIn(eq(URL), any())).thenReturn(true);

        service.pumpUrl(URL);

        verify(repo).acquireUrlSerializationLock(URL);
        verify(repo).existsByLinkAndStatusIn(eq(URL), any());
        verify(repo, never()).findOrdersByLinkAndStatusOrderById(any(), any(), any());
        verify(instagram, never()).createInstagramOrder(any());
    }

    @Test
    void freeUrlDispatchesLowestPending() {
        when(repo.existsByLinkAndStatusIn(eq(URL), any())).thenReturn(false);
        Order o = order(30000);
        when(repo.findOrdersByLinkAndStatusOrderById(eq(URL), eq(OrderStatus.PENDING), any()))
                .thenReturn(List.of(o));
        when(instagram.createInstagramOrder(o)).thenReturn(ok("bot-1"));

        service.pumpUrl(URL);

        verify(o).setInstagramBotOrderId("bot-1");
        verify(o).setStatus(OrderStatus.IN_PROGRESS);
        verify(repo).save(o);
        verify(balance, never()).refund(any(), any(), any(), any());
    }

    @Test
    void nothingPendingIsANoOpAfterLock() {
        when(repo.existsByLinkAndStatusIn(eq(URL), any())).thenReturn(false);
        when(repo.findOrdersByLinkAndStatusOrderById(eq(URL), eq(OrderStatus.PENDING), any()))
                .thenReturn(List.of());

        service.pumpUrl(URL);

        verify(repo).acquireUrlSerializationLock(URL);
        verify(instagram, never()).createInstagramOrder(any());
    }

    @Test
    void hardFailureCancelsRefundsThenTriesNext() {
        when(repo.existsByLinkAndStatusIn(eq(URL), any())).thenReturn(false);
        Order bad = order(30000);
        Order good = order(30001);
        // 1st pick → bad (dispatch fails → cancelled), 2nd pick → good (succeeds)
        when(repo.findOrdersByLinkAndStatusOrderById(eq(URL), eq(OrderStatus.PENDING), any()))
                .thenReturn(List.of(bad))
                .thenReturn(List.of(good));
        when(instagram.createInstagramOrder(bad)).thenReturn(fail("boom"));
        when(instagram.createInstagramOrder(good)).thenReturn(ok("bot-2"));

        service.pumpUrl(URL);

        // bad cancelled + fully refunded
        verify(bad).setStatus(OrderStatus.CANCELLED);
        verify(balance).refund(any(), eq(new BigDecimal("5.00")), eq(bad), anyString());
        verify(bad).setCharge(BigDecimal.ZERO);
        // then good dispatched
        verify(good).setStatus(OrderStatus.IN_PROGRESS);
        verify(repo, times(2))
                .findOrdersByLinkAndStatusOrderById(eq(URL), eq(OrderStatus.PENDING), any());
    }

    @Test
    void dispatchOrderToBotSuccessReturnsTrue() {
        Order o = order(1);
        when(instagram.createInstagramOrder(o)).thenReturn(ok("b"));

        assertThat(service.dispatchOrderToBot(o)).isTrue();
        verify(o).setInstagramBotOrderId("b");
        verify(o).setStatus(OrderStatus.IN_PROGRESS);
        verify(repo).save(o);
    }

    @Test
    void dispatchOrderToBotBotErrorReturnsFalseAndRefunds() {
        Order o = order(1);
        when(instagram.createInstagramOrder(o)).thenReturn(fail("nope"));

        assertThat(service.dispatchOrderToBot(o)).isFalse();
        verify(o).setStatus(OrderStatus.CANCELLED);
        verify(o).setRemains(100);
        verify(balance).refund(any(), eq(new BigDecimal("5.00")), eq(o), anyString());
        verify(o).setCharge(BigDecimal.ZERO);
        verify(repo).save(o);
    }
}
