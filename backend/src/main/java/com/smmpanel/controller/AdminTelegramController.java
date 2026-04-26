package com.smmpanel.controller;

import com.smmpanel.client.InstagramBotClient;
import com.smmpanel.dto.admin.PendingDecisionResponse;
import com.smmpanel.dto.telegram.CancelPendingDecision;
import com.smmpanel.entity.DailyProfitSummary;
import com.smmpanel.repository.jpa.DailyProfitSummaryRepository;
import com.smmpanel.service.notification.CancelDecisionService;
import com.smmpanel.service.notification.TelegramCallbackTxService;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * Admin Telegram operations — backs the {@code /admin/telegram} page in the panel.
 *
 * <p>Surfaces the pending circuit-breaker decisions that the bot pauses and lets the operator
 * either resume or cancel them from the web UI. Both paths reuse the exact same plumbing as the
 * Telegram-button callbacks ({@link TelegramCallbackTxService} + {@link InstagramBotClient}) so
 * audit + refund behavior is identical regardless of which surface decided.
 */
@Slf4j
@RestController
@RequestMapping("/api/v2/admin/telegram")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ADMIN','OPERATOR')")
public class AdminTelegramController {

    private final CancelDecisionService cancelDecisionService;
    private final TelegramCallbackTxService callbackTxService;
    private final InstagramBotClient instagramBotClient;
    private final DailyProfitSummaryRepository profitRepository;

    @Value("${app.telegram.cancel.timeout-hours:4}")
    private int decisionTimeoutHours;

    // ---------------------------------------------------------------------
    // Pending decisions
    // ---------------------------------------------------------------------

    @GetMapping("/pending-decisions")
    public ResponseEntity<List<PendingDecisionResponse>> listPending() {
        List<Long> ids = cancelDecisionService.getAllPendingOrderIds();
        List<PendingDecisionResponse> out = new ArrayList<>(ids.size());
        for (Long orderId : ids) {
            Optional<CancelPendingDecision> opt = cancelDecisionService.getPendingDecision(orderId);
            opt.ifPresent(d -> out.add(toResponse(d)));
        }
        // Newest first so the UI doesn't have to sort.
        out.sort(
                (a, b) -> {
                    if (a.getCreatedAt() == null || b.getCreatedAt() == null) return 0;
                    return b.getCreatedAt().compareTo(a.getCreatedAt());
                });
        return ResponseEntity.ok(out);
    }

    @PostMapping("/decisions/{orderId}/proceed")
    public ResponseEntity<Map<String, Object>> proceed(@PathVariable Long orderId) {
        // Atomic claim: GETDEL in Redis. Two concurrent clicks can't both pass — only one gets the
        // decision back, the other gets empty and exits early.
        Optional<CancelPendingDecision> decisionOpt =
                cancelDecisionService.claimPendingDecision(orderId);
        if (decisionOpt.isEmpty()) {
            return ResponseEntity.status(409)
                    .body(Map.of("ok", false, "reason", "decision_not_found_or_expired"));
        }

        boolean resumed = false;
        String botOrderId = decisionOpt.get().getBotOrderId();
        if (botOrderId != null && !botOrderId.isBlank()) {
            try {
                resumed = instagramBotClient.resumeOrderFast(botOrderId);
            } catch (Exception e) {
                log.warn("resumeOrderFast failed for bot order {}: {}", botOrderId, e.getMessage());
            }
        }
        log.info("Admin PROCEED via panel order={} resumed={}", orderId, resumed);
        return ResponseEntity.ok(Map.of("ok", true, "resumed", resumed));
    }

    @PostMapping("/decisions/{orderId}/cancel")
    public ResponseEntity<Map<String, Object>> cancel(@PathVariable Long orderId) {
        Optional<CancelPendingDecision> decisionOpt =
                cancelDecisionService.claimPendingDecision(orderId);
        if (decisionOpt.isEmpty()) {
            return ResponseEntity.status(409)
                    .body(Map.of("ok", false, "reason", "decision_not_found_or_expired"));
        }

        CancelPendingDecision decision = decisionOpt.get();
        Integer completedCount = decision.getCompletedCount();

        TelegramCallbackTxService.CancelResult res;
        try {
            res = callbackTxService.performCancelTx(orderId, completedCount);
        } catch (Exception e) {
            log.error("Admin cancel failed for order {}: {}", orderId, e.getMessage(), e);
            return ResponseEntity.status(500).body(Map.of("ok", false, "reason", "tx_failure"));
        }
        if (!res.processed()) {
            return ResponseEntity.status(409).body(Map.of("ok", false, "reason", res.reason()));
        }

        // Best-effort bot signal — DB state is already correct regardless of this outcome.
        String botOrderId = decision.getBotOrderId();
        if (botOrderId != null && !botOrderId.isBlank()) {
            try {
                instagramBotClient.cancelOrderFast(botOrderId);
            } catch (Exception e) {
                log.warn("cancelOrderFast failed for bot order {}: {}", botOrderId, e.getMessage());
            }
        }
        log.info("Admin CANCEL via panel order={} completed={}", orderId, completedCount);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    // ---------------------------------------------------------------------
    // Daily profit calendar
    // ---------------------------------------------------------------------

    /**
     * Returns one entry per day in the requested month with the profit, completed, and partial
     * counts. Uses {@code daily_profit_summary} (persisted by {@code DailyProfitService} at 23:55)
     * — Redis-buffered counters for the current day are not exposed here.
     */
    @GetMapping("/profit")
    public ResponseEntity<List<Map<String, Object>>> profit(
            @RequestParam(value = "month", required = false) String monthParam) {
        YearMonth ym;
        try {
            ym =
                    monthParam == null || monthParam.isBlank()
                            ? YearMonth.now()
                            : YearMonth.parse(monthParam);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(List.of());
        }
        LocalDate from = ym.atDay(1);
        LocalDate to = ym.atEndOfMonth();
        List<DailyProfitSummary> rows = profitRepository.findAllByReportDateBetween(from, to);
        List<Map<String, Object>> out =
                rows.stream()
                        .map(
                                r ->
                                        Map.<String, Object>of(
                                                "date", r.getReportDate().toString(),
                                                "profit",
                                                        r.getTotalProfit() == null
                                                                ? "0"
                                                                : r.getTotalProfit().toString(),
                                                "completedCount",
                                                        r.getCompletedCount() == null
                                                                ? 0
                                                                : r.getCompletedCount(),
                                                "partialCount",
                                                        r.getPartialCount() == null
                                                                ? 0
                                                                : r.getPartialCount()))
                        .collect(Collectors.toList());
        return ResponseEntity.ok(out);
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    private PendingDecisionResponse toResponse(CancelPendingDecision d) {
        long expiresInMs;
        if (d.getCreatedAt() == null) {
            expiresInMs = Duration.ofHours(decisionTimeoutHours).toMillis();
        } else {
            LocalDateTime expiresAt = d.getCreatedAt().plusHours(decisionTimeoutHours);
            long now = LocalDateTime.now().toInstant(ZoneOffset.UTC).toEpochMilli();
            long exp = expiresAt.toInstant(ZoneOffset.UTC).toEpochMilli();
            expiresInMs = Math.max(0, exp - now);
        }
        return PendingDecisionResponse.builder()
                .orderId(d.getOrderId())
                .botOrderId(d.getBotOrderId())
                .completed(d.getCompletedCount())
                .quantity(d.getOriginalCount())
                .orderStatusAtTime(d.getOrderStatusAtTime())
                .createdAt(d.getCreatedAt())
                .expiresInMs(expiresInMs)
                .build();
    }
}
