package com.smmpanel.controller;

import com.smmpanel.entity.Order;
import com.smmpanel.repository.jpa.OrderRepository;
import com.smmpanel.service.DeadLetterQueueService;
import com.smmpanel.service.DeadLetterQueueStats;
import com.smmpanel.service.DlqOperationResult;
import com.smmpanel.service.ErrorRecoveryService;
import com.smmpanel.service.ErrorRecoveryStats;
import com.smmpanel.service.ManualRetryResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * ERROR RECOVERY CONTROLLER
 *
 * <p>Provides operator interface for manual error recovery: 1. Manual retry functionality for
 * failed orders 2. Dead letter queue management 3. Error recovery statistics and monitoring 4. Bulk
 * operations for error handling 5. Error analysis and reporting tools
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/admin/error-recovery")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN') or hasRole('OPERATOR')")
public class ErrorRecoveryController {

    private final ErrorRecoveryService errorRecoveryService;
    private final DeadLetterQueueService deadLetterQueueService;
    private final OrderRepository orderRepository;

    /** MANUAL RETRY ORDER Allows operators to manually retry failed orders */
    @PostMapping("/orders/{orderId}/retry")
    @PreAuthorize("hasRole('ADMIN') or hasRole('OPERATOR')")
    public ResponseEntity<ApiResponse<ManualRetryResult>> manualRetryOrder(
            @PathVariable Long orderId, @Valid @RequestBody ManualRetryRequest request) {

        try {
            log.info(
                    "Manual retry requested for order {} by operator: {}",
                    orderId,
                    request.getOperatorNotes());

            ManualRetryResult result =
                    errorRecoveryService.manualRetry(
                            orderId, request.getOperatorNotes(), request.isResetRetryCount());

            if (result.isSuccess()) {
                return ResponseEntity.ok(
                        ApiResponse.success(result, "Order retry initiated successfully"));
            } else {
                return ResponseEntity.badRequest()
                        .body(ApiResponse.error("RETRY_FAILED", result.getErrorMessage()));
            }

        } catch (Exception e) {
            log.error("Manual retry failed for order {}: {}", orderId, e.getMessage(), e);
            return ResponseEntity.internalServerError()
                    .body(
                            ApiResponse.error(
                                    "INTERNAL_ERROR", "Manual retry failed: " + e.getMessage()));
        }
    }

    /** GET FAILED ORDERS Returns paginated list of failed orders for operator review */
    @GetMapping("/orders/failed")
    public ResponseEntity<ApiResponse<Page<Order>>> getFailedOrders(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "updatedAt") String sortBy,
            @RequestParam(defaultValue = "desc") String sortDir,
            @RequestParam(required = false) String errorType,
            @RequestParam(required = false) String failedPhase,
            @RequestParam(required = false) @Min(0) @Max(10) Integer minRetries) {

        try {
            Sort sort = Sort.by(Sort.Direction.fromString(sortDir), sortBy);
            Pageable pageable = PageRequest.of(page, size, sort);

            Page<Order> failedOrders;

            if (errorType != null) {
                failedOrders =
                        deadLetterQueueService.getDeadLetterQueueOrdersByErrorType(
                                errorType, pageable);
            } else if (failedPhase != null) {
                failedOrders = orderRepository.findOrdersByFailedPhase(failedPhase, pageable);
            } else if (minRetries != null) {
                failedOrders = orderRepository.findOrdersWithHighRetryCount(minRetries, pageable);
            } else {
                failedOrders = deadLetterQueueService.getDeadLetterQueueOrders(pageable);
            }

            return ResponseEntity.ok(
                    ApiResponse.success(failedOrders, "Failed orders retrieved successfully"));

        } catch (Exception e) {
            log.error("Failed to get failed orders: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError()
                    .body(ApiResponse.error("INTERNAL_ERROR", "Failed to retrieve failed orders"));
        }
    }

    /** GET DEAD LETTER QUEUE Returns orders in dead letter queue */
    @GetMapping("/dead-letter-queue")
    public ResponseEntity<ApiResponse<Page<Order>>> getDeadLetterQueue(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "updatedAt") String sortBy,
            @RequestParam(defaultValue = "desc") String sortDir) {

        try {
            Sort sort = Sort.by(Sort.Direction.fromString(sortDir), sortBy);
            Pageable pageable = PageRequest.of(page, size, sort);

            Page<Order> dlqOrders = deadLetterQueueService.getDeadLetterQueueOrders(pageable);

            return ResponseEntity.ok(
                    ApiResponse.success(dlqOrders, "Dead letter queue retrieved successfully"));

        } catch (Exception e) {
            log.error("Failed to get dead letter queue: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError()
                    .body(
                            ApiResponse.error(
                                    "INTERNAL_ERROR", "Failed to retrieve dead letter queue"));
        }
    }

    /** REQUEUE FROM DEAD LETTER QUEUE Moves order back to processing queue */
    @PostMapping("/dead-letter-queue/{orderId}/requeue")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<DlqOperationResult>> requeueFromDeadLetterQueue(
            @PathVariable Long orderId, @Valid @RequestBody RequeueRequest request) {

        try {
            log.info(
                    "Requeue from DLQ requested for order {} by operator: {}",
                    orderId,
                    request.getOperatorNotes());

            DlqOperationResult result =
                    deadLetterQueueService.requeueFromDeadLetterQueue(
                            orderId, request.getOperatorNotes());

            if (result.isSuccess()) {
                return ResponseEntity.ok(
                        ApiResponse.success(result, "Order requeued successfully"));
            } else {
                return ResponseEntity.badRequest()
                        .body(ApiResponse.error("REQUEUE_FAILED", result.getMessage()));
            }

        } catch (Exception e) {
            log.error("Requeue from DLQ failed for order {}: {}", orderId, e.getMessage(), e);
            return ResponseEntity.internalServerError()
                    .body(ApiResponse.error("INTERNAL_ERROR", "Requeue failed: " + e.getMessage()));
        }
    }

    /** PURGE FROM DEAD LETTER QUEUE Permanently removes order from DLQ */
    @DeleteMapping("/dead-letter-queue/{orderId}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<DlqOperationResult>> purgeFromDeadLetterQueue(
            @PathVariable Long orderId, @Valid @RequestBody PurgeRequest request) {

        try {
            log.info(
                    "Purge from DLQ requested for order {} by operator: {}",
                    orderId,
                    request.getOperatorNotes());

            DlqOperationResult result =
                    deadLetterQueueService.purgeDeadLetterQueueEntry(
                            orderId, request.getOperatorNotes());

            if (result.isSuccess()) {
                return ResponseEntity.ok(ApiResponse.success(result, "Order purged successfully"));
            } else {
                return ResponseEntity.badRequest()
                        .body(ApiResponse.error("PURGE_FAILED", result.getMessage()));
            }

        } catch (Exception e) {
            log.error("Purge from DLQ failed for order {}: {}", orderId, e.getMessage(), e);
            return ResponseEntity.internalServerError()
                    .body(ApiResponse.error("INTERNAL_ERROR", "Purge failed: " + e.getMessage()));
        }
    }

    /** BULK RETRY ORDERS Retry multiple orders at once */
    @PostMapping("/orders/bulk-retry")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<BulkRetryResult>> bulkRetryOrders(
            @Valid @RequestBody BulkRetryRequest request) {

        try {
            log.info(
                    "Bulk retry requested for {} orders by operator", request.getOrderIds().size());

            BulkRetryResult result = processBulkRetry(request);

            return ResponseEntity.ok(
                    ApiResponse.success(
                            result,
                            String.format(
                                    "Bulk retry completed: %d successful, %d failed",
                                    result.getSuccessfulRetries(), result.getFailedRetries())));

        } catch (Exception e) {
            log.error("Bulk retry failed: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError()
                    .body(
                            ApiResponse.error(
                                    "INTERNAL_ERROR", "Bulk retry failed: " + e.getMessage()));
        }
    }

    /** GET ERROR RECOVERY STATISTICS Returns comprehensive error recovery statistics */
    @GetMapping("/statistics")
    public ResponseEntity<ApiResponse<ErrorRecoveryDashboard>> getErrorRecoveryStatistics() {
        try {
            ErrorRecoveryStats errorStats = errorRecoveryService.getErrorStatistics();
            DeadLetterQueueStats dlqStats = deadLetterQueueService.getDeadLetterQueueStatistics();

            ErrorRecoveryDashboard dashboard =
                    ErrorRecoveryDashboard.builder()
                            .errorRecoveryStats(errorStats)
                            .deadLetterQueueStats(dlqStats)
                            .lastUpdated(java.time.LocalDateTime.now())
                            .build();

            return ResponseEntity.ok(
                    ApiResponse.success(dashboard, "Statistics retrieved successfully"));

        } catch (Exception e) {
            log.error("Failed to get error recovery statistics: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError()
                    .body(ApiResponse.error("INTERNAL_ERROR", "Failed to retrieve statistics"));
        }
    }

    /** GET ORDER ERROR DETAILS Returns detailed error information for specific order */
    @GetMapping("/orders/{orderId}/error-details")
    public ResponseEntity<ApiResponse<OrderErrorDetails>> getOrderErrorDetails(
            @PathVariable Long orderId) {
        try {
            Order order =
                    orderRepository
                            .findById(orderId)
                            .orElseThrow(() -> new RuntimeException("Order not found: " + orderId));

            OrderErrorDetails errorDetails =
                    OrderErrorDetails.builder()
                            .orderId(orderId)
                            .retryCount(order.getRetryCount())
                            .maxRetries(order.getMaxRetries())
                            .lastErrorType(order.getLastErrorType())
                            .failureReason(order.getFailureReason())
                            .failedPhase(order.getFailedPhase())
                            .lastRetryAt(order.getLastRetryAt())
                            .nextRetryAt(order.getNextRetryAt())
                            .isManuallyFailed(order.getIsManuallyFailed())
                            .operatorNotes(order.getOperatorNotes())
                            .errorStackTrace(order.getErrorStackTrace())
                            .build();

            return ResponseEntity.ok(
                    ApiResponse.success(errorDetails, "Error details retrieved successfully"));

        } catch (Exception e) {
            log.error("Failed to get error details for order {}: {}", orderId, e.getMessage(), e);
            return ResponseEntity.internalServerError()
                    .body(ApiResponse.error("INTERNAL_ERROR", "Failed to retrieve error details"));
        }
    }

    // Private helper methods

    private BulkRetryResult processBulkRetry(BulkRetryRequest request) {
        int successful = 0;
        int failed = 0;

        for (Long orderId : request.getOrderIds()) {
            try {
                ManualRetryResult result =
                        errorRecoveryService.manualRetry(
                                orderId, request.getOperatorNotes(), request.isResetRetryCount());

                if (result.isSuccess()) {
                    successful++;
                } else {
                    failed++;
                    log.warn(
                            "Bulk retry failed for order {}: {}",
                            orderId,
                            result.getErrorMessage());
                }
            } catch (Exception e) {
                failed++;
                log.error("Bulk retry failed for order {}: {}", orderId, e.getMessage());
            }
        }

        return BulkRetryResult.builder()
                .totalOrders(request.getOrderIds().size())
                .successfulRetries(successful)
                .failedRetries(failed)
                .operatorNotes(request.getOperatorNotes())
                .build();
    }
}

// Request/Response DTOs

@lombok.Data
@lombok.Builder
@lombok.NoArgsConstructor
@lombok.AllArgsConstructor
class ManualRetryRequest {
    @NotBlank(message = "Operator notes are required")
    private String operatorNotes;

    @lombok.Builder.Default private boolean resetRetryCount = false;
}

@lombok.Data
@lombok.Builder
@lombok.NoArgsConstructor
@lombok.AllArgsConstructor
class RequeueRequest {
    @NotBlank(message = "Operator notes are required")
    private String operatorNotes;
}

@lombok.Data
@lombok.Builder
@lombok.NoArgsConstructor
@lombok.AllArgsConstructor
class PurgeRequest {
    @NotBlank(message = "Operator notes are required")
    private String operatorNotes;
}

@lombok.Data
@lombok.Builder
@lombok.NoArgsConstructor
@lombok.AllArgsConstructor
class BulkRetryRequest {
    @jakarta.validation.constraints.NotEmpty(message = "Order IDs are required")
    @jakarta.validation.constraints.Size(
            max = 100,
            message = "Maximum 100 orders per bulk operation")
    private java.util.List<Long> orderIds;

    @NotBlank(message = "Operator notes are required")
    private String operatorNotes;

    @lombok.Builder.Default private boolean resetRetryCount = false;
}

@lombok.Builder
@lombok.Data
class BulkRetryResult {
    private final int totalOrders;
    private final int successfulRetries;
    private final int failedRetries;
    private final String operatorNotes;
}

@lombok.Builder
@lombok.Data
class ErrorRecoveryDashboard {
    private final ErrorRecoveryStats errorRecoveryStats;
    private final DeadLetterQueueStats deadLetterQueueStats;
    private final java.time.LocalDateTime lastUpdated;
}

@lombok.Builder
@lombok.Data
class OrderErrorDetails {
    private final Long orderId;
    private final Integer retryCount;
    private final Integer maxRetries;
    private final String lastErrorType;
    private final String failureReason;
    private final String failedPhase;
    private final java.time.LocalDateTime lastRetryAt;
    private final java.time.LocalDateTime nextRetryAt;
    private final Boolean isManuallyFailed;
    private final String operatorNotes;
    private final String errorStackTrace;
}

@lombok.Builder
@lombok.Data
class ApiResponse<T> {
    private final boolean success;
    private final T data;
    private final String message;
    private final String errorCode;
    private final java.time.LocalDateTime timestamp;

    public static <T> ApiResponse<T> success(T data, String message) {
        return ApiResponse.<T>builder()
                .success(true)
                .data(data)
                .message(message)
                .timestamp(java.time.LocalDateTime.now())
                .build();
    }

    public static <T> ApiResponse<T> error(String errorCode, String message) {
        return ApiResponse.<T>builder()
                .success(false)
                .errorCode(errorCode)
                .message(message)
                .timestamp(java.time.LocalDateTime.now())
                .build();
    }
}
