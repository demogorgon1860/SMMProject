package com.smmpanel.dto.response;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Response DTO for Mass Order creation Contains results of processing multiple orders */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MassOrderResponse {

    // Total number of orders submitted
    private Integer totalOrders;

    // Number of successfully created orders
    private Integer successfulOrders;

    // Number of failed orders
    private Integer failedOrders;

    // List of successfully created order IDs and their details
    @Builder.Default private List<OrderResult> successful = new ArrayList<>();

    // List of failed orders with error messages
    @Builder.Default private List<OrderResult> failed = new ArrayList<>();

    // List of validation errors during parsing
    @Builder.Default private List<ParseError> parseErrors = new ArrayList<>();

    // Total cost of all successful orders
    private Double totalCost;

    // Processing timestamp
    @Builder.Default private LocalDateTime processedAt = LocalDateTime.now();

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class OrderResult {
        private Long orderId;
        private Long serviceId;
        private String link;
        private Integer quantity;
        private Double cost;
        private String status;
        private String errorMessage;
        private Integer lineNumber;
        private String originalLine;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ParseError {
        private Integer lineNumber;
        private String originalLine;
        private String errorMessage;
    }
}
