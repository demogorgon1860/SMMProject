package com.smmpanel.service.monitoring;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Gauge;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Memory Monitoring Service
 * Monitors JVM memory usage and provides metrics for Kafka consumer threads
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MemoryMonitoringService {

    private final MeterRegistry meterRegistry;
    private final MemoryMXBean memoryMXBean = ManagementFactory.getMemoryMXBean();
    
    // Track memory metrics per consumer thread
    private final AtomicLong heapUsed = new AtomicLong(0);
    private final AtomicLong heapMax = new AtomicLong(0);
    private final AtomicLong nonHeapUsed = new AtomicLong(0);
    
    public void initialize() {
        // Register memory metrics
        Gauge.builder("kafka.consumer.memory.heap.used", heapUsed, AtomicLong::get)
                .description("Heap memory used by Kafka consumer threads")
                .baseUnit("bytes")
                .register(meterRegistry);
                
        Gauge.builder("kafka.consumer.memory.heap.max", heapMax, AtomicLong::get)
                .description("Maximum heap memory available")
                .baseUnit("bytes")
                .register(meterRegistry);
                
        Gauge.builder("kafka.consumer.memory.nonheap.used", nonHeapUsed, AtomicLong::get)
                .description("Non-heap memory used by Kafka consumer threads")
                .baseUnit("bytes")
                .register(meterRegistry);
    }

    @Scheduled(fixedRate = 5000) // Update every 5 seconds
    public void updateMemoryMetrics() {
        MemoryUsage heapMemoryUsage = memoryMXBean.getHeapMemoryUsage();
        MemoryUsage nonHeapMemoryUsage = memoryMXBean.getNonHeapMemoryUsage();
        
        heapUsed.set(heapMemoryUsage.getUsed());
        heapMax.set(heapMemoryUsage.getMax());
        nonHeapUsed.set(nonHeapMemoryUsage.getUsed());
        
        // Log warning if memory usage is high
        double memoryUsagePercent = (double) heapMemoryUsage.getUsed() / heapMemoryUsage.getMax() * 100;
        if (memoryUsagePercent > 85) {
            log.warn("High memory usage detected: {}% of heap used", String.format("%.2f", memoryUsagePercent));
        }
    }

    /**
     * Get current memory usage summary
     */
    public MemoryUsageSummary getMemoryUsageSummary() {
        MemoryUsage heap = memoryMXBean.getHeapMemoryUsage();
        MemoryUsage nonHeap = memoryMXBean.getNonHeapMemoryUsage();
        
        return MemoryUsageSummary.builder()
                .heapUsed(heap.getUsed())
                .heapCommitted(heap.getCommitted())
                .heapMax(heap.getMax())
                .nonHeapUsed(nonHeap.getUsed())
                .nonHeapCommitted(nonHeap.getCommitted())
                .build();
    }

    @lombok.Data
    @lombok.Builder
    public static class MemoryUsageSummary {
        private long heapUsed;
        private long heapCommitted;
        private long heapMax;
        private long nonHeapUsed;
        private long nonHeapCommitted;
        
        public String getFormattedSummary() {
            return String.format(
                "Memory Usage: Heap[used=%dMB, committed=%dMB, max=%dMB], NonHeap[used=%dMB, committed=%dMB]",
                heapUsed >> 20, heapCommitted >> 20, heapMax >> 20,
                nonHeapUsed >> 20, nonHeapCommitted >> 20
            );
        }
    }
}
