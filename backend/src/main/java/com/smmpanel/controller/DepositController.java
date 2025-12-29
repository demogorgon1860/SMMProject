package com.smmpanel.controller;

import com.smmpanel.dto.balance.CreateDepositRequest;
import com.smmpanel.dto.balance.CreateDepositResponse;
import com.smmpanel.dto.balance.DepositResponse;
import com.smmpanel.entity.BalanceDeposit;
import com.smmpanel.entity.User;
import com.smmpanel.exception.ResourceNotFoundException;
import com.smmpanel.repository.jpa.BalanceDepositRepository;
import com.smmpanel.repository.jpa.UserRepository;
import com.smmpanel.service.integration.CryptomusService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/api/v1/deposits")
@RequiredArgsConstructor
@Tag(name = "Deposits", description = "Endpoints for managing balance deposits")
@SecurityRequirement(name = "bearerAuth")
public class DepositController {

    private final CryptomusService cryptomusService;
    private final BalanceDepositRepository depositRepository;
    private final UserRepository userRepository;

    @PostMapping("/create")
    @Operation(
            summary = "Create a new deposit",
            description = "Creates a new Cryptomus payment for balance top-up")
    public ResponseEntity<?> createDeposit(@RequestBody CreateDepositRequest request) {
        try {
            User user = getCurrentUser();
            CreateDepositResponse response =
                    cryptomusService.createPayment(user.getUsername(), request);
            log.info(
                    "Created deposit for user: {} amount: {}",
                    user.getUsername(),
                    request.getAmount());
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            log.warn("Invalid deposit request: {}", e.getMessage());
            return ResponseEntity.badRequest()
                    .body(java.util.Map.of("error", e.getMessage(), "code", "INVALID_REQUEST"));
        } catch (Exception e) {
            log.error("Failed to create deposit: {}", e.getMessage(), e);
            return ResponseEntity.status(500)
                    .body(
                            java.util.Map.of(
                                    "error",
                                    "Failed to create payment. Please try again later.",
                                    "code",
                                    "PAYMENT_CREATION_FAILED",
                                    "details",
                                    e.getMessage()));
        }
    }

    @GetMapping
    @Operation(
            summary = "Get user deposits",
            description = "Returns paginated list of user's deposits")
    public ResponseEntity<Page<DepositResponse>> getUserDeposits(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "100") int size) {
        User user = getCurrentUser();

        PageRequest pageRequest = PageRequest.of(page, size);
        Page<BalanceDeposit> deposits =
                depositRepository.findByUserOrderByCreatedAtDesc(user, pageRequest);

        Page<DepositResponse> response = deposits.map(this::mapToDepositResponse);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get deposit by ID", description = "Returns details of a specific deposit")
    public ResponseEntity<DepositResponse> getDepositById(@PathVariable Long id) {
        User user = getCurrentUser();

        BalanceDeposit deposit =
                depositRepository
                        .findById(id)
                        .orElseThrow(() -> new ResourceNotFoundException("Deposit not found"));

        // Ensure user owns this deposit
        if (!deposit.getUser().getId().equals(user.getId())) {
            throw new ResourceNotFoundException("Deposit not found");
        }

        return ResponseEntity.ok(mapToDepositResponse(deposit));
    }

    @GetMapping("/recent")
    @Operation(
            summary = "Get recent deposits",
            description = "Returns last 10 deposits for the user")
    public ResponseEntity<List<DepositResponse>> getRecentDeposits() {
        User user = getCurrentUser();

        PageRequest pageRequest = PageRequest.of(0, 10);
        Page<BalanceDeposit> deposits =
                depositRepository.findByUserOrderByCreatedAtDesc(user, pageRequest);

        List<DepositResponse> response =
                deposits.getContent().stream().map(this::mapToDepositResponse).toList();

        return ResponseEntity.ok(response);
    }

    private DepositResponse mapToDepositResponse(BalanceDeposit deposit) {
        return DepositResponse.builder()
                .id(deposit.getId())
                .orderId(deposit.getOrderId())
                .amount(deposit.getAmountUsdt())
                .currency("USD")
                .status(deposit.getStatus().name())
                .paymentMethod("Cryptomus")
                .createdAt(deposit.getCreatedAt())
                .paymentUrl(deposit.getPaymentUrl())
                .confirmedAt(deposit.getConfirmedAt())
                .build();
    }

    private User getCurrentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        String username = authentication.getName();

        return userRepository
                .findByUsername(username)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + username));
    }
}
