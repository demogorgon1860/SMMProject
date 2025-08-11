package com.smmpanel.consumer;

import com.smmpanel.dto.payment.PaymentConfirmationDto;
import com.smmpanel.service.kafka.MessageDeduplicationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Real-Time Payment Confirmation Consumer
 * Optimized for immediate processing of payment confirmations
 * Features:
 * - Real-time processing with minimal latency
 * - Immediate acknowledgment mode
 * - Message deduplication for payment safety
 * - Small poll intervals for responsiveness
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentConfirmationConsumer {

    private final MessageDeduplicationService deduplicationService;
    // Add payment processing services as needed

    /**
     * Process payment confirmations in real-time
     * Prioritizes latency over throughput for immediate payment processing
     */
    @KafkaListener(
        topics = "smm.payment.confirmations",
        groupId = "smm-payment-confirmations-realtime-group",
        containerFactory = "paymentConfirmationContainerFactory"
    )
    @Transactional
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
            if (deduplicationService.isPaymentConfirmationAlreadyProcessed(messageId, transactionId)) {
                log.warn("Duplicate payment confirmation detected - POTENTIAL FRAUD RISK: transactionId={}, messageId={}", 
                    transactionId, messageId);
                acknowledgment.acknowledge();
                return;
            }

            log.info("Processing payment confirmation in real-time: transactionId={}, amount={}, currency={}, messageId={}", 
                    transactionId, confirmation.getAmount(), confirmation.getCurrency(), messageId);
            
            // Process payment confirmation immediately
            processPaymentConfirmationInternal(confirmation);
            
            // Mark as processed IMMEDIATELY to prevent double-processing
            deduplicationService.markPaymentConfirmationAsProcessed(messageId, transactionId);
            
            // Acknowledge immediately for real-time processing
            acknowledgment.acknowledge();
            
            log.info("Payment confirmation processed successfully: transactionId={}, messageId={}", 
                    transactionId, messageId);
                    
        } catch (Exception e) {
            log.error("CRITICAL: Failed to process payment confirmation: transactionId={}, messageId={}, error={}", 
                    transactionId, messageId, e.getMessage(), e);
            // Don't acknowledge on failure - let error handler manage payment failures
            throw e;
        }
    }

    /**
     * Process payment webhook confirmations for external payment processors
     */
    @KafkaListener(
        topics = "smm.payment.webhooks",
        groupId = "smm-payment-webhooks-group",
        containerFactory = "paymentConfirmationContainerFactory"
    )
    @Transactional
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
            if (deduplicationService.isMessageAlreadyProcessed("payment", messageId, webhookId)) {
                log.debug("Duplicate payment webhook detected, skipping: webhookId={}, messageId={}", 
                    webhookId, messageId);
                acknowledgment.acknowledge();
                return;
            }

            log.info("Processing payment webhook: webhookId={}, messageId={}", webhookId, messageId);
            
            // Process webhook data
            processPaymentWebhookInternal(webhookData);
            
            // Mark webhook as processed
            deduplicationService.markMessageAsProcessed("payment", messageId, webhookId, 
                java.time.Duration.ofHours(1));
            
            acknowledgment.acknowledge();
            
            log.info("Payment webhook processed: webhookId={}, messageId={}", webhookId, messageId);
                    
        } catch (Exception e) {
            log.error("Failed to process payment webhook: webhookId={}, messageId={}, error={}", 
                    webhookId, messageId, e.getMessage(), e);
            throw e;
        }
    }

    /**
     * Process payment refunds with real-time processing
     */
    @KafkaListener(
        topics = "smm.payment.refunds",
        groupId = "smm-payment-refunds-group",
        containerFactory = "paymentConfirmationContainerFactory"
    )
    @Transactional
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
            if (deduplicationService.isMessageAlreadyProcessed("payment", messageId, refundId)) {
                log.warn("Duplicate payment refund detected: refundId={}, messageId={}", 
                    refundId, messageId);
                acknowledgment.acknowledge();
                return;
            }

            log.info("Processing payment refund: refundId={}, amount={}, messageId={}", 
                    refundId, refundData.get("amount"), messageId);
            
            // Process refund
            processPaymentRefundInternal(refundData);
            
            // Mark refund as processed
            deduplicationService.markMessageAsProcessed("payment", messageId, refundId, 
                java.time.Duration.ofHours(24));
            
            acknowledgment.acknowledge();
            
            log.info("Payment refund processed: refundId={}, messageId={}", refundId, messageId);
                    
        } catch (Exception e) {
            log.error("CRITICAL: Failed to process payment refund: refundId={}, messageId={}, error={}", 
                    refundId, messageId, e.getMessage(), e);
            throw e;
        }
    }

    /**
     * Internal method to process payment confirmation
     */
    private void processPaymentConfirmationInternal(PaymentConfirmationDto confirmation) {
        // Implementation would include:
        // 1. Update user balance
        // 2. Update order status if related to order
        // 3. Send confirmation notification
        // 4. Log transaction for audit
        // 5. Update payment status in database
        
        log.debug("Processing payment confirmation internally: transactionId={}", 
            confirmation.getTransactionId());
        
        // TODO: Implement actual payment processing logic
        // This is where you would integrate with:
        // - BalanceService to update user balance
        // - OrderService to update order payment status
        // - NotificationService to send confirmation
        // - AuditService to log transaction
    }

    /**
     * Internal method to process payment webhook
     */
    private void processPaymentWebhookInternal(java.util.Map<String, Object> webhookData) {
        // Implementation would include:
        // 1. Validate webhook signature
        // 2. Parse webhook data
        // 3. Update payment status
        // 4. Trigger appropriate business logic
        
        log.debug("Processing payment webhook internally: webhookId={}", webhookData.get("webhookId"));
        
        // TODO: Implement webhook processing logic
    }

    /**
     * Internal method to process payment refund
     */
    private void processPaymentRefundInternal(java.util.Map<String, Object> refundData) {
        // Implementation would include:
        // 1. Validate refund request
        // 2. Update user balance (subtract refunded amount)
        // 3. Update order status to refunded
        // 4. Send refund notification
        // 5. Log refund for audit
        
        log.debug("Processing payment refund internally: refundId={}", refundData.get("refundId"));
        
        // TODO: Implement refund processing logic
    }
}