package com.smmpanel.service.order;

import com.smmpanel.dto.ParsedOrder;
import com.smmpanel.dto.request.MassOrderRequest;
import com.smmpanel.repository.jpa.ServiceRepository;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Service for parsing Mass Order text input Parses text line by line and validates each order
 * component
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MassOrderParser {

    private final ServiceRepository serviceRepository;

    /**
     * Parse mass order text into individual orders
     *
     * @param request Mass order request containing text to parse
     * @return List of parsed orders with validation status
     */
    public List<ParsedOrder> parseOrders(MassOrderRequest request) {
        List<ParsedOrder> parsedOrders = new ArrayList<>();
        String ordersText = request.getOrdersText();
        String delimiter = request.getDelimiter();
        Integer maxOrders = request.getMaxOrders();

        if (ordersText == null || ordersText.trim().isEmpty()) {
            log.warn("Empty orders text received");
            return parsedOrders;
        }

        // Split text by lines
        String[] lines = ordersText.split("\\r?\\n");

        for (int i = 0; i < lines.length && parsedOrders.size() < maxOrders; i++) {
            String line = lines[i].trim();
            int lineNumber = i + 1;

            // Skip empty lines
            if (line.isEmpty()) {
                continue;
            }

            // Parse the line
            ParsedOrder parsedOrder = parseLine(line, delimiter, lineNumber);
            parsedOrders.add(parsedOrder);
        }

        if (lines.length > maxOrders) {
            log.warn(
                    "Input contains {} lines, but only {} orders will be processed",
                    lines.length,
                    maxOrders);
        }

        log.info("Parsed {} orders from {} lines", parsedOrders.size(), lines.length);
        return parsedOrders;
    }

    /**
     * Parse a single line into an order
     *
     * @param line Line text to parse
     * @param delimiter Delimiter to use for splitting
     * @param lineNumber Line number for error reporting
     * @return Parsed order with validation status
     */
    private ParsedOrder parseLine(String line, String delimiter, int lineNumber) {
        ParsedOrder.ParsedOrderBuilder builder =
                ParsedOrder.builder().lineNumber(lineNumber).originalLine(line).valid(false);

        try {
            // Split by delimiter (escaping it for regex)
            String[] parts = line.split(java.util.regex.Pattern.quote(delimiter));

            if (parts.length != 3) {
                return builder.errorMessage(
                                String.format(
                                        "Line %d: Invalid format. Expected 3 parts separated by"
                                                + " '%s', got %d parts",
                                        lineNumber, delimiter, parts.length))
                        .build();
            }

            // Parse service ID
            String serviceIdStr = parts[0].trim();
            Long serviceId;
            try {
                serviceId = Long.parseLong(serviceIdStr);
                if (serviceId <= 0) {
                    return builder.errorMessage(
                                    String.format(
                                            "Line %d: Service ID must be positive, got %d",
                                            lineNumber, serviceId))
                            .build();
                }
            } catch (NumberFormatException e) {
                return builder.errorMessage(
                                String.format(
                                        "Line %d: Invalid service ID '%s', must be a number",
                                        lineNumber, serviceIdStr))
                        .build();
            }

            // Parse link
            String link = parts[1].trim();
            if (link.isEmpty()) {
                return builder.serviceId(serviceId)
                        .errorMessage(String.format("Line %d: Link cannot be empty", lineNumber))
                        .build();
            }

            // Basic URL validation
            if (!isValidUrl(link)) {
                return builder.serviceId(serviceId)
                        .link(link)
                        .errorMessage(
                                String.format("Line %d: Invalid URL format '%s'", lineNumber, link))
                        .build();
            }

            // Parse quantity
            String quantityStr = parts[2].trim();
            Integer quantity;
            try {
                quantity = Integer.parseInt(quantityStr);
                if (quantity <= 0) {
                    return builder.serviceId(serviceId)
                            .link(link)
                            .errorMessage(
                                    String.format(
                                            "Line %d: Quantity must be positive, got %d",
                                            lineNumber, quantity))
                            .build();
                }
            } catch (NumberFormatException e) {
                return builder.serviceId(serviceId)
                        .link(link)
                        .errorMessage(
                                String.format(
                                        "Line %d: Invalid quantity '%s', must be a number",
                                        lineNumber, quantityStr))
                        .build();
            }

            // Build valid parsed order
            return builder.serviceId(serviceId)
                    .link(link)
                    .quantity(quantity)
                    .valid(true)
                    .errorMessage(null)
                    .build();

        } catch (Exception e) {
            log.error("Error parsing line {}: {}", lineNumber, line, e);
            return builder.errorMessage(
                            String.format(
                                    "Line %d: Unexpected error: %s", lineNumber, e.getMessage()))
                    .build();
        }
    }

    /**
     * Basic URL validation
     *
     * @param url URL to validate
     * @return true if URL appears valid
     */
    private boolean isValidUrl(String url) {
        if (url == null || url.trim().isEmpty()) {
            return false;
        }

        // Basic check for URL patterns
        String urlLower = url.toLowerCase();
        return urlLower.startsWith("http://")
                || urlLower.startsWith("https://")
                || urlLower.contains(".")
                || // Domain with TLD
                urlLower.startsWith("www.")
                || urlLower.contains("youtube.")
                || urlLower.contains("instagram.")
                || urlLower.contains("facebook.")
                || urlLower.contains("twitter.")
                || urlLower.contains("tiktok.");
    }

    /**
     * Validate service IDs exist in database
     *
     * @param parsedOrders List of parsed orders to validate
     * @return List of parsed orders with service validation status updated
     */
    public List<ParsedOrder> validateServices(List<ParsedOrder> parsedOrders) {
        for (ParsedOrder order : parsedOrders) {
            if (!order.isValid()) {
                continue;
            }

            // Check if service exists
            boolean serviceExists = serviceRepository.existsById(order.getServiceId());
            if (!serviceExists) {
                order.setValid(false);
                order.setErrorMessage(
                        String.format(
                                "Line %d: Service with ID %d does not exist",
                                order.getLineNumber(), order.getServiceId()));
            }
        }
        return parsedOrders;
    }
}
