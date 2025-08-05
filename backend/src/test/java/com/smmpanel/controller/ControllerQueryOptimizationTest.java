package com.smmpanel.controller;

import com.smmpanel.entity.Order;
import com.smmpanel.entity.User;
import com.smmpanel.repository.OrderRepository;
import com.smmpanel.repository.UserRepository;
import com.smmpanel.service.OrderService;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test class to verify that controller layer optimizations prevent N+1 queries
 * and use proper batch fetching strategies
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional
public class ControllerQueryOptimizationTest {

    @Autowired
    private OrderRepository orderRepository;
    
    @Autowired
    private UserRepository userRepository;
    
    @Autowired
    private OrderService orderService;
    
    @PersistenceContext
    private EntityManager entityManager;
    
    private Statistics statistics;
    
    @BeforeEach
    public void setup() {
        // Get Hibernate session and enable statistics
        Session session = entityManager.unwrap(Session.class);
        SessionFactory sessionFactory = session.getSessionFactory();
        statistics = sessionFactory.getStatistics();
        statistics.setStatisticsEnabled(true);
        statistics.clear();
    }

    @Test
    public void testOptimizedOrderListingPreventsNPlusOne() {
        // Given: We have users with orders
        List<User> users = userRepository.findAll();
        assumeDataExists(users);
        
        // Clear statistics before test
        statistics.clear();
        
        // When: We fetch orders using optimized repository method
        long initialQueryCount = statistics.getQueryExecutionCount();
        
        Page<Order> orders = orderRepository.findOrdersWithDetailsByUserId(
            users.get(0).getId(), 
            PageRequest.of(0, 10)
        );
        
        long afterFetchQueryCount = statistics.getQueryExecutionCount();
        
        // Force initialization of relationships to test lazy loading
        for (Order order : orders.getContent()) {
            // These should NOT trigger additional queries due to JOIN FETCH
            String username = order.getUser().getUsername();
            String serviceName = order.getService().getName();
            assertNotNull(username);
            assertNotNull(serviceName);
        }
        
        long finalQueryCount = statistics.getQueryExecutionCount();
        
        // Then: Verify minimal query count
        long fetchQueries = afterFetchQueryCount - initialQueryCount;
        long lazyLoadQueries = finalQueryCount - afterFetchQueryCount;
        
        System.out.println("Fetch queries: " + fetchQueries);
        System.out.println("Lazy load queries: " + lazyLoadQueries);
        System.out.println("Total orders processed: " + orders.getContent().size());
        
        // Assert that we used minimal queries (ideally 1-2 queries total)
        assertTrue(fetchQueries <= 2, "Should use at most 2 queries for initial fetch");
        assertEquals(0, lazyLoadQueries, "Should have zero lazy loading queries due to JOIN FETCH");
        
        // Log additional statistics
        System.out.println("Entity loads: " + statistics.getEntityLoadCount());
        System.out.println("Collection loads: " + statistics.getCollectionLoadCount());
    }

    @Test
    public void testOptimizedSingleOrderFetchPreventsNPlusOne() {
        // Given: We have an order with relationships
        List<Order> orders = orderRepository.findAll();
        assumeDataExists(orders);
        
        Order testOrder = orders.get(0);
        Long orderId = testOrder.getId();
        
        // Clear Hibernate session and statistics
        entityManager.clear();
        statistics.clear();
        
        // When: We fetch single order using optimized method
        long initialQueryCount = statistics.getQueryExecutionCount();
        
        Order fetchedOrder = orderRepository.findByIdWithAllDetails(orderId).orElse(null);
        
        long afterFetchQueryCount = statistics.getQueryExecutionCount();
        
        assertNotNull(fetchedOrder);
        
        // Force access to relationships - should not trigger additional queries
        String username = fetchedOrder.getUser().getUsername();
        String serviceName = fetchedOrder.getService().getName();
        
        // Access collections if they exist
        if (fetchedOrder.getBinomCampaigns() != null) {
            fetchedOrder.getBinomCampaigns().size();
        }
        
        if (fetchedOrder.getVideoProcessing() != null) {
            fetchedOrder.getVideoProcessing().getStatus();
        }
        
        long finalQueryCount = statistics.getQueryExecutionCount();
        
        // Then: Verify single query was used
        long fetchQueries = afterFetchQueryCount - initialQueryCount;
        long lazyLoadQueries = finalQueryCount - afterFetchQueryCount;
        
        System.out.println("Single order fetch queries: " + fetchQueries);
        System.out.println("Lazy load queries: " + lazyLoadQueries);
        
        assertEquals(1, fetchQueries, "Should use exactly 1 query to fetch order with all details");
        assertEquals(0, lazyLoadQueries, "Should have zero lazy loading queries");
        
        assertNotNull(username);
        assertNotNull(serviceName);
    }

    @Test
    public void testBatchFetchingForMultipleOrders() {
        // Given: We have multiple orders
        List<Order> orders = orderRepository.findAll();
        assumeDataExists(orders);
        
        // Take first 5 orders for testing
        List<Long> orderIds = orders.stream()
                .limit(5)
                .map(Order::getId)
                .toList();
        
        // Clear session and statistics
        entityManager.clear();
        statistics.clear();
        
        // When: We fetch multiple orders by ID
        long initialQueryCount = statistics.getQueryExecutionCount();
        
        // Use IN clause to fetch multiple orders
        Query query = entityManager.createQuery(
            "SELECT o FROM Order o " +
            "JOIN FETCH o.user " +
            "JOIN FETCH o.service " +
            "WHERE o.id IN :ids"
        );
        query.setParameter("ids", orderIds);
        
        @SuppressWarnings("unchecked")
        List<Order> fetchedOrders = query.getResultList();
        
        long afterFetchQueryCount = statistics.getQueryExecutionCount();
        
        // Access relationships for all orders
        for (Order order : fetchedOrders) {
            order.getUser().getUsername();
            order.getService().getName();
        }
        
        long finalQueryCount = statistics.getQueryExecutionCount();
        
        // Then: Verify efficient batching
        long fetchQueries = afterFetchQueryCount - initialQueryCount;
        long lazyLoadQueries = finalQueryCount - afterFetchQueryCount;
        
        System.out.println("Batch fetch queries: " + fetchQueries);
        System.out.println("Orders fetched: " + fetchedOrders.size());
        System.out.println("Lazy load queries: " + lazyLoadQueries);
        
        assertEquals(1, fetchQueries, "Should use exactly 1 query to batch fetch orders");
        assertEquals(0, lazyLoadQueries, "Should have zero lazy loading queries");
        assertTrue(fetchedOrders.size() >= Math.min(5, orders.size()));
    }

    @Test
    public void testQueryCountForUserTransactions() {
        // Given: We have users with transactions
        List<User> users = userRepository.findAll();
        assumeDataExists(users);
        
        User testUser = users.get(0);
        
        // Clear statistics
        entityManager.clear();
        statistics.clear();
        
        // When: We fetch user's transaction history using pagination
        long initialQueryCount = statistics.getQueryExecutionCount();
        
        // Simulate what the controller would do - fetch transactions with details
        Query query = entityManager.createQuery(
            "SELECT bt FROM BalanceTransaction bt " +
            "JOIN FETCH bt.user " +
            "LEFT JOIN FETCH bt.order " +
            "LEFT JOIN FETCH bt.deposit " +
            "WHERE bt.user.id = :userId " +
            "ORDER BY bt.createdAt DESC"
        );
        query.setParameter("userId", testUser.getId());
        query.setMaxResults(20); // Simulate pagination
        
        @SuppressWarnings("unchecked")
        List<Object> transactions = query.getResultList();
        
        long afterFetchQueryCount = statistics.getQueryExecutionCount();
        
        // Then: Verify query efficiency
        long fetchQueries = afterFetchQueryCount - initialQueryCount;
        
        System.out.println("Transaction fetch queries: " + fetchQueries);
        System.out.println("Transactions fetched: " + transactions.size());
        
        assertTrue(fetchQueries <= 1, "Should use at most 1 query to fetch transactions with relations");
    }

    private void assumeDataExists(List<?> data) {
        if (data.isEmpty()) {
            System.out.println("Warning: No test data found. Skipping query optimization test.");
            org.junit.jupiter.api.Assumptions.assumeTrue(false);
        }
    }
}