package com.smmpanel.controller;

import com.smmpanel.dto.request.DepositRequest;
import com.smmpanel.dto.response.BalanceResponse;
import com.smmpanel.dto.response.TransactionHistoryResponse;
import com.smmpanel.entity.OrderStatus;
import com.smmpanel.entity.User;
import com.smmpanel.exception.ResourceNotFoundException;
import com.smmpanel.repository.jpa.OrderRepository;
import com.smmpanel.repository.jpa.UserRepository;
import com.smmpanel.service.balance.BalanceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/api/v1/balance")
@RequiredArgsConstructor
@Tag(name = "Balance Management", description = "Endpoints for managing user balance")
@SecurityRequirement(name = "bearerAuth")
public class BalanceController {

    private final BalanceService balanceService;
    private final UserRepository userRepository;
    private final OrderRepository orderRepository;

    @GetMapping
    @Operation(
            summary = "Get current balance",
            description = "Returns the current balance of the authenticated user")
    public ResponseEntity<BalanceResponse> getBalance() {
        User user = getCurrentUser();

        BalanceResponse response = new BalanceResponse();
        response.setBalance(user.getBalance());
        response.setCurrency("USD");
        response.setLastUpdated(user.getUpdatedAt());
        response.setTotalSpent(orderRepository.sumChargeByUser_Username(user.getUsername()));
        response.setTotalOrders(orderRepository.countByUser_Username(user.getUsername()));
        response.setTotalDelivered(
                orderRepository.sumDeliveredByUserAndStatuses(
                        user.getUsername(), List.of(OrderStatus.COMPLETED, OrderStatus.PARTIAL)));

        return ResponseEntity.ok(response);
    }

    @PostMapping("/deposit")
    @Operation(
            summary = "Add funds to balance",
            description = "Adds funds to user balance (admin only in production)")
    public ResponseEntity<Map<String, Object>> deposit(@RequestBody DepositRequest request) {
        User user = getCurrentUser();

        // In production, this would integrate with payment gateway
        // For now, allow manual deposits for testing
        BigDecimal newBalance =
                balanceService.addBalance(
                        user, request.getAmount(), "Manual deposit: " + request.getDescription());

        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("newBalance", newBalance);
        response.put("message", "Funds added successfully");

        log.info("Deposit of {} for user: {}", request.getAmount(), user.getUsername());
        return ResponseEntity.ok(response);
    }

    @GetMapping("/transactions")
    @Operation(
            summary = "Get transaction history",
            description =
                    "Returns paginated transaction history. Optional ?type=DEPOSIT,REFUND filter"
                            + " narrows to those types so the dedicated frontend tabs (Deposits,"
                            + " Refunds, etc.) don't get drowned by ORDER_PAYMENT rows.")
    public ResponseEntity<Page<TransactionHistoryResponse>> getTransactionHistory(
            @RequestParam(value = "type", required = false) String type, Pageable pageable) {
        User user = getCurrentUser();

        java.util.List<com.smmpanel.entity.TransactionType> types = null;
        if (type != null && !type.isBlank()) {
            // Caller explicitly asked for a filter — parse what they sent, but if the
            // entire list ends up empty (every value was unrecognized), return an empty
            // page rather than silently falling through to "All". The latter would be a
            // surprise: ?type=GARBAGE returning 200 unrelated rows is worse than 0.
            java.util.List<com.smmpanel.entity.TransactionType> parsed =
                    new java.util.ArrayList<>();
            for (String raw : type.split(",")) {
                String trimmed = raw.trim();
                if (trimmed.isEmpty()) continue;
                try {
                    parsed.add(
                            com.smmpanel.entity.TransactionType.valueOf(
                                    trimmed.toUpperCase(java.util.Locale.ROOT)));
                } catch (IllegalArgumentException ignored) {
                    // skip unknown — combined with the empty check below this is safe
                }
            }
            if (parsed.isEmpty()) {
                return ResponseEntity.ok(Page.empty(pageable));
            }
            types = parsed;
        }

        Page<TransactionHistoryResponse> transactions =
                balanceService.getTransactionHistory(user.getId(), types, pageable);
        return ResponseEntity.ok(transactions);
    }

    @GetMapping("/transactions/recent")
    @Operation(summary = "Get recent transactions", description = "Returns last 10 transactions")
    public ResponseEntity<List<TransactionHistoryResponse>> getRecentTransactions() {
        User user = getCurrentUser();
        List<TransactionHistoryResponse> transactions =
                balanceService.getRecentTransactions(user.getId(), 10);
        return ResponseEntity.ok(transactions);
    }

    @PostMapping("/check-funds")
    @Operation(
            summary = "Check if user has sufficient funds",
            description = "Checks if user has enough balance for a transaction")
    public ResponseEntity<Map<String, Object>> checkFunds(
            @RequestBody Map<String, BigDecimal> request) {
        User user = getCurrentUser();
        BigDecimal requiredAmount = request.get("amount");

        boolean hasSufficientFunds = user.getBalance().compareTo(requiredAmount) >= 0;

        Map<String, Object> response = new HashMap<>();
        response.put("hasSufficientFunds", hasSufficientFunds);
        response.put("currentBalance", user.getBalance());
        response.put("requiredAmount", requiredAmount);

        if (!hasSufficientFunds) {
            response.put("shortfall", requiredAmount.subtract(user.getBalance()));
        }

        return ResponseEntity.ok(response);
    }

    private User getCurrentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        String username = authentication.getName();

        return userRepository
                .findByUsername(username)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + username));
    }
}
