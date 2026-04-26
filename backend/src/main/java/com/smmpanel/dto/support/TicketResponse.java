package com.smmpanel.dto.support;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.smmpanel.entity.SupportTicket;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class TicketResponse {
    private Long id;
    private String topic;
    private String subject;
    private String status;
    private Long orderId;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime lastUserMessageAt;
    private LocalDateTime lastAdminMessageAt;
    private boolean unread;

    public static TicketResponse from(SupportTicket t) {
        if (t == null) return null;
        boolean unread =
                t.getLastAdminMessageAt() != null
                        && (t.getLastUserMessageAt() == null
                                || t.getLastAdminMessageAt().isAfter(t.getLastUserMessageAt()));
        return TicketResponse.builder()
                .id(t.getId())
                .topic(t.getTopic())
                .subject(t.getSubject())
                .status(t.getStatus() == null ? null : t.getStatus().name())
                .orderId(t.getOrderId())
                .createdAt(t.getCreatedAt())
                .updatedAt(t.getUpdatedAt())
                .lastUserMessageAt(t.getLastUserMessageAt())
                .lastAdminMessageAt(t.getLastAdminMessageAt())
                .unread(unread)
                .build();
    }
}
