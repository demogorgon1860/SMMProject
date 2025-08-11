package com.smmpanel.dto.response;

import com.smmpanel.entity.BalanceTransaction;
import com.smmpanel.entity.TransactionType;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Optimized TransactionResponse DTO with static factory methods to prevent lazy loading during
 * entity-to-DTO conversion
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TransactionResponse {
    private Long id;
    private BigDecimal amount;
    private BigDecimal balanceBefore;
    private BigDecimal balanceAfter;
    private String transactionId;
    private TransactionType transactionType;
    private String description;
    private LocalDateTime createdAt;

    // Nested DTOs to avoid lazy loading
    private UserSummaryResponse user;
    private OrderSummaryResponse order;
    private DepositSummaryResponse deposit;

    /**
     * OPTIMIZED: Static factory method that assumes all relations are already fetched Use this when
     * BalanceTransaction entity has been fetched with JOIN FETCH
     */
    public static TransactionResponse fromEntityWithFetchedRelations(
            BalanceTransaction transaction) {
        TransactionResponseBuilder builder =
                TransactionResponse.builder()
                        .id(transaction.getId())
                        .amount(transaction.getAmount())
                        .balanceBefore(transaction.getBalanceBefore())
                        .balanceAfter(transaction.getBalanceAfter())
                        .transactionId(transaction.getTransactionId())
                        .transactionType(transaction.getTransactionType())
                        .description(transaction.getDescription())
                        .createdAt(transaction.getCreatedAt());

        // Only map relations if they exist and are initialized
        if (transaction.getUser() != null) {
            builder.user(UserSummaryResponse.fromEntity(transaction.getUser()));
        }

        if (transaction.getOrder() != null) {
            builder.order(OrderSummaryResponse.fromEntity(transaction.getOrder()));
        }

        if (transaction.getDeposit() != null) {
            builder.deposit(DepositSummaryResponse.fromEntity(transaction.getDeposit()));
        }

        return builder.build();
    }

    /**
     * LEGACY: Create minimal response without accessing relationships Use this when relations are
     * not fetched to avoid lazy loading
     */
    public static TransactionResponse fromEntity(BalanceTransaction transaction) {
        return TransactionResponse.builder()
                .id(transaction.getId())
                .amount(transaction.getAmount())
                .balanceBefore(transaction.getBalanceBefore())
                .balanceAfter(transaction.getBalanceAfter())
                .transactionId(transaction.getTransactionId())
                .transactionType(transaction.getTransactionType())
                .description(transaction.getDescription())
                .createdAt(transaction.getCreatedAt())
                .build();
    }

    /** Supporting nested DTOs */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UserSummaryResponse {
        private Long id;
        private String username;

        public static UserSummaryResponse fromEntity(com.smmpanel.entity.User user) {
            return UserSummaryResponse.builder()
                    .id(user.getId())
                    .username(user.getUsername())
                    .build();
        }
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class OrderSummaryResponse {
        private Long id;
        private String orderId;
        private String status;

        public static OrderSummaryResponse fromEntity(com.smmpanel.entity.Order order) {
            return OrderSummaryResponse.builder()
                    .id(order.getId())
                    .orderId(order.getOrderId())
                    .status(order.getStatus().name())
                    .build();
        }
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DepositSummaryResponse {
        private Long id;
        private String orderId;
        private String status;

        public static DepositSummaryResponse fromEntity(
                com.smmpanel.entity.BalanceDeposit deposit) {
            return DepositSummaryResponse.builder()
                    .id(deposit.getId())
                    .orderId(deposit.getOrderId())
                    .status(deposit.getStatus().name())
                    .build();
        }
    }
}
