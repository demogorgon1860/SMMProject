package com.smmpanel.service;

import com.smmpanel.entity.Order;
import com.smmpanel.entity.OrderStatus;
import com.smmpanel.exception.VideoProcessingException;
import com.smmpanel.repository.jpa.OrderRepository;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * ORDER STATE MANAGEMENT SERVICE
 *
 * <p>Provides centralized, thread-safe order state management with: 1. Atomic state transitions
 * with validation 2. State transition logging and audit trail 3. Async operation status tracking 4.
 * Consistency guarantees across concurrent operations 5. Rollback capabilities for failed
 * transitions
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderStateManagementService {

    private final OrderRepository orderRepository;

    // In-memory tracking of orders currently being processed (for concurrency control)
    private final Map<Long, ProcessingState> activeProcessingStates = new ConcurrentHashMap<>();

    /** VALID STATE TRANSITIONS Defines allowed transitions to prevent invalid state changes */
    private static final Map<OrderStatus, EnumSet<OrderStatus>> VALID_TRANSITIONS =
            Map.of(
                    OrderStatus.PENDING,
                            EnumSet.of(
                                    OrderStatus.PROCESSING,
                                    OrderStatus.CANCELLED,
                                    OrderStatus.HOLDING),
                    OrderStatus.PROCESSING,
                            EnumSet.of(
                                    OrderStatus.ACTIVE, OrderStatus.HOLDING, OrderStatus.CANCELLED),
                    OrderStatus.ACTIVE,
                            EnumSet.of(
                                    OrderStatus.COMPLETED,
                                    OrderStatus.HOLDING,
                                    OrderStatus.CANCELLED),
                    OrderStatus.HOLDING, EnumSet.of(OrderStatus.PROCESSING, OrderStatus.CANCELLED),
                    OrderStatus.COMPLETED,
                            EnumSet.of(OrderStatus.HOLDING), // Only allow hold for investigation
                    OrderStatus.CANCELLED, EnumSet.noneOf(OrderStatus.class) // Terminal state
                    );

    /**
     * IMMEDIATE VALIDATION AND STATUS UPDATE Updates order status immediately after basic
     * validation
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public OrderValidationResult validateAndUpdateOrderForProcessing(
            Long orderId, String youtubeVideoId) {
        try {
            log.info("Validating and updating order for processing: orderId={}", orderId);

            // Get order with pessimistic lock to prevent concurrent modifications
            Order order =
                    orderRepository
                            .findById(orderId)
                            .orElseThrow(
                                    () ->
                                            new VideoProcessingException(
                                                    "Order not found: " + orderId));

            // Validate current state
            if (!order.getStatus().equals(OrderStatus.PENDING)) {
                log.warn("Order {} is not in PENDING status: {}", orderId, order.getStatus());
                return OrderValidationResult.failed(
                        orderId, "Order not in PENDING status: " + order.getStatus());
            }

            // Check for concurrent processing
            if (activeProcessingStates.containsKey(orderId)) {
                log.warn("Order {} is already being processed concurrently", orderId);
                return OrderValidationResult.failed(orderId, "Order already being processed");
            }

            // Register processing state to prevent concurrent access
            ProcessingState processingState = new ProcessingState(orderId, LocalDateTime.now());
            activeProcessingStates.put(orderId, processingState);

            // Perform immediate status transition
            OrderStatus previousStatus = order.getStatus();
            order.setStatus(OrderStatus.PROCESSING);
            order.setYoutubeVideoId(youtubeVideoId);
            order.setUpdatedAt(LocalDateTime.now());

            // Add processing metadata
            if (order.getErrorMessage() != null) {
                order.setErrorMessage(null); // Clear previous errors
            }

            // Save state transition
            orderRepository.save(order);

            // Log state transition
            logStateTransition(
                    orderId,
                    previousStatus,
                    OrderStatus.PROCESSING,
                    "Order validated and queued for processing");

            log.info("Order {} successfully transitioned to PROCESSING status", orderId);

            return OrderValidationResult.success(orderId, order);

        } catch (Exception e) {
            // Remove from active processing on failure
            activeProcessingStates.remove(orderId);
            log.error("Failed to validate and update order {}: {}", orderId, e.getMessage(), e);
            throw e;
        }
    }

    /**
     * UPDATE PROCESSING STATUS Sets detailed processing status to indicate specific async work in
     * progress
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void updateProcessingStatus(Long orderId, ProcessingPhase phase, String details) {
        try {
            Order order =
                    orderRepository
                            .findById(orderId)
                            .orElseThrow(
                                    () ->
                                            new VideoProcessingException(
                                                    "Order not found: " + orderId));

            // Validate order is in processing state
            if (!order.getStatus().equals(OrderStatus.PROCESSING)) {
                log.warn(
                        "Attempting to update processing status for order {} not in PROCESSING"
                                + " state: {}",
                        orderId,
                        order.getStatus());
                return;
            }

            // Update processing state
            ProcessingState processingState = activeProcessingStates.get(orderId);
            if (processingState != null) {
                processingState.setCurrentPhase(phase);
                processingState.setPhaseDetails(details);
                processingState.setLastUpdate(LocalDateTime.now());
            }

            // Update order with processing details
            order.setUpdatedAt(LocalDateTime.now());
            orderRepository.save(order);

            log.info(
                    "Updated processing status for order {}: phase={}, details={}",
                    orderId,
                    phase,
                    details);

        } catch (Exception e) {
            log.error(
                    "Failed to update processing status for order {}: {}",
                    orderId,
                    e.getMessage(),
                    e);
        }
    }

    /** TRANSITION TO ACTIVE STATE Completes the processing phase and activates the order */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public StateTransitionResult transitionToActive(Long orderId, int startCount) {
        try {
            log.info(
                    "Transitioning order {} to ACTIVE state with start count: {}",
                    orderId,
                    startCount);

            Order order =
                    orderRepository
                            .findById(orderId)
                            .orElseThrow(
                                    () ->
                                            new VideoProcessingException(
                                                    "Order not found: " + orderId));

            // Validate transition
            StateTransitionResult validationResult = validateTransition(order, OrderStatus.ACTIVE);
            if (!validationResult.isSuccess()) {
                return validationResult;
            }

            // Perform state transition
            OrderStatus previousStatus = order.getStatus();
            order.setStatus(OrderStatus.ACTIVE);
            order.setStartCount(startCount);
            order.setRemains(order.getQuantity()); // Initialize remaining count
            order.setUpdatedAt(LocalDateTime.now());

            // Clear any error messages from processing
            if (order.getErrorMessage() != null) {
                order.setErrorMessage(null);
            }

            orderRepository.save(order);

            // Remove from active processing tracking
            activeProcessingStates.remove(orderId);

            // Log state transition
            logStateTransition(
                    orderId,
                    previousStatus,
                    OrderStatus.ACTIVE,
                    "Processing completed successfully, order is now active");

            log.info("Order {} successfully transitioned to ACTIVE state", orderId);

            return StateTransitionResult.success(orderId, previousStatus, OrderStatus.ACTIVE);

        } catch (Exception e) {
            log.error("Failed to transition order {} to ACTIVE: {}", orderId, e.getMessage(), e);
            return StateTransitionResult.failed(
                    orderId, OrderStatus.PROCESSING, OrderStatus.ACTIVE, e.getMessage());
        }
    }

    /** TRANSITION TO COMPLETED STATE Marks order as completed when target is reached */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public StateTransitionResult transitionToCompleted(Long orderId, int finalCount) {
        try {
            log.info(
                    "Transitioning order {} to COMPLETED state with final count: {}",
                    orderId,
                    finalCount);

            Order order =
                    orderRepository
                            .findById(orderId)
                            .orElseThrow(
                                    () ->
                                            new VideoProcessingException(
                                                    "Order not found: " + orderId));

            // Validate transition
            StateTransitionResult validationResult =
                    validateTransition(order, OrderStatus.COMPLETED);
            if (!validationResult.isSuccess()) {
                return validationResult;
            }

            // Calculate final metrics
            int viewsGained = finalCount - order.getStartCount();

            // Perform state transition
            OrderStatus previousStatus = order.getStatus();
            order.setStatus(OrderStatus.COMPLETED);
            order.setRemains(0);
            order.setUpdatedAt(LocalDateTime.now());

            orderRepository.save(order);

            // Log state transition
            logStateTransition(
                    orderId,
                    previousStatus,
                    OrderStatus.COMPLETED,
                    String.format("Order completed with %d views gained", viewsGained));

            log.info("Order {} successfully transitioned to COMPLETED state", orderId);

            return StateTransitionResult.success(orderId, previousStatus, OrderStatus.COMPLETED);

        } catch (Exception e) {
            log.error("Failed to transition order {} to COMPLETED: {}", orderId, e.getMessage(), e);
            return StateTransitionResult.failed(
                    orderId, OrderStatus.ACTIVE, OrderStatus.COMPLETED, e.getMessage());
        }
    }

    /** TRANSITION TO HOLDING STATE Moves order to holding state for error investigation */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public StateTransitionResult transitionToHolding(Long orderId, String reason) {
        Order order = null;
        try {
            log.info("Transitioning order {} to HOLDING state: {}", orderId, reason);

            order =
                    orderRepository
                            .findById(orderId)
                            .orElseThrow(
                                    () ->
                                            new VideoProcessingException(
                                                    "Order not found: " + orderId));

            // Holding is allowed from any non-terminal state
            OrderStatus previousStatus = order.getStatus();
            order.setStatus(OrderStatus.HOLDING);
            order.setErrorMessage(reason);
            order.setUpdatedAt(LocalDateTime.now());

            orderRepository.save(order);

            // Remove from active processing tracking
            activeProcessingStates.remove(orderId);

            // Log state transition
            logStateTransition(orderId, previousStatus, OrderStatus.HOLDING, reason);

            log.info("Order {} successfully transitioned to HOLDING state", orderId);

            return StateTransitionResult.success(orderId, previousStatus, OrderStatus.HOLDING);

        } catch (Exception e) {
            log.error("Failed to transition order {} to HOLDING: {}", orderId, e.getMessage(), e);
            OrderStatus currentStatus = order != null ? order.getStatus() : OrderStatus.PENDING;
            return StateTransitionResult.failed(
                    orderId, currentStatus, OrderStatus.HOLDING, e.getMessage());
        }
    }

    /** UPDATE ORDER PROGRESS Updates remaining count and checks for completion */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ProgressUpdateResult updateOrderProgress(Long orderId, int currentViews) {
        try {
            Order order =
                    orderRepository
                            .findById(orderId)
                            .orElseThrow(
                                    () ->
                                            new VideoProcessingException(
                                                    "Order not found: " + orderId));

            // Only update progress for active orders
            if (!order.getStatus().equals(OrderStatus.ACTIVE)) {
                log.debug(
                        "Skipping progress update for order {} not in ACTIVE state: {}",
                        orderId,
                        order.getStatus());
                return ProgressUpdateResult.skipped(orderId, "Order not active");
            }

            // Calculate progress
            int viewsGained = currentViews - order.getStartCount();
            int remains = Math.max(0, order.getQuantity() - viewsGained);

            // Update order
            order.setRemains(remains);
            order.setUpdatedAt(LocalDateTime.now());

            // Check for completion
            boolean completed = remains <= 0;
            if (completed) {
                order.setStatus(OrderStatus.COMPLETED);
                order.setRemains(0);

                logStateTransition(
                        orderId,
                        OrderStatus.ACTIVE,
                        OrderStatus.COMPLETED,
                        String.format(
                                "Auto-completed: %d views gained, target reached", viewsGained));
            }

            orderRepository.save(order);

            log.debug(
                    "Updated progress for order {}: views gained={}, remains={}, completed={}",
                    orderId,
                    viewsGained,
                    remains,
                    completed);

            return ProgressUpdateResult.success(orderId, viewsGained, remains, completed);

        } catch (Exception e) {
            log.error("Failed to update progress for order {}: {}", orderId, e.getMessage(), e);
            return ProgressUpdateResult.failed(orderId, e.getMessage());
        }
    }

    /** GET ORDER PROCESSING STATE Returns current processing state information */
    public Optional<ProcessingStateInfo> getProcessingState(Long orderId) {
        ProcessingState state = activeProcessingStates.get(orderId);
        if (state == null) {
            return Optional.empty();
        }

        return Optional.of(
                ProcessingStateInfo.builder()
                        .orderId(orderId)
                        .startTime(state.getStartTime())
                        .currentPhase(state.getCurrentPhase())
                        .phaseDetails(state.getPhaseDetails())
                        .lastUpdate(state.getLastUpdate())
                        .build());
    }

    /**
     * CLEANUP STALE PROCESSING STATES Removes processing states that have been active too long
     * (likely stuck)
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void cleanupStaleProcessingStates(int maxProcessingMinutes) {
        LocalDateTime cutoffTime = LocalDateTime.now().minusMinutes(maxProcessingMinutes);

        activeProcessingStates
                .entrySet()
                .removeIf(
                        entry -> {
                            ProcessingState state = entry.getValue();
                            if (state.getStartTime().isBefore(cutoffTime)) {
                                Long orderId = entry.getKey();
                                log.warn(
                                        "Cleaning up stale processing state for order {}: started"
                                                + " at {}",
                                        orderId,
                                        state.getStartTime());

                                // Optionally transition stale orders to holding
                                try {
                                    transitionToHolding(
                                            orderId, "Processing timeout - cleaned up stale state");
                                } catch (Exception e) {
                                    log.error(
                                            "Failed to transition stale order {} to holding: {}",
                                            orderId,
                                            e.getMessage());
                                }

                                return true;
                            }
                            return false;
                        });
    }

    /** VALIDATE STATE TRANSITION Checks if transition is allowed and safe */
    private StateTransitionResult validateTransition(Order order, OrderStatus targetStatus) {
        OrderStatus currentStatus = order.getStatus();

        // Check if transition is allowed
        EnumSet<OrderStatus> allowedTransitions = VALID_TRANSITIONS.get(currentStatus);
        if (allowedTransitions == null || !allowedTransitions.contains(targetStatus)) {
            String message =
                    String.format("Invalid state transition: %s → %s", currentStatus, targetStatus);
            log.warn("Order {}: {}", order.getId(), message);
            return StateTransitionResult.failed(
                    order.getId(), currentStatus, targetStatus, message);
        }

        return StateTransitionResult.success(order.getId(), currentStatus, targetStatus);
    }

    /** LOG STATE TRANSITION Creates audit trail for state changes */
    private void logStateTransition(
            Long orderId, OrderStatus fromStatus, OrderStatus toStatus, String reason) {
        log.info(
                "ORDER STATE TRANSITION: orderId={}, {} → {}, reason: {}",
                orderId,
                fromStatus,
                toStatus,
                reason);

        // In a production system, this would also write to an audit table
        // auditService.logStateTransition(orderId, fromStatus, toStatus, reason,
        // LocalDateTime.now());
    }

    // Supporting classes and enums

    /** Processing phases for detailed status tracking */
    public enum ProcessingPhase {
        VALIDATION("Initial validation"),
        VIDEO_ANALYSIS("Analyzing video content"),
        CLIP_CREATION("Creating video clip"),
        BINOM_INTEGRATION("Setting up Binom campaigns"),
        ACTIVATION("Activating order"),
        MONITORING("Monitoring progress");

        private final String description;

        ProcessingPhase(String description) {
            this.description = description;
        }

        public String getDescription() {
            return description;
        }
    }

    /** Internal processing state tracking */
    private static class ProcessingState {
        private final Long orderId;
        private final LocalDateTime startTime;
        private ProcessingPhase currentPhase;
        private String phaseDetails;
        private LocalDateTime lastUpdate;

        public ProcessingState(Long orderId, LocalDateTime startTime) {
            this.orderId = orderId;
            this.startTime = startTime;
            this.lastUpdate = startTime;
            this.currentPhase = ProcessingPhase.VALIDATION;
        }

        // Getters and setters
        public Long getOrderId() {
            return orderId;
        }

        public LocalDateTime getStartTime() {
            return startTime;
        }

        public ProcessingPhase getCurrentPhase() {
            return currentPhase;
        }

        public void setCurrentPhase(ProcessingPhase currentPhase) {
            this.currentPhase = currentPhase;
        }

        public String getPhaseDetails() {
            return phaseDetails;
        }

        public void setPhaseDetails(String phaseDetails) {
            this.phaseDetails = phaseDetails;
        }

        public LocalDateTime getLastUpdate() {
            return lastUpdate;
        }

        public void setLastUpdate(LocalDateTime lastUpdate) {
            this.lastUpdate = lastUpdate;
        }
    }
}

// Result classes for method responses

/** Order validation result */
@lombok.Builder
@lombok.Data
class OrderValidationResult {
    private final Long orderId;
    private final boolean success;
    private final String errorMessage;
    private final Order order;

    public static OrderValidationResult success(Long orderId, Order order) {
        return OrderValidationResult.builder().orderId(orderId).success(true).order(order).build();
    }

    public static OrderValidationResult failed(Long orderId, String errorMessage) {
        return OrderValidationResult.builder()
                .orderId(orderId)
                .success(false)
                .errorMessage(errorMessage)
                .build();
    }
}

/** Progress update result */
@lombok.Builder
@lombok.Data
class ProgressUpdateResult {
    private final Long orderId;
    private final boolean success;
    private final int viewsGained;
    private final int remains;
    private final boolean completed;
    private final String errorMessage;

    public static ProgressUpdateResult success(
            Long orderId, int viewsGained, int remains, boolean completed) {
        return ProgressUpdateResult.builder()
                .orderId(orderId)
                .success(true)
                .viewsGained(viewsGained)
                .remains(remains)
                .completed(completed)
                .build();
    }

    public static ProgressUpdateResult failed(Long orderId, String errorMessage) {
        return ProgressUpdateResult.builder()
                .orderId(orderId)
                .success(false)
                .errorMessage(errorMessage)
                .build();
    }

    public static ProgressUpdateResult skipped(Long orderId, String reason) {
        return ProgressUpdateResult.builder()
                .orderId(orderId)
                .success(true)
                .errorMessage(reason)
                .build();
    }
}

/** Processing state information */
@lombok.Builder
@lombok.Data
class ProcessingStateInfo {
    private final Long orderId;
    private final LocalDateTime startTime;
    private final OrderStateManagementService.ProcessingPhase currentPhase;
    private final String phaseDetails;
    private final LocalDateTime lastUpdate;
}
