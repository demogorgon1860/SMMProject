package com.smmpanel.service.profile;

import com.smmpanel.entity.BalanceDeposit;
import com.smmpanel.entity.BalanceTransaction;
import com.smmpanel.entity.Order;
import com.smmpanel.entity.RefillRequest;
import com.smmpanel.entity.SupportTicket;
import com.smmpanel.entity.User;
import com.smmpanel.exception.UserNotFoundException;
import com.smmpanel.repository.jpa.BalanceDepositRepository;
import com.smmpanel.repository.jpa.BalanceTransactionRepository;
import com.smmpanel.repository.jpa.OrderRepository;
import com.smmpanel.repository.jpa.RefillRequestRepository;
import com.smmpanel.repository.jpa.SupportTicketRepository;
import com.smmpanel.repository.jpa.UserRepository;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Builds the GDPR data-export bundle for {@code POST /v1/me/export}. Synchronous, in-memory, single
 * JSON document — see Task 03 spec: the async-S3-and-emailed-link plan was descoped until export
 * volume warrants it.
 *
 * <p>Every collection here is filtered to exactly the calling user. We never touch {@code
 * AdminService} helpers (they include cross-user data) — every repository call is parameterized
 * with {@code userId}/{@code username} explicitly.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AccountExportService {

    private final UserRepository userRepository;
    private final OrderRepository orderRepository;
    private final BalanceTransactionRepository balanceTransactionRepository;
    private final BalanceDepositRepository balanceDepositRepository;
    private final RefillRequestRepository refillRequestRepository;
    private final SupportTicketRepository supportTicketRepository;

    @Transactional(readOnly = true)
    public Map<String, Object> buildExport() {
        User user = currentUser();
        log.info("User {} requested account data export (audit: GDPR Art. 15)", user.getUsername());

        Map<String, Object> bundle = new LinkedHashMap<>();
        bundle.put("schemaVersion", 1);
        bundle.put("generatedAt", LocalDateTime.now().toString());
        bundle.put("profile", profileSection(user));
        bundle.put("orders", ordersSection(user));
        bundle.put("transactions", transactionsSection(user));
        bundle.put("deposits", depositsSection(user));
        bundle.put("refillRequests", refillRequestsSection(user));
        bundle.put("supportTickets", ticketsSection(user));
        return bundle;
    }

    // ---------------------------------------------------------------------
    // Per-section serializers
    // ---------------------------------------------------------------------

    private Map<String, Object> profileSection(User user) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("id", user.getId());
        p.put("username", user.getUsername());
        p.put("email", user.getEmail());
        p.put("role", user.getRole() == null ? null : user.getRole().name());
        p.put("balance", user.getBalance());
        p.put("totalSpent", user.getTotalSpent());
        p.put("timezone", user.getTimezone());
        p.put("emailVerified", user.isEmailVerified());
        p.put("emailVerifiedAt", user.getEmailVerifiedAt());
        p.put("twoFactorEnabled", user.isTwoFactorEnabled());
        p.put("createdAt", user.getCreatedAt());
        p.put("updatedAt", user.getUpdatedAt());
        p.put("lastLoginAt", user.getLastLoginAt());
        p.put("lastApiAccessAt", user.getLastApiAccessAt());
        p.put("apiKeyConfigured", user.getApiKeyHash() != null);
        p.put("apiKeyLastRotated", user.getApiKeyLastRotated());
        p.put("apiKeyPausedAt", user.getApiKeyPausedAt());
        return p;
    }

    private List<Map<String, Object>> ordersSection(User user) {
        // Reusing the eager-fetch query that Profile pages already use means a single JPA call
        // for the whole order list — no N+1 hitting the services table when we serialize names.
        List<Order> orders = orderRepository.findOrdersWithDetailsByUserId(user.getId());
        return orders.stream().map(AccountExportService::toOrderMap).toList();
    }

    private static Map<String, Object> toOrderMap(Order o) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", o.getId());
        m.put("userOrderNumber", o.getUserOrderNumber());
        m.put("serviceId", o.getService() == null ? null : o.getService().getId());
        m.put("serviceName", o.getService() == null ? null : o.getService().getName());
        m.put("link", o.getLink());
        m.put("quantity", o.getQuantity());
        m.put("charge", o.getCharge());
        m.put("startCount", o.getStartCount());
        m.put("remains", o.getRemains());
        m.put("status", o.getStatus() == null ? null : o.getStatus().name());
        m.put("trafficStatus", o.getTrafficStatus());
        m.put("customComments", o.getCustomComments());
        m.put("isRefill", Boolean.TRUE.equals(o.getIsRefill()));
        m.put("refillParentId", o.getRefillParentId());
        m.put("createdAt", o.getCreatedAt());
        m.put("updatedAt", o.getUpdatedAt());
        return m;
    }

    private List<Map<String, Object>> transactionsSection(User user) {
        List<BalanceTransaction> txs =
                balanceTransactionRepository.findByUser_IdOrderByCreatedAtAsc(user.getId());
        return txs.stream().map(AccountExportService::toTxMap).toList();
    }

    private static Map<String, Object> toTxMap(BalanceTransaction t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", t.getId());
        m.put("transactionId", t.getTransactionId());
        m.put("type", t.getTransactionType() == null ? null : t.getTransactionType().name());
        m.put("amount", t.getAmount());
        m.put("balanceBefore", t.getBalanceBefore());
        m.put("balanceAfter", t.getBalanceAfter());
        m.put("description", t.getDescription());
        m.put("orderId", t.getOrder() == null ? null : t.getOrder().getId());
        m.put("createdAt", t.getCreatedAt());
        return m;
    }

    private List<Map<String, Object>> depositsSection(User user) {
        List<BalanceDeposit> deposits =
                balanceDepositRepository.findByUser_IdOrderByCreatedAtDesc(user.getId());
        return deposits.stream().map(AccountExportService::toDepositMap).toList();
    }

    private static Map<String, Object> toDepositMap(BalanceDeposit d) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", d.getId());
        m.put("orderId", d.getOrderId());
        m.put("amountUsd", d.getAmountUsdt());
        m.put("currency", d.getCurrency());
        m.put("cryptoAmount", d.getCryptoAmount());
        m.put("status", d.getStatus() == null ? null : d.getStatus().name());
        m.put("createdAt", d.getCreatedAt());
        m.put("confirmedAt", d.getConfirmedAt());
        m.put("confirmedAmount", d.getConfirmedAmount());
        m.put("expiresAt", d.getExpiresAt());
        return m;
    }

    private List<Map<String, Object>> refillRequestsSection(User user) {
        List<RefillRequest> requests =
                refillRequestRepository.findByUserIdOrderByCreatedAtDesc(user.getId());
        return requests.stream()
                .map(
                        r -> {
                            Map<String, Object> m = new LinkedHashMap<>();
                            m.put("id", r.getId());
                            m.put("orderId", r.getOrderId());
                            m.put("status", r.getStatus() == null ? null : r.getStatus().name());
                            m.put("userNote", r.getUserNote());
                            m.put("rejectionReason", r.getRejectionReason());
                            m.put("decidedAt", r.getDecidedAt());
                            m.put("refillOrderId", r.getRefillOrderId());
                            m.put("createdAt", r.getCreatedAt());
                            return m;
                        })
                .toList();
    }

    private List<Map<String, Object>> ticketsSection(User user) {
        List<SupportTicket> tickets =
                supportTicketRepository.findByUserIdOrderByUpdatedAtDesc(user.getId());
        return tickets.stream()
                .map(
                        t -> {
                            Map<String, Object> m = new LinkedHashMap<>();
                            m.put("id", t.getId());
                            m.put("topic", t.getTopic());
                            m.put("subject", t.getSubject());
                            m.put("status", t.getStatus() == null ? null : t.getStatus().name());
                            m.put("orderId", t.getOrderId());
                            m.put("createdAt", t.getCreatedAt());
                            m.put("updatedAt", t.getUpdatedAt());
                            return m;
                        })
                .toList();
    }

    private User currentUser() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            throw new UserNotFoundException("Not authenticated");
        }
        return userRepository
                .findByUsername(auth.getName())
                .orElseThrow(() -> new UserNotFoundException("User not found"));
    }
}
