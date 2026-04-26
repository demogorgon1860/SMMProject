package com.smmpanel.dto.refill;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.smmpanel.entity.RefillRequest;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Wire format for {@link RefillRequest}. Used by both user-facing and admin endpoints. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class RefillRequestResponse {

    private Long id;
    private Long orderId;
    private Long userId;

    /** PENDING | APPROVED | REJECTED */
    private String status;

    private String userNote;
    private String rejectionReason;

    /** id of the admin user who decided. Null until decided. */
    private Long adminId;

    private LocalDateTime decidedAt;

    /** id of the new refill order created on approval. Null until APPROVED. */
    private Long refillOrderId;

    private LocalDateTime createdAt;

    public static RefillRequestResponse from(RefillRequest r) {
        if (r == null) return null;
        return RefillRequestResponse.builder()
                .id(r.getId())
                .orderId(r.getOrderId())
                .userId(r.getUserId())
                .status(r.getStatus() == null ? null : r.getStatus().name())
                .userNote(r.getUserNote())
                .rejectionReason(r.getRejectionReason())
                .adminId(r.getAdminId())
                .decidedAt(r.getDecidedAt())
                .refillOrderId(r.getRefillOrderId())
                .createdAt(r.getCreatedAt())
                .build();
    }
}
