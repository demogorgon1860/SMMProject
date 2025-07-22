package com.smmpanel.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Event для создания оффера и назначения на фиксированные кампании
 */
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
    private LocalDateTime timestamp;
    private String source; // "VIDEO_PROCESSING", "MANUAL", etc.
}
