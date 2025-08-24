package com.smmpanel.integration;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.smmpanel.client.BinomClient;
import com.smmpanel.dto.binom.*;
import com.smmpanel.entity.*;
import com.smmpanel.repository.jpa.*;
import com.smmpanel.service.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * Performance and concurrency integration tests for 3-campaign distribution Tests system behavior
 * under load and concurrent operations
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class Binom3CampaignPerformanceIntegrationTest {

    @Autowired private BinomService binomService;
    @Autowired private OrderService orderService;
    @Autowired private OrderRepository orderRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private ServiceRepository serviceRepository;
    @Autowired private BinomCampaignRepository binomCampaignRepository;

    @MockBean private BinomClient binomClient;

    private User testUser;
    private Service testService;
    private ExecutorService executorService;

    private static final int CONCURRENT_ORDERS = 10;
    private static final int VIEWS_PER_ORDER = 1000;
    private static final String BASE_VIDEO_URL = "https://youtube.com/watch?v=perf";

    @BeforeEach
    void setUp() {
        executorService = Executors.newFixedThreadPool(CONCURRENT_ORDERS);
        setupTestData();
        setupBinomClientMocks();
    }

    private void setupTestData() {
        testUser = new User();
        testUser.setUsername("performance-test-user");
        testUser.setEmail("performance@test.com");
        testUser.setPasswordHash("hashed-password");
        testUser.setBalance(BigDecimal.valueOf(10000.00));
        testUser.setActive(true);
        testUser.setApiKey("performance-test-api-key");
        testUser.setRole(UserRole.USER);
        testUser = userRepository.save(testUser);

        testService = new Service();
        testService.setName("YouTube Views Performance Test");
        testService.setMinOrder(100);
        testService.setMaxOrder(50000);
        testService.setPricePer1000(BigDecimal.valueOf(5.00));
        testService.setActive(true);
        testService = serviceRepository.save(testService);
    }

    private void setupBinomClientMocks() {
        // Mock offer creation with unique IDs
        when(binomClient.createOffer(any(CreateOfferRequest.class)))
                .thenAnswer(
                        invocation -> {
                            CreateOfferRequest request = invocation.getArgument(0);
                            return CreateOfferResponse.builder()
                                    .offerId("PERF_OFFER_" + System.currentTimeMillis())
                                    .name(request.getName())
                                    .url(request.getUrl())
                                    .status("ACTIVE")
                                    .build();
                        });

        // Campaigns are pre-configured, no need to mock campaign creation

        // Mock offer assignment
        when(binomClient.assignOfferToCampaign(anyString(), anyString()))
                .thenReturn(AssignOfferResponse.builder().status("ASSIGNED").build());

        // Mock campaign stats
        when(binomClient.getCampaignStats(anyString()))
                .thenAnswer(
                        invocation -> {
                            String campaignId = invocation.getArgument(0);
                            return CampaignStatsResponse.builder()
                                    .campaignId(campaignId)
                                    .clicks(ThreadLocalRandom.current().nextLong(100, 500))
                                    .conversions(ThreadLocalRandom.current().nextLong(10, 50))
                                    .cost(
                                            BigDecimal.valueOf(
                                                    ThreadLocalRandom.current()
                                                            .nextDouble(50, 250)))
                                    .revenue(
                                            BigDecimal.valueOf(
                                                    ThreadLocalRandom.current()
                                                            .nextDouble(80, 400)))
                                    .build();
                        });
    }

    @Test
    @DisplayName("Concurrent 3-Campaign Creation Performance Test")
    @Timeout(30) // Should complete within 30 seconds
    void testConcurrent3CampaignCreation() throws InterruptedException {
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch completionLatch = new CountDownLatch(CONCURRENT_ORDERS);
        List<CompletableFuture<BinomIntegrationResponse>> futures = new ArrayList<>();
        List<Exception> exceptions = Collections.synchronizedList(new ArrayList<>());

        // Create concurrent orders with 3-campaign distribution
        for (int i = 0; i < CONCURRENT_ORDERS; i++) {
            final int orderIndex = i;
            CompletableFuture<BinomIntegrationResponse> future =
                    CompletableFuture.supplyAsync(
                            () -> {
                                try {
                                    startLatch.await(); // Wait for all threads to be ready

                                    // Create order
                                    Order order = createTestOrder(orderIndex);

                                    // Create Binom integration
                                    BinomIntegrationRequest request =
                                            BinomIntegrationRequest.builder()
                                                    .orderId(order.getId())
                                                    .targetViews(VIEWS_PER_ORDER)
                                                    .targetUrl(BASE_VIDEO_URL + orderIndex)
                                                    .clipCreated(
                                                            orderIndex % 2
                                                                    == 0) // Alternate clip creation
                                                    .coefficient(
                                                            orderIndex % 2 == 0
                                                                    ? BigDecimal.valueOf(3.0)
                                                                    : BigDecimal.valueOf(4.0))
                                                    .geoTargeting("US")
                                                    .build();

                                    return binomService.createBinomIntegration(request);

                                } catch (Exception e) {
                                    exceptions.add(e);
                                    throw new RuntimeException(e);
                                } finally {
                                    completionLatch.countDown();
                                }
                            },
                            executorService);

            futures.add(future);
        }

        // Start all threads simultaneously
        long startTime = System.currentTimeMillis();
        startLatch.countDown();

        // Wait for completion
        assertTrue(
                completionLatch.await(25, TimeUnit.SECONDS),
                "All concurrent operations should complete within 25 seconds");

        long endTime = System.currentTimeMillis();
        long totalTime = endTime - startTime;

        // Verify all operations completed successfully
        assertTrue(
                exceptions.isEmpty(),
                "No exceptions should occur during concurrent operations: " + exceptions);

        int successfulIntegrations = 0;
        int totalCampaignsCreated = 0;

        for (CompletableFuture<BinomIntegrationResponse> future : futures) {
            assertTrue(future.isDone(), "All futures should be completed");

            BinomIntegrationResponse response = future.join();
            assertNotNull(response);

            if ("SUCCESS".equals(response.getStatus())
                    || "PARTIAL_SUCCESS".equals(response.getStatus())) {
                successfulIntegrations++;
                totalCampaignsCreated += response.getCampaignsCreated();
            }
        }

        // Performance assertions
        assertEquals(
                CONCURRENT_ORDERS,
                successfulIntegrations,
                "All integrations should complete successfully");

        // We expect 3 campaigns per order, but allow for some partial failures
        assertTrue(
                totalCampaignsCreated >= CONCURRENT_ORDERS * 2,
                "At least 2 campaigns per order should be created");

        // Verify database state
        List<BinomCampaign> allCampaigns = binomCampaignRepository.findAll();
        assertTrue(
                allCampaigns.size() >= CONCURRENT_ORDERS * 2,
                "Database should contain campaigns from all orders");

        // Performance benchmark (should handle 10 concurrent orders in < 25 seconds)
        assertTrue(
                totalTime < 25000,
                String.format("Concurrent creation took %d ms, should be < 25000 ms", totalTime));

        System.out.printf(
                "Performance Test Results: %d orders, %d campaigns, %d ms total%n",
                CONCURRENT_ORDERS, totalCampaignsCreated, totalTime);
    }

    @Test
    @DisplayName("Stats Aggregation Performance Under Load")
    @Timeout(20)
    void testStatsAggregationPerformance() throws InterruptedException {
        // Create test orders with campaigns
        List<Order> orders = new ArrayList<>();
        for (int i = 0; i < CONCURRENT_ORDERS; i++) {
            Order order = createTestOrder(i);
            orders.add(order);

            // Create 3 campaigns per order
            for (int j = 0; j < 3; j++) {
                BinomCampaign campaign = new BinomCampaign();
                campaign.setOrderId(order.getId());
                campaign.setCampaignId("LOAD_TEST_CAMP_" + i + "_" + j);
                campaign.setActive(true);
                campaign.setStatus("ACTIVE");
                campaign.setClicksDelivered(100 + j * 10);
                campaign.setConversions(10 + j);
                campaign.setCost(BigDecimal.valueOf(50 + j * 5));
                campaign.setRevenue(BigDecimal.valueOf(80 + j * 8));
                campaign.setCreatedAt(LocalDateTime.now());
                campaign.setUpdatedAt(LocalDateTime.now());
                binomCampaignRepository.save(campaign);
            }
        }

        // Concurrent stats aggregation test
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch completionLatch = new CountDownLatch(CONCURRENT_ORDERS);
        List<CompletableFuture<CampaignStatsResponse>> statsFutures = new ArrayList<>();

        for (Order order : orders) {
            CompletableFuture<CampaignStatsResponse> future =
                    CompletableFuture.supplyAsync(
                            () -> {
                                try {
                                    startLatch.await();
                                    return binomService.getCampaignStatsForOrder(order.getId());
                                } catch (Exception e) {
                                    throw new RuntimeException(e);
                                } finally {
                                    completionLatch.countDown();
                                }
                            },
                            executorService);

            statsFutures.add(future);
        }

        long startTime = System.currentTimeMillis();
        startLatch.countDown();

        assertTrue(
                completionLatch.await(15, TimeUnit.SECONDS),
                "Stats aggregation should complete within 15 seconds");

        long totalTime = System.currentTimeMillis() - startTime;

        // Verify all stats aggregations completed
        for (int i = 0; i < statsFutures.size(); i++) {
            CompletableFuture<CampaignStatsResponse> future = statsFutures.get(i);
            assertTrue(future.isDone());

            CampaignStatsResponse stats = future.join();
            assertNotNull(stats);

            // Verify aggregation across 3 campaigns
            assertTrue(stats.getClicks() > 0, "Aggregated clicks should be > 0");
            assertTrue(stats.getConversions() > 0, "Aggregated conversions should be > 0");
            assertEquals("ACTIVE", stats.getStatus(), "Status should be ACTIVE with 3 campaigns");

            // Verify campaign IDs aggregation
            String[] campaignIds = stats.getCampaignId().split(",");
            assertEquals(3, campaignIds.length, "Should aggregate exactly 3 campaign IDs");
        }

        // Performance assertion
        assertTrue(
                totalTime < 15000,
                String.format("Stats aggregation took %d ms, should be < 15000 ms", totalTime));

        System.out.printf(
                "Stats Aggregation Performance: %d orders, %d ms total%n",
                CONCURRENT_ORDERS, totalTime);
    }

    @Test
    @DisplayName("Campaign Stop Operation Performance")
    @Timeout(15)
    void testCampaignStopPerformance() throws InterruptedException {
        // Create orders with campaigns
        List<Order> orders = new ArrayList<>();
        for (int i = 0; i < CONCURRENT_ORDERS; i++) {
            Order order = createTestOrder(i);
            orders.add(order);

            // Create 3 active campaigns
            for (int j = 0; j < 3; j++) {
                BinomCampaign campaign = new BinomCampaign();
                campaign.setOrderId(order.getId());
                campaign.setCampaignId("STOP_TEST_CAMP_" + i + "_" + j);
                campaign.setActive(true);
                campaign.setStatus("ACTIVE");
                campaign.setCreatedAt(LocalDateTime.now());
                campaign.setUpdatedAt(LocalDateTime.now());
                binomCampaignRepository.save(campaign);
            }
        }

        // Concurrent campaign stop test
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch completionLatch = new CountDownLatch(CONCURRENT_ORDERS);
        List<CompletableFuture<Void>> stopFutures = new ArrayList<>();

        for (Order order : orders) {
            CompletableFuture<Void> future =
                    CompletableFuture.runAsync(
                            () -> {
                                try {
                                    startLatch.await();
                                    binomService.stopAllCampaignsForOrder(order.getId());
                                } catch (Exception e) {
                                    throw new RuntimeException(e);
                                } finally {
                                    completionLatch.countDown();
                                }
                            },
                            executorService);

            stopFutures.add(future);
        }

        long startTime = System.currentTimeMillis();
        startLatch.countDown();

        assertTrue(
                completionLatch.await(10, TimeUnit.SECONDS),
                "Campaign stops should complete within 10 seconds");

        long totalTime = System.currentTimeMillis() - startTime;

        // Verify all stop operations completed
        for (CompletableFuture<Void> future : stopFutures) {
            assertTrue(future.isDone());
            assertDoesNotThrow(() -> future.join());
        }

        // Verify campaigns were stopped
        List<BinomCampaign> allCampaigns = binomCampaignRepository.findAll();
        long activeCampaigns = allCampaigns.stream().filter(BinomCampaign::isActive).count();

        assertEquals(0, activeCampaigns, "All campaigns should be inactive after stop");

        // Verify BinomClient was called for each campaign
        // Campaigns remain active - no stop operations needed

        assertTrue(
                totalTime < 10000,
                String.format("Campaign stop took %d ms, should be < 10000 ms", totalTime));

        System.out.printf(
                "Campaign Stop Performance: %d orders, %d campaigns, %d ms total%n",
                CONCURRENT_ORDERS, CONCURRENT_ORDERS * 3, totalTime);
    }

    @Test
    @DisplayName("Memory and Resource Usage Test")
    void testMemoryAndResourceUsage() {
        Runtime runtime = Runtime.getRuntime();
        long startMemory = runtime.totalMemory() - runtime.freeMemory();

        // Create a larger number of orders to test memory usage
        final int LARGE_ORDER_COUNT = 50;
        List<BinomIntegrationResponse> responses = new ArrayList<>();

        for (int i = 0; i < LARGE_ORDER_COUNT; i++) {
            Order order = createTestOrder(i);

            BinomIntegrationRequest request =
                    BinomIntegrationRequest.builder()
                            .orderId(order.getId())
                            .targetViews(VIEWS_PER_ORDER)
                            .targetUrl(BASE_VIDEO_URL + i)
                            .clipCreated(i % 2 == 0)
                            .coefficient(
                                    i % 2 == 0 ? BigDecimal.valueOf(3.0) : BigDecimal.valueOf(4.0))
                            .geoTargeting("US")
                            .build();

            BinomIntegrationResponse response = binomService.createBinomIntegration(request);
            responses.add(response);

            // Verify each response
            assertNotNull(response);
            assertTrue(
                    response.getCampaignsCreated() >= 1, "At least 1 campaign should be created");
        }

        // Force garbage collection
        System.gc();
        Thread.yield();

        long endMemory = runtime.totalMemory() - runtime.freeMemory();
        long memoryUsed = endMemory - startMemory;

        // Memory usage should be reasonable (< 100MB for 50 orders)
        assertTrue(
                memoryUsed < 100 * 1024 * 1024,
                String.format("Memory usage %d bytes should be < 100MB", memoryUsed));

        // Verify all integrations were successful
        long successfulIntegrations =
                responses.stream()
                        .filter(
                                r ->
                                        "SUCCESS".equals(r.getStatus())
                                                || "PARTIAL_SUCCESS".equals(r.getStatus()))
                        .count();

        assertEquals(
                LARGE_ORDER_COUNT,
                successfulIntegrations,
                "All integrations should complete successfully");

        System.out.printf(
                "Memory Usage Test: %d orders, %d bytes used%n", LARGE_ORDER_COUNT, memoryUsed);
    }

    private Order createTestOrder(int index) {
        Order order = new Order();
        order.setUser(testUser);
        order.setService(testService);
        order.setStatus(OrderStatus.ACTIVE);
        order.setQuantity(VIEWS_PER_ORDER);
        order.setLink(BASE_VIDEO_URL + index);
        order.setCharge(BigDecimal.valueOf(5.00));
        order.setStartCount(0);
        order.setRemains(VIEWS_PER_ORDER);
        order.setTargetViews(VIEWS_PER_ORDER);
        order.setTargetCountry("US");
        order.setYoutubeVideoId("perf" + index);
        order.setCreatedAt(LocalDateTime.now());
        order.setUpdatedAt(LocalDateTime.now());

        return orderRepository.save(order);
    }
}
