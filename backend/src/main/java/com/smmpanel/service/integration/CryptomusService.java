package com.smmpanel.service.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smmpanel.client.CryptomusClient;
import com.smmpanel.dto.balance.CreateDepositRequest;
import com.smmpanel.dto.balance.CreateDepositResponse;
import com.smmpanel.dto.balance.DepositStatusResponse;
import com.smmpanel.dto.cryptomus.CreatePaymentRequest;
import com.smmpanel.dto.cryptomus.CreatePaymentResponse;
import com.smmpanel.dto.cryptomus.CreateWalletRequest;
import com.smmpanel.dto.cryptomus.CryptomusWebhook;
import com.smmpanel.entity.BalanceDeposit;
import com.smmpanel.entity.PaymentStatus;
import com.smmpanel.entity.User;
import com.smmpanel.exception.UserNotFoundException;
import com.smmpanel.repository.jpa.BalanceDepositRepository;
import com.smmpanel.repository.jpa.UserRepository;
import com.smmpanel.service.balance.BalanceService;
import com.smmpanel.service.settings.AppSettingsService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.Base64;
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
    private final ObjectMapper objectMapper;
    private final AppSettingsService appSettingsService;

    @Value("${app.cryptomus.min-deposit:5.00}")
    private BigDecimal minDepositAmount;

    @Value("${app.cryptomus.api.payment-key}")
    private String cryptomusApiKey;

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
                        .urlCallback("https://smmworld.vip/api/v1/payments/cryptomus/callback")
                        .urlSuccess("https://smmworld.vip/dashboard/balance?deposit=success")
                        .urlReturn("https://smmworld.vip/dashboard/balance?deposit=cancel")
                        .lifetime(1440) // 24 hours in minutes
                        .isPaymentMultiple(false) // Each deposit gets unique payment address
                        .build();

        try {
            CreatePaymentResponse cryptomusResponse =
                    cryptomusClient.createPayment(cryptomusRequest);

            // Save deposit record
            BalanceDeposit deposit = new BalanceDeposit();
            deposit.setUser(user);
            deposit.setOrderId(orderId);
            deposit.setAmountUsdt(request.getAmount());
            deposit.setCurrency("USD");
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
                    .currency("USD")
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

        // Get real-time status from Cryptomus API
        try {
            var paymentInfo = cryptomusClient.getPaymentInfo(null, orderId);
            if (paymentInfo != null && paymentInfo.getPaymentStatus() != null) {
                // Update local status based on Cryptomus response
                updateDepositStatus(deposit, paymentInfo.getPaymentStatus());
            }
        } catch (Exception e) {
            log.warn("Failed to get real-time status from Cryptomus: {}", e.getMessage());
        }

        return DepositStatusResponse.builder()
                .orderId(deposit.getOrderId())
                .status(deposit.getStatus().name())
                .amount(deposit.getAmountUsdt())
                .currency("USD")
                .cryptoAmount(deposit.getCryptoAmount())
                .createdAt(deposit.getCreatedAt())
                .confirmedAt(deposit.getConfirmedAt())
                .expiresAt(deposit.getExpiresAt())
                .build();
    }

    private void updateDepositStatus(BalanceDeposit deposit, String status) {
        PaymentStatus oldStatus = deposit.getStatus();
        PaymentStatus newStatus = mapCryptomusStatus(status);
        if (newStatus == null || newStatus == oldStatus) return;

        if (newStatus == PaymentStatus.COMPLETED) {
            // Atomic CAS: only one of N concurrent paths (poll, webhook, retry) flips
            // PENDING/PROCESSING -> COMPLETED. The thread that wins is the unique caller
            // responsible for crediting the wallet.
            int updated =
                    depositRepository.markCompletedIfNotAlready(
                            deposit.getId(), LocalDateTime.now());
            if (updated == 0) {
                log.info(
                        "Deposit {} already COMPLETED — poll path skipping duplicate credit",
                        deposit.getOrderId());
                return;
            }
            // Refresh the in-memory copy so callers see the new state.
            deposit.setStatus(PaymentStatus.COMPLETED);
            deposit.setConfirmedAt(LocalDateTime.now());

            BigDecimal grossAmount = deposit.getAmountUsdt();
            BigDecimal creditedAmount = applyCryptomusFeePassthrough(grossAmount);
            balanceService.addBalance(
                    deposit.getUser(),
                    creditedAmount,
                    deposit,
                    creditedAmount.compareTo(grossAmount) < 0
                            ? "USDT deposit (after platform fee)"
                            : "USDT deposit");
            return;
        }

        // GUARD: never downgrade a credited/reversed deposit. If a stale Cryptomus poll comes
        // back with `cancel` or `fail` after we already credited the wallet via `paid`, the
        // pre-fix behavior would silently flip COMPLETED → FAILED — wallet stays credited but
        // the audit row reads FAILED, which is a chargeback-dispute landmine. PENDING → FAILED
        // and PROCESSING → FAILED are still allowed (legitimate negative outcomes).
        if (oldStatus.isCreditedOrReversed()) {
            log.warn(
                    "Refusing to downgrade deposit {} from {} to {} (Cryptomus poll returned"
                            + " stale state)",
                    deposit.getOrderId(),
                    oldStatus,
                    newStatus);
            return;
        }

        // Non-completion transitions don't move money, so a plain save is fine. We only run
        // here for terminal/failure states (FAILED) or PROCESSING — ordering between concurrent
        // updates is admin-cosmetic, not financial.
        deposit.setStatus(newStatus);
        depositRepository.save(deposit);
    }

    private PaymentStatus mapCryptomusStatus(String status) {
        switch (status) {
            case "paid":
            case "paid_over":
                return PaymentStatus.COMPLETED;
            case "process":
            case "check":
                return PaymentStatus.PROCESSING;
            case "fail":
            case "cancel":
            case "system_fail":
                return PaymentStatus.FAILED;
            case "wait":
            case "payment_process":
                return PaymentStatus.PENDING;
            default:
                log.warn("Unknown Cryptomus payment status: {}", status);
                return null;
        }
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

    /** Get available payment services and cryptocurrencies */
    public Map<String, Object> getAvailablePaymentServices() {
        try {
            var services = cryptomusClient.getPaymentServices();
            return Map.of("success", true, "services", services.getServices());
        } catch (Exception e) {
            log.error("Failed to get payment services: {}", e.getMessage());
            return Map.of("success", false, "error", "Failed to fetch payment services");
        }
    }

    /** Get payment history for admin */
    public Map<String, Object> getPaymentHistory(String dateFrom, String dateTo, Integer page) {
        try {
            var paymentList = cryptomusClient.getPaymentList(dateFrom, dateTo, page, 50);
            return Map.of(
                    "success", true,
                    "payments", paymentList.getItems(),
                    "pagination",
                            Map.of(
                                    "currentPage", paymentList.getCurrentPage(),
                                    "perPage", paymentList.getPerPage(),
                                    "total", paymentList.getTotal()));
        } catch (Exception e) {
            log.error("Failed to get payment history: {}", e.getMessage());
            return Map.of("success", false, "error", "Failed to fetch payment history");
        }
    }

    /** Get transaction list for admin */
    public Map<String, Object> getTransactionList(String dateFrom, String dateTo, String type) {
        try {
            var transactions = cryptomusClient.getTransactionList(dateFrom, dateTo, type);
            return Map.of("success", true, "transactions", transactions.getItems());
        } catch (Exception e) {
            log.error("Failed to get transactions: {}", e.getMessage());
            return Map.of("success", false, "error", "Failed to fetch transactions");
        }
    }

    /** Create a static wallet for a user */
    @Transactional
    public Map<String, Object> createStaticWallet(
            String username, String currency, String network) {
        try {
            User user =
                    userRepository
                            .findByUsername(username)
                            .orElseThrow(() -> new UserNotFoundException("User not found"));

            String orderId = "WALLET_" + user.getId() + "_" + System.currentTimeMillis();

            var walletRequest =
                    CreateWalletRequest.builder()
                            .currency(currency)
                            .network(network)
                            .orderId(orderId)
                            .urlCallback("https://smmworld.vip/api/v2/webhooks/cryptomus/wallet")
                            .build();

            var walletResponse = cryptomusClient.createWallet(walletRequest);

            // Save wallet info to database if needed
            // You might want to create a WalletEntity to store static wallets

            return Map.of(
                    "success",
                    true,
                    "wallet",
                    Map.of(
                            "address", walletResponse.getAddress(),
                            "network", walletResponse.getNetwork(),
                            "currency", walletResponse.getCurrency(),
                            "url", walletResponse.getUrl()));
        } catch (Exception e) {
            log.error("Failed to create static wallet: {}", e.getMessage());
            return Map.of("success", false, "error", "Failed to create wallet: " + e.getMessage());
        }
    }

    /**
     * Validates webhook signature using MD5 as per Cryptomus documentation
     *
     * <p>Cryptomus signature algorithm: 1. Extract sign field from webhook JSON 2. Remove sign
     * field from JSON 3. Base64 encode the JSON without sign 4. Concatenate: base64(json) + apiKey
     * 5. MD5 hash the combined string (return as hex) 6. Compare with extracted sign
     *
     * @param webhook Webhook object containing the sign field
     * @return true if signature is valid
     */
    public boolean validateWebhookSignature(String rawJsonBody, String providedSignature) {
        if (rawJsonBody == null || providedSignature == null) {
            log.warn("Missing raw JSON body or signature for validation");
            return false;
        }

        try {
            // IMPORTANT: Extract sign value FIRST before removing it from JSON
            // Per expert advice: JSON must be compact (no spaces) with escaped slashes
            com.fasterxml.jackson.databind.ObjectMapper mapper =
                    new com.fasterxml.jackson.databind.ObjectMapper();

            // Configure mapper for compact JSON output matching Cryptomus format
            mapper.configure(
                    com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS,
                    true);
            // Disable indentation - we need compact JSON (no spaces)
            mapper.configure(
                    com.fasterxml.jackson.databind.SerializationFeature.INDENT_OUTPUT, false);

            com.fasterxml.jackson.databind.JsonNode rootNode = mapper.readTree(rawJsonBody);

            // Step 1: Extract the sign field value to a separate variable BEFORE removing it
            String signFromJson = null;
            if (rootNode.has("sign")) {
                signFromJson = rootNode.get("sign").asText();
                log.debug("Extracted sign from webhook JSON: {}", signFromJson);
            }

            // Verify the provided signature matches what we extracted
            if (signFromJson == null) {
                log.warn("No sign field found in webhook JSON");
                return false;
            }

            if (!signFromJson.equals(providedSignature)) {
                log.warn(
                        "Sign mismatch: JSON contains '{}', but parameter provided '{}'",
                        signFromJson,
                        providedSignature);
            }

            // Step 2: Remove the "sign" field from the JSON
            if (rootNode instanceof com.fasterxml.jackson.databind.node.ObjectNode) {
                ((com.fasterxml.jackson.databind.node.ObjectNode) rootNode).remove("sign");
            }

            // Step 3: Serialize back to COMPACT string (no spaces, escaped slashes)
            String jsonWithoutSign = mapper.writeValueAsString(rootNode);

            // Step 4: Ensure forward slashes are escaped (per expert advice)
            // This is critical for matching Cryptomus's signature calculation
            jsonWithoutSign = jsonWithoutSign.replace("/", "\\/");

            log.debug("JSON without sign field (compact, escaped): {}", jsonWithoutSign);

            // Cryptomus signature algorithm (from docs + expert advice):
            // $sign = md5(base64_encode($dataJsonString) . $API_KEY);
            // where $dataJsonString is compact JSON with escaped slashes
            // 5. Base64 encode the compact JSON (without sign field)
            String base64Json =
                    Base64.getEncoder().encodeToString(jsonWithoutSign.getBytes("UTF-8"));

            log.debug("Base64 encoded: {}", base64Json);

            // 6. Concatenate: base64(json) + apiKey
            String combined = base64Json + cryptomusApiKey;

            // 7. MD5 hash and convert to hex string
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] hashBytes = md.digest(combined.getBytes("UTF-8"));

            // Convert MD5 bytes to hex string (NOT base64!)
            StringBuilder hexString = new StringBuilder();
            for (byte b : hashBytes) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            String calculatedSignature = hexString.toString();

            // Step 8: Compare calculated signature with the EXTRACTED sign value
            // Use the sign value we extracted from JSON, not the parameter
            boolean isValid =
                    MessageDigest.isEqual(calculatedSignature.getBytes(), signFromJson.getBytes());

            if (!isValid) {
                log.warn(
                        "Invalid webhook signature. Calculated: {}, Expected: {}",
                        calculatedSignature,
                        signFromJson);
                log.debug(
                        "API Key (last 4 chars): ...{}",
                        cryptomusApiKey.substring(Math.max(0, cryptomusApiKey.length() - 4)));
                log.debug("Raw JSON body: {}", rawJsonBody);
                log.debug("JSON without sign (compact, escaped): {}", jsonWithoutSign);
                log.debug("Base64 of JSON: {}", base64Json);
            } else {
                log.info("Webhook signature validated successfully for sign: {}", signFromJson);
            }

            return isValid;
        } catch (Exception e) {
            log.error("Error validating webhook signature: {}", e.getMessage(), e);
            return false;
        }
    }

    @Deprecated
    public boolean validateWebhookSignature(CryptomusWebhook webhook) {
        log.warn("Using deprecated validateWebhookSignature method - should use raw JSON version");
        return false;
    }

    /**
     * Process a Cryptomus webhook delivery. Cryptomus retries on any non-2xx response (and
     * sometimes on 2xx delays), so this method MUST be idempotent: a second delivery for the same
     * order_id+status MUST NOT credit the wallet twice.
     *
     * <p>Idempotency model:
     *
     * <ol>
     *   <li>{@code order_id} is unique on {@code balance_deposits} → at most one row per Cryptomus
     *       order.
     *   <li>The PENDING→COMPLETED transition is wrapped in an atomic CAS UPDATE ({@link
     *       BalanceDepositRepository#markCompletedIfNotAlready}). Only the unique winner proceeds
     *       to credit the wallet. Replays / concurrent deliveries see {@code updated=0} and exit
     *       cleanly. This replaces the previous in-memory {@code ConcurrentHashMap} hack which was
     *       lost on restart and broken across multiple JVMs.
     *   <li>The CAS and {@link BalanceService#addBalance} run in the same {@code @Transactional}
     *       boundary, so a credit failure rolls back the status flip and a future retry can
     *       legitimately succeed.
     * </ol>
     */
    @Transactional
    public void processWebhook(CryptomusWebhook webhook) {
        try {
            BalanceDeposit deposit =
                    depositRepository
                            .findByOrderId(webhook.getOrderId())
                            .orElseThrow(
                                    () ->
                                            new IllegalArgumentException(
                                                    "Deposit not found: " + webhook.getOrderId()));

            String status = webhook.getStatus();
            switch (status) {
                case "paid":
                case "paid_over":
                    int updated =
                            depositRepository.markCompletedIfNotAlready(
                                    deposit.getId(), LocalDateTime.now());
                    if (updated == 0) {
                        log.info(
                                "Deposit {} already COMPLETED — webhook replay skipped (no"
                                        + " duplicate credit)",
                                webhook.getOrderId());
                        return; // skip the persist of webhook_data — keep the original payload
                    }
                    deposit.setStatus(PaymentStatus.COMPLETED);
                    deposit.setConfirmedAt(LocalDateTime.now());

                    BigDecimal grossAmount = deposit.getAmountUsdt();
                    BigDecimal creditedAmount = applyCryptomusFeePassthrough(grossAmount);

                    balanceService.addBalance(
                            deposit.getUser(),
                            creditedAmount,
                            deposit,
                            creditedAmount.compareTo(grossAmount) < 0
                                    ? "USD deposit (after platform fee)"
                                    : "USD deposit");

                    log.info(
                            "Processed deposit {} for user {} gross={} credited={} USD",
                            webhook.getOrderId(),
                            deposit.getUser().getUsername(),
                            grossAmount,
                            creditedAmount);
                    break;

                case "fail":
                case "cancel":
                    // GUARD: never downgrade a credited/reversed deposit. A late fail/cancel
                    // webhook arriving after we already paid the user (Cryptomus retry race)
                    // must NOT flip COMPLETED → FAILED — wallet would stay credited but the
                    // audit trail would read FAILED, which is a chargeback-dispute landmine.
                    if (deposit.getStatus().isCreditedOrReversed()) {
                        log.warn(
                                "Refusing to downgrade deposit {} from {} to FAILED (late"
                                        + " {} webhook from Cryptomus)",
                                webhook.getOrderId(),
                                deposit.getStatus(),
                                status);
                    } else if (deposit.getStatus() != PaymentStatus.FAILED) {
                        deposit.setStatus(PaymentStatus.FAILED);
                        log.info(
                                "Deposit {} failed for user {}",
                                webhook.getOrderId(),
                                deposit.getUser().getUsername());
                    }
                    break;

                case "process":
                    // PENDING is the only legitimate predecessor — never downgrade
                    // COMPLETED/FAILED/CANCELLED to PROCESSING.
                    if (deposit.getStatus() == PaymentStatus.PENDING) {
                        deposit.setStatus(PaymentStatus.PROCESSING);
                    }
                    break;

                default:
                    log.warn(
                            "Unknown webhook status: {} for deposit {}",
                            status,
                            webhook.getOrderId());
            }

            // Serialize webhook to JSON for JSONB column
            try {
                String webhookJson = objectMapper.writeValueAsString(webhook);
                deposit.setWebhookData(webhookJson);
            } catch (Exception jsonException) {
                log.error(
                        "Failed to serialize webhook to JSON: {}",
                        jsonException.getMessage(),
                        jsonException);
                // Store null instead of invalid data
                deposit.setWebhookData(null);
            }

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

    /**
     * Apply the configured Cryptomus fee passthrough percentage to a gross deposit amount. A 1%
     * setting on a $100 deposit credits $99 to the user. Defaults to 0% (no fee), which credits the
     * full amount unchanged. Negative results are clamped to zero (defensive — settings validation
     * already rejects negatives, but this is the money path).
     */
    private BigDecimal applyCryptomusFeePassthrough(BigDecimal grossAmount) {
        if (grossAmount == null) return BigDecimal.ZERO;
        BigDecimal feePct =
                appSettingsService.getDecimal(
                        AppSettingsService.KEY_CRYPTOMUS_PASSTHROUGH_PCT, BigDecimal.ZERO);
        if (feePct.signum() <= 0) return grossAmount;
        BigDecimal multiplier =
                BigDecimal.ONE.subtract(
                        feePct.divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP));
        if (multiplier.signum() <= 0) return BigDecimal.ZERO;
        return grossAmount.multiply(multiplier).setScale(2, RoundingMode.HALF_UP);
    }
}
