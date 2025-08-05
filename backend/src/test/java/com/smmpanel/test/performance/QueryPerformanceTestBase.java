package com.smmpanel.test.performance;

import com.smmpanel.entity.Order;
import com.smmpanel.entity.Service;
import com.smmpanel.entity.User;
import com.smmpanel.entity.UserRole;
import com.smmpanel.entity.OrderStatus;
import com.smmpanel.repository.OrderRepository;
import com.smmpanel.repository.ServiceRepository;
import com.smmpanel.repository.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Base class for query performance testing with actual SQL query counting
 * Provides utilities for creating test data and measuring query performance
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@Slf4j
public abstract class QueryPerformanceTestBase {

    @PersistenceContext
    protected EntityManager entityManager;
    
    @Autowired
    protected UserRepository userRepository;
    
    @Autowired
    protected OrderRepository orderRepository;
    
    @Autowired
    protected ServiceRepository serviceRepository;
    
    // Performance thresholds from configuration
    @Value("${app.performance.max-queries.order-creation:3}")
    protected int maxQueriesOrderCreation;
    
    @Value("${app.performance.max-queries.order-listing:2}")
    protected int maxQueriesOrderListing;
    
    @Value("${app.performance.max-queries.single-order-fetch:1}")
    protected int maxQueriesSingleOrderFetch;
    
    @Value("${app.performance.max-queries.transaction-history:2}")
    protected int maxQueriesTransactionHistory;
    
    @Value("${app.performance.max-queries.balance-check:1}")
    protected int maxQueriesBalanceCheck;
    
    @Value("${app.performance.max-queries.service-listing:1}")
    protected int maxQueriesServiceListing;
    
    @Value("${app.performance.max-queries.user-profile:1}")
    protected int maxQueriesUserProfile;
    
    @Value("${app.performance.max-queries.bulk-operations:5}")
    protected int maxQueriesBulkOperations;
    
    protected Statistics statistics;
    protected Random random = new Random(42); // Fixed seed for reproducible tests
    
    @BeforeEach
    public void setupQueryPerformanceTesting() {
        // Enable Hibernate statistics
        Session session = entityManager.unwrap(Session.class);
        SessionFactory sessionFactory = session.getSessionFactory();
        statistics = sessionFactory.getStatistics();
        statistics.setStatisticsEnabled(true);
        statistics.clear();
        
        log.info("Query performance testing setup completed");
        log.info("Performance thresholds: Order Creation={}, Order Listing={}, Single Fetch={}", 
            maxQueriesOrderCreation, maxQueriesOrderListing, maxQueriesSingleOrderFetch);
    }
    
    /**
     * Measure query performance for a given operation
     */
    protected QueryPerformanceResult measureQueryPerformance(String operationName, Runnable operation) {
        statistics.clear();
        
        long startTime = System.currentTimeMillis();
        long initialQueryCount = statistics.getQueryExecutionCount();
        long initialEntityLoadCount = statistics.getEntityLoadCount();
        long initialCollectionLoadCount = statistics.getCollectionLoadCount();
        long initialSecondLevelCacheHitCount = statistics.getSecondLevelCacheHitCount();
        long initialSessionOpenCount = statistics.getSessionOpenCount();
        
        try {
            operation.run();
        } catch (Exception e) {
            log.error("Operation {} failed during performance measurement", operationName, e);
            throw new RuntimeException("Performance test operation failed", e);
        }
        
        long endTime = System.currentTimeMillis();
        long finalQueryCount = statistics.getQueryExecutionCount();
        long finalEntityLoadCount = statistics.getEntityLoadCount();
        long finalCollectionLoadCount = statistics.getCollectionLoadCount();
        long finalSecondLevelCacheHitCount = statistics.getSecondLevelCacheHitCount();
        long finalSessionOpenCount = statistics.getSessionOpenCount();
        
        QueryPerformanceResult result = QueryPerformanceResult.builder()
                .operationName(operationName)
                .queryCount(finalQueryCount - initialQueryCount)
                .entityLoadCount(finalEntityLoadCount - initialEntityLoadCount)
                .collectionLoadCount(finalCollectionLoadCount - initialCollectionLoadCount)
                .cacheHitCount(finalSecondLevelCacheHitCount - initialSecondLevelCacheHitCount)
                .sessionCount(finalSessionOpenCount - initialSessionOpenCount)
                .executionTimeMs(endTime - startTime)
                .build();
        
        log.info("Performance result for {}: {}", operationName, result);
        return result;
    }
    
    /**
     * Assert that query count is within acceptable limits
     */
    protected void assertQueryCountWithinLimit(QueryPerformanceResult result, int maxQueries) {
        if (result.getQueryCount() > maxQueries) {
            String errorMsg = String.format(
                "Query count exceeded limit for %s: expected <= %d, actual = %d. " +
                "Full stats: %s",
                result.getOperationName(), maxQueries, result.getQueryCount(), result
            );
            log.error(errorMsg);
            throw new AssertionError(errorMsg);
        }
        
        log.info("✓ Query count assertion passed for {}: {} <= {}", 
            result.getOperationName(), result.getQueryCount(), maxQueries);
    }
    
    /**
     * Create test users with different roles and balances
     */
    protected List<User> createTestUsers(int count) {
        List<User> users = new ArrayList<>();
        
        for (int i = 0; i < count; i++) {
            User user = User.builder()
                    .username("testuser" + i)
                    .email("testuser" + i + "@example.com")
                    .passwordHash("hashedpassword")
                    .balance(BigDecimal.valueOf(100 + random.nextInt(900))) // 100-999
                    .role(i == 0 ? UserRole.ADMIN : UserRole.USER)
                    .isActive(true)
                    .emailVerified(true)
                    .createdAt(LocalDateTime.now().minusDays(random.nextInt(30)))
                    .updatedAt(LocalDateTime.now())
                    .build();
            
            users.add(userRepository.save(user));
        }
        
        entityManager.flush();
        log.info("Created {} test users", count);
        return users;
    }
    
    /**
     * Create test services
     */
    protected List<Service> createTestServices(int count) {
        List<Service> services = new ArrayList<>();
        String[] categories = {"YouTube", "Instagram", "TikTok", "Facebook", "Twitter"};
        
        for (int i = 0; i < count; i++) {
            Service service = Service.builder()
                    .name("Test Service " + i)
                    .category(categories[i % categories.length])
                    .minOrder(10 + random.nextInt(90)) // 10-99
                    .maxOrder(1000 + random.nextInt(9000)) // 1000-9999
                    .pricePer1000(BigDecimal.valueOf(0.5 + random.nextDouble() * 4.5)) // 0.5-5.0
                    .description("Test service description " + i)
                    .active(true)
                    .geoTargeting("US")
                    .createdAt(LocalDateTime.now().minusDays(random.nextInt(60)))
                    .updatedAt(LocalDateTime.now())
                    .build();
            
            services.add(serviceRepository.save(service));
        }
        
        entityManager.flush();
        log.info("Created {} test services", count);
        return services;
    }
    
    /**
     * Create test orders for users
     */
    protected List<Order> createTestOrders(List<User> users, List<Service> services, int ordersPerUser) {
        List<Order> orders = new ArrayList<>();
        OrderStatus[] statuses = OrderStatus.values();
        
        for (User user : users) {
            for (int i = 0; i < ordersPerUser; i++) {
                Service service = services.get(random.nextInt(services.size()));
                
                Order order = Order.builder()
                        .user(user)
                        .service(service)
                        .link("https://youtube.com/watch?v=test" + random.nextInt(10000))
                        .quantity(service.getMinOrder() + random.nextInt(service.getMaxOrder() - service.getMinOrder()))
                        .charge(BigDecimal.valueOf(1 + random.nextDouble() * 49)) // 1-50
                        .startCount(0)
                        .remains(null)
                        .status(statuses[random.nextInt(statuses.length)])
                        .youtubeVideoId("test_video_" + random.nextInt(10000))
                        .targetViews(1000 + random.nextInt(9000))
                        .targetCountry("US")
                        .orderId("ORDER_" + System.currentTimeMillis() + "_" + random.nextInt(1000))
                        .processingPriority(random.nextInt(10))
                        .retryCount(0)
                        .maxRetries(3)
                        .createdAt(LocalDateTime.now().minusDays(random.nextInt(30)))
                        .updatedAt(LocalDateTime.now())
                        .build();
                
                orders.add(orderRepository.save(order));
            }
        }
        
        entityManager.flush();
        log.info("Created {} test orders ({} per user)", orders.size(), ordersPerUser);
        return orders;
    }
    
    /**
     * Clear Hibernate session and statistics
     */
    protected void clearSessionAndStats() {
        entityManager.flush();
        entityManager.clear();
        statistics.clear();
    }
    
    /**
     * Log current Hibernate statistics
     */
    protected void logHibernateStats(String context) {
        log.info("Hibernate Stats [{}]: Queries={}, Entities={}, Collections={}, Cache Hits={}, Sessions={}", 
            context,
            statistics.getQueryExecutionCount(),
            statistics.getEntityLoadCount(),
            statistics.getCollectionLoadCount(),
            statistics.getSecondLevelCacheHitCount(),
            statistics.getSessionOpenCount()
        );
    }
}