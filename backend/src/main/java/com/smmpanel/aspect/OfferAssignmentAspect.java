package com.smmpanel.aspect;

import com.smmpanel.monitoring.OfferAssignmentMetrics;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class OfferAssignmentAspect {

    private final OfferAssignmentMetrics metrics;

    @Around(
            "execution(*"
                + " com.smmpanel.service.integration.BinomService.assignOfferToFixedCampaigns(..))")
    public Object logOfferAssignment(ProceedingJoinPoint joinPoint) throws Throwable {
        Timer.Sample sample = metrics.startAssignmentTimer();

        try {
            Object result = joinPoint.proceed();
            metrics.incrementSuccessfulAssignment();
            log.info("Offer assignment completed successfully");
            return result;

        } catch (Exception e) {
            metrics.incrementFailedAssignment(e.getClass().getSimpleName());
            log.error("Offer assignment failed: {}", e.getMessage(), e);
            throw e;

        } finally {
            metrics.recordAssignmentTime(sample);
        }
    }

    @Around("execution(* com.smmpanel.client.BinomClient.*(..))")
    public Object logBinomApiCalls(ProceedingJoinPoint joinPoint) throws Throwable {
        Timer.Sample sample = metrics.startBinomApiTimer();
        String methodName = joinPoint.getSignature().getName();

        try {
            log.debug("Calling Binom API method: {}", methodName);
            Object result = joinPoint.proceed();
            log.debug("Binom API call successful: {}", methodName);
            return result;

        } catch (Exception e) {
            log.error("Binom API call failed: {} - {}", methodName, e.getMessage());
            throw e;

        } finally {
            metrics.recordBinomApiTime(sample);
        }
    }
}
