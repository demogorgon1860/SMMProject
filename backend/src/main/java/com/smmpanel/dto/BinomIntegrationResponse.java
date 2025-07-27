package com.smmpanel.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;

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