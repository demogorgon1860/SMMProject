package com.smmpanel.config.order;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Getter
@Setter
@Validated
@ConfigurationProperties(prefix = "app.order.processing")
public class OrderProcessingProperties {

    /** Number of orders to process in a single batch */
    @Min(1)
    private int batchSize = 100;

    /** Number of threads in the order processing thread pool */
    @Min(1)
    private int threadPoolSize = 10;

    /** Maximum number of retry attempts for failed order processing */
    @Min(0)
    private int maxRetryAttempts = 3;

    /** Delay between retry attempts */
    @NotNull private Duration retryDelay = Duration.ofMinutes(1);

    /** Initial delay before first retry */
    @NotNull private Duration initialDelay = Duration.ofSeconds(5);

    /** Maximum delay between retry attempts */
    @NotNull private Duration maxDelay = Duration.ofMinutes(10);

    @Getter
    @Setter
    public static class ClipCreation {
        /** Whether clip creation is enabled */
        private boolean enabled = true;

        /** Timeout for clip creation */
        @NotNull private Duration timeout = Duration.ofMinutes(5);

        /** Number of retry attempts for failed clip creation */
        @Min(0)
        private int retryAttempts = 2;
    }

    @Getter
    @Setter
    public static class StateTransition {
        /** Timeout for state transitions */
        @NotNull private Duration timeout = Duration.ofMinutes(5);

        /** Maximum number of retry attempts for state transitions */
        @Min(0)
        private int maxRetries = 3;
    }

    private final ClipCreation clipCreation = new ClipCreation();
    private final StateTransition stateTransition = new StateTransition();
}
