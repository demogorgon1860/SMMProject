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
    private int campaignsCreated;
    private String status;
    private java.util.List<String> campaignIds;

    public int getCampaignsCreated() {
        return campaignsCreated;
    }
}
