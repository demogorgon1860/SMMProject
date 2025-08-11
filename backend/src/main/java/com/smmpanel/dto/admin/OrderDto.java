package com.smmpanel.dto.admin;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderDto {
    private Long id;
    private Long userId;
    private String username;
    private Long serviceId;
    private String serviceName;
    private String link;
    private Integer quantity;
    private Integer startCount;
    private Integer remains;
    private String status;
    private BigDecimal charge;
    private Integer processingPriority;
    private String errorMessage;
    private String youtubeVideoId;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private boolean hasVideoProcessing;
    private boolean hasBinomCampaign;
}
