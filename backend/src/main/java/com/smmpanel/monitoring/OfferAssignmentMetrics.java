package com.smmpanel.monitoring;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Метрики для мониторинга назначения офферов
 */
@Component
@RequiredArgsConstructor
public class OfferAssignmentMetrics {

    private final MeterRegistry meterRegistry;

    // Counters
    private final Counter offerAssignmentSuccessCounter;
    private final Counter offerAssignmentErrorCounter;
    private final Counter offerCreationCounter;
    private final Counter campaignAssignmentCounter;

    // Timers
    private final Timer offerAssignmentTimer;
    private final Timer binomApiCallTimer;

    public OfferAssignmentMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        
        // Initialize counters
        this.offerAssignmentSuccessCounter = Counter.builder("offer.assignment.success")
                .description("Number of successful offer assignments")
                .register(meterRegistry);
                
        this.offerAssignmentErrorCounter = Counter.builder("offer.assignment.error")
                .description("Number of failed offer assignments")
                .tag("error_type", "unknown")
                .register(meterRegistry);
                
        this.offerCreationCounter = Counter.builder("offer.creation")
                .description("Number of offers created in Binom")
                .register(meterRegistry);
                
        this.campaignAssignmentCounter = Counter.builder("campaign.assignment")
                .description("Number of campaign assignments")
                .register(meterRegistry);

        // Initialize timers
        this.offerAssignmentTimer = Timer.builder("offer.assignment.duration")
                .description("Time taken for offer assignment")
                .register(meterRegistry);
                
        this.binomApiCallTimer = Timer.builder("binom.api.call.duration")
                .description("Time taken for Binom API calls")
                .register(meterRegistry);
    }

    public void incrementSuccessfulAssignment() {
        offerAssignmentSuccessCounter.increment();
    }

    public void incrementFailedAssignment(String errorType) {
        Counter.builder("offer.assignment.error")
                .tag("error_type", errorType)
                .register(meterRegistry)
                .increment();
    }

    public void incrementOfferCreation() {
        offerCreationCounter.increment();
    }

    public void incrementCampaignAssignment() {
        campaignAssignmentCounter.increment();
    }

    public Timer.Sample startAssignmentTimer() {
        return Timer.start(meterRegistry);
    }

    public void recordAssignmentTime(Timer.Sample sample) {
        sample.stop(offerAssignmentTimer);
    }

    public Timer.Sample startBinomApiTimer() {
        return Timer.start(meterRegistry);
    }

    public void recordBinomApiTime(Timer.Sample sample) {
        sample.stop(binomApiCallTimer);
    }
}
