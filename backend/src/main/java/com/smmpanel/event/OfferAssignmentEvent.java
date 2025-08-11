package com.smmpanel.event;

import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OfferAssignmentEvent {
    private String eventId;
    private Long orderId;
    private String offerName;
    private String targetUrl;
    private String description;
    private String geoTargeting;
    private String source;
    private LocalDateTime timestamp;
}
