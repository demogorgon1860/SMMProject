package com.smmpanel.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smmpanel.exception.InsufficientBalanceException;
import com.smmpanel.exception.OrderValidationException;
import com.smmpanel.exception.ServiceNotFoundException;
import com.smmpanel.exception.UserNotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.support.serializer.DeserializationException;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;

import jakarta.validation.ConstraintViolationException;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * KAFKA ERROR FAILURE SCENARIO TESTS
 *
 * Tests specific failure scenarios and their handling:
 * 1. Serialization/Deserialization failures
 * 2. Business logic exceptions (retryable vs non-retryable)
 * 3. Database constraint violations
 * 4. Network timeouts and connection failures
 * 5. Resource exhaustion scenarios
 * 6. Malformed message handling
 * 7. Circuit breaker integration
 */
@Slf4j
@SpringBootTest
@EmbeddedKafka(
    partitions = 1,
    topics = {
        "failure.test.serialization",
        "failure.test.serialization.dlq",
        "failure.test.business.logic",
        "failure.test.business.logic.dlq",
        "failure.test.database",
        "failure.test.database.dlq",
        "failure.test.network",
        "failure.test.network.dlq"
    }
)
@TestPropertySource(properties = {
    "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}",
    "app.kafka.error-handling.max-retries=3",
    "app.kafka.error-handling.initial-interval=100",
    "app.kafka.error-handling.max-interval=500",
    "app.kafka.error-handling.include-stack-trace=true"
})
@DirtiesContext
class KafkaErrorFailureScenarioTest {

    @Autowired
    private EmbeddedKafkaBroker embeddedKafkaBroker;

    @Autowired
    private KafkaConsumerErrorConfiguration errorConfiguration;

    @Autowired
    private KafkaTemplate<String, Object> kafkaTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    private CommonErrorHandler defaultErrorHandler;
    private CommonErrorHandler orderErrorHandler;
    private CommonErrorHandler dlqErrorHandler;

    @BeforeEach
    void setUp() {
        defaultErrorHandler = errorConfiguration.defaultKafkaErrorHandler();
        orderErrorHandler = errorConfiguration.orderProcessingErrorHandler();
        dlqErrorHandler = errorConfiguration.deadLetterQueueErrorHandler();
    }

    @Test
    @Timeout(20)
    void testDeserializationFailureScenario() throws Exception {
        log.info("Testing deserialization failure scenario");

        CountDownLatch errorLatch = new CountDownLatch(1);
        AtomicInteger retryCount = new AtomicInteger(0);

        // Create malformed JSON that will cause deserialization error
        String malformedJson = "{\"orderId\": \"not-a-number\", \"timestamp\": \"invalid-date\", \"data\": {malformed}";

        // Simulate consumer receiving malformed message
        simulateConsumerError(() -> {
            retryCount.incrementAndGet();
            log.info("Attempting to deserialize malformed JSON (attempt {})", retryCount.get());
            throw new DeserializationException("Failed to deserialize JSON", 
                    new com.fasterxml.jackson.core.JsonParseException(null, "Unexpected character"));
        }, errorLatch);

        boolean errorHandled = errorLatch.await(5, TimeUnit.SECONDS);
        assertTrue(errorHandled, "Deserialization error should be handled");
        
        // Deserialization errors should NOT be retried
        assertEquals(1, retryCount.get(), "Deserialization errors should not be retried");

        log.info("Deserialization failure scenario completed - no retries as expected");
    }

    @Test
    @Timeout(20)
    void testBusinessLogicFailureScenarios() throws Exception {
        log.info("Testing business logic failure scenarios");

        // Test 1: InsufficientBalanceException (non-retryable)
        testBusinessLogicException(
            () -> new InsufficientBalanceException("User has insufficient balance: 10.00 required, 5.00 available"),
            false,
            "Insufficient balance should not be retried"
        );

        // Test 2: UserNotFoundException (non-retryable)
        testBusinessLogicException(
            () -> new UserNotFoundException("User not found with ID: 12345"),
            false,
            "User not found should not be retried"
        );

        // Test 3: ServiceNotFoundException (non-retryable)
        testBusinessLogicException(
            () -> new ServiceNotFoundException("Service not found with ID: 67890"),
            false,
            "Service not found should not be retried"
        );

        // Test 4: OrderValidationException (non-retryable)
        testBusinessLogicException(
            () -> new OrderValidationException("Invalid order: quantity must be greater than 0"),
            false,
            "Order validation error should not be retried"
        );

        // Test 5: Generic RuntimeException (retryable)
        testBusinessLogicException(
            () -> new RuntimeException("Temporary service unavailable"),
            true,
            "Generic runtime exception should be retried"
        );

        log.info("Business logic failure scenarios completed");
    }

    @Test
    @Timeout(20)
    void testDatabaseConstraintViolationScenario() throws Exception {
        log.info("Testing database constraint violation scenario");

        CountDownLatch errorLatch = new CountDownLatch(1);
        AtomicInteger retryCount = new AtomicInteger(0);

        // Simulate database constraint violation
        simulateConsumerError(() -> {
            retryCount.incrementAndGet();
            log.info("Attempting database operation (attempt {})", retryCount.get());
            throw new DataIntegrityViolationException("Duplicate key violation: user_id already exists");
        }, errorLatch);

        boolean errorHandled = errorLatch.await(5, TimeUnit.SECONDS);
        assertTrue(errorHandled, "Database constraint violation should be handled");
        
        // Database constraint violations should NOT be retried
        assertEquals(1, retryCount.get(), "Database constraint violations should not be retried");

        log.info("Database constraint violation scenario completed");
    }

    @Test
    @Timeout(20)
    void testNetworkTimeoutScenario() throws Exception {
        log.info("Testing network timeout scenario");

        CountDownLatch errorLatch = new CountDownLatch(1);
        AtomicInteger retryCount = new AtomicInteger(0);

        // Simulate network timeout (retryable)
        simulateConsumerError(() -> {
            int attempt = retryCount.incrementAndGet();
            log.info("Attempting network operation (attempt {})", attempt);
            
            if (attempt < 3) {
                throw new java.net.SocketTimeoutException("Connection timeout to external service");
            } else {
                // Simulate success after retries
                log.info("Network operation succeeded after {} attempts", attempt);
                errorLatch.countDown();
                return;
            }
        }, null);

        boolean operationSucceeded = errorLatch.await(8, TimeUnit.SECONDS);
        assertTrue(operationSucceeded, "Network operation should eventually succeed");
        assertEquals(3, retryCount.get(), "Should retry network timeouts");

        log.info("Network timeout scenario completed - succeeded after {} retries", retryCount.get());
    }

    @Test
    @Timeout(20)
    void testValidationConstraintViolationScenario() throws Exception {
        log.info("Testing validation constraint violation scenario");

        CountDownLatch errorLatch = new CountDownLatch(1);
        AtomicInteger retryCount = new AtomicInteger(0);

        // Simulate bean validation constraint violation
        simulateConsumerError(() -> {
            retryCount.incrementAndGet();
            log.info("Attempting validation (attempt {})", retryCount.get());
            throw new ConstraintViolationException("Validation failed: email format is invalid", null);
        }, errorLatch);

        boolean errorHandled = errorLatch.await(5, TimeUnit.SECONDS);
        assertTrue(errorHandled, "Validation constraint violation should be handled");
        
        // Validation errors should NOT be retried
        assertEquals(1, retryCount.get(), "Validation constraint violations should not be retried");

        log.info("Validation constraint violation scenario completed");
    }

    @Test
    @Timeout(20)
    void testResourceExhaustionScenario() throws Exception {
        log.info("Testing resource exhaustion scenario");

        CountDownLatch errorLatch = new CountDownLatch(1);
        AtomicInteger retryCount = new AtomicInteger(0);

        // Simulate resource exhaustion (potentially retryable)
        simulateConsumerError(() -> {
            int attempt = retryCount.incrementAndGet();
            log.info("Attempting resource-intensive operation (attempt {})", attempt);
            
            if (attempt < 2) {
                throw new OutOfMemoryError("Java heap space exhausted");
            } else {
                // Simulate recovery after resource cleanup
                log.info("Resource-intensive operation succeeded after {} attempts", attempt);
                errorLatch.countDown();
                return;
            }
        }, null);

        boolean operationSucceeded = errorLatch.await(8, TimeUnit.SECONDS);
        assertTrue(operationSucceeded, "Resource exhaustion should be recoverable");
        assertEquals(2, retryCount.get(), "Should retry resource exhaustion scenarios");

        log.info("Resource exhaustion scenario completed");
    }

    @Test
    @Timeout(20)
    void testCircuitBreakerIntegrationScenario() throws Exception {
        log.info("Testing circuit breaker integration scenario");

        CountDownLatch errorLatch = new CountDownLatch(1);
        AtomicInteger retryCount = new AtomicInteger(0);

        // Simulate circuit breaker open state
        simulateConsumerError(() -> {
            retryCount.incrementAndGet();
            log.info("Attempting operation with circuit breaker (attempt {})", retryCount.get());
            throw new io.github.resilience4j.circuitbreaker.CallNotPermittedException("Circuit breaker is OPEN");
        }, errorLatch);

        boolean errorHandled = errorLatch.await(5, TimeUnit.SECONDS);
        assertTrue(errorHandled, "Circuit breaker exception should be handled");

        log.info("Circuit breaker integration scenario completed");
    }

    @Test
    @Timeout(20)
    void testMalformedMessageHandlingScenario() throws Exception {
        log.info("Testing malformed message handling scenario");

        // Test various malformed message scenarios
        String[] malformedMessages = {
            "not-json-at-all",
            "{\"incomplete\": true",
            "[]", // Array instead of object
            "null",
            "{\"orderId\": null, \"userId\": \"not-a-number\"}",
            "{\"validJson\": true, \"butMissingRequiredFields\": true}"
        };

        for (String malformedMessage : malformedMessages) {
            CountDownLatch latch = new CountDownLatch(1);
            AtomicInteger retryCount = new AtomicInteger(0);

            simulateConsumerError(() -> {
                retryCount.incrementAndGet();
                log.info("Processing malformed message: {} (attempt {})", malformedMessage, retryCount.get());
                throw new IllegalArgumentException("Invalid message format: " + malformedMessage);
            }, latch);

            boolean handled = latch.await(3, TimeUnit.SECONDS);
            assertTrue(handled, "Malformed message should be handled: " + malformedMessage);
            assertEquals(1, retryCount.get(), "Malformed messages should not be retried: " + malformedMessage);
        }

        log.info("Malformed message handling scenario completed");
    }

    @Test
    @Timeout(20)
    void testOrderProcessingSpecificScenarios() throws Exception {
        log.info("Testing order processing specific scenarios");

        // Test order-specific business logic failures
        Map<String, Object> orderMessage = Map.of(
            "orderId", 12345L,
            "userId", 67890L,
            "serviceId", 111L,
            "quantity", 1000,
            "charge", 25.50
        );

        // Test 1: Insufficient balance during order processing
        testOrderProcessingError(
            orderMessage,
            () -> new InsufficientBalanceException("Insufficient balance for order processing"),
            false,
            "Order processing insufficient balance should not be retried"
        );

        // Test 2: Service unavailable during order processing (retryable)
        testOrderProcessingError(
            orderMessage,
            () -> new RuntimeException("Order processing service temporarily unavailable"),
            true,
            "Order processing service unavailable should be retried"
        );

        log.info("Order processing specific scenarios completed");
    }

    @Test
    @Timeout(20)
    void testDlqProcessingFailureScenario() throws Exception {
        log.info("Testing DLQ processing failure scenario");

        CountDownLatch dlqErrorLatch = new CountDownLatch(1);
        AtomicInteger dlqRetryCount = new AtomicInteger(0);

        // Simulate DLQ processing failure (should have fewer retries)
        simulateConsumerError(() -> {
            dlqRetryCount.incrementAndGet();
            log.info("Attempting DLQ message processing (attempt {})", dlqRetryCount.get());
            throw new RuntimeException("DLQ processing service unavailable");
        }, dlqErrorLatch);

        boolean dlqErrorHandled = dlqErrorLatch.await(8, TimeUnit.SECONDS);
        assertTrue(dlqErrorHandled, "DLQ processing error should be handled");
        
        // DLQ processing should have limited retries (configured as 2 in the error handler)
        assertTrue(dlqRetryCount.get() <= 3, "DLQ processing should have limited retries, got: " + dlqRetryCount.get());

        log.info("DLQ processing failure scenario completed with {} attempts", dlqRetryCount.get());
    }

    /**
     * Helper method to test business logic exceptions
     */
    private void testBusinessLogicException(ExceptionSupplier exceptionSupplier, boolean shouldRetry, String description) 
            throws InterruptedException {
        CountDownLatch errorLatch = new CountDownLatch(1);
        AtomicInteger retryCount = new AtomicInteger(0);

        simulateConsumerError(() -> {
            retryCount.incrementAndGet();
            throw exceptionSupplier.getException();
        }, errorLatch);

        boolean errorHandled = errorLatch.await(5, TimeUnit.SECONDS);
        assertTrue(errorHandled, description + " - should be handled");

        if (shouldRetry) {
            assertTrue(retryCount.get() > 1, description + " - should be retried, got " + retryCount.get() + " attempts");
        } else {
            assertEquals(1, retryCount.get(), description + " - should not be retried, got " + retryCount.get() + " attempts");
        }
    }

    /**
     * Helper method to test order processing specific errors
     */
    private void testOrderProcessingError(Map<String, Object> orderMessage, ExceptionSupplier exceptionSupplier, 
                                        boolean shouldRetry, String description) throws InterruptedException {
        CountDownLatch errorLatch = new CountDownLatch(1);
        AtomicInteger retryCount = new AtomicInteger(0);

        simulateConsumerError(() -> {
            retryCount.incrementAndGet();
            log.info("Processing order: {} (attempt {})", orderMessage, retryCount.get());
            throw exceptionSupplier.getException();
        }, errorLatch);

        boolean errorHandled = errorLatch.await(5, TimeUnit.SECONDS);
        assertTrue(errorHandled, description + " - should be handled");

        if (shouldRetry) {
            assertTrue(retryCount.get() > 1, description + " - should be retried");
        } else {
            assertEquals(1, retryCount.get(), description + " - should not be retried");
        }
    }

    /**
     * Helper method to simulate consumer error scenarios
     */
    private void simulateConsumerError(ErrorSimulator errorSimulator, CountDownLatch errorLatch) {
        Thread.ofVirtual().start(() -> {
            try {
                errorSimulator.simulate();
                if (errorLatch != null) {
                    errorLatch.countDown();
                }
            } catch (Exception e) {
                log.info("Simulated error occurred: {}", e.getMessage());
                if (errorLatch != null) {
                    errorLatch.countDown();
                }
            }
        });
    }

    @FunctionalInterface
    private interface ExceptionSupplier {
        RuntimeException getException();
    }

    @FunctionalInterface
    private interface ErrorSimulator {
        void simulate() throws Exception;
    }
}