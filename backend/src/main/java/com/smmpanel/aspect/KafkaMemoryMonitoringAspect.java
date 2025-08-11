package com.smmpanel.aspect;

import com.smmpanel.service.monitoring.MemoryMonitoringService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

/**
 * Memory Monitoring Aspect
 * Monitors memory usage for Kafka consumer operations
 */
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class KafkaMemoryMonitoringAspect {

    private final MemoryMonitoringService memoryMonitoringService;

    @Around("@annotation(org.springframework.kafka.annotation.KafkaListener)")
    public Object monitorKafkaConsumerMemory(ProceedingJoinPoint joinPoint) throws Throwable {
        String methodName = joinPoint.getSignature().getName();
        
        try {
            // Check memory before processing
            var beforeMemory = memoryMonitoringService.getMemoryUsageSummary();
            
            // Execute the Kafka consumer method
            Object result = joinPoint.proceed();
            
            // Check memory after processing
            var afterMemory = memoryMonitoringService.getMemoryUsageSummary();
            
            // Log memory usage if there's significant change
            long memoryDelta = afterMemory.getHeapUsed() - beforeMemory.getHeapUsed();
            if (memoryDelta > 10_485_760) { // 10MB threshold
                log.info("Memory usage for {}: Before={}, After={}, Delta={}MB", 
                    methodName,
                    beforeMemory.getFormattedSummary(),
                    afterMemory.getFormattedSummary(),
                    memoryDelta >> 20);
            }
            
            return result;
            
        } catch (OutOfMemoryError oome) {
            log.error("Out of memory in Kafka consumer: {}", methodName, oome);
            memoryMonitoringService.updateMemoryMetrics(); // Force metrics update
            throw oome;
        }
    }
}
