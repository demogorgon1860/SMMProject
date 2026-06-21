package com.smmpanel.dto.refill;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.smmpanel.entity.RefillCheck;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Wire format for a {@link RefillCheck} — what the Refill page polls and renders. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class RefillCheckResponse {

    private Long id;
    private Long orderId;

    /** RUNNING | DONE | FAILED */
    private String status;

    /** like | follow | comment */
    private String actionType;

    private Integer orderedCount;
    private Integer delivered;
    private Integer present;
    private Integer dropped;
    private Integer refillNeeded;
    private Integer currentCount;
    private BigDecimal dropRate;
    private Boolean earlyStopped;
    private String note;
    private String error;

    /** True only when the check is DONE and there is something to re-deliver. */
    private Boolean canRefill;

    private LocalDateTime requestedAt;
    private LocalDateTime checkedAt;

    public static RefillCheckResponse from(RefillCheck c) {
        if (c == null) return null;
        boolean canRefill =
                c.getStatus() == RefillCheck.Status.DONE
                        && c.getRefillNeeded() != null
                        && c.getRefillNeeded() > 0;
        return RefillCheckResponse.builder()
                .id(c.getId())
                .orderId(c.getOrderId())
                .status(c.getStatus() == null ? null : c.getStatus().name())
                .actionType(c.getActionType())
                .orderedCount(c.getOrderedCount())
                .delivered(c.getDelivered())
                .present(c.getPresent())
                .dropped(c.getDropped())
                .refillNeeded(c.getRefillNeeded())
                .currentCount(c.getCurrentCount())
                .dropRate(c.getDropRate())
                .earlyStopped(c.getEarlyStopped())
                .note(c.getNote())
                .error(c.getError())
                .canRefill(canRefill)
                .requestedAt(c.getRequestedAt())
                .checkedAt(c.getCheckedAt())
                .build();
    }
}
