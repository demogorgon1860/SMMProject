package com.smmpanel.consumer;

import com.smmpanel.dto.payment.PaymentConfirmationDto;
import com.smmpanel.entity.BalanceDeposit;
import com.smmpanel.entity.Order;
import com.smmpanel.entity.OrderStatus;
import com.smmpanel.entity.PaymentStatus;
import com.smmpanel.entity.User;
import com.smmpanel.repository.jpa.BalanceDepositRepository;
import com.smmpanel.repository.jpa.OrderRepository;
import com.smmpanel.repository.jpa.UserRepository;
import com.smmpanel.service.balance.BalanceService;
import com.smmpanel.service.kafka.MessageIdempotencyService;
import com.smmpanel.service.notification.NotificationService;
import com.smmpanel.service.order.OrderService;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Real-Time Payment Confirmation Consumer Optimized for immediate processing of payment
 * confirmations Features: - Real-time processing with minimal latency - Immediate acknowledgment
 * mode - Message deduplication for payment safety - Small poll intervals for responsiveness
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentConfirmationConsumer {

    private final MessageIdempotencyService deduplicationService;
    private final BalanceService balanceService;
    private final OrderService orderService;
    private final NotificationService notificationService;
    private final UserRepository userRepository;
    private final OrderRepository orderRepository;
    private final BalanceDepositRepository balanceDepositRepository;

    /**
     * Process payment confirmations in real-time Prioritizes latency over throughput for immediate
     * payment processing
     */
    @KafkaListener(
            topics = "smm.payment.confirmations",
            groupId = "smm-payment-confirmations-realtime-group",
            containerFactory = "paymentConfirmationContainerFactory")
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void processPaymentConfirmation(
            @Payload PaymentConfirmationDto confirmation,
            @Header(KafkaHeaders.RECEIVED_TOPIC) String topic,
            @Header(KafkaHeaders.RECEIVED_PARTITION) int partition,
            @Header(KafkaHeaders.OFFSET) long offset,
            @Header(value = KafkaHeaders.RECEIVED_TIMESTAMP, required = false) Long timestamp,
            Acknowledgment acknowledgment) {

        // Generate unique message ID for idempotency
        String messageId = deduplicationService.generateMessageId(topic, partition, offset);
        String transactionId = confirmation.getTransactionId();

        try {
            // Check for duplicate processing (critical for payments)
            if (deduplicationService.isPaymentConfirmationAlreadyProcessed(
                    messageId, transactionId)) {
                log.warn(
                        "Duplicate payment confirmation detected - POTENTIAL FRAUD RISK:"
                                + " transactionId={}, messageId={}",
                        transactionId,
                        messageId);
                acknowledgment.acknowledge();
                return;
            }

            log.info(
                    "Processing payment confirmation in real-time: transactionId={}, amount={},"
                            + " currency={}, messageId={}",
                    transactionId,
                    confirmation.getAmount(),
                    confirmation.getCurrency(),
                    messageId);

            // Process payment confirmation immediately
            processPaymentConfirmationInternal(confirmation);

            // Mark as processed IMMEDIATELY to prevent double-processing
            deduplicationService.markPaymentConfirmationAsProcessed(messageId, transactionId);

            // Acknowledge immediately for real-time processing
            acknowledgment.acknowledge();

            log.info(
                    "Payment confirmation processed successfully: transactionId={}, messageId={}",
                    transactionId,
                    messageId);

        } catch (Exception e) {
            log.error(
                    "CRITICAL: Failed to process payment confirmation: transactionId={},"
                            + " messageId={}, error={}",
                    transactionId,
                    messageId,
                    e.getMessage(),
                    e);
            // Don't acknowledge on failure - let error handler manage payment failures
            throw e;
        }
    }

    /** Process payment webhook confirmations for external payment processors */
    @KafkaListener(
            topics = "smm.payment.webhooks",
            groupId = "smm-payment-webhooks-group",
            containerFactory = "paymentConfirmationContainerFactory")
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void processPaymentWebhook(
            @Payload java.util.Map<String, Object> webhookData,
            @Header(KafkaHeaders.RECEIVED_TOPIC) String topic,
            @Header(KafkaHeaders.RECEIVED_PARTITION) int partition,
            @Header(KafkaHeaders.OFFSET) long offset,
            Acknowledgment acknowledgment) {

        String messageId = deduplicationService.generateMessageId(topic, partition, offset);
        String webhookId = (String) webhookData.get("webhookId");

        try {
            // Check for duplicate webhook processing
            if (deduplicationService.isPaymentConfirmationAlreadyProcessed(messageId, webhookId)) {
                log.debug(
                        "Duplicate payment webhook detected, skipping: webhookId={}, messageId={}",
                        webhookId,
                        messageId);
                acknowledgment.acknowledge();
                return;
            }

            log.info(
                    "Processing payment webhook: webhookId={}, messageId={}", webhookId, messageId);

            // Process webhook data
            processPaymentWebhookInternal(webhookData);

            // Mark webhook as processed
            deduplicationService.markPaymentConfirmationAsProcessed(messageId, webhookId);

            acknowledgment.acknowledge();

            log.info("Payment webhook processed: webhookId={}, messageId={}", webhookId, messageId);

        } catch (Exception e) {
            log.error(
                    "Failed to process payment webhook: webhookId={}, messageId={}, error={}",
                    webhookId,
                    messageId,
                    e.getMessage(),
                    e);
            throw e;
        }
    }

    /** Process payment refunds with real-time processing */
    @KafkaListener(
            topics = "smm.payment.refunds",
            groupId = "smm-payment-refunds-group",
            containerFactory = "paymentConfirmationContainerFactory")
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void processPaymentRefund(
            @Payload java.util.Map<String, Object> refundData,
            @Header(KafkaHeaders.RECEIVED_TOPIC) String topic,
            @Header(KafkaHeaders.RECEIVED_PARTITION) int partition,
            @Header(KafkaHeaders.OFFSET) long offset,
            Acknowledgment acknowledgment) {

        String messageId = deduplicationService.generateMessageId(topic, partition, offset);
        String refundId = (String) refundData.get("refundId");

        try {
            // Check for duplicate refund processing
            if (deduplicationService.isPaymentConfirmationAlreadyProcessed(messageId, refundId)) {
                log.warn(
                        "Duplicate payment refund detected: refundId={}, messageId={}",
                        refundId,
                        messageId);
                acknowledgment.acknowledge();
                return;
            }

            log.info(
                    "Processing payment refund: refundId={}, amount={}, messageId={}",
                    refundId,
                    refundData.get("amount"),
                    messageId);

            // Process refund
            processPaymentRefundInternal(refundData);

            // Mark refund as processed (uses 1 hour TTL from service)
            deduplicationService.markPaymentConfirmationAsProcessed(messageId, refundId);

            acknowledgment.acknowledge();

            log.info("Payment refund processed: refundId={}, messageId={}", refundId, messageId);

        } catch (Exception e) {
            log.error(
                    "CRITICAL: Failed to process payment refund: refundId={}, messageId={},"
                            + " error={}",
                    refundId,
                    messageId,
                    e.getMessage(),
                    e);
            throw e;
        }
    }

    /** Internal method to process payment confirmation */
    private void processPaymentConfirmationInternal(PaymentConfirmationDto confirmation) {
        log.debug(
                "Processing payment confirmation internally: transactionId={}",
                confirmation.getTransactionId());

        // 1. Find the balance deposit by transaction ID
        BalanceDeposit deposit =
                balanceDepositRepository
                        .findByPaymentId(confirmation.getTransactionId())
                        .orElse(null);

        if (deposit == null) {
            log.warn("No deposit found for transaction ID: {}", confirmation.getTransactionId());
            return;
        }

        // 2. Check if already processed
        if (deposit.getStatus() == PaymentStatus.COMPLETED) {
            log.info(
                    "Payment already processed for transaction: {}",
                    confirmation.getTransactionId());
            return;
        }

        // 3. Get the user
        User user = deposit.getUser();
        if (user == null) {
            log.error("User not found for deposit: {}", deposit.getId());
            return;
        }

        // 4. Update user balance
        BigDecimal amount = confirmation.getAmount();
        balanceService.addToBalance(
                user,
                amount,
                String.format(
                        "Deposit confirmed - Transaction: %s", confirmation.getTransactionId()));

        // 5. Update deposit status
        deposit.setStatus(PaymentStatus.COMPLETED);
        deposit.setConfirmedAt(LocalDateTime.now());
        deposit.setConfirmedAmount(amount);
        balanceDepositRepository.save(deposit);

        // 6. If this deposit is related to an order, update order status
        if (confirmation.getOrderId() != null) {
            updateOrderStatusOnPayment(confirmation.getOrderId().toString(), confirmation);
        }

        // 7. Send confirmation notification
        notificationService.sendPaymentConfirmation(
                user, confirmation.getTransactionId(), amount, confirmation.getCurrency());

        // 8. Log success
        log.info(
                "Successfully processed payment confirmation for user {} with amount {} {}",
                user.getUsername(),
                amount,
                confirmation.getCurrency());
    }

    /** Helper method to update order status on payment confirmation */
    private void updateOrderStatusOnPayment(String orderId, PaymentConfirmationDto confirmation) {
        try {
            Long orderIdLong = Long.parseLong(orderId);
            Order order = orderRepository.findById(orderIdLong).orElse(null);

            if (order == null) {
                log.warn("Order not found for ID: {}", orderId);
                return;
            }

            // Update order status if it's in PENDING state
            if (order.getStatus() == OrderStatus.PENDING) {
                order.setStatus(OrderStatus.ACTIVE);
                order.setUpdatedAt(LocalDateTime.now());
                orderRepository.save(order);

                log.info("Order {} status updated to ACTIVE after payment confirmation", orderId);
            }
        } catch (NumberFormatException e) {
            log.error("Invalid order ID format: {}", orderId);
        } catch (Exception e) {
            log.error("Error updating order status for order {}: {}", orderId, e.getMessage());
        }
    }

    /** Internal method to process payment webhook */
    private void processPaymentWebhookInternal(java.util.Map<String, Object> webhookData) {
        log.debug(
                "Processing payment webhook internally: webhookId={}",
                webhookData.get("webhookId"));

        // Extract key data from webhook
        String transactionId = (String) webhookData.get("payment_id");
        String status = (String) webhookData.get("status");
        BigDecimal amount = new BigDecimal(webhookData.get("amount").toString());
        String currency = (String) webhookData.get("currency");
        String orderId = (String) webhookData.get("order_id");

        // Create a PaymentConfirmationDto from webhook data
        PaymentConfirmationDto confirmation =
                PaymentConfirmationDto.builder()
                        .transactionId(transactionId)
                        .amount(amount)
                        .currency(currency)
                        .orderId(orderId != null ? Long.parseLong(orderId) : null)
                        .status(status)
                        .paymentMethod((String) webhookData.get("payment_method"))
                        .confirmedAt(LocalDateTime.now())
                        .build();

        // Process based on status
        if ("paid".equalsIgnoreCase(status) || "completed".equalsIgnoreCase(status)) {
            // Process successful payment
            processPaymentConfirmationInternal(confirmation);
        } else if ("cancelled".equalsIgnoreCase(status) || "failed".equalsIgnoreCase(status)) {
            // Handle failed payment
            handleFailedPayment(transactionId, status);
        }
    }

    /** Handle failed payment */
    private void handleFailedPayment(String transactionId, String status) {
        BalanceDeposit deposit =
                balanceDepositRepository.findByPaymentId(transactionId).orElse(null);

        if (deposit != null) {
            deposit.setStatus(PaymentStatus.FAILED);
            deposit.setFailedAt(LocalDateTime.now());
            balanceDepositRepository.save(deposit);

            log.info(
                    "Payment marked as failed for transaction: {} with status: {}",
                    transactionId,
                    status);
        }
    }

    /** Internal method to process payment refund */
    private void processPaymentRefundInternal(java.util.Map<String, Object> refundData) {
        log.debug("Processing payment refund internally: refundId={}", refundData.get("refundId"));

        String transactionId = (String) refundData.get("transaction_id");
        String orderId = (String) refundData.get("order_id");
        BigDecimal refundAmount = new BigDecimal(refundData.get("amount").toString());
        String reason = (String) refundData.getOrDefault("reason", "Refund requested");

        // 1. Find the original deposit
        BalanceDeposit deposit =
                balanceDepositRepository.findByPaymentId(transactionId).orElse(null);

        if (deposit == null) {
            log.warn("No deposit found for refund transaction ID: {}", transactionId);
            return;
        }

        User user = deposit.getUser();

        // 2. Process refund amount
        balanceService.refund(user, refundAmount, null, reason);

        // 3. Update deposit status
        deposit.setStatus(PaymentStatus.REFUNDED);
        balanceDepositRepository.save(deposit);

        // 4. Update order status if applicable
        if (orderId != null) {
            try {
                Long orderIdLong = Long.parseLong(orderId);
                Order order = orderRepository.findById(orderIdLong).orElse(null);
                if (order != null) {
                    order.setStatus(OrderStatus.CANCELLED);
                    order.setUpdatedAt(LocalDateTime.now());
                    orderRepository.save(order);
                }
            } catch (Exception e) {
                log.error("Error updating order status for refund: {}", e.getMessage());
            }
        }

        // 5. Send refund notification
        notificationService.sendRefundNotification(user, transactionId, refundAmount, reason);

        log.info(
                "Successfully processed refund for user {} with amount {}",
                user.getUsername(),
                refundAmount);
    }
}
