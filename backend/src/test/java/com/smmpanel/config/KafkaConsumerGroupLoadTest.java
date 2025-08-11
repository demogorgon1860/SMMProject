package com.smmpanel.config;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;

/**
 * KAFKA CONSUMER GROUP LOAD TESTING
 *
 * <p>Tests consumer group stability and performance under various load conditions: 1. High message
 * volume processing 2. Consumer group rebalancing under load 3. Consumer failure and recovery
 * scenarios 4. Partition reassignment handling 5. Consumer lag monitoring and recovery 6.
 * Concurrent consumer group operations
 */
@Slf4j
@SpringBootTest
@EmbeddedKafka(
        partitions = 6,
        brokerProperties = {
            "listeners=PLAINTEXT://localhost:9092",
            "port=9092",
            "num.network.threads=8",
            "num.io.threads=8",
            "socket.send.buffer.bytes=102400",
            "socket.receive.buffer.bytes=102400",
            "socket.request.max.bytes=104857600"
        },
        topics = {
            "load.test.high.throughput",
            "load.test.rebalancing",
            "load.test.consumer.failure",
            "load.test.partition.assignment"
        })
@TestPropertySource(
        properties = {
            "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}",
            "app.kafka.consumer.session-timeout-ms=10000",
            "app.kafka.consumer.heartbeat-interval-ms=1000",
            "app.kafka.consumer.max-poll-interval-ms=60000",
            "app.kafka.consumer.max-poll-records=100"
        })
@DirtiesContext
class KafkaConsumerGroupLoadTest {

    @Autowired private EmbeddedKafkaBroker embeddedKafkaBroker;

    @Autowired private KafkaConsumerGroupConfiguration consumerGroupConfig;

    @Autowired private KafkaTemplate<String, Object> kafkaTemplate;

    @Autowired private ObjectMapper objectMapper;

    private Producer<String, Object> testProducer;
    private ExecutorService executorService;

    // Test metrics
    private final AtomicLong messagesProduced = new AtomicLong(0);
    private final AtomicLong messagesConsumed = new AtomicLong(0);
    private final AtomicInteger rebalanceCount = new AtomicInteger(0);
    private final AtomicInteger activeConsumers = new AtomicInteger(0);

    @BeforeEach
    void setUp() {
        // Set up test producer
        Map<String, Object> producerProps = KafkaTestUtils.producerProps(embeddedKafkaBroker);
        producerProps.put(
                org.apache.kafka.clients.producer.ProducerConfig.BATCH_SIZE_CONFIG, 65536);
        producerProps.put(org.apache.kafka.clients.producer.ProducerConfig.LINGER_MS_CONFIG, 10);
        producerProps.put(
                org.apache.kafka.clients.producer.ProducerConfig.COMPRESSION_TYPE_CONFIG, "snappy");
        testProducer =
                new DefaultKafkaProducerFactory<String, Object>(producerProps).createProducer();

        // Set up executor service for concurrent operations
        executorService = Executors.newFixedThreadPool(20);

        // Reset metrics
        messagesProduced.set(0);
        messagesConsumed.set(0);
        rebalanceCount.set(0);
        activeConsumers.set(0);
    }

    @Test
    @Timeout(120)
    void testHighThroughputConsumerGroupStability() throws Exception {
        log.info("Testing high throughput consumer group stability");

        int messageCount = 10000;
        int consumerCount = 5;
        CountDownLatch consumedLatch = new CountDownLatch(messageCount);

        // Start multiple consumers in the same group
        List<CompletableFuture<Void>> consumerFutures = new ArrayList<>();
        for (int i = 0; i < consumerCount; i++) {
            final int consumerId = i;
            CompletableFuture<Void> consumerFuture =
                    CompletableFuture.runAsync(
                            () -> {
                                runHighThroughputConsumer(consumerId, consumedLatch);
                            },
                            executorService);
            consumerFutures.add(consumerFuture);
        }

        // Wait for consumers to start
        Thread.sleep(2000);

        // Produce messages rapidly
        CompletableFuture<Void> producerFuture =
                CompletableFuture.runAsync(
                        () -> {
                            for (int i = 0; i < messageCount; i++) {
                                Map<String, Object> message =
                                        createTestMessage(i, "high-throughput-test");
                                try {
                                    testProducer.send(
                                            new ProducerRecord<>(
                                                    "load.test.high.throughput",
                                                    "key-" + i,
                                                    message));
                                    messagesProduced.incrementAndGet();

                                    if (i % 1000 == 0) {
                                        log.info("Produced {} messages", i);
                                    }
                                } catch (Exception e) {
                                    log.error(
                                            "Failed to produce message {}: {}", i, e.getMessage());
                                }
                            }
                            testProducer.flush();
                        },
                        executorService);

        // Wait for all messages to be consumed
        boolean allConsumed = consumedLatch.await(90, TimeUnit.SECONDS);
        assertTrue(allConsumed, "All messages should be consumed within timeout");

        // Stop consumers
        consumerFutures.forEach(future -> future.cancel(true));

        // Verify metrics
        assertEquals(messageCount, messagesProduced.get(), "Should have produced all messages");
        assertEquals(messageCount, messagesConsumed.get(), "Should have consumed all messages");

        log.info(
                "High throughput test completed - Produced: {}, Consumed: {}, Rebalances: {}",
                messagesProduced.get(),
                messagesConsumed.get(),
                rebalanceCount.get());
    }

    @Test
    @Timeout(90)
    void testConsumerGroupRebalancingUnderLoad() throws Exception {
        log.info("Testing consumer group rebalancing under load");

        int messageCount = 5000;
        int initialConsumerCount = 3;
        CountDownLatch consumedLatch = new CountDownLatch(messageCount);

        // Start initial consumers
        List<ConsumerWorker> consumers = Collections.synchronizedList(new ArrayList<>());
        for (int i = 0; i < initialConsumerCount; i++) {
            ConsumerWorker consumer = new ConsumerWorker(i, "load.test.rebalancing", consumedLatch);
            consumers.add(consumer);
            executorService.submit(consumer);
        }

        // Start producing messages
        CompletableFuture<Void> producerFuture =
                CompletableFuture.runAsync(
                        () -> {
                            for (int i = 0; i < messageCount; i++) {
                                Map<String, Object> message =
                                        createTestMessage(i, "rebalancing-test");
                                try {
                                    testProducer.send(
                                            new ProducerRecord<>(
                                                    "load.test.rebalancing", "key-" + i, message));
                                    messagesProduced.incrementAndGet();

                                    // Add delay to spread production over time
                                    if (i % 100 == 0) {
                                        Thread.sleep(10);
                                    }
                                } catch (Exception e) {
                                    log.error(
                                            "Failed to produce message {}: {}", i, e.getMessage());
                                }
                            }
                        },
                        executorService);

        // Trigger rebalancing by adding/removing consumers during processing
        Thread.sleep(5000); // Let initial processing start

        // Add more consumers (should trigger rebalance)
        log.info("Adding 2 more consumers to trigger rebalance");
        for (int i = initialConsumerCount; i < initialConsumerCount + 2; i++) {
            ConsumerWorker consumer = new ConsumerWorker(i, "load.test.rebalancing", consumedLatch);
            consumers.add(consumer);
            executorService.submit(consumer);
        }

        Thread.sleep(10000); // Let rebalancing settle

        // Remove some consumers (should trigger another rebalance)
        log.info("Stopping 2 consumers to trigger another rebalance");
        for (int i = 0; i < 2; i++) {
            if (!consumers.isEmpty()) {
                ConsumerWorker consumer = consumers.remove(0);
                consumer.stop();
            }
        }

        // Wait for all messages to be consumed
        boolean allConsumed = consumedLatch.await(60, TimeUnit.SECONDS);

        // Stop remaining consumers
        consumers.forEach(ConsumerWorker::stop);

        assertTrue(allConsumed, "All messages should be consumed despite rebalancing");
        assertTrue(rebalanceCount.get() >= 2, "Should have triggered at least 2 rebalances");

        log.info(
                "Rebalancing test completed - Rebalances: {}, Messages consumed: {}",
                rebalanceCount.get(),
                messagesConsumed.get());
    }

    @Test
    @Timeout(60)
    void testConsumerFailureRecovery() throws Exception {
        log.info("Testing consumer failure and recovery");

        int messageCount = 2000;
        int consumerCount = 4;
        CountDownLatch consumedLatch = new CountDownLatch(messageCount);

        // Start consumers
        List<ConsumerWorker> consumers = Collections.synchronizedList(new ArrayList<>());
        for (int i = 0; i < consumerCount; i++) {
            ConsumerWorker consumer =
                    new ConsumerWorker(i, "load.test.consumer.failure", consumedLatch);
            consumers.add(consumer);
            executorService.submit(consumer);
        }

        // Start producing messages
        CompletableFuture.runAsync(
                () -> {
                    for (int i = 0; i < messageCount; i++) {
                        Map<String, Object> message = createTestMessage(i, "failure-recovery-test");
                        try {
                            testProducer.send(
                                    new ProducerRecord<>(
                                            "load.test.consumer.failure", "key-" + i, message));
                            messagesProduced.incrementAndGet();
                        } catch (Exception e) {
                            log.error("Failed to produce message {}: {}", i, e.getMessage());
                        }
                    }
                },
                executorService);

        Thread.sleep(5000); // Let processing start

        // Simulate consumer failures
        log.info("Simulating consumer failures");
        int failedConsumers = 0;
        for (ConsumerWorker consumer : new ArrayList<>(consumers)) {
            if (failedConsumers < 2) {
                log.info("Stopping consumer {} to simulate failure", consumer.getId());
                consumer.stop();
                consumers.remove(consumer);
                failedConsumers++;
            }
        }

        Thread.sleep(5000); // Let rebalancing happen

        // Add replacement consumers
        log.info("Adding replacement consumers");
        for (int i = consumerCount; i < consumerCount + 2; i++) {
            ConsumerWorker consumer =
                    new ConsumerWorker(i, "load.test.consumer.failure", consumedLatch);
            consumers.add(consumer);
            executorService.submit(consumer);
        }

        // Wait for recovery and completion
        boolean allConsumed = consumedLatch.await(45, TimeUnit.SECONDS);

        // Stop remaining consumers
        consumers.forEach(ConsumerWorker::stop);

        assertTrue(allConsumed, "Should recover from consumer failures and process all messages");

        log.info(
                "Consumer failure recovery test completed - Messages consumed: {}",
                messagesConsumed.get());
    }

    @Test
    @Timeout(45)
    void testPartitionAssignmentStability() throws Exception {
        log.info("Testing partition assignment stability");

        int messageCount = 1000;
        int consumerCount = 6; // Equal to partition count
        CountDownLatch consumedLatch = new CountDownLatch(messageCount);

        // Track partition assignments
        Map<Integer, Set<Integer>> consumerPartitionAssignments = new ConcurrentHashMap<>();

        // Start consumers with partition assignment tracking
        List<ConsumerWorker> consumers = Collections.synchronizedList(new ArrayList<>());
        for (int i = 0; i < consumerCount; i++) {
            ConsumerWorker consumer =
                    new ConsumerWorker(
                            i,
                            "load.test.partition.assignment",
                            consumedLatch,
                            consumerPartitionAssignments);
            consumers.add(consumer);
            executorService.submit(consumer);
        }

        Thread.sleep(3000); // Let initial assignment happen

        // Produce messages to all partitions
        CompletableFuture.runAsync(
                () -> {
                    for (int i = 0; i < messageCount; i++) {
                        Map<String, Object> message =
                                createTestMessage(i, "partition-assignment-test");
                        try {
                            // Use different partition keys to distribute across partitions
                            String partitionKey = "partition-" + (i % 6);
                            testProducer.send(
                                    new ProducerRecord<>(
                                            "load.test.partition.assignment",
                                            partitionKey,
                                            message));
                            messagesProduced.incrementAndGet();
                        } catch (Exception e) {
                            log.error("Failed to produce message {}: {}", i, e.getMessage());
                        }
                    }
                },
                executorService);

        // Wait for processing to complete
        boolean allConsumed = consumedLatch.await(30, TimeUnit.SECONDS);

        // Stop consumers
        consumers.forEach(ConsumerWorker::stop);

        assertTrue(allConsumed, "All messages should be consumed");

        // Verify partition assignment distribution
        int totalAssignedPartitions =
                consumerPartitionAssignments.values().stream().mapToInt(Set::size).sum();

        assertTrue(totalAssignedPartitions >= 6, "Should have assigned all 6 partitions");

        log.info(
                "Partition assignment test completed - Consumer assignments: {}",
                consumerPartitionAssignments);
    }

    @Test
    @Timeout(60)
    void testConsumerGroupLagRecovery() throws Exception {
        log.info("Testing consumer group lag recovery");

        int messageCount = 3000;
        int slowConsumerCount = 2;
        CountDownLatch consumedLatch = new CountDownLatch(messageCount);

        // Start slow consumers initially
        List<ConsumerWorker> consumers = Collections.synchronizedList(new ArrayList<>());
        for (int i = 0; i < slowConsumerCount; i++) {
            // Add processing delay to create lag
            ConsumerWorker consumer =
                    new ConsumerWorker(
                            i,
                            "load.test.high.throughput",
                            consumedLatch,
                            50); // 50ms processing delay
            consumers.add(consumer);
            executorService.submit(consumer);
        }

        // Produce messages quickly to create lag
        CompletableFuture.runAsync(
                () -> {
                    for (int i = 0; i < messageCount; i++) {
                        Map<String, Object> message = createTestMessage(i, "lag-recovery-test");
                        try {
                            testProducer.send(
                                    new ProducerRecord<>(
                                            "load.test.high.throughput", "key-" + i, message));
                            messagesProduced.incrementAndGet();
                        } catch (Exception e) {
                            log.error("Failed to produce message {}: {}", i, e.getMessage());
                        }
                    }
                },
                executorService);

        Thread.sleep(10000); // Let lag build up

        // Add fast consumers to help with lag recovery
        log.info("Adding fast consumers to help with lag recovery");
        for (int i = slowConsumerCount; i < slowConsumerCount + 3; i++) {
            ConsumerWorker consumer =
                    new ConsumerWorker(
                            i,
                            "load.test.high.throughput",
                            consumedLatch,
                            0); // No processing delay
            consumers.add(consumer);
            executorService.submit(consumer);
        }

        // Wait for lag recovery
        boolean allConsumed = consumedLatch.await(40, TimeUnit.SECONDS);

        // Stop consumers
        consumers.forEach(ConsumerWorker::stop);

        assertTrue(allConsumed, "Should recover from consumer lag");

        log.info(
                "Consumer lag recovery test completed - Messages consumed: {}",
                messagesConsumed.get());
    }

    @Test
    @Timeout(30)
    void testConcurrentConsumerGroupOperations() throws Exception {
        log.info("Testing concurrent consumer group operations");

        int operationCount = 50;
        CountDownLatch operationLatch = new CountDownLatch(operationCount);

        // Run multiple concurrent operations
        for (int i = 0; i < operationCount; i++) {
            final int operationId = i;
            executorService.submit(
                    () -> {
                        try {
                            // Simulate various consumer group operations
                            switch (operationId % 4) {
                                case 0 -> runShortLivedConsumer(operationId);
                                case 1 -> runConsumerWithRebalancing(operationId);
                                case 2 -> runConsumerWithErrors(operationId);
                                case 3 -> runConsumerWithCommitOperations(operationId);
                            }
                        } catch (Exception e) {
                            log.error(
                                    "Concurrent operation {} failed: {}",
                                    operationId,
                                    e.getMessage());
                        } finally {
                            operationLatch.countDown();
                        }
                    });
        }

        boolean allCompleted = operationLatch.await(25, TimeUnit.SECONDS);
        assertTrue(allCompleted, "All concurrent operations should complete");

        log.info("Concurrent consumer group operations test completed");
    }

    /** Helper method to run high throughput consumer */
    private void runHighThroughputConsumer(int consumerId, CountDownLatch latch) {
        Consumer<String, Object> consumer =
                consumerGroupConfig
                        .highThroughputConsumerFactory()
                        .createConsumer(
                                "high-throughput-group-" + consumerId, "client-" + consumerId);

        consumer.subscribe(Collections.singletonList("load.test.high.throughput"));
        activeConsumers.incrementAndGet();

        try {
            while (!Thread.currentThread().isInterrupted() && latch.getCount() > 0) {
                ConsumerRecords<String, Object> records = consumer.poll(Duration.ofMillis(100));
                for (ConsumerRecord<String, Object> record : records) {
                    // Simulate processing
                    messagesConsumed.incrementAndGet();
                    latch.countDown();
                }
                consumer.commitSync();
            }
        } catch (Exception e) {
            log.debug("High throughput consumer {} stopped: {}", consumerId, e.getMessage());
        } finally {
            consumer.close();
            activeConsumers.decrementAndGet();
        }
    }

    /** Helper method to run short-lived consumer */
    private void runShortLivedConsumer(int operationId) throws Exception {
        Consumer<String, Object> consumer =
                consumerGroupConfig
                        .balancedConsumerFactory()
                        .createConsumer("short-lived-group", "client-" + operationId);

        consumer.subscribe(Collections.singletonList("load.test.high.throughput"));

        // Run for a short time then stop
        long endTime = System.currentTimeMillis() + 1000; // 1 second
        while (System.currentTimeMillis() < endTime) {
            consumer.poll(Duration.ofMillis(100));
        }

        consumer.close();
    }

    /** Helper method to run consumer with rebalancing */
    private void runConsumerWithRebalancing(int operationId) throws Exception {
        Consumer<String, Object> consumer =
                consumerGroupConfig
                        .balancedConsumerFactory()
                        .createConsumer("rebalancing-group", "client-" + operationId);

        consumer.subscribe(Collections.singletonList("load.test.rebalancing"));

        // Consume for a bit, then trigger rebalance by subscribing to different topic
        for (int i = 0; i < 5; i++) {
            consumer.poll(Duration.ofMillis(100));
        }

        consumer.close();
    }

    /** Helper method to run consumer with errors */
    private void runConsumerWithErrors(int operationId) throws Exception {
        Consumer<String, Object> consumer =
                consumerGroupConfig
                        .balancedConsumerFactory()
                        .createConsumer("error-group", "client-" + operationId);

        consumer.subscribe(Collections.singletonList("load.test.consumer.failure"));

        try {
            for (int i = 0; i < 3; i++) {
                consumer.poll(Duration.ofMillis(100));
                // Simulate error
                if (i == 1) {
                    throw new RuntimeException("Simulated consumer error");
                }
            }
        } catch (Exception e) {
            // Expected error
        } finally {
            consumer.close();
        }
    }

    /** Helper method to run consumer with commit operations */
    private void runConsumerWithCommitOperations(int operationId) throws Exception {
        Consumer<String, Object> consumer =
                consumerGroupConfig
                        .balancedConsumerFactory()
                        .createConsumer("commit-group", "client-" + operationId);

        consumer.subscribe(Collections.singletonList("load.test.partition.assignment"));

        for (int i = 0; i < 5; i++) {
            ConsumerRecords<String, Object> records = consumer.poll(Duration.ofMillis(100));
            if (!records.isEmpty()) {
                consumer.commitSync();
            }
        }

        consumer.close();
    }

    /** Helper method to create test message */
    private Map<String, Object> createTestMessage(int id, String testType) {
        return Map.of(
                "id",
                id,
                "testType",
                testType,
                "timestamp",
                LocalDateTime.now().toString(),
                "data",
                "test-data-" + id);
    }

    /** Consumer worker class for testing */
    private class ConsumerWorker implements Runnable {
        private final int id;
        private final String topic;
        private final CountDownLatch latch;
        private final int processingDelayMs;
        private final Map<Integer, Set<Integer>> partitionAssignments;
        private volatile Consumer<String, Object> consumer;
        private volatile boolean running = true;

        public ConsumerWorker(int id, String topic, CountDownLatch latch) {
            this(id, topic, latch, 0, null);
        }

        public ConsumerWorker(int id, String topic, CountDownLatch latch, int processingDelayMs) {
            this(id, topic, latch, processingDelayMs, null);
        }

        public ConsumerWorker(
                int id,
                String topic,
                CountDownLatch latch,
                Map<Integer, Set<Integer>> partitionAssignments) {
            this(id, topic, latch, 0, partitionAssignments);
        }

        public ConsumerWorker(
                int id,
                String topic,
                CountDownLatch latch,
                int processingDelayMs,
                Map<Integer, Set<Integer>> partitionAssignments) {
            this.id = id;
            this.topic = topic;
            this.latch = latch;
            this.processingDelayMs = processingDelayMs;
            this.partitionAssignments = partitionAssignments;
        }

        @Override
        public void run() {
            consumer =
                    consumerGroupConfig
                            .balancedConsumerFactory()
                            .createConsumer("test-group", "worker-" + id);

            consumer.subscribe(
                    Collections.singletonList(topic),
                    new org.apache.kafka.clients.consumer.ConsumerRebalanceListener() {
                        @Override
                        public void onPartitionsRevoked(Collection<TopicPartition> partitions) {
                            rebalanceCount.incrementAndGet();
                            log.debug(
                                    "Consumer {} - partitions revoked: {}", id, partitions.size());
                        }

                        @Override
                        public void onPartitionsAssigned(Collection<TopicPartition> partitions) {
                            log.debug(
                                    "Consumer {} - partitions assigned: {}", id, partitions.size());
                            if (partitionAssignments != null) {
                                Set<Integer> assignedPartitionIds = new HashSet<>();
                                partitions.forEach(tp -> assignedPartitionIds.add(tp.partition()));
                                partitionAssignments.put(id, assignedPartitionIds);
                            }
                        }
                    });

            activeConsumers.incrementAndGet();

            try {
                while (running && !Thread.currentThread().isInterrupted()) {
                    ConsumerRecords<String, Object> records = consumer.poll(Duration.ofMillis(100));
                    for (ConsumerRecord<String, Object> record : records) {
                        if (processingDelayMs > 0) {
                            Thread.sleep(processingDelayMs);
                        }
                        messagesConsumed.incrementAndGet();
                        if (latch != null) {
                            latch.countDown();
                        }
                    }
                    if (!records.isEmpty()) {
                        consumer.commitSync();
                    }
                }
            } catch (Exception e) {
                log.debug("Consumer worker {} stopped: {}", id, e.getMessage());
            } finally {
                if (consumer != null) {
                    consumer.close();
                }
                activeConsumers.decrementAndGet();
            }
        }

        public void stop() {
            running = false;
            if (consumer != null) {
                consumer.wakeup();
            }
        }

        public int getId() {
            return id;
        }
    }
}
