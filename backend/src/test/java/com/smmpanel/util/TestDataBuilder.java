package com.smmpanel.util;

import com.smmpanel.dto.request.CreateOrderRequest;
import com.smmpanel.entity.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/** Test Data Builder for SMM Panel Tests Provides utility methods to create consistent test data */
public class TestDataBuilder {

    /** Creates a test user with default values */
    public static User.UserBuilder createTestUser() {
        return User.builder()
                .username("testuser")
                .email("test@example.com")
                .passwordHash("password")
                .balance(new BigDecimal("100.00"))
                .isActive(true)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now());
    }

    /** Creates a test service with default values */
    public static Service.ServiceBuilder createTestService() {
        return Service.builder()
                .name("YouTube Views")
                .category("YouTube")
                .pricePer1000(new BigDecimal("1.50"))
                .minOrder(100)
                .maxOrder(10000)
                .active(true)
                .description("Test service for YouTube views")
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now());
    }

    /** Creates a test order with default values */
    public static Order.OrderBuilder createTestOrder() {
        return Order.builder()
                .user(createTestUser().build())
                .service(createTestService().build())
                .quantity(1000)
                .charge(new BigDecimal("1.50"))
                .status(OrderStatus.PENDING)
                .link("https://youtube.com/watch?v=test")
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now());
    }

    /** Creates a test order request */
    public static CreateOrderRequest createTestOrderRequest() {
        return CreateOrderRequest.builder()
                .service(1L)
                .quantity(1000)
                .link("https://youtube.com/watch?v=test")
                .build();
    }

    /** Creates a test balance deposit */
    public static BalanceDeposit createTestBalanceDeposit() {
        BalanceDeposit deposit = new BalanceDeposit();
        deposit.setUser(createTestUser().build());
        deposit.setAmountUsdt(new BigDecimal("50.00"));
        deposit.setOrderId("test-txn-123");
        deposit.setStatus(PaymentStatus.PENDING);
        deposit.setExpiresAt(LocalDateTime.now().plusHours(1));
        deposit.setCreatedAt(LocalDateTime.now());
        return deposit;
    }

    /** Creates a test campaign */
    public static BinomCampaign.BinomCampaignBuilder createTestCampaign() {
        return BinomCampaign.builder()
                .campaignId("12345")
                .campaignName("Test Campaign")
                .status("active")
                .targetUrl("https://example.com/landing")
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now());
    }

    /** Creates a list of test currencies */
    public static List<String> createTestCurrencies() {
        return List.of("USD", "EUR", "GBP", "CAD", "AUD");
    }

    /** Creates test exchange rates */
    public static java.util.Map<String, BigDecimal> createTestExchangeRates() {
        return java.util.Map.of(
                "USD", BigDecimal.ONE,
                "EUR", new BigDecimal("0.85"),
                "GBP", new BigDecimal("0.73"),
                "CAD", new BigDecimal("1.25"),
                "AUD", new BigDecimal("1.35"));
    }
}
