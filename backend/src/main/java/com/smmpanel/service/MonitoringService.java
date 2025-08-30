package com.smmpanel.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.ThreadMXBean;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.actuate.health.Status;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class MonitoringService {

    private final MeterRegistry meterRegistry;
    private final List<HealthIndicator> healthIndicators;
    private final KafkaTemplate<String, Object> kafkaTemplate;

    // Metrics counters
    private final Counter paymentProcessedCounter;
    private final Counter orderCreatedCounter;
    private final Counter errorCounter;
    private final Timer paymentProcessingTimer;

    // Alert thresholds
    private static final double CPU_ALERT_THRESHOLD = 0.8;
    private static final double MEMORY_ALERT_THRESHOLD = 0.9;
    private static final long RESPONSE_TIME_ALERT_MS = 5000;

    // Metrics cache
    private final Map<String, AtomicLong> metricValues = new ConcurrentHashMap<>();
    private final AtomicInteger activeOrders = new AtomicInteger(0);
    private final AtomicInteger activePayments = new AtomicInteger(0);

    public MonitoringService(
            MeterRegistry meterRegistry,
            List<HealthIndicator> healthIndicators,
            KafkaTemplate<String, Object> kafkaTemplate) {
        this.meterRegistry = meterRegistry;
        this.healthIndicators = healthIndicators;
        this.kafkaTemplate = kafkaTemplate;

        // Initialize counters
        this.paymentProcessedCounter =
                Counter.builder("payments.processed")
                        .description("Number of payments processed")
                        .register(meterRegistry);

        this.orderCreatedCounter =
                Counter.builder("orders.created")
                        .description("Number of orders created")
                        .register(meterRegistry);

        this.errorCounter =
                Counter.builder("application.errors")
                        .description("Number of application errors")
                        .register(meterRegistry);

        this.paymentProcessingTimer =
                Timer.builder("payment.processing.time")
                        .description("Payment processing time")
                        .register(meterRegistry);

        // Register gauges for active metrics
        Gauge.builder("orders.active", activeOrders, AtomicInteger::get)
                .description("Number of active orders")
                .register(meterRegistry);

        Gauge.builder("payments.active", activePayments, AtomicInteger::get)
                .description("Number of active payments")
                .register(meterRegistry);

        // Register JVM metrics
        registerJvmMetrics();
    }

    /** Register JVM metrics */
    private void registerJvmMetrics() {
        MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
        ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();

        Gauge.builder(
                        "jvm.memory.heap.used",
                        memoryBean,
                        bean -> bean.getHeapMemoryUsage().getUsed())
                .description("JVM heap memory used")
                .register(meterRegistry);

        Gauge.builder("jvm.threads.count", threadBean, ThreadMXBean::getThreadCount)
                .description("JVM thread count")
                .register(meterRegistry);
    }

    /** Force a comprehensive health check */
    public Map<String, Object> forceHealthCheck() {
        log.info("Forcing comprehensive health check at {}", LocalDateTime.now());

        Map<String, Object> healthStatus = new HashMap<>();

        // Check all health indicators
        for (HealthIndicator indicator : healthIndicators) {
            try {
                Health health = indicator.health();
                String indicatorName =
                        indicator.getClass().getSimpleName().replace("HealthIndicator", "");
                healthStatus.put(indicatorName, health.getStatus().getCode());

                // Log if not healthy
                if (!Status.UP.equals(health.getStatus())) {
                    log.warn("Health check failed for {}: {}", indicatorName, health.getDetails());
                    // Send alert
                    sendHealthAlert(indicatorName, health);
                }
            } catch (Exception e) {
                log.error("Error checking health indicator: {}", e.getMessage());
            }
        }

        // Add system metrics
        healthStatus.putAll(getSystemMetrics());

        return healthStatus;
    }

    /** Get comprehensive system health status with metrics */
    public Map<String, Object> getSystemHealth() {
        Map<String, Object> health = new HashMap<>();

        // Basic info
        health.put("status", calculateOverallStatus());
        health.put("timestamp", LocalDateTime.now());
        health.put("version", "1.0.0");

        // Component health
        Map<String, String> componentHealth = new HashMap<>();
        for (HealthIndicator indicator : healthIndicators) {
            try {
                Health indicatorHealth = indicator.health();
                String name = indicator.getClass().getSimpleName().replace("HealthIndicator", "");
                componentHealth.put(name, indicatorHealth.getStatus().getCode());
            } catch (Exception e) {
                componentHealth.put(indicator.getClass().getSimpleName(), "UNKNOWN");
            }
        }
        health.put("components", componentHealth);

        // Metrics
        health.put("metrics", getSystemMetrics());

        // Active counts
        health.put("activeOrders", activeOrders.get());
        health.put("activePayments", activePayments.get());

        return health;
    }

    /** Calculate overall system status */
    private String calculateOverallStatus() {
        for (HealthIndicator indicator : healthIndicators) {
            try {
                Health health = indicator.health();
                if (!Status.UP.equals(health.getStatus())) {
                    return "DEGRADED";
                }
            } catch (Exception e) {
                return "UNKNOWN";
            }
        }
        return "UP";
    }

    /** Get system metrics */
    private Map<String, Object> getSystemMetrics() {
        Map<String, Object> metrics = new HashMap<>();

        // Memory metrics
        MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
        long heapUsed = memoryBean.getHeapMemoryUsage().getUsed();
        long heapMax = memoryBean.getHeapMemoryUsage().getMax();
        double memoryUsage = (double) heapUsed / heapMax;

        metrics.put("memory.heap.used", heapUsed);
        metrics.put("memory.heap.max", heapMax);
        metrics.put("memory.heap.usage", memoryUsage);

        // Thread metrics
        ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();
        metrics.put("threads.count", threadBean.getThreadCount());
        metrics.put("threads.peak", threadBean.getPeakThreadCount());

        // CPU metrics
        double cpuUsage = ManagementFactory.getOperatingSystemMXBean().getSystemLoadAverage();
        metrics.put("cpu.usage", cpuUsage);

        // Check thresholds and send alerts if needed
        checkMetricThresholds(memoryUsage, cpuUsage);

        return metrics;
    }

    /** Check metric thresholds and send alerts */
    private void checkMetricThresholds(double memoryUsage, double cpuUsage) {
        if (memoryUsage > MEMORY_ALERT_THRESHOLD) {
            sendAlert(
                    "HIGH_MEMORY",
                    "Memory usage is at " + String.format("%.2f%%", memoryUsage * 100));
        }

        if (cpuUsage > CPU_ALERT_THRESHOLD) {
            sendAlert("HIGH_CPU", "CPU usage is at " + String.format("%.2f", cpuUsage));
        }
    }

    /** Send health alert */
    private void sendHealthAlert(String component, Health health) {
        Map<String, Object> alert =
                Map.of(
                        "type", "HEALTH_ALERT",
                        "component", component,
                        "status", health.getStatus().getCode(),
                        "details", health.getDetails(),
                        "timestamp", LocalDateTime.now());

        try {
            kafkaTemplate.send("smm.monitoring.alerts", alert);
            log.warn("Health alert sent for component: {}", component);
        } catch (Exception e) {
            log.error("Failed to send health alert: {}", e.getMessage());
        }
    }

    /** Send metric alert */
    private void sendAlert(String alertType, String message) {
        Map<String, Object> alert =
                Map.of(
                        "type", alertType,
                        "message", message,
                        "timestamp", LocalDateTime.now());

        try {
            kafkaTemplate.send("smm.monitoring.alerts", alert);
            log.warn("Alert sent: {} - {}", alertType, message);
        } catch (Exception e) {
            log.error("Failed to send alert: {}", e.getMessage());
        }
    }

    // Metric recording methods

    /** Record payment processed */
    public void recordPaymentProcessed(long processingTimeMs) {
        paymentProcessedCounter.increment();
        paymentProcessingTimer.record(Duration.ofMillis(processingTimeMs));
        activePayments.decrementAndGet();
    }

    /** Record order created */
    public void recordOrderCreated() {
        orderCreatedCounter.increment();
        activeOrders.incrementAndGet();
    }

    /** Record order completed */
    public void recordOrderCompleted() {
        activeOrders.decrementAndGet();
    }

    /** Record error */
    public void recordError(String errorType) {
        errorCounter.increment();
        metricValues
                .computeIfAbsent("errors." + errorType, k -> new AtomicLong(0))
                .incrementAndGet();
    }

    /** Start payment processing */
    public Timer.Sample startPaymentProcessing() {
        activePayments.incrementAndGet();
        return Timer.start(meterRegistry);
    }

    /** Get current metrics snapshot */
    public Map<String, Object> getMetricsSnapshot() {
        Map<String, Object> snapshot = new HashMap<>();

        snapshot.put("payments.processed.count", paymentProcessedCounter.count());
        snapshot.put("orders.created.count", orderCreatedCounter.count());
        snapshot.put("errors.count", errorCounter.count());
        snapshot.put("orders.active", activeOrders.get());
        snapshot.put("payments.active", activePayments.get());

        // Add custom metrics
        metricValues.forEach((key, value) -> snapshot.put(key, value.get()));

        return snapshot;
    }
}
