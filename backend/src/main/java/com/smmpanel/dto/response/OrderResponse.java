package com.smmpanel.dto.response;

import com.smmpanel.entity.Order;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Optimized OrderResponse DTO with static factory methods to prevent lazy loading during
 * entity-to-DTO conversion
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderResponse {
    private Long id;
    private Integer service;
    private String serviceName;
    private String status;
    private String link;
    private Integer quantity;
    private Integer startCount;
    private Integer remains;
    private String charge;
    private String orderId;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    /**
     * True when this order is a refill issued for an earlier completed/partial order. The UI
     * renders a small "Refill" badge next to the order so the customer can tell the row apart
     * from a fresh paid order — same row layout, just a tag. Backed by {@code Order.isRefill}.
     */
    private Boolean isRefill;

    // Nested DTOs to avoid lazy loading
    private UserSummaryResponse user;
    private ServiceSummaryResponse serviceDetails;

    /**
     * OPTIMIZED: Static factory method that assumes all relations are already fetched Use this when
     * Order entity has been fetched with JOIN FETCH
     */
    public static OrderResponse fromEntityWithFetchedRelations(Order order) {
        return OrderResponse.builder()
                .id(
                        order.getUserOrderNumber() != null
                                ? order.getUserOrderNumber().longValue()
                                : order.getId())
                .service(order.getService().getId().intValue())
                .serviceName(order.getService().getName())
                .status(order.getStatus().name())
                .link(order.getLink())
                .quantity(order.getQuantity())
                .startCount(order.getStartCount())
                .remains(order.getRemains())
                .charge(order.getCharge().toString())
                .orderId(order.getOrderId())
                .createdAt(order.getCreatedAt())
                .updatedAt(order.getUpdatedAt())
                .isRefill(Boolean.TRUE.equals(order.getIsRefill()))
                .user(UserSummaryResponse.fromEntity(order.getUser()))
                .serviceDetails(ServiceSummaryResponse.fromEntity(order.getService()))
                .build();
    }

    /** OPTIMIZED: Create minimal response for listing views Avoids accessing any relationships */
    public static OrderResponse fromEntityMinimal(Order order, String serviceName) {
        return OrderResponse.builder()
                .id(
                        order.getUserOrderNumber() != null
                                ? order.getUserOrderNumber().longValue()
                                : order.getId())
                .service(order.getService() != null ? order.getService().getId().intValue() : null)
                .serviceName(serviceName)
                .status(order.getStatus().name())
                .link(order.getLink())
                .quantity(order.getQuantity())
                .startCount(order.getStartCount())
                .remains(order.getRemains())
                .charge(order.getCharge().toString())
                .orderId(order.getOrderId())
                .createdAt(order.getCreatedAt())
                .updatedAt(order.getUpdatedAt())
                .isRefill(Boolean.TRUE.equals(order.getIsRefill()))
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
        private String email;

        public static UserSummaryResponse fromEntity(com.smmpanel.entity.User user) {
            return UserSummaryResponse.builder()
                    .id(user.getId())
                    .username(user.getUsername())
                    .email(user.getEmail())
                    .build();
        }
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ServiceSummaryResponse {
        private Long id;
        private String name;
        private String category;
        private BigDecimal pricePer1000;

        public static ServiceSummaryResponse fromEntity(com.smmpanel.entity.Service service) {
            return ServiceSummaryResponse.builder()
                    .id(service.getId())
                    .name(service.getName())
                    .category(service.getCategory())
                    .pricePer1000(service.getPricePer1000())
                    .build();
        }
    }
}
