package com.smmpanel.dto.support;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.smmpanel.entity.SupportTicketMessage;
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
public class TicketMessageResponse {
    private Long id;
    private String authorKind;
    private Long authorUserId;
    private String body;
    private LocalDateTime createdAt;

    public static TicketMessageResponse from(SupportTicketMessage m) {
        if (m == null) return null;
        return TicketMessageResponse.builder()
                .id(m.getId())
                .authorKind(m.getAuthorKind() == null ? null : m.getAuthorKind().name())
                .authorUserId(m.getAuthorUserId())
                .body(m.getBody())
                .createdAt(m.getCreatedAt())
                .build();
    }
}
