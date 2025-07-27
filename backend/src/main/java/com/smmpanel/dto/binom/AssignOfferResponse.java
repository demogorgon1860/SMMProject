package com.smmpanel.dto.binom;

import lombok.*;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AssignOfferResponse {
    private boolean success;
    private String campaignId;
    private String status;
    private String message;
    private Integer campaignsCreated;
    private List<String> campaignIds;
    private String offerId;
} 
