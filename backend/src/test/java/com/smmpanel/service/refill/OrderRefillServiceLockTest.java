package com.smmpanel.service.refill;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.smmpanel.entity.Order;
import com.smmpanel.entity.OrderStatus;
import com.smmpanel.exception.ApiException;
import com.smmpanel.exception.ResourceNotFoundException;
import com.smmpanel.producer.OrderEventProducer;
import com.smmpanel.repository.jpa.OrderRefillRepository;
import com.smmpanel.repository.jpa.OrderRepository;
import com.smmpanel.service.balance.BalanceService;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Lock acquisition of {@link OrderRefillService#createRefill}: the owner's balance row first (the
 * platform-wide order createOrder and the refund paths use), then the order row, both as scalar
 * locks, and only then the unlocked load of the order. The former {@code @Lock} JOIN FETCH query
 * fell back to version-checked per-entity locks and failed under the reseller's order traffic
 * (User#856).
 */
@ExtendWith(MockitoExtension.class)
class OrderRefillServiceLockTest {

    private static final long ORDER_ID = 42L;
    private static final long OWNER_ID = 856L;

    @Mock private OrderRepository orderRepository;
    @Mock private OrderRefillRepository orderRefillRepository;
    @Mock private OrderEventProducer orderEventProducer;
    @Mock private BalanceService balanceService;

    @InjectMocks private OrderRefillService service;

    @Test
    void locksTheOwnerThenTheOrderRow_thenLoadsTheOrder() {
        Order inProgress = new Order();
        inProgress.setId(ORDER_ID);
        inProgress.setStatus(OrderStatus.IN_PROGRESS); // ineligible → stops right after the locks
        when(orderRepository.findUserIdById(ORDER_ID)).thenReturn(Optional.of(OWNER_ID));
        when(orderRepository.lockRowById(ORDER_ID)).thenReturn(List.of(ORDER_ID));
        when(orderRepository.findByIdWithDetails(ORDER_ID)).thenReturn(Optional.of(inProgress));

        assertThatThrownBy(() -> service.createRefill(ORDER_ID))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("not eligible");

        InOrder lockOrder = inOrder(orderRepository, balanceService);
        lockOrder.verify(orderRepository).findUserIdById(ORDER_ID);
        lockOrder.verify(balanceService).lockUserForUpdate(OWNER_ID);
        lockOrder.verify(orderRepository).lockRowById(ORDER_ID);
        lockOrder.verify(orderRepository).findByIdWithDetails(ORDER_ID);
    }

    @Test
    void unknownOrder_isNotFound_andTakesNoLock() {
        when(orderRepository.findUserIdById(ORDER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.createRefill(ORDER_ID))
                .isInstanceOf(ResourceNotFoundException.class);

        verify(balanceService, never()).lockUserForUpdate(anyLong());
        verify(orderRepository, never()).lockRowById(anyLong());
    }
}
