package com.smmpanel.controller;

import com.smmpanel.dto.balance.CreateDepositRequest;
import com.smmpanel.dto.balance.CreateDepositResponse;
import com.smmpanel.dto.balance.DepositStatusResponse;
import com.smmpanel.dto.response.PerfectPanelResponse;
import com.smmpanel.service.BalanceService;
import com.smmpanel.service.CryptomusService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;
import java.util.Map;

@RestController
@RequestMapping("/api/v2")
@RequiredArgsConstructor
public class BalanceController {

    private final BalanceService balanceService;
    private final CryptomusService cryptomusService;

    // Perfect Panel compatible balance endpoint
    @GetMapping("/balance")
    public ResponseEntity<PerfectPanelResponse> getBalance() {
        String username = SecurityContextHolder.getContext().getAuthentication().getName();
        String balance = balanceService.getUserBalance(username);
        
        return ResponseEntity.ok(PerfectPanelResponse.builder()
                .balance(balance)
                .build());
    }

    @PostMapping("/deposits")
    public ResponseEntity<CreateDepositResponse> createDeposit(@Valid @RequestBody CreateDepositRequest request) {
        String username = SecurityContextHolder.getContext().getAuthentication().getName();
        CreateDepositResponse response = cryptomusService.createPayment(username, request);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/deposits/{orderId}/status")
    public ResponseEntity<DepositStatusResponse> getDepositStatus(@PathVariable String orderId) {
        String username = SecurityContextHolder.getContext().getAuthentication().getName();
        DepositStatusResponse response = cryptomusService.getPaymentStatus(username, orderId);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/deposits")
    public ResponseEntity<Map<String, Object>> getUserDeposits(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        String username = SecurityContextHolder.getContext().getAuthentication().getName();
        Map<String, Object> deposits = cryptomusService.getUserDeposits(username, page, size);
        return ResponseEntity.ok(deposits);
    }
}