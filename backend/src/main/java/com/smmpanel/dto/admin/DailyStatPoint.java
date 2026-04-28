package com.smmpanel.dto.admin;

import java.math.BigDecimal;
import java.time.LocalDate;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One day's worth of order activity for the dashboard charts. Fields are absent (zero) on days with
 * no orders so the frontend can render a contiguous N-day series without re-bucketing on the
 * client.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DailyStatPoint {
    private LocalDate date;
    private long total;
    private long completed;
    private long partial;
    private long cancelled;
    private BigDecimal revenue;
}
