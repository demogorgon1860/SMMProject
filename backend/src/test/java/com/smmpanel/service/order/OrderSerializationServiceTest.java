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
import com.smmpanel.dto.instagram.InstagramOrderType;
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

/**
 * Unit tests for the metric-aware per-URL dispatch gate {@link OrderSerializationService}. Two
 * same-link orders serialize only when their affected-metric masks overlap; independent metrics
 * (e.g. likes vs comments) dispatch in parallel.
 */
class OrderSerializationServiceTest {

    private static final String URL = "https://www.instagram.com/p/ABC1234567/";
    private static final int LIKE = InstagramOrderType.METRIC_LIKE; // 1
    private static final int COMMENT = InstagramOrderType.METRIC_COMMENT; // 2
    private static final int FOLLOW = InstagramOrderType.METRIC_FOLLOW; // 4
    private static final int LIKE_COMMENT = LIKE | COMMENT; // 3

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

    private Order order(long id, int mask) {
        Order o = mock(Order.class);
        when(o.getId()).thenReturn(id);
        when(o.getLink()).thenReturn(URL);
        when(o.getQuantity()).thenReturn(100);
        when(o.getCharge()).thenReturn(new BigDecimal("5.00"));
        when(o.getUser()).thenReturn(mock(User.class));
        when(o.getMetricMask()).thenReturn(mask);
        return o;
    }

    /** No active orders occupy the URL. */
    private void urlFree() {
        when(repo.findMetricMasksByLinkAndStatusIn(eq(URL), any())).thenReturn(List.of());
    }

    /** The given metric masks currently occupy the URL. */
    private void urlOccupiedBy(Integer... masks) {
        when(repo.findMetricMasksByLinkAndStatusIn(eq(URL), any())).thenReturn(List.of(masks));
    }

    private void pending(Order... orders) {
        when(repo.findOrdersByLinkAndStatusOrderById(eq(URL), eq(OrderStatus.PENDING), any()))
                .thenReturn(List.of(orders));
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
    void fullyOccupiedUrlTakesLockButDoesNotDispatch() {
        urlOccupiedBy(InstagramOrderType.METRIC_ALL); // every metric busy

        service.pumpUrl(URL);

        verify(repo).acquireUrlSerializationLock(URL);
        verify(repo, never()).findOrdersByLinkAndStatusOrderById(any(), any(), any());
        verify(instagram, never()).createInstagramOrder(any());
    }

    @Test
    void freeUrlDispatchesLowestPending() {
        urlFree();
        Order o = order(30000, LIKE);
        pending(o);
        when(instagram.createInstagramOrder(o)).thenReturn(ok("bot-1"));

        service.pumpUrl(URL);

        verify(o).setInstagramBotOrderId("bot-1");
        verify(o).setStatus(OrderStatus.IN_PROGRESS);
        verify(repo).save(o);
        verify(balance, never()).refund(any(), any(), any(), any());
    }

    @Test
    void nothingPendingIsANoOpAfterLock() {
        urlFree();
        when(repo.findOrdersByLinkAndStatusOrderById(eq(URL), eq(OrderStatus.PENDING), any()))
                .thenReturn(List.of());

        service.pumpUrl(URL);

        verify(repo).acquireUrlSerializationLock(URL);
        verify(instagram, never()).createInstagramOrder(any());
    }

    // ---------- The fix: metric-aware conflict ----------

    @Test
    void likesAndCommentsOnSameLinkDispatchInParallel() {
        urlFree();
        Order likes = order(30000, LIKE);
        Order comments = order(30001, COMMENT);
        pending(likes, comments);
        when(instagram.createInstagramOrder(likes)).thenReturn(ok("b-like"));
        when(instagram.createInstagramOrder(comments)).thenReturn(ok("b-comment"));

        service.pumpUrl(URL);

        // Independent metrics → BOTH go out together (this is the bug being fixed).
        verify(likes).setStatus(OrderStatus.IN_PROGRESS);
        verify(comments).setStatus(OrderStatus.IN_PROGRESS);
        verify(instagram).createInstagramOrder(likes);
        verify(instagram).createInstagramOrder(comments);
    }

    @Test
    void twoLikeOrdersOnSameLinkSerialize() {
        urlFree();
        Order first = order(30000, LIKE);
        Order second = order(30001, LIKE);
        pending(first, second);
        when(instagram.createInstagramOrder(first)).thenReturn(ok("b1"));

        service.pumpUrl(URL);

        // Same metric → only the lowest-id one dispatches; the second stays PENDING.
        verify(instagram).createInstagramOrder(first);
        verify(first).setStatus(OrderStatus.IN_PROGRESS);
        verify(instagram, never()).createInstagramOrder(second);
        verify(second, never()).setStatus(OrderStatus.IN_PROGRESS);
    }

    @Test
    void activeLikesBlocksMoreLikesButNotComments() {
        urlOccupiedBy(LIKE); // a likes order is already running on this link
        Order moreLikes = order(30000, LIKE);
        Order comments = order(30001, COMMENT);
        pending(moreLikes, comments);
        when(instagram.createInstagramOrder(comments)).thenReturn(ok("b-comment"));

        service.pumpUrl(URL);

        verify(instagram, never()).createInstagramOrder(moreLikes); // conflicts with active likes
        verify(instagram).createInstagramOrder(comments); // independent metric → dispatched
        verify(comments).setStatus(OrderStatus.IN_PROGRESS);
    }

    @Test
    void compositeActiveBlocksAnyOverlappingMetric() {
        urlOccupiedBy(LIKE_COMMENT); // a like_comment order occupies like + comment
        Order likes = order(30000, LIKE);
        Order comments = order(30001, COMMENT);
        Order follows = order(30002, FOLLOW);
        pending(likes, comments, follows);
        when(instagram.createInstagramOrder(follows)).thenReturn(ok("b-follow"));

        service.pumpUrl(URL);

        verify(instagram, never()).createInstagramOrder(likes); // overlaps like
        verify(instagram, never()).createInstagramOrder(comments); // overlaps comment
        verify(instagram).createInstagramOrder(follows); // follow is free → dispatched
    }

    @Test
    void hardFailureDoesNotOccupyTheUrlSoNextSameMetricDispatches() {
        urlFree();
        Order bad = order(30000, LIKE);
        Order good =
                order(30001, LIKE); // same metric — only reachable because `bad` never occupied
        pending(bad, good);
        when(instagram.createInstagramOrder(bad)).thenReturn(fail("boom"));
        when(instagram.createInstagramOrder(good)).thenReturn(ok("bot-2"));

        service.pumpUrl(URL);

        // bad cancelled + fully refunded, never occupied the URL
        verify(bad).setStatus(OrderStatus.CANCELLED);
        verify(balance).refund(any(), eq(new BigDecimal("5.00")), eq(bad), anyString());
        verify(bad).setCharge(BigDecimal.ZERO);
        // then good dispatched — single batch fetch, no re-query loop
        verify(good).setStatus(OrderStatus.IN_PROGRESS);
        verify(repo, times(1))
                .findOrdersByLinkAndStatusOrderById(eq(URL), eq(OrderStatus.PENDING), any());
    }

    @Test
    void dispatchOrderToBotSuccessReturnsTrue() {
        Order o = order(1, LIKE);
        when(instagram.createInstagramOrder(o)).thenReturn(ok("b"));

        assertThat(service.dispatchOrderToBot(o)).isTrue();
        verify(o).setInstagramBotOrderId("b");
        verify(o).setStatus(OrderStatus.IN_PROGRESS);
        verify(repo).save(o);
    }

    @Test
    void dispatchOrderToBotBotErrorReturnsFalseAndRefunds() {
        Order o = order(1, LIKE);
        when(instagram.createInstagramOrder(o)).thenReturn(fail("nope"));

        assertThat(service.dispatchOrderToBot(o)).isFalse();
        verify(o).setStatus(OrderStatus.CANCELLED);
        verify(o).setRemains(100);
        verify(balance).refund(any(), eq(new BigDecimal("5.00")), eq(o), anyString());
        verify(o).setCharge(BigDecimal.ZERO);
        verify(repo).save(o);
    }
}
