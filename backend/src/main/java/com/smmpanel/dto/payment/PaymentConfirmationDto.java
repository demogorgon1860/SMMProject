package com.smmpanel.dto.payment;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Payment Confirmation DTO for Kafka messaging Used in real-time payment confirmation processing
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PaymentConfirmationDto {

    private String transactionId;
    private String paymentMethod;
    private BigDecimal amount;
    private String currency;
    private String status;
    private Long userId;
    private Long orderId; // Optional - if payment is related to an order
    private String paymentProcessor; // e.g., "cryptomus", "paypal", "stripe"
    private LocalDateTime confirmedAt;
    private String confirmationCode;
    private java.util.Map<String, Object> metadata;

    // Payment processor specific fields
    private String externalTransactionId;
    private String paymentGatewayResponse;
    private String webhookId;
}
