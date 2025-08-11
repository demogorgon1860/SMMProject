package com.smmpanel.dto.response;

import com.smmpanel.entity.Order;
import com.smmpanel.entity.OrderStatus;
import com.smmpanel.entity.Service;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Perfect Panel Response Builder Ensures consistent response formatting that matches Perfect Panel
 * API exactly
 */
@Slf4j
@Component
public class PerfectPanelResponseBuilder {

    /** Build add order response Format: {"order": 12345} */
    public Map<String, Object> buildAddOrderResponse(Long orderId) {
        Map<String, Object> response = new HashMap<>();
        response.put("order", orderId);
        return response;
    }

    /**
     * Build order status response Format: {"order": 12345, "status": "In progress", "remains": 500,
     * "start_count": 1000, "charge": "10.50"}
     */
    public Map<String, Object> buildOrderStatusResponse(Order order) {
        Map<String, Object> response = new HashMap<>();
        response.put("order", order.getId());
        response.put("status", mapToPerfectPanelStatus(order.getStatus()));
        response.put("remains", order.getRemains() != null ? order.getRemains() : 0);
        response.put("start_count", order.getStartCount() != null ? order.getStartCount() : 0);
        response.put("charge", formatPrice(order.getCharge()));
        response.put("currency", "USD");
        return response;
    }

    /** Build balance response Format: {"balance": "45.67", "currency": "USD"} */
    public Map<String, Object> buildBalanceResponse(BigDecimal balance) {
        Map<String, Object> response = new HashMap<>();
        response.put("balance", formatBalance(balance));
        response.put("currency", "USD");
        return response;
    }

    /** Build services response Format: Array of service objects with exact field names */
    public List<Map<String, Object>> buildServicesResponse(List<Service> services) {
        return services.stream().map(this::buildServiceResponse).toList();
    }

    /** Build individual service response */
    private Map<String, Object> buildServiceResponse(Service service) {
        Map<String, Object> serviceMap = new HashMap<>();
        serviceMap.put("service", service.getId());
        serviceMap.put("name", service.getName());
        serviceMap.put("category", service.getCategory());
        serviceMap.put("rate", formatPrice(service.getPricePer1000()));
        serviceMap.put("min", service.getMinOrder());
        serviceMap.put("max", service.getMaxOrder());
        serviceMap.put("dripfeed", false); // Default values for Perfect Panel compatibility
        serviceMap.put("refill", false);
        serviceMap.put("cancel", true);
        return serviceMap;
    }

    /** Build error response Format: {"error": "Error message"} */
    public Map<String, Object> buildErrorResponse(String errorMessage) {
        Map<String, Object> response = new HashMap<>();
        response.put("error", errorMessage);
        return response;
    }

    /**
     * Build success response for operations like cancel/refill Format: {"cancel": 12345, "status":
     * "Success"}
     */
    public Map<String, Object> buildOperationResponse(String operation, Long orderId) {
        Map<String, Object> response = new HashMap<>();
        response.put(operation, orderId);
        response.put("status", "Success");
        return response;
    }

    /**
     * CRITICAL: Status mapping MUST match Perfect Panel exactly Perfect Panel status strings MUST
     * be preserved exactly
     */
    private String mapToPerfectPanelStatus(OrderStatus internalStatus) {
        return switch (internalStatus) {
            case PENDING -> "Pending";
            case IN_PROGRESS -> "In progress";
            case PROCESSING -> "In progress";
            case ACTIVE -> "In progress";
            case PARTIAL -> "Partial";
            case COMPLETED -> "Completed";
            case CANCELLED -> "Canceled"; // Note: Perfect Panel uses "Canceled" not "Cancelled"
            case PAUSED -> "Paused";
            case HOLDING -> "In progress";
            case REFILL -> "Refill";
            case ERROR -> "Error";
            case SUSPENDED -> "Canceled";
        };
    }

    /** Format price with 4 decimal places (Perfect Panel standard) */
    private String formatPrice(BigDecimal price) {
        if (price == null) {
            return "0.0000";
        }
        return price.setScale(4, RoundingMode.HALF_UP).toString();
    }

    /** Format balance with 2 decimal places (Perfect Panel standard) */
    private String formatBalance(BigDecimal balance) {
        if (balance == null) {
            return "0.00";
        }
        return balance.setScale(2, RoundingMode.HALF_UP).toString();
    }
}
