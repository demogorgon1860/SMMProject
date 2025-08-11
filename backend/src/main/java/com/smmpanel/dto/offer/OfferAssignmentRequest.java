package com.smmpanel.dto.offer;

import lombok.*;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OfferAssignmentRequest {
    private Long orderId;
    private String offerId;
    private String source; // Add this field
}
