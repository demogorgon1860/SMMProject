package com.smmpanel.dto.binom;

import java.util.List;
import lombok.*;

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
