package com.smmpanel.service.monitoring;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MemoryUsageSummary {
    private long heapUsed;
    private long heapMax;
    private long nonHeapUsed;
    private long nonHeapMax;
    private double heapUsagePercentage;
    private String formattedSummary;

    public long getHeapUsed() {
        return heapUsed;
    }

    public String getFormattedSummary() {
        if (formattedSummary == null) {
            formattedSummary =
                    String.format(
                            "Heap: %.2fMB/%.2fMB (%.1f%%)",
                            heapUsed / 1048576.0, heapMax / 1048576.0, heapUsagePercentage);
        }
        return formattedSummary;
    }
}
