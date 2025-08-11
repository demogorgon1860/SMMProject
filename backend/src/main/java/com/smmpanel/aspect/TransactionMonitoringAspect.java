package com.smmpanel.aspect;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.interceptor.TransactionAspectSupport;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Slf4j
@Aspect
@Component
@ConditionalOnProperty(
        name = "app.transaction.monitoring.enabled",
        havingValue = "true",
        matchIfMissing = false)
public class TransactionMonitoringAspect {

    private final AtomicLong transactionCounter = new AtomicLong(0);
    private final ConcurrentHashMap<String, TransactionMetrics> transactionMetrics =
            new ConcurrentHashMap<>();

    @Around("execution(* com.smmpanel.service.BalanceService.*(..))")
    public Object monitorBalanceTransactions(ProceedingJoinPoint joinPoint) throws Throwable {
        return monitorTransaction(joinPoint, "BALANCE");
    }

    @Around("execution(* com.smmpanel.service.OrderService.*(..))")
    public Object monitorOrderTransactions(ProceedingJoinPoint joinPoint) throws Throwable {
        return monitorTransaction(joinPoint, "ORDER");
    }

    @Around("execution(* com.smmpanel.service.*Service.*(..))")
    public Object monitorGeneralTransactions(ProceedingJoinPoint joinPoint) throws Throwable {
        return monitorTransaction(joinPoint, "GENERAL");
    }

    private Object monitorTransaction(ProceedingJoinPoint joinPoint, String category)
            throws Throwable {
        String methodName = joinPoint.getSignature().toShortString();
        long transactionId = transactionCounter.incrementAndGet();
        long startTime = System.currentTimeMillis();

        // Check transaction state before execution
        boolean wasTransactionActive =
                TransactionSynchronizationManager.isActualTransactionActive();
        String isolationLevel = getIsolationLevel();
        boolean isReadOnly = TransactionSynchronizationManager.isCurrentTransactionReadOnly();

        log.debug(
                "Transaction {} started - Method: {}, Category: {}, Active: {}, Isolation: {},"
                        + " ReadOnly: {}",
                transactionId,
                methodName,
                category,
                wasTransactionActive,
                isolationLevel,
                isReadOnly);

        TransactionMetrics metrics =
                transactionMetrics.computeIfAbsent(methodName, k -> new TransactionMetrics());
        metrics.incrementAttempts();

        try {
            Object result = joinPoint.proceed();

            long duration = System.currentTimeMillis() - startTime;
            metrics.recordSuccess(duration);

            // Check if transaction is still active after execution
            boolean isStillActive = TransactionSynchronizationManager.isActualTransactionActive();

            log.debug(
                    "Transaction {} completed successfully - Method: {}, Duration: {}ms, Still"
                            + " Active: {}",
                    transactionId,
                    methodName,
                    duration,
                    isStillActive);

            // Log long-running transactions
            if (duration > 5000) {
                log.warn(
                        "Long-running transaction detected - Method: {}, Duration: {}ms, Category:"
                                + " {}",
                        methodName,
                        duration,
                        category);
            }

            return result;

        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            metrics.recordFailure(duration);

            boolean isMarkedRollbackOnly =
                    TransactionAspectSupport.currentTransactionStatus().isRollbackOnly();

            log.error(
                    "Transaction {} failed - Method: {}, Duration: {}ms, Exception: {},"
                            + " RollbackOnly: {}",
                    transactionId,
                    methodName,
                    duration,
                    e.getClass().getSimpleName(),
                    isMarkedRollbackOnly);

            throw e;
        }
    }

    private String getIsolationLevel() {
        try {
            if (TransactionSynchronizationManager.isActualTransactionActive()) {
                // This is a simplified approach - in practice, you might need to access the
                // transaction definition
                return "ACTIVE";
            }
            return "NONE";
        } catch (Exception e) {
            return "UNKNOWN";
        }
    }

    /** Get transaction metrics for monitoring */
    public ConcurrentHashMap<String, TransactionMetrics> getTransactionMetrics() {
        return new ConcurrentHashMap<>(transactionMetrics);
    }

    /** Reset transaction metrics (useful for testing) */
    public void resetMetrics() {
        transactionMetrics.clear();
        transactionCounter.set(0);
    }

    /** Transaction metrics holder */
    public static class TransactionMetrics {
        private final AtomicLong attempts = new AtomicLong(0);
        private final AtomicLong successes = new AtomicLong(0);
        private final AtomicLong failures = new AtomicLong(0);
        private final AtomicLong totalDuration = new AtomicLong(0);
        private final AtomicLong maxDuration = new AtomicLong(0);
        private final AtomicLong minDuration = new AtomicLong(Long.MAX_VALUE);

        public void incrementAttempts() {
            attempts.incrementAndGet();
        }

        public void recordSuccess(long duration) {
            successes.incrementAndGet();
            recordDuration(duration);
        }

        public void recordFailure(long duration) {
            failures.incrementAndGet();
            recordDuration(duration);
        }

        private void recordDuration(long duration) {
            totalDuration.addAndGet(duration);
            maxDuration.accumulateAndGet(duration, Math::max);
            minDuration.accumulateAndGet(duration, Math::min);
        }

        public long getAttempts() {
            return attempts.get();
        }

        public long getSuccesses() {
            return successes.get();
        }

        public long getFailures() {
            return failures.get();
        }

        public long getTotalDuration() {
            return totalDuration.get();
        }

        public long getMaxDuration() {
            return maxDuration.get();
        }

        public long getMinDuration() {
            return minDuration.get() == Long.MAX_VALUE ? 0 : minDuration.get();
        }

        public double getAverageDuration() {
            long total = getAttempts();
            return total > 0 ? (double) getTotalDuration() / total : 0.0;
        }

        public double getSuccessRate() {
            long total = getAttempts();
            return total > 0 ? (double) getSuccesses() / total * 100 : 0.0;
        }

        @Override
        public String toString() {
            return String.format(
                    "TransactionMetrics{attempts=%d, successes=%d, failures=%d, avgDuration=%.2fms,"
                            + " maxDuration=%dms, successRate=%.2f%%}",
                    getAttempts(),
                    getSuccesses(),
                    getFailures(),
                    getAverageDuration(),
                    getMaxDuration(),
                    getSuccessRate());
        }
    }
}
