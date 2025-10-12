package com.smmpanel.service.startup;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.LivenessState;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

/**
 * Service to manage application readiness and liveness states. Ensures the application reports as
 * ready and live after successful startup.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ApplicationReadinessService {

    private final ApplicationEventPublisher eventPublisher;

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        log.info("Application is ready - setting readiness and liveness states to UP");

        // Set readiness state to ACCEPTING_TRAFFIC
        AvailabilityChangeEvent.publish(eventPublisher, this, ReadinessState.ACCEPTING_TRAFFIC);

        // Set liveness state to CORRECT
        AvailabilityChangeEvent.publish(eventPublisher, this, LivenessState.CORRECT);

        log.info("Application readiness and liveness states have been set to UP");
    }
}
