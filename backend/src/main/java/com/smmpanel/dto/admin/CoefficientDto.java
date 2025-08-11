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
public class CoefficientDto {
    private Long id;
    private Long serviceId;
    private BigDecimal withClip;
    private BigDecimal withoutClip;
    private String updatedBy;
    private LocalDateTime updatedAt;
}
