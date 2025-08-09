package com.smmpanel.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests to verify that controller optimizations work end-to-end
 * and prevent N+1 query issues in real HTTP requests
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Transactional
public class ControllerOptimizationIntegrationTest {

    @Autowired
    private WebApplicationContext context;
    
    @Autowired
    private ObjectMapper objectMapper;
    
    @PersistenceContext
    private EntityManager entityManager;
    
    private MockMvc mockMvc;
    private Statistics statistics;
    
    @BeforeEach
    public void setup() {
        mockMvc = MockMvcBuilders
                .webAppContextSetup(context)
                .apply(springSecurity())
                .build();
                
        // Enable Hibernate statistics
        Session session = entityManager.unwrap(Session.class);
        SessionFactory sessionFactory = session.getSessionFactory();
        statistics = sessionFactory.getStatistics();
        statistics.setStatisticsEnabled(true);
        statistics.clear();
    }

    @Test
    @WithMockUser(username = "testuser", roles = {"USER"})
    public void testOrderListEndpointQueryCount() throws Exception {
        // Clear statistics before test
        statistics.clear();
        long initialQueryCount = statistics.getQueryExecutionCount();
        
        // When: Call the orders listing endpoint
        mockMvc.perform(get("/api/v1/orders")
                .param("page", "0")
                .param("size", "10")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").exists());
        
        long finalQueryCount = statistics.getQueryExecutionCount();
        long queryCount = finalQueryCount - initialQueryCount;
        
        // Then: Verify reasonable query count
        System.out.println("Order list endpoint queries: " + queryCount);
        System.out.println("Entity loads: " + statistics.getEntityLoadCount());
        System.out.println("Collection loads: " + statistics.getCollectionLoadCount());
        
        // Should use minimal queries due to optimizations
        assertTrue(queryCount <= 3, 
            "Order listing should use at most 3 queries (user lookup + paginated orders + count), got: " + queryCount);
    }

    @Test
    @WithMockUser(username = "testuser", roles = {"USER"})
    public void testSingleOrderEndpointQueryCount() throws Exception {
        // Assuming we have an order with ID 1 for testing
        statistics.clear();
        long initialQueryCount = statistics.getQueryExecutionCount();
        
        // When: Call single order endpoint
        mockMvc.perform(get("/api/v1/orders/1")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
        
        long finalQueryCount = statistics.getQueryExecutionCount();
        long queryCount = finalQueryCount - initialQueryCount;
        
        // Then: Verify single optimized query
        System.out.println("Single order endpoint queries: " + queryCount);
        
        assertTrue(queryCount <= 2, 
            "Single order fetch should use at most 2 queries (user lookup + order with details), got: " + queryCount);
    }

    @Test
    @WithMockUser(username = "testuser", roles = {"USER"})
    public void testTransactionHistoryEndpointQueryCount() throws Exception {
        statistics.clear();
        long initialQueryCount = statistics.getQueryExecutionCount();
        
        // When: Call transaction history endpoint
        mockMvc.perform(get("/api/v1/balance/transactions")
                .param("page", "0")
                .param("size", "10")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
        
        long finalQueryCount = statistics.getQueryExecutionCount();
        long queryCount = finalQueryCount - initialQueryCount;
        
        // Then: Verify optimized query count
        System.out.println("Transaction history endpoint queries: " + queryCount);
        
        assertTrue(queryCount <= 3, 
            "Transaction history should use at most 3 queries, got: " + queryCount);
    }

    @Test
    @WithMockUser(username = "testuser", roles = {"ADMIN"})
    public void testOrderStatisticsEndpointQueryCount() throws Exception {
        statistics.clear();
        long initialQueryCount = statistics.getQueryExecutionCount();
        
        // When: Call order statistics endpoint (admin only)
        mockMvc.perform(get("/api/v1/orders/stats")
                .param("days", "30")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
        
        long finalQueryCount = statistics.getQueryExecutionCount();
        long queryCount = finalQueryCount - initialQueryCount;
        
        // Then: Statistics queries should be optimized
        System.out.println("Order statistics endpoint queries: " + queryCount);
        
        // Statistics endpoints may need more queries for aggregations, but should still be reasonable
        assertTrue(queryCount <= 10, 
            "Order statistics should use reasonable number of queries, got: " + queryCount);
    }

    @Test
    public void testPerfectPanelServicesEndpointCaching() throws Exception {
        // Test Perfect Panel services endpoint which should use caching
        statistics.clear();
        
        // First call
        long initialQueryCount = statistics.getQueryExecutionCount();
        
        mockMvc.perform(get("/api/v2")
                .param("key", "test-api-key")
                .param("action", "services")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED))
                .andExpect(status().isOk());
        
        long firstCallQueryCount = statistics.getQueryExecutionCount() - initialQueryCount;
        
        // Second call should use cache
        long secondCallInitial = statistics.getQueryExecutionCount();
        
        mockMvc.perform(get("/api/v2")
                .param("key", "test-api-key")
                .param("action", "services")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED))
                .andExpect(status().isOk());
        
        long secondCallQueryCount = statistics.getQueryExecutionCount() - secondCallInitial;
        
        System.out.println("First services call queries: " + firstCallQueryCount);
        System.out.println("Second services call queries: " + secondCallQueryCount);
        
        // Second call should use fewer queries due to caching
        assertTrue(secondCallQueryCount <= firstCallQueryCount, 
            "Second call should use same or fewer queries due to caching");
    }
    
    private void assertTrue(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}