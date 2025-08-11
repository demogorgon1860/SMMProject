package com.smmpanel.integration;

import com.smmpanel.entity.Order;
import com.smmpanel.entity.OrderStatus;
import com.smmpanel.entity.Service;
import com.smmpanel.entity.User;
import com.smmpanel.entity.UserRole;
import java.math.BigDecimal;

/** Test Data Builder Helper class to create test data for integration tests */
public class TestDataBuilder {

    /** Build a test user */
    public static User.UserBuilder userBuilder() {
        return User.builder()
                .username("testuser")
                .email("test@example.com")
                .passwordHash("password")
                .balance(new BigDecimal("100.00"))
                .isActive(true)
                .role(UserRole.USER)
                .preferredCurrency("USD");
    }

    /** Build a test service */
    public static Service.ServiceBuilder serviceBuilder() {
        return Service.builder()
                .name("YouTube Views")
                .category("YouTube")
                .pricePer1000(new BigDecimal("1.50"))
                .minOrder(100)
                .maxOrder(10000)
                .active(true)
                .description("YouTube view service")
                .geoTargeting("US");
    }

    /** Build a test order */
    public static Order.OrderBuilder orderBuilder(User user, Service service) {
        return Order.builder()
                .user(user)
                .service(service)
                .link("https://www.youtube.com/watch?v=dQw4w9WgXcQ")
                .quantity(1000)
                .charge(new BigDecimal("1.50"))
                .status(OrderStatus.PENDING)
                .startCount(0)
                .remains(1000)
                .coefficient(new BigDecimal("4.0"));
    }

    /** Build a YouTube order with clip creation */
    public static Order.OrderBuilder youtubeClipOrderBuilder(User user, Service service) {
        return Order.builder()
                .user(user)
                .service(service)
                .link("https://www.youtube.com/watch?v=dQw4w9WgXcQ")
                .quantity(1000)
                .charge(new BigDecimal("1.50"))
                .status(OrderStatus.PENDING)
                .startCount(0)
                .remains(1000)
                .coefficient(new BigDecimal("3.0")); // Clip creation coefficient
    }

    /** Build an admin user */
    public static User.UserBuilder adminUserBuilder() {
        return User.builder()
                .username("admin")
                .email("admin@example.com")
                .passwordHash("password")
                .balance(new BigDecimal("1000.00"))
                .isActive(true)
                .role(UserRole.ADMIN)
                .preferredCurrency("USD");
    }

    /** Build an operator user */
    public static User.UserBuilder operatorUserBuilder() {
        return User.builder()
                .username("operator")
                .email("operator@example.com")
                .passwordHash("password")
                .balance(new BigDecimal("500.00"))
                .isActive(true)
                .role(UserRole.OPERATOR)
                .preferredCurrency("USD");
    }

    /** Build a service with specific parameters */
    public static Service.ServiceBuilder serviceBuilder(
            String name, String category, BigDecimal price) {
        return Service.builder()
                .name(name)
                .category(category)
                .pricePer1000(price)
                .minOrder(100)
                .maxOrder(10000)
                .active(true)
                .description(name + " service")
                .geoTargeting("US");
    }

    /** Build an order with specific parameters */
    public static Order.OrderBuilder orderBuilder(
            User user, Service service, String link, int quantity) {
        BigDecimal charge =
                service.getPricePer1000()
                        .multiply(new BigDecimal(quantity))
                        .divide(new BigDecimal(1000), 2, BigDecimal.ROUND_HALF_UP);

        return Order.builder()
                .user(user)
                .service(service)
                .link(link)
                .quantity(quantity)
                .charge(charge)
                .status(OrderStatus.PENDING)
                .startCount(0)
                .remains(quantity)
                .coefficient(new BigDecimal("4.0"));
    }
}
