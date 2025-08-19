package com.smmpanel.integration;

import static org.junit.jupiter.api.Assertions.*;

import com.smmpanel.entity.Order;
import com.smmpanel.entity.OrderStatus;
import com.smmpanel.event.OfferAssignmentEvent;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Slf4j
@SpringBootTest
@Testcontainers
public class KafkaConnectivityIntegrationTest {

    @Container
    static KafkaContainer kafka =
            new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.5.0"))
                    .withEnv("KAFKA_AUTO_CREATE_TOPICS_ENABLE", "true");

    @DynamicPropertySource
    static void kafkaProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
        registry.add("spring.kafka.consumer.auto-offset-reset", () -> "earliest");
        registry.add("spring.kafka.consumer.group-id", () -> "test-group");
    }

    @Autowired private KafkaTemplate<String, Object> kafkaTemplate;

    @Autowired private AdminClient adminClient;

    private final CountDownLatch orderLatch = new CountDownLatch(1);
    private final CountDownLatch notificationLatch = new CountDownLatch(1);
    private final CountDownLatch offerAssignmentLatch = new CountDownLatch(1);

    private volatile Long receivedOrderId;
    private volatile String receivedNotification;
    private volatile OfferAssignmentEvent receivedOfferEvent;

    @Test
    public void testKafkaConnection() throws Exception {
        log.info("Testing Kafka connectivity...");

        assertNotNull(kafkaTemplate, "KafkaTemplate should be initialized");
        assertNotNull(adminClient, "AdminClient should be initialized");

        var topics = adminClient.listTopics().names().get(10, TimeUnit.SECONDS);
        assertNotNull(topics, "Should be able to list topics");
        log.info("Successfully connected to Kafka. Available topics: {}", topics);
    }

    @Test
    public void testOrderEventPublishAndConsume() throws Exception {
        log.info("Testing order event publish and consume...");

        Long orderId = System.currentTimeMillis();
        Order order = new Order();
        order.setId(orderId);
        order.setStatus(OrderStatus.PENDING);
        order.setOrderId(UUID.randomUUID().toString());
        order.setLink("https://example.com/test");
        order.setQuantity(100);
        order.setCharge(new BigDecimal("10.00"));

        CompletableFuture<SendResult<String, Object>> future =
                kafkaTemplate.send("smm.orders", String.valueOf(orderId), order);

        SendResult<String, Object> result = future.get(10, TimeUnit.SECONDS);
        assertNotNull(result, "Should receive send result");
        log.info("Successfully published order event with id: {}", orderId);

        boolean received = orderLatch.await(10, TimeUnit.SECONDS);
        assertTrue(received, "Should receive order message");
        assertEquals(orderId, receivedOrderId, "Received order ID should match");
        log.info("Successfully consumed order event with id: {}", receivedOrderId);
    }

    @Test
    public void testNotificationEventPublishAndConsume() throws Exception {
        log.info("Testing notification event publish and consume...");

        Map<String, Object> notification =
                Map.of(
                        "type",
                        "ORDER_UPDATE",
                        "orderId",
                        UUID.randomUUID().toString(),
                        "message",
                        "Order status updated",
                        "timestamp",
                        System.currentTimeMillis());

        CompletableFuture<SendResult<String, Object>> future =
                kafkaTemplate.send("smm.notifications", notification);

        SendResult<String, Object> result = future.get(10, TimeUnit.SECONDS);
        assertNotNull(result, "Should receive send result");
        log.info("Successfully published notification event");

        boolean received = notificationLatch.await(10, TimeUnit.SECONDS);
        assertTrue(received, "Should receive notification message");
        assertNotNull(receivedNotification, "Received notification should not be null");
        log.info("Successfully consumed notification: {}", receivedNotification);
    }

    @Test
    public void testOfferAssignmentEventPublishAndConsume() throws Exception {
        log.info("Testing offer assignment event publish and consume...");

        OfferAssignmentEvent event = new OfferAssignmentEvent();
        event.setEventId(UUID.randomUUID().toString());
        event.setOrderId(123L);
        event.setOfferName("Test Offer");
        event.setTargetUrl("https://example.com/offer");
        event.setDescription("Test offer assignment");
        event.setGeoTargeting("US");
        event.setSource("TEST");
        event.setTimestamp(LocalDateTime.now());

        CompletableFuture<SendResult<String, Object>> future =
                kafkaTemplate.send(
                        "smm.offer-assignment", String.valueOf(event.getOrderId()), event);

        SendResult<String, Object> result = future.get(10, TimeUnit.SECONDS);
        assertNotNull(result, "Should receive send result");
        log.info("Successfully published offer assignment event for order: {}", event.getOrderId());

        boolean received = offerAssignmentLatch.await(10, TimeUnit.SECONDS);
        assertTrue(received, "Should receive offer assignment message");
        assertNotNull(receivedOfferEvent, "Received offer event should not be null");
        assertEquals(event.getOrderId(), receivedOfferEvent.getOrderId(), "Order IDs should match");
        log.info(
                "Successfully consumed offer assignment event for order: {}",
                receivedOfferEvent.getOrderId());
    }

    @Test
    public void testKafkaTransactionalProducer() throws Exception {
        log.info("Testing Kafka transactional producer...");

        kafkaTemplate.executeInTransaction(
                operations -> {
                    String orderId = UUID.randomUUID().toString();
                    operations.send(
                            "smm.orders", orderId, Map.of("orderId", orderId, "status", "CREATED"));
                    operations.send(
                            "smm.notifications",
                            Map.of("orderId", orderId, "type", "ORDER_CREATED"));
                    log.info("Sent transactional messages for order: {}", orderId);
                    return true;
                });

        log.info("Successfully executed transactional Kafka operations");
    }

    @KafkaListener(topics = "smm.orders", containerFactory = "kafkaListenerContainerFactory")
    public void handleOrderEvent(ConsumerRecord<String, Order> record) {
        log.info("Received order event: key={}, value={}", record.key(), record.value());
        if (record.value() != null) {
            receivedOrderId = record.value().getId();
        }
        orderLatch.countDown();
    }

    @KafkaListener(topics = "smm.notifications", containerFactory = "kafkaListenerContainerFactory")
    public void handleNotificationEvent(ConsumerRecord<String, Map<String, Object>> record) {
        log.info("Received notification event: {}", record.value());
        receivedNotification = record.value().toString();
        notificationLatch.countDown();
    }

    @KafkaListener(
            topics = "smm.offer-assignment",
            containerFactory = "kafkaListenerContainerFactory")
    public void handleOfferAssignmentEvent(ConsumerRecord<String, OfferAssignmentEvent> record) {
        log.info("Received offer assignment event: key={}, value={}", record.key(), record.value());
        receivedOfferEvent = record.value();
        offerAssignmentLatch.countDown();
    }
}
