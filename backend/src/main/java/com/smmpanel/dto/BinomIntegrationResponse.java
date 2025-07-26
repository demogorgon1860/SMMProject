package com.smmpanel.dto;

import lombok.Data;
import lombok.Builder;
import java.time.LocalDateTime;

@Data
@Builder
public class BinomIntegrationResponse {
    private boolean success;
    private String campaignId;
    private String message;
    private String errorCode;
    private LocalDateTime createdAt;
} 