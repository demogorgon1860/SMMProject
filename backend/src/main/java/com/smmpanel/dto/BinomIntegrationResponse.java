package com.smmpanel.dto;

import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BinomIntegrationResponse {
    private boolean success;
    private String campaignId;
    private String message;
    private String errorCode;
    private LocalDateTime createdAt;
}
