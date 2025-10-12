package com.smmpanel.service.order;

import com.smmpanel.dto.ParsedOrder;
import com.smmpanel.dto.request.CreateOrderRequest;
import com.smmpanel.dto.request.MassOrderRequest;
import com.smmpanel.dto.response.MassOrderResponse;
import com.smmpanel.dto.response.OrderResponse;
import com.smmpanel.entity.Service;
import com.smmpanel.entity.User;
import com.smmpanel.repository.jpa.ServiceRepository;
import com.smmpanel.repository.jpa.UserRepository;
import com.smmpanel.service.balance.BalanceService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Service for processing Mass Orders Handles parsing, validation, and creation of multiple orders
 * at once
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MassOrderService {

    private final MassOrderParser massOrderParser;
    private final OrderService orderService;
    private final ServiceRepository serviceRepository;
    private final UserRepository userRepository;
    private final BalanceService balanceService;

    /**
     * Process a mass order request
     *
     * @param request Mass order request
     * @param username Username of the user creating the orders
     * @return Response containing results of all order creations
     */
    // Remove transaction annotation - each order will be processed in its own transaction
    public MassOrderResponse processMassOrder(MassOrderRequest request, String username) {
        log.info("Processing mass order for user: {}", username);

        // Parse the orders
        List<ParsedOrder> parsedOrders = massOrderParser.parseOrders(request);

        // Validate services exist
        parsedOrders = massOrderParser.validateServices(parsedOrders);

        // Separate valid and invalid orders
        List<ParsedOrder> validOrders = new ArrayList<>();
        List<MassOrderResponse.ParseError> parseErrors = new ArrayList<>();

        for (ParsedOrder order : parsedOrders) {
            if (order.isValid()) {
                validOrders.add(order);
            } else {
                parseErrors.add(
                        MassOrderResponse.ParseError.builder()
                                .lineNumber(order.getLineNumber())
                                .originalLine(order.getOriginalLine())
                                .errorMessage(order.getErrorMessage())
                                .build());
            }
        }

        // Calculate total cost and check balance
        BigDecimal totalCost = calculateTotalCost(validOrders);

        // Get user
        User user =
                userRepository
                        .findByUsername(username)
                        .orElseThrow(() -> new RuntimeException("User not found: " + username));

        // Check if user has sufficient balance for all orders
        BigDecimal userBalance = user.getBalance();
        if (userBalance.compareTo(totalCost) < 0) {
            log.warn(
                    "Insufficient balance for user {}. Required: {}, Available: {}",
                    username,
                    totalCost,
                    userBalance);

            return MassOrderResponse.builder()
                    .totalOrders(parsedOrders.size())
                    .successfulOrders(0)
                    .failedOrders(validOrders.size())
                    .parseErrors(parseErrors)
                    .failed(
                            validOrders.stream()
                                    .map(
                                            order ->
                                                    MassOrderResponse.OrderResult.builder()
                                                            .serviceId(order.getServiceId())
                                                            .link(order.getLink())
                                                            .quantity(order.getQuantity())
                                                            .lineNumber(order.getLineNumber())
                                                            .originalLine(order.getOriginalLine())
                                                            .errorMessage(
                                                                    "Insufficient balance for all"
                                                                            + " orders. Required: "
                                                                            + totalCost
                                                                            + ", Available: "
                                                                            + userBalance)
                                                            .build())
                                    .toList())
                    .totalCost(0.0)
                    .processedAt(LocalDateTime.now())
                    .build();
        }

        // Process valid orders
        List<MassOrderResponse.OrderResult> successfulOrders = new ArrayList<>();
        List<MassOrderResponse.OrderResult> failedOrders = new ArrayList<>();
        double actualTotalCost = 0.0;

        for (ParsedOrder parsedOrder : validOrders) {
            try {
                // Create order request
                CreateOrderRequest orderRequest =
                        CreateOrderRequest.builder()
                                .service(parsedOrder.getServiceId())
                                .link(parsedOrder.getLink())
                                .quantity(parsedOrder.getQuantity())
                                .build();

                // Create the order in a separate transaction
                OrderResponse orderResponse = processSingleOrder(orderRequest, username);

                // Calculate cost for this order
                Optional<Service> serviceOpt =
                        serviceRepository.findById(parsedOrder.getServiceId());
                double orderCost = 0.0;
                if (serviceOpt.isPresent()) {
                    Service service = serviceOpt.get();
                    orderCost =
                            service.getPricePer1000()
                                    .multiply(
                                            BigDecimal.valueOf(parsedOrder.getQuantity())
                                                    .divide(
                                                            BigDecimal.valueOf(1000),
                                                            4,
                                                            RoundingMode.HALF_UP))
                                    .doubleValue();
                }

                actualTotalCost += orderCost;

                // Add to successful orders
                successfulOrders.add(
                        MassOrderResponse.OrderResult.builder()
                                .orderId(orderResponse.getId())
                                .serviceId(parsedOrder.getServiceId())
                                .link(parsedOrder.getLink())
                                .quantity(parsedOrder.getQuantity())
                                .cost(orderCost)
                                .status(orderResponse.getStatus())
                                .lineNumber(parsedOrder.getLineNumber())
                                .originalLine(parsedOrder.getOriginalLine())
                                .build());

                log.info(
                        "Successfully created order {} for line {}",
                        orderResponse.getId(),
                        parsedOrder.getLineNumber());

            } catch (Exception e) {
                log.error(
                        "Failed to create order for line {}: {}",
                        parsedOrder.getLineNumber(),
                        e.getMessage());

                // Add to failed orders
                failedOrders.add(
                        MassOrderResponse.OrderResult.builder()
                                .serviceId(parsedOrder.getServiceId())
                                .link(parsedOrder.getLink())
                                .quantity(parsedOrder.getQuantity())
                                .lineNumber(parsedOrder.getLineNumber())
                                .originalLine(parsedOrder.getOriginalLine())
                                .errorMessage(e.getMessage())
                                .build());
            }
        }

        // Build response
        MassOrderResponse response =
                MassOrderResponse.builder()
                        .totalOrders(parsedOrders.size())
                        .successfulOrders(successfulOrders.size())
                        .failedOrders(failedOrders.size() + parseErrors.size())
                        .successful(successfulOrders)
                        .failed(failedOrders)
                        .parseErrors(parseErrors)
                        .totalCost(actualTotalCost)
                        .processedAt(LocalDateTime.now())
                        .build();

        log.info(
                "Mass order processing completed for user {}. Total: {}, Success: {}, Failed: {}",
                username,
                parsedOrders.size(),
                successfulOrders.size(),
                failedOrders.size() + parseErrors.size());

        return response;
    }

    /**
     * Process a single order in its own transaction This ensures that if one order fails, others
     * can still be processed
     *
     * @param orderRequest Order request details
     * @param username Username of the user
     * @return Order response
     */
    @Transactional(
            propagation = Propagation.REQUIRES_NEW,
            isolation = Isolation.READ_COMMITTED,
            rollbackFor = Exception.class)
    public OrderResponse processSingleOrder(CreateOrderRequest orderRequest, String username) {
        return orderService.createOrder(orderRequest, username);
    }

    /**
     * Calculate total cost for all valid orders
     *
     * @param validOrders List of valid parsed orders
     * @return Total cost
     */
    private BigDecimal calculateTotalCost(List<ParsedOrder> validOrders) {
        BigDecimal totalCost = BigDecimal.ZERO;

        for (ParsedOrder order : validOrders) {
            Optional<Service> serviceOpt = serviceRepository.findById(order.getServiceId());
            if (serviceOpt.isPresent()) {
                Service service = serviceOpt.get();
                // Calculate cost: rate * quantity / 1000
                BigDecimal orderCost =
                        service.getPricePer1000()
                                .multiply(
                                        BigDecimal.valueOf(order.getQuantity())
                                                .divide(
                                                        BigDecimal.valueOf(1000),
                                                        4,
                                                        RoundingMode.HALF_UP));
                totalCost = totalCost.add(orderCost);
            }
        }

        return totalCost;
    }

    /**
     * Preview mass order without actually creating orders Useful for validation and cost
     * calculation before submission
     *
     * @param request Mass order request
     * @param username Username of the user
     * @return Preview response with parsed orders and total cost
     */
    public MassOrderResponse previewMassOrder(MassOrderRequest request, String username) {
        log.info("Previewing mass order for user: {}", username);

        // Parse the orders
        List<ParsedOrder> parsedOrders = massOrderParser.parseOrders(request);

        // Validate services exist
        parsedOrders = massOrderParser.validateServices(parsedOrders);

        // Separate valid and invalid orders
        List<ParsedOrder> validOrders = new ArrayList<>();
        List<MassOrderResponse.ParseError> parseErrors = new ArrayList<>();

        for (ParsedOrder order : parsedOrders) {
            if (order.isValid()) {
                validOrders.add(order);
            } else {
                parseErrors.add(
                        MassOrderResponse.ParseError.builder()
                                .lineNumber(order.getLineNumber())
                                .originalLine(order.getOriginalLine())
                                .errorMessage(order.getErrorMessage())
                                .build());
            }
        }

        // Calculate total cost
        BigDecimal totalCost = calculateTotalCost(validOrders);

        // Build preview response (no actual orders created)
        List<MassOrderResponse.OrderResult> previewOrders =
                validOrders.stream()
                        .map(
                                order -> {
                                    Optional<Service> serviceOpt =
                                            serviceRepository.findById(order.getServiceId());
                                    double orderCost = 0.0;
                                    if (serviceOpt.isPresent()) {
                                        Service service = serviceOpt.get();
                                        orderCost =
                                                service.getPricePer1000()
                                                        .multiply(
                                                                BigDecimal.valueOf(
                                                                                order.getQuantity())
                                                                        .divide(
                                                                                BigDecimal.valueOf(
                                                                                        1000),
                                                                                4,
                                                                                RoundingMode
                                                                                        .HALF_UP))
                                                        .doubleValue();
                                    }

                                    return MassOrderResponse.OrderResult.builder()
                                            .serviceId(order.getServiceId())
                                            .link(order.getLink())
                                            .quantity(order.getQuantity())
                                            .cost(orderCost)
                                            .lineNumber(order.getLineNumber())
                                            .originalLine(order.getOriginalLine())
                                            .status("PREVIEW")
                                            .build();
                                })
                        .toList();

        return MassOrderResponse.builder()
                .totalOrders(parsedOrders.size())
                .successfulOrders(validOrders.size())
                .failedOrders(parseErrors.size())
                .successful(previewOrders)
                .parseErrors(parseErrors)
                .totalCost(totalCost.doubleValue())
                .processedAt(LocalDateTime.now())
                .build();
    }
}
