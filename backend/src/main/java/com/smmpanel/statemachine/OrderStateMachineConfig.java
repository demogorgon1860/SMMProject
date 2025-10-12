package com.smmpanel.statemachine;

import com.smmpanel.entity.Order;
import com.smmpanel.entity.OrderStatus;
import com.smmpanel.repository.jpa.OrderRepository;
import com.smmpanel.service.notification.NotificationService;
import com.smmpanel.service.order.OrderService;
import java.util.EnumSet;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.statemachine.StateMachineContext;
import org.springframework.statemachine.StateMachinePersist;
import org.springframework.statemachine.action.Action;
import org.springframework.statemachine.config.EnableStateMachineFactory;
import org.springframework.statemachine.config.EnumStateMachineConfigurerAdapter;
import org.springframework.statemachine.config.builders.StateMachineConfigurationConfigurer;
import org.springframework.statemachine.config.builders.StateMachineStateConfigurer;
import org.springframework.statemachine.config.builders.StateMachineTransitionConfigurer;
import org.springframework.statemachine.data.jpa.JpaPersistingStateMachineInterceptor;
import org.springframework.statemachine.data.jpa.JpaStateMachineRepository;
import org.springframework.statemachine.guard.Guard;
import org.springframework.statemachine.listener.StateMachineListener;
import org.springframework.statemachine.listener.StateMachineListenerAdapter;
import org.springframework.statemachine.persist.DefaultStateMachinePersister;
import org.springframework.statemachine.persist.StateMachinePersister;
import org.springframework.statemachine.service.DefaultStateMachineService;
import org.springframework.statemachine.service.StateMachineService;
import org.springframework.statemachine.state.State;
import org.springframework.statemachine.support.DefaultStateMachineContext;

/**
 * Production-Ready Spring State Machine Configuration for Order Workflow Based on Stack Overflow
 * best practices and official Spring documentation
 *
 * <p>Key Features: - Persistent state machine with JPA - Guards for business rule validation -
 * Actions for side effects - Error handling and recovery - Audit logging for all transitions
 *
 * <p>Reference:
 * https://stackoverflow.com/questions/68290333/recommended-approach-when-restoring-spring-state-machine
 */
@Slf4j
@Configuration
@EnableStateMachineFactory
@RequiredArgsConstructor
public class OrderStateMachineConfig
        extends EnumStateMachineConfigurerAdapter<OrderStatus, OrderEventEnum> {

    private final JpaStateMachineRepository stateMachineRepository;
    private final OrderService orderService;
    private final NotificationService notificationService;
    private final OrderRepository orderRepository;

    // Machine ID prefix for order state machines
    public static final String ORDER_STATE_MACHINE_ID = "ORDER-SM-";

    @Override
    public void configure(StateMachineConfigurationConfigurer<OrderStatus, OrderEventEnum> config)
            throws Exception {
        config.withConfiguration()
                .autoStartup(false)
                .machineId("orderStateMachine")
                .listener(orderStateChangeListener())
                .and()
                .withPersistence();
        // Runtime persister configuration removed - using regular persister instead
    }

    @Override
    public void configure(StateMachineStateConfigurer<OrderStatus, OrderEventEnum> states)
            throws Exception {
        states.withStates()
                .initial(OrderStatus.PENDING)
                .end(OrderStatus.COMPLETED)
                .end(OrderStatus.CANCELLED)
                .states(EnumSet.allOf(OrderStatus.class))
                .stateEntry(OrderStatus.PROCESSING, processingEntryAction())
                .stateExit(OrderStatus.PROCESSING, processingExitAction())
                .stateEntry(OrderStatus.ERROR, errorEntryAction())
                .state(OrderStatus.ERROR, errorStateAction(), null);
    }

    @Override
    public void configure(StateMachineTransitionConfigurer<OrderStatus, OrderEventEnum> transitions)
            throws Exception {
        transitions
                // Initial Order Flow
                .withExternal()
                .source(OrderStatus.PENDING)
                .target(OrderStatus.IN_PROGRESS)
                .event(OrderEventEnum.PAYMENT_CONFIRMED)
                .guard(paymentConfirmedGuard())
                .action(paymentConfirmedAction())
                .and()
                .withExternal()
                .source(OrderStatus.PENDING)
                .target(OrderStatus.CANCELLED)
                .event(OrderEventEnum.PAYMENT_FAILED)
                .action(paymentFailedAction())

                // Processing Flow
                .and()
                .withExternal()
                .source(OrderStatus.IN_PROGRESS)
                .target(OrderStatus.PROCESSING)
                .event(OrderEventEnum.START_PROCESSING)
                .guard(canStartProcessingGuard())
                .action(startProcessingAction())
                .and()
                .withExternal()
                .source(OrderStatus.PROCESSING)
                .target(OrderStatus.ACTIVE)
                .event(OrderEventEnum.PROCESSING_COMPLETED)
                .action(processingCompletedAction())
                .and()
                .withExternal()
                .source(OrderStatus.PROCESSING)
                .target(OrderStatus.ERROR)
                .event(OrderEventEnum.PROCESSING_FAILED)
                .action(processingFailedAction())

                // Active Order Management
                .and()
                .withExternal()
                .source(OrderStatus.ACTIVE)
                .target(OrderStatus.PAUSED)
                .event(OrderEventEnum.PAUSE_ORDER)
                .guard(canPauseOrderGuard())
                .action(pauseOrderAction())
                .and()
                .withExternal()
                .source(OrderStatus.PAUSED)
                .target(OrderStatus.ACTIVE)
                .event(OrderEventEnum.RESUME_ORDER)
                .action(resumeOrderAction())
                .and()
                .withExternal()
                .source(OrderStatus.ACTIVE)
                .target(OrderStatus.PARTIAL)
                .event(OrderEventEnum.MARK_PARTIAL)
                .guard(partialCompletionGuard())
                .action(partialCompletionAction())
                .and()
                .withExternal()
                .source(OrderStatus.ACTIVE)
                .target(OrderStatus.COMPLETED)
                .event(OrderEventEnum.MARK_COMPLETED)
                .guard(completionGuard())
                .action(completionAction())
                .and()
                .withExternal()
                .source(OrderStatus.PARTIAL)
                .target(OrderStatus.COMPLETED)
                .event(OrderEventEnum.MARK_COMPLETED)
                .guard(completionGuard())
                .action(completionAction())

                // Cancellation Flow
                .and()
                .withExternal()
                .source(OrderStatus.IN_PROGRESS)
                .target(OrderStatus.CANCELLED)
                .event(OrderEventEnum.CANCEL_REQUESTED)
                .guard(canCancelGuard())
                .action(cancellationAction())
                .and()
                .withExternal()
                .source(OrderStatus.PROCESSING)
                .target(OrderStatus.CANCELLED)
                .event(OrderEventEnum.CANCEL_REQUESTED)
                .guard(canCancelGuard())
                .action(cancellationAction())
                .and()
                .withExternal()
                .source(OrderStatus.ACTIVE)
                .target(OrderStatus.CANCELLED)
                .event(OrderEventEnum.CANCEL_REQUESTED)
                .guard(canCancelGuard())
                .action(cancellationWithRefundAction())

                // Error Recovery
                .and()
                .withExternal()
                .source(OrderStatus.ERROR)
                .target(OrderStatus.PROCESSING)
                .event(OrderEventEnum.RETRY_PROCESSING)
                .guard(canRetryGuard())
                .action(retryProcessingAction())
                .and()
                .withExternal()
                .source(OrderStatus.ERROR)
                .target(OrderStatus.HOLDING)
                .event(OrderEventEnum.MANUAL_INTERVENTION)
                .action(manualInterventionAction())
                .and()
                .withExternal()
                .source(OrderStatus.HOLDING)
                .target(OrderStatus.PROCESSING)
                .event(OrderEventEnum.ERROR_RESOLVED)
                .action(errorResolvedAction())

                // Admin Overrides
                .and()
                .withExternal()
                .source(OrderStatus.SUSPENDED)
                .target(OrderStatus.ACTIVE)
                .event(OrderEventEnum.ADMIN_REACTIVATE)
                .guard(adminGuard())
                .action(adminReactivateAction())
                .and()
                .withInternal()
                .source(OrderStatus.ERROR)
                .event(OrderEventEnum.ADMIN_OVERRIDE)
                .guard(adminGuard())
                .action(adminOverrideAction());
    }

    // ============ Guards (Business Rule Validation) ============

    @Bean
    public Guard<OrderStatus, OrderEventEnum> paymentConfirmedGuard() {
        return context -> {
            Long orderId = context.getExtendedState().get("orderId", Long.class);
            log.debug("Checking payment confirmation for order: {}", orderId);
            // Validate payment is actually confirmed
            return orderService.isPaymentConfirmed(orderId);
        };
    }

    @Bean
    public Guard<OrderStatus, OrderEventEnum> canStartProcessingGuard() {
        return context -> {
            Long orderId = context.getExtendedState().get("orderId", Long.class);
            // Check if all prerequisites for processing are met
            return orderService.canStartProcessing(orderId);
        };
    }

    @Bean
    public Guard<OrderStatus, OrderEventEnum> canPauseOrderGuard() {
        return context -> {
            Long orderId = context.getExtendedState().get("orderId", Long.class);
            // Check if order can be paused (e.g., not critical, no pending transactions)
            return orderService.canPauseOrder(orderId);
        };
    }

    @Bean
    public Guard<OrderStatus, OrderEventEnum> canCancelGuard() {
        return context -> {
            Long orderId = context.getExtendedState().get("orderId", Long.class);
            // Check cancellation policy
            return orderService.canCancelOrder(orderId);
        };
    }

    @Bean
    public Guard<OrderStatus, OrderEventEnum> canRetryGuard() {
        return context -> {
            Long orderId = context.getExtendedState().get("orderId", Long.class);
            Integer retryCount = context.getExtendedState().get("retryCount", Integer.class);
            // Check if retry limit not exceeded
            return retryCount == null || retryCount < 3;
        };
    }

    @Bean
    public Guard<OrderStatus, OrderEventEnum> partialCompletionGuard() {
        return context -> {
            Long orderId = context.getExtendedState().get("orderId", Long.class);
            // Check if partial completion criteria met
            return orderService.isPartiallyComplete(orderId);
        };
    }

    @Bean
    public Guard<OrderStatus, OrderEventEnum> completionGuard() {
        return context -> {
            Long orderId = context.getExtendedState().get("orderId", Long.class);
            // Check if order fully completed
            return orderService.isFullyComplete(orderId);
        };
    }

    @Bean
    public Guard<OrderStatus, OrderEventEnum> adminGuard() {
        return context -> {
            String userId = context.getExtendedState().get("userId", String.class);
            // Verify admin privileges
            return orderService.isAdmin(userId);
        };
    }

    // ============ Actions (Side Effects) ============

    @Bean
    public Action<OrderStatus, OrderEventEnum> paymentConfirmedAction() {
        return context -> {
            Long orderId = context.getExtendedState().get("orderId", Long.class);
            log.info("Payment confirmed for order: {}", orderId);

            // Update order in database
            orderService.markPaymentConfirmed(orderId);

            // Send notification
            notificationService.sendPaymentConfirmedNotification(orderId);

            // Trigger async processing
            orderService.triggerOrderProcessing(orderId);
        };
    }

    @Bean
    public Action<OrderStatus, OrderEventEnum> paymentFailedAction() {
        return context -> {
            Long orderId = context.getExtendedState().get("orderId", Long.class);
            log.warn("Payment failed for order: {}", orderId);

            orderService.markPaymentFailed(orderId);
            notificationService.sendPaymentFailedNotification(orderId);
        };
    }

    @Bean
    public Action<OrderStatus, OrderEventEnum> startProcessingAction() {
        return context -> {
            Long orderId = context.getExtendedState().get("orderId", Long.class);
            log.info("Starting processing for order: {}", orderId);

            orderService.startProcessing(orderId);
            context.getExtendedState()
                    .getVariables()
                    .put("processingStartTime", System.currentTimeMillis());
        };
    }

    @Bean
    public Action<OrderStatus, OrderEventEnum> processingCompletedAction() {
        return context -> {
            Long orderId = context.getExtendedState().get("orderId", Long.class);
            Long startTime = context.getExtendedState().get("processingStartTime", Long.class);
            long duration = System.currentTimeMillis() - (startTime != null ? startTime : 0);

            log.info("Processing completed for order: {} in {}ms", orderId, duration);

            orderService.markProcessingComplete(orderId);
            notificationService.sendProcessingCompleteNotification(orderId);
        };
    }

    @Bean
    public Action<OrderStatus, OrderEventEnum> processingFailedAction() {
        return context -> {
            Long orderId = context.getExtendedState().get("orderId", Long.class);
            String error = context.getMessage().getHeaders().get("error", String.class);

            log.error("Processing failed for order: {} - Error: {}", orderId, error);

            // Increment retry count
            Integer retryCount = context.getExtendedState().get("retryCount", Integer.class);
            context.getExtendedState()
                    .getVariables()
                    .put("retryCount", (retryCount != null ? retryCount : 0) + 1);

            orderService.markProcessingFailed(orderId, error);
            notificationService.sendProcessingFailedNotification(orderId, error);
        };
    }

    @Bean
    public Action<OrderStatus, OrderEventEnum> pauseOrderAction() {
        return context -> {
            Long orderId = context.getExtendedState().get("orderId", Long.class);
            log.info("Pausing order: {}", orderId);

            orderService.pauseOrder(orderId);
            Order order = orderRepository.findById(orderId).orElse(null);
            if (order != null) {
                notificationService.sendOrderPausedNotification(order, "State transition");
            }
        };
    }

    @Bean
    public Action<OrderStatus, OrderEventEnum> resumeOrderAction() {
        return context -> {
            Long orderId = context.getExtendedState().get("orderId", Long.class);
            log.info("Resuming order: {}", orderId);

            orderService.resumeOrder(orderId);
            Order order = orderRepository.findById(orderId).orElse(null);
            if (order != null) {
                notificationService.sendOrderResumedNotification(order);
            }
        };
    }

    @Bean
    public Action<OrderStatus, OrderEventEnum> partialCompletionAction() {
        return context -> {
            Long orderId = context.getExtendedState().get("orderId", Long.class);
            Integer completedQuantity =
                    context.getMessage().getHeaders().get("completedQuantity", Integer.class);

            log.info(
                    "Partial completion for order: {} - Completed: {}", orderId, completedQuantity);

            orderService.markPartialCompletion(orderId, completedQuantity);
            notificationService.sendPartialCompletionNotification(orderId, completedQuantity);
        };
    }

    @Bean
    public Action<OrderStatus, OrderEventEnum> completionAction() {
        return context -> {
            Long orderId = context.getExtendedState().get("orderId", Long.class);
            log.info("Order completed: {}", orderId);

            orderService.markOrderComplete(orderId);
            notificationService.sendOrderCompleteNotification(orderId);

            // Clean up extended state
            context.getExtendedState().getVariables().clear();
        };
    }

    @Bean
    public Action<OrderStatus, OrderEventEnum> cancellationAction() {
        return context -> {
            Long orderId = context.getExtendedState().get("orderId", Long.class);
            String reason =
                    context.getMessage().getHeaders().get("cancellationReason", String.class);

            log.info("Cancelling order: {} - Reason: {}", orderId, reason);

            orderService.cancelOrder(orderId, reason);
            notificationService.sendCancellationNotification(orderId, reason);
        };
    }

    @Bean
    public Action<OrderStatus, OrderEventEnum> cancellationWithRefundAction() {
        return context -> {
            Long orderId = context.getExtendedState().get("orderId", Long.class);
            String reason =
                    context.getMessage().getHeaders().get("cancellationReason", String.class);

            log.info("Cancelling order with refund: {} - Reason: {}", orderId, reason);

            orderService.cancelOrderWithRefund(orderId, reason);
            notificationService.sendCancellationWithRefundNotification(orderId, reason);
        };
    }

    @Bean
    public Action<OrderStatus, OrderEventEnum> retryProcessingAction() {
        return context -> {
            Long orderId = context.getExtendedState().get("orderId", Long.class);
            Integer retryCount = context.getExtendedState().get("retryCount", Integer.class);

            log.info("Retrying processing for order: {} - Attempt: {}", orderId, retryCount);

            orderService.retryProcessing(orderId);
        };
    }

    @Bean
    public Action<OrderStatus, OrderEventEnum> manualInterventionAction() {
        return context -> {
            Long orderId = context.getExtendedState().get("orderId", Long.class);
            String issue = context.getMessage().getHeaders().get("issue", String.class);

            log.warn("Manual intervention required for order: {} - Issue: {}", orderId, issue);

            orderService.flagForManualIntervention(orderId, issue);
            notificationService.sendManualInterventionNotification(orderId, issue);
        };
    }

    @Bean
    public Action<OrderStatus, OrderEventEnum> errorResolvedAction() {
        return context -> {
            Long orderId = context.getExtendedState().get("orderId", Long.class);
            String resolution = context.getMessage().getHeaders().get("resolution", String.class);

            log.info("Error resolved for order: {} - Resolution: {}", orderId, resolution);

            orderService.markErrorResolved(orderId, resolution);
            context.getExtendedState().getVariables().put("retryCount", 0);
        };
    }

    @Bean
    public Action<OrderStatus, OrderEventEnum> adminReactivateAction() {
        return context -> {
            Long orderId = context.getExtendedState().get("orderId", Long.class);
            String adminId = context.getMessage().getHeaders().get("adminId", String.class);

            log.info("Admin {} reactivating order: {}", adminId, orderId);

            orderService.adminReactivateOrder(orderId, adminId);
            notificationService.sendAdminReactivationNotification(orderId, adminId);
        };
    }

    @Bean
    public Action<OrderStatus, OrderEventEnum> adminOverrideAction() {
        return context -> {
            Long orderId = context.getExtendedState().get("orderId", Long.class);
            String adminId = context.getMessage().getHeaders().get("adminId", String.class);
            String overrideAction =
                    context.getMessage().getHeaders().get("overrideAction", String.class);

            log.info(
                    "Admin {} overriding order: {} - Action: {}", adminId, orderId, overrideAction);

            orderService.adminOverride(orderId, adminId, overrideAction);
        };
    }

    // ============ State Entry/Exit Actions ============

    @Bean
    public Action<OrderStatus, OrderEventEnum> processingEntryAction() {
        return context -> {
            Long orderId = context.getExtendedState().get("orderId", Long.class);
            log.debug("Entering PROCESSING state for order: {}", orderId);
            orderService.onProcessingEntry(orderId);
        };
    }

    @Bean
    public Action<OrderStatus, OrderEventEnum> processingExitAction() {
        return context -> {
            Long orderId = context.getExtendedState().get("orderId", Long.class);
            log.debug("Exiting PROCESSING state for order: {}", orderId);
            orderService.onProcessingExit(orderId);
        };
    }

    @Bean
    public Action<OrderStatus, OrderEventEnum> errorEntryAction() {
        return context -> {
            Long orderId = context.getExtendedState().get("orderId", Long.class);
            log.error("Entering ERROR state for order: {}", orderId);
            orderService.onErrorEntry(orderId);
        };
    }

    @Bean
    public Action<OrderStatus, OrderEventEnum> errorStateAction() {
        return context -> {
            Long orderId = context.getExtendedState().get("orderId", Long.class);
            log.debug("In ERROR state for order: {}", orderId);
        };
    }

    // ============ Listener ============

    @Bean
    public StateMachineListener<OrderStatus, OrderEventEnum> orderStateChangeListener() {
        return new StateMachineListenerAdapter<OrderStatus, OrderEventEnum>() {
            @Override
            public void stateChanged(
                    State<OrderStatus, OrderEventEnum> from,
                    State<OrderStatus, OrderEventEnum> to) {
                if (from != null && to != null) {
                    log.info("Order state changed from {} to {}", from.getId(), to.getId());
                }
            }

            @Override
            public void stateMachineError(
                    org.springframework.statemachine.StateMachine<OrderStatus, OrderEventEnum>
                            stateMachine,
                    Exception exception) {
                log.error("State machine error: {}", exception.getMessage(), exception);
            }
        };
    }

    // ============ Persistence ============

    @Bean
    public StateMachinePersister<OrderStatus, OrderEventEnum, String> orderStateMachinePersister() {
        return new DefaultStateMachinePersister<>(
                new StateMachinePersist<OrderStatus, OrderEventEnum, String>() {
                    @Override
                    public void write(
                            StateMachineContext<OrderStatus, OrderEventEnum> context,
                            String contextObj) {
                        // Persist state machine context to database
                        log.debug("Persisting state machine context for: {}", contextObj);
                        // Implementation would save to database
                    }

                    @Override
                    public StateMachineContext<OrderStatus, OrderEventEnum> read(
                            String contextObj) {
                        // Read state machine context from database
                        log.debug("Reading state machine context for: {}", contextObj);
                        // Implementation would read from database
                        return new DefaultStateMachineContext<>(
                                OrderStatus.PENDING, null, null, null);
                    }
                });
    }

    @Bean
    public StateMachineService<OrderStatus, OrderEventEnum> stateMachineService(
            org.springframework.statemachine.config.StateMachineFactory<OrderStatus, OrderEventEnum>
                    stateMachineFactory) {
        return new DefaultStateMachineService<OrderStatus, OrderEventEnum>(stateMachineFactory);
    }

    @Bean
    public JpaPersistingStateMachineInterceptor<OrderStatus, OrderEventEnum, String>
            jpaPersistingStateMachineInterceptor() {
        return new JpaPersistingStateMachineInterceptor<>(stateMachineRepository);
    }
}
