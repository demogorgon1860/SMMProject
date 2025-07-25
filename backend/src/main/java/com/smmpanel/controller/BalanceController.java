package com.smmpanel.controller;

import com.smmpanel.dto.balance.*;
import com.smmpanel.dto.response.ApiResponse;
import com.smmpanel.dto.response.PerfectPanelResponse;
import com.smmpanel.entity.BalanceTransaction;
import com.smmpanel.entity.User;
import com.smmpanel.security.CurrentUser;
import com.smmpanel.service.BalanceService;
import com.smmpanel.service.CryptomusService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;

import static com.smmpanel.util.Constants.API_BASE_PATH;

/**
 * Endpoints for managing user balances and transactions
 */
@RestController
@RequestMapping(API_BASE_PATH + "/balance")
@RequiredArgsConstructor
@Tag(name = "Balance Management", description = "Endpoints for managing user balances and transactions")
@SecurityRequirement(name = "bearerAuth")
public class BalanceController {

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final String DEFAULT_SORT_FIELD = "createdAt";

    private final BalanceService balanceService;
    private final CryptomusService cryptomusService;

    /**
     * Perfect Panel compatible balance endpoint
     * @deprecated Use {@link #getCurrentBalance()} instead
     */
    @Deprecated(since = "2.0", forRemoval = true)
    @GetMapping("/v2/balance")
    public ResponseEntity<PerfectPanelResponse> getBalance() {
        String username = SecurityContextHolder.getContext().getAuthentication().getName();
        String balance = balanceService.getUserBalanceByUsername(username);

        return ResponseEntity.ok(PerfectPanelResponse.builder()
                .balance(balance)
                .build());
    }

    /**
     * Get current user's balance
     * @param currentUser The authenticated user
     * @return Current balance information
     */
    @GetMapping("/me")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Get current balance", description = "Gets the current balance of the authenticated user")
    public ResponseEntity<ApiResponse<BalanceResponse>> getCurrentBalance(@CurrentUser User currentUser) {
        BigDecimal balance = balanceService.getUserBalance(currentUser.getId());

        BalanceResponse response = BalanceResponse.builder()
                .balance(balance)
                .currency(user.getPreferredCurrency())
                .build();

        return ResponseEntity.ok(ApiResponse.success(response));
    }

    /**
     * Check if user has sufficient balance
     * @param currentUser The authenticated user
     * @param amount The amount to check
     * @return Balance check result
     */
    @GetMapping("/check")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Check balance sufficiency",
            description = "Checks if the user has sufficient balance for the specified amount")
    public ResponseEntity<ApiResponse<BalanceCheckResponse>> checkBalance(
            @CurrentUser User currentUser,
            @RequestParam @Min(value = 0, message = "Amount must be positive") BigDecimal amount) {

        boolean hasSufficientBalance = balanceService.hasSufficientBalance(currentUser.getId(), amount);

        BalanceCheckResponse response = BalanceCheckResponse.builder()
                .hasSufficientBalance(hasSufficientBalance)
                .requestedAmount(amount)
                .build();

        return ResponseEntity.ok(ApiResponse.success(response));
    }

    /**
     * Get transaction history with pagination
     * @param currentUser The authenticated user
     * @param page Page number (0-based)
     * @param size Number of items per page
     * @return Paginated transaction history
     */
    @GetMapping("/transactions")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Get transaction history",
            description = "Gets a paginated list of the user's transaction history")
    public ResponseEntity<ApiResponse<Page<TransactionResponse>>> getTransactionHistory(
            @CurrentUser User currentUser,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "" + DEFAULT_PAGE_SIZE) int size) {

        Page<BalanceTransaction> transactions = balanceService.getTransactionHistory(
                currentUser.getId(),
                page,
                size
        );

        // Map to DTO
        Page<TransactionResponse> response = transactions.map(transaction ->
                TransactionResponse.fromEntity(transaction)
        );

        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @PostMapping("/deposits")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Create deposit",
            description = "Creates a new deposit request")
    public ResponseEntity<ApiResponse<CreateDepositResponse>> createDeposit(
            @CurrentUser User currentUser,
            @Valid @RequestBody CreateDepositRequest request) {

        CreateDepositResponse response = cryptomusService.createPayment(
                currentUser.getUsername(),
                request
        );

        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @GetMapping("/deposits/{orderId}/status")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Get deposit status",
            description = "Gets the status of a specific deposit")
    public ResponseEntity<ApiResponse<DepositStatusResponse>> getDepositStatus(
            @CurrentUser User currentUser,
            @PathVariable String orderId) {

        DepositStatusResponse response = cryptomusService.getPaymentStatus(
                currentUser.getUsername(),
                orderId
        );

        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @GetMapping("/deposits")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "List deposits",
            description = "Gets a list of the user's deposits")
    public ResponseEntity<ApiResponse<Page<DepositResponse>>> listDeposits(
            @CurrentUser User currentUser,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "" + DEFAULT_PAGE_SIZE) int size) {

        Page<DepositResponse> deposits = cryptomusService.getUserDeposits(
                currentUser.getUsername(),
                page,
                size
        );

        return ResponseEntity.ok(ApiResponse.success(deposits));
    }
}