package com.smmpanel.dto.refill;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.smmpanel.entity.RefillRequest;
import java.math.BigDecimal;
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

    // ---- Bot drop-check snapshot (null when the request had no prior check) ----
    private Integer refillNeeded;
    private Integer dropped;
    private BigDecimal dropRate;
    private Integer currentCount;
    private LocalDateTime checkedAt;

    /** True when the bot scan stopped early — the dropped amount is a conservative estimate. */
    private Boolean earlyStopped;

    /** True when the backing drop-check is older than the staleness threshold at read time. */
    private Boolean staleCheck;

    /** A drop-check older than this (minutes) is flagged stale for the admin warning. */
    private static final long STALE_CHECK_MINUTES = 30;

    public static RefillRequestResponse from(RefillRequest r) {
        if (r == null) return null;
        Boolean stale =
                r.getBotCheckedAt() == null
                        ? null
                        : r.getBotCheckedAt()
                                .isBefore(LocalDateTime.now().minusMinutes(STALE_CHECK_MINUTES));
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
                .refillNeeded(r.getBotRefillNeeded())
                .dropped(r.getBotDropped())
                .dropRate(r.getBotDropRate())
                .currentCount(r.getBotCurrentCount())
                .checkedAt(r.getBotCheckedAt())
                .earlyStopped(r.getBotEarlyStopped())
                .staleCheck(stale)
                .build();
    }
}
