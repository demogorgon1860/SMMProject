package com.smmpanel.service;

import com.smmpanel.client.CryptomusClient;
import com.smmpanel.dto.balance.CreateDepositRequest;
import com.smmpanel.dto.balance.CreateDepositResponse;
import com.smmpanel.dto.balance.DepositStatusResponse;
import com.smmpanel.dto.cryptomus.CreatePaymentRequest;
import com.smmpanel.dto.cryptomus.CreatePaymentResponse;
import com.smmpanel.dto.cryptomus.CryptomusWebhook;
import com.smmpanel.entity.BalanceDeposit;
import com.smmpanel.entity.PaymentStatus;
import com.smmpanel.entity.User;
import com.smmpanel.exception.UserNotFoundException;
import com.smmpanel.repository.jpa.BalanceDepositRepository;
import com.smmpanel.repository.jpa.UserRepository;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class CryptomusService {

    private final CryptomusClient cryptomusClient;
    private final UserRepository userRepository;
    private final BalanceDepositRepository depositRepository;
    private final BalanceService balanceService;

    @Value("${app.cryptomus.min-deposit:5.00}")
    private BigDecimal minDepositAmount;

    @Transactional
    public CreateDepositResponse createPayment(String username, CreateDepositRequest request) {
        User user =
                userRepository
                        .findByUsername(username)
                        .orElseThrow(() -> new UserNotFoundException("User not found"));

        if (request.getAmount().compareTo(minDepositAmount) < 0) {
            throw new IllegalArgumentException("Minimum deposit amount is $" + minDepositAmount);
        }

        // Generate unique order ID
        String orderId = "SMM_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);

        // Create Cryptomus payment request
        CreatePaymentRequest cryptomusRequest =
                CreatePaymentRequest.builder()
                        .amount(request.getAmount())
                        .currency("USD")
                        .orderId(orderId)
                        .callbackUrl("https://yourdomain.com/api/v2/webhooks/cryptomus")
                        .successUrl("https://yourdomain.com/deposits/success")
                        .failUrl("https://yourdomain.com/deposits/fail")
                        .build();

        try {
            CreatePaymentResponse cryptomusResponse =
                    cryptomusClient.createPayment(cryptomusRequest);

            // Save deposit record
            BalanceDeposit deposit = new BalanceDeposit();
            deposit.setUser(user);
            deposit.setOrderId(orderId);
            deposit.setAmountUsd(request.getAmount());
            deposit.setCurrency(request.getCurrency());
            deposit.setCryptoAmount(cryptomusResponse.getAmount());
            deposit.setCryptomusPaymentId(cryptomusResponse.getUuid());
            deposit.setPaymentUrl(cryptomusResponse.getUrl());
            deposit.setStatus(PaymentStatus.PENDING);
            deposit.setExpiresAt(LocalDateTime.now().plusHours(24));

            depositRepository.save(deposit);

            log.info(
                    "Created deposit {} for user {} amount ${}",
                    orderId,
                    username,
                    request.getAmount());

            return CreateDepositResponse.builder()
                    .orderId(orderId)
                    .paymentUrl(cryptomusResponse.getUrl())
                    .amount(request.getAmount())
                    .currency(request.getCurrency())
                    .cryptoAmount(cryptomusResponse.getAmount().toString())
                    .expiresAt(deposit.getExpiresAt())
                    .build();

        } catch (Exception e) {
            log.error(
                    "Failed to create Cryptomus payment for user {}: {}",
                    username,
                    e.getMessage(),
                    e);
            throw new RuntimeException("Failed to create payment. Please try again.");
        }
    }

    public DepositStatusResponse getPaymentStatus(String username, String orderId) {
        User user =
                userRepository
                        .findByUsername(username)
                        .orElseThrow(() -> new UserNotFoundException("User not found"));

        BalanceDeposit deposit =
                depositRepository
                        .findByOrderIdAndUser(orderId, user)
                        .orElseThrow(() -> new IllegalArgumentException("Deposit not found"));

        return DepositStatusResponse.builder()
                .orderId(deposit.getOrderId())
                .status(deposit.getStatus().name())
                .amount(deposit.getAmountUsd())
                .currency(deposit.getCurrency())
                .cryptoAmount(deposit.getCryptoAmount())
                .createdAt(deposit.getCreatedAt())
                .confirmedAt(deposit.getConfirmedAt())
                .expiresAt(deposit.getExpiresAt())
                .build();
    }

    public Map<String, Object> getUserDeposits(String username, int page, int size) {
        User user =
                userRepository
                        .findByUsername(username)
                        .orElseThrow(() -> new UserNotFoundException("User not found"));

        Page<BalanceDeposit> deposits =
                depositRepository.findByUserOrderByCreatedAtDesc(user, PageRequest.of(page, size));

        Map<String, Object> response = new HashMap<>();
        response.put("deposits", deposits.getContent());
        response.put("totalPages", deposits.getTotalPages());
        response.put("totalElements", deposits.getTotalElements());
        response.put("currentPage", page);
        response.put("pageSize", size);

        return response;
    }

    @Transactional
    public void processWebhook(CryptomusWebhook webhook) {
        try {
            // Verify webhook signature here if needed

            BalanceDeposit deposit =
                    depositRepository
                            .findByOrderId(webhook.getOrderId())
                            .orElseThrow(
                                    () ->
                                            new IllegalArgumentException(
                                                    "Deposit not found: " + webhook.getOrderId()));

            if (deposit.getStatus() == PaymentStatus.COMPLETED) {
                log.info("Deposit {} already processed", webhook.getOrderId());
                return;
            }

            switch (webhook.getStatus()) {
                case "paid":
                case "paid_over":
                    deposit.setStatus(PaymentStatus.COMPLETED);
                    deposit.setConfirmedAt(LocalDateTime.now());

                    // Add balance to user
                    balanceService.addBalance(
                            deposit.getUser(),
                            deposit.getAmountUsd(),
                            deposit,
                            "Cryptocurrency deposit");

                    log.info(
                            "Processed deposit {} for user {} amount ${}",
                            webhook.getOrderId(),
                            deposit.getUser().getUsername(),
                            deposit.getAmountUsd());
                    break;

                case "fail":
                case "cancel":
                    deposit.setStatus(PaymentStatus.FAILED);
                    log.info(
                            "Deposit {} failed for user {}",
                            webhook.getOrderId(),
                            deposit.getUser().getUsername());
                    break;

                case "process":
                    deposit.setStatus(PaymentStatus.PROCESSING);
                    break;

                default:
                    log.warn(
                            "Unknown webhook status: {} for deposit {}",
                            webhook.getStatus(),
                            webhook.getOrderId());
            }

            deposit.setWebhookData(webhook.toString());
            depositRepository.save(deposit);

        } catch (Exception e) {
            log.error(
                    "Failed to process webhook for order {}: {}",
                    webhook.getOrderId(),
                    e.getMessage(),
                    e);
            throw e;
        }
    }
}
