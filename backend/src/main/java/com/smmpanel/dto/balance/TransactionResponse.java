package com.smmpanel.dto.balance;

import com.smmpanel.entity.BalanceTransaction;
import com.smmpanel.entity.TransactionType;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Response DTO for transaction history */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TransactionResponse {
    private Long id;
    private String transactionId;
    private BigDecimal amount;
    private String currency;
    private BigDecimal balanceBefore;
    private BigDecimal balanceAfter;
    private TransactionType type;
    private String description;
    private LocalDateTime createdAt;
    private OrderReference order;
    private DepositReference deposit;

    /** Maps a BalanceTransaction entity to TransactionResponse DTO */
    public static TransactionResponse fromEntity(BalanceTransaction transaction) {
        return fromEntity(transaction, transaction.getUser().getPreferredCurrency());
    }

    public static TransactionResponse fromEntity(BalanceTransaction transaction, String currency) {
        return TransactionResponse.builder()
                .id(transaction.getId())
                .transactionId(transaction.getTransactionId())
                .amount(transaction.getAmount().abs())
                .currency(currency)
                .balanceBefore(transaction.getBalanceBefore())
                .balanceAfter(transaction.getBalanceAfter())
                .type(transaction.getTransactionType())
                .description(transaction.getDescription())
                .createdAt(transaction.getCreatedAt())
                .order(
                        transaction.getOrder() != null
                                ? new OrderReference(
                                        transaction.getOrder().getId(),
                                        transaction.getOrder().getOrderId())
                                : null)
                .deposit(
                        transaction.getDeposit() != null
                                ? new DepositReference(
                                        transaction.getDeposit().getId(),
                                        transaction.getDeposit().getOrderId())
                                : null)
                .build();
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class OrderReference {
        private Long id;
        private String orderId;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DepositReference {
        private Long id;
        private String orderId;
    }
}
