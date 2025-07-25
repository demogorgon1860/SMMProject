package com.smmpanel.config.monitoring;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;

@Getter
@Setter
@Validated
@ConfigurationProperties(prefix = "app.order.sla")
public class SlaMonitoringProperties {
    
    /**
     * Whether SLA monitoring is enabled
     */
    private boolean enabled = true;
    
    /**
     * Interval between SLA monitoring runs
     */
    @NotNull
    private Duration interval = Duration.ofMinutes(5);
    
    /**
     * Initial delay before starting SLA monitoring
     */
    @NotNull
    private Duration initialDelay = Duration.ofSeconds(30);
    
    /**
     * Number of threads in the SLA monitoring thread pool
     */
    @Min(1)
    private int threadPoolSize = 3;
    
    @Getter
    @Setter
    public static class Thresholds {
        /**
         * Warning threshold for order processing time
         */
        @NotNull
        private Duration warning = Duration.ofMinutes(5);
        
        /**
         * Critical threshold for order processing time
         */
        @NotNull
        private Duration critical = Duration.ofMinutes(15);
    }
    
    private final Thresholds processing = new Thresholds();
    
    @Getter
    @Setter
    public static class CompletionThresholds {
        /**
         * Warning threshold for order completion time
         */
        @NotNull
        private Duration warning = Duration.ofDays(1);
        
        /**
         * Critical threshold for order completion time
         */
        @NotNull
        private Duration critical = Duration.ofDays(2);
    }
    
    private final CompletionThresholds completion = new CompletionThresholds();
}
