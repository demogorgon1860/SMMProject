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
public class AdminOrderDto {
    private Long id;
    private String username;
    private Long serviceId;
    private String serviceName;
    private String link;
    private Integer quantity;
    private BigDecimal charge;
    private Integer startCount;
    private Integer remains;
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
