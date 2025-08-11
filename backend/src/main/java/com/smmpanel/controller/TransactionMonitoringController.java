package com.smmpanel.controller;

import com.smmpanel.aspect.TransactionMonitoringAspect;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/admin/monitoring/transactions")
@RequiredArgsConstructor
@ConditionalOnBean(TransactionMonitoringAspect.class)
@Tag(name = "Transaction Monitoring", description = "Transaction monitoring and metrics endpoints")
@PreAuthorize("hasRole('ADMIN')")
public class TransactionMonitoringController {

    private final TransactionMonitoringAspect transactionMonitoringAspect;

    @GetMapping("/metrics")
    @Operation(
            summary = "Get transaction metrics",
            description = "Returns detailed transaction metrics for all monitored methods")
    public ResponseEntity<Map<String, Object>> getTransactionMetrics() {
        ConcurrentHashMap<String, TransactionMonitoringAspect.TransactionMetrics> metrics =
                transactionMonitoringAspect.getTransactionMetrics();

        Map<String, Object> response = new HashMap<>();
        Map<String, Object> methodMetrics = new HashMap<>();

        for (Map.Entry<String, TransactionMonitoringAspect.TransactionMetrics> entry :
                metrics.entrySet()) {
            TransactionMonitoringAspect.TransactionMetrics metric = entry.getValue();

            Map<String, Object> methodData = new HashMap<>();
            methodData.put("attempts", metric.getAttempts());
            methodData.put("successes", metric.getSuccesses());
            methodData.put("failures", metric.getFailures());
            methodData.put("successRate", metric.getSuccessRate());
            methodData.put("averageDurationMs", metric.getAverageDuration());
            methodData.put("maxDurationMs", metric.getMaxDuration());
            methodData.put("minDurationMs", metric.getMinDuration());
            methodData.put("totalDurationMs", metric.getTotalDuration());

            methodMetrics.put(entry.getKey(), methodData);
        }

        response.put("methods", methodMetrics);
        response.put("totalMethods", metrics.size());

        // Calculate overall statistics
        long totalAttempts =
                metrics.values().stream()
                        .mapToLong(TransactionMonitoringAspect.TransactionMetrics::getAttempts)
                        .sum();
        long totalSuccesses =
                metrics.values().stream()
                        .mapToLong(TransactionMonitoringAspect.TransactionMetrics::getSuccesses)
                        .sum();
        long totalFailures =
                metrics.values().stream()
                        .mapToLong(TransactionMonitoringAspect.TransactionMetrics::getFailures)
                        .sum();

        Map<String, Object> overallStats = new HashMap<>();
        overallStats.put("totalAttempts", totalAttempts);
        overallStats.put("totalSuccesses", totalSuccesses);
        overallStats.put("totalFailures", totalFailures);
        overallStats.put(
                "overallSuccessRate",
                totalAttempts > 0 ? (double) totalSuccesses / totalAttempts * 100 : 0.0);

        response.put("overall", overallStats);

        return ResponseEntity.ok(response);
    }

    @GetMapping("/metrics/{methodPattern}")
    @Operation(
            summary = "Get metrics for specific method pattern",
            description = "Returns transaction metrics for methods matching the given pattern")
    public ResponseEntity<Map<String, Object>> getMethodMetrics(
            @PathVariable String methodPattern) {
        ConcurrentHashMap<String, TransactionMonitoringAspect.TransactionMetrics> allMetrics =
                transactionMonitoringAspect.getTransactionMetrics();

        Map<String, Object> response = new HashMap<>();
        Map<String, Object> matchingMetrics = new HashMap<>();

        for (Map.Entry<String, TransactionMonitoringAspect.TransactionMetrics> entry :
                allMetrics.entrySet()) {
            if (entry.getKey().contains(methodPattern)) {
                TransactionMonitoringAspect.TransactionMetrics metric = entry.getValue();

                Map<String, Object> methodData = new HashMap<>();
                methodData.put("attempts", metric.getAttempts());
                methodData.put("successes", metric.getSuccesses());
                methodData.put("failures", metric.getFailures());
                methodData.put("successRate", metric.getSuccessRate());
                methodData.put("averageDurationMs", metric.getAverageDuration());
                methodData.put("maxDurationMs", metric.getMaxDuration());
                methodData.put("minDurationMs", metric.getMinDuration());

                matchingMetrics.put(entry.getKey(), methodData);
            }
        }

        response.put("pattern", methodPattern);
        response.put("matchingMethods", matchingMetrics);
        response.put("matchCount", matchingMetrics.size());

        return ResponseEntity.ok(response);
    }

    @PostMapping("/metrics/reset")
    @Operation(
            summary = "Reset transaction metrics",
            description = "Clears all transaction metrics (useful for testing)")
    public ResponseEntity<Map<String, String>> resetMetrics() {
        transactionMonitoringAspect.resetMetrics();

        Map<String, String> response = new HashMap<>();
        response.put("status", "success");
        response.put("message", "Transaction metrics have been reset");

        return ResponseEntity.ok(response);
    }

    @GetMapping("/health")
    @Operation(
            summary = "Transaction system health check",
            description = "Returns health status of the transaction monitoring system")
    public ResponseEntity<Map<String, Object>> getTransactionHealth() {
        ConcurrentHashMap<String, TransactionMonitoringAspect.TransactionMetrics> metrics =
                transactionMonitoringAspect.getTransactionMetrics();

        Map<String, Object> response = new HashMap<>();

        // Calculate health indicators
        long totalAttempts =
                metrics.values().stream()
                        .mapToLong(TransactionMonitoringAspect.TransactionMetrics::getAttempts)
                        .sum();
        long totalFailures =
                metrics.values().stream()
                        .mapToLong(TransactionMonitoringAspect.TransactionMetrics::getFailures)
                        .sum();

        double overallFailureRate =
                totalAttempts > 0 ? (double) totalFailures / totalAttempts * 100 : 0.0;

        // Check for concerning patterns
        boolean hasHighFailureRate = overallFailureRate > 10.0; // More than 10% failure rate
        boolean hasSlowTransactions =
                metrics.values().stream()
                        .anyMatch(
                                m ->
                                        m.getMaxDuration()
                                                > 10000); // Any transaction taking more than 10
        // seconds

        String healthStatus;
        if (hasHighFailureRate || hasSlowTransactions) {
            healthStatus = "WARNING";
        } else if (overallFailureRate > 5.0) {
            healthStatus = "CAUTION";
        } else {
            healthStatus = "HEALTHY";
        }

        response.put("status", healthStatus);
        response.put("overallFailureRate", overallFailureRate);
        response.put("totalAttempts", totalAttempts);
        response.put("totalFailures", totalFailures);
        response.put("hasHighFailureRate", hasHighFailureRate);
        response.put("hasSlowTransactions", hasSlowTransactions);
        response.put("monitoredMethods", metrics.size());

        // Add recommendations
        Map<String, String> recommendations = new HashMap<>();
        if (hasHighFailureRate) {
            recommendations.put(
                    "highFailureRate",
                    "Review failing transactions and consider optimizing retry logic");
        }
        if (hasSlowTransactions) {
            recommendations.put(
                    "slowTransactions",
                    "Investigate slow transactions and consider optimizing database queries or"
                            + " isolation levels");
        }
        if (overallFailureRate > 5.0) {
            recommendations.put(
                    "moderateFailures",
                    "Monitor transaction patterns and consider adjusting timeout settings");
        }

        response.put("recommendations", recommendations);

        return ResponseEntity.ok(response);
    }
}
