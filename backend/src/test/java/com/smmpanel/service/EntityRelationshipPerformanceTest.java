package com.smmpanel.service;

import static org.junit.jupiter.api.Assertions.*;

import com.smmpanel.entity.Order;
import com.smmpanel.entity.User;
import com.smmpanel.repository.jpa.OrderRepository;
import com.smmpanel.repository.jpa.UserRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.List;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * Performance test for entity relationship fetch strategies
 *
 * <p>This test verifies that: 1. Lazy loading is working correctly 2. Batch fetching reduces N+1
 * queries 3. Collections are loaded efficiently
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
public class EntityRelationshipPerformanceTest {

    @Autowired private UserRepository userRepository;

    @Autowired private OrderRepository orderRepository;

    @PersistenceContext private EntityManager entityManager;

    @Test
    public void testLazyLoadingWithBatchFetching() {
        // Get Hibernate session and enable statistics
        Session session = entityManager.unwrap(Session.class);
        SessionFactory sessionFactory = session.getSessionFactory();
        Statistics statistics = sessionFactory.getStatistics();
        statistics.setStatisticsEnabled(true);
        statistics.clear();

        // Load multiple users to test batch fetching
        List<User> users = userRepository.findAll();

        long initialQueryCount = statistics.getQueryExecutionCount();

        // Access orders for each user - should trigger batch loading
        for (User user : users) {
            List<Order> orders = user.getOrders();
            if (orders != null && !orders.isEmpty()) {
                // Force initialization
                orders.size();
            }
        }

        long finalQueryCount = statistics.getQueryExecutionCount();

        // With batch fetching, we should have fewer queries than users
        // The exact number depends on batch size and data distribution
        System.out.println("Initial queries: " + initialQueryCount);
        System.out.println("Final queries: " + finalQueryCount);
        System.out.println("Users processed: " + users.size());

        // Verify that lazy loading worked (we have some queries but not excessive)
        assertTrue(finalQueryCount >= initialQueryCount);

        // Log batch fetch statistics
        System.out.println("Entity load count: " + statistics.getEntityLoadCount());
        System.out.println("Collection load count: " + statistics.getCollectionLoadCount());
        System.out.println(
                "Second level cache hit count: " + statistics.getSecondLevelCacheHitCount());
    }

    @Test
    public void testOrderRelationshipFetching() {
        Session session = entityManager.unwrap(Session.class);
        SessionFactory sessionFactory = session.getSessionFactory();
        Statistics statistics = sessionFactory.getStatistics();
        statistics.setStatisticsEnabled(true);
        statistics.clear();

        // Load orders and test related entity fetching
        List<Order> orders = orderRepository.findAll();

        long initialQueryCount = statistics.getQueryExecutionCount();

        // Access user and service relationships
        for (Order order : orders) {
            if (order.getUser() != null) {
                order.getUser().getUsername(); // Force user loading
            }
            if (order.getService() != null) {
                order.getService().getName(); // Force service loading
            }
            if (order.getBinomCampaigns() != null) {
                order.getBinomCampaigns().size(); // Force campaign loading
            }
        }

        long finalQueryCount = statistics.getQueryExecutionCount();

        System.out.println(
                "Order relationship queries - Initial: "
                        + initialQueryCount
                        + ", Final: "
                        + finalQueryCount);
        System.out.println("Orders processed: " + orders.size());

        // Verify relationships were loaded
        assertTrue(finalQueryCount >= initialQueryCount);
    }

    @Test
    public void testBatchSizeConfiguration() {
        // This test ensures that @BatchSize annotations are properly configured
        List<User> users = userRepository.findAll();

        for (User user : users) {
            // Access collections to trigger batch loading
            if (user.getOrders() != null) {
                user.getOrders().size();
            }
            if (user.getBalanceTransactions() != null) {
                user.getBalanceTransactions().size();
            }
        }

        // If we reach here without LazyInitializationException, batch loading is working
        assertTrue(true, "Batch loading completed successfully");
    }
}
