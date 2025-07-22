package com.smmpanel.dto.binom;

import lombok.Data;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * DTO для создания оффера и назначения его на фиксированные кампании
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OfferAssignmentRequest {
    
    @NotBlank(message = "Offer name is required")
    private String offerName;
    
    @NotBlank(message = "Target URL is required")
    private String targetUrl;
    
    @NotNull(message = "Order ID is required")
    private Long orderId;
    
    // Дополнительные параметры (если нужны)
    private String description;
    private String geoTargeting; // По умолчанию US
    private Integer priority; // Приоритет оффера (опционально)
}
