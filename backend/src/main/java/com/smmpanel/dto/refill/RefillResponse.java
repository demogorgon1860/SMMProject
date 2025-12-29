package com.smmpanel.dto.refill;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RefillResponse {
    private Long refillId;
    private Long originalOrderId;
    private Long refillOrderId;
    private Integer refillNumber;

    private Integer originalQuantity;
    private Integer deliveredQuantity;
    private Integer refillQuantity;

    private Long currentViewCount;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createdAt;

    private String message;
}
