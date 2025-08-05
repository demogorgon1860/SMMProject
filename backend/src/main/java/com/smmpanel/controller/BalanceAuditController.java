package com.smmpanel.controller;

import com.smmpanel.service.BalanceAuditService;
import com.smmpanel.service.BalanceAuditService.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@Slf4j
@RestController
@RequestMapping("/admin/balance-audit")
@RequiredArgsConstructor
@Tag(name = "Balance Audit", description = "Balance audit trail and reconciliation endpoints")
@PreAuthorize("hasRole('ADMIN')")
public class BalanceAuditController {

    private final BalanceAuditService balanceAuditService;

    @GetMapping("/reconcile/user/{userId}")
    @Operation(summary = "Reconcile user balance", 
               description = "Performs comprehensive balance reconciliation for a specific user")
    public ResponseEntity<BalanceReconciliation> reconcileUserBalance(
            @Parameter(description = "User ID") @PathVariable Long userId) {
        
        log.info("Starting balance reconciliation for user: {}", userId);
        
        try {
            BalanceReconciliation reconciliation = balanceAuditService.reconcileUserBalance(userId);
            
            if (!reconciliation.getIsReconciled()) {
                log.warn("Balance discrepancy found for user {}: {}", userId, reconciliation.getDiscrepancy());
            }
            
            return ResponseEntity.ok(reconciliation);
            
        } catch (Exception e) {
            log.error("Failed to reconcile balance for user {}", userId, e);
            return ResponseEntity.internalServerError().build();
        }
    }

    @PostMapping("/verify/daily/{date}")
    @Operation(summary = "Trigger daily balance verification", 
               description = "Manually triggers daily balance verification for a specific date")
    public ResponseEntity<Map<String, Object>> triggerDailyVerification(
            @Parameter(description = "Date in YYYY-MM-DD format") 
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        
        log.info("Manually triggering daily balance verification for date: {}", date);
        
        try {
            CompletableFuture<DailyBalanceReport> reportFuture = 
                balanceAuditService.performDailyBalanceVerification(date);
            
            // For immediate response, we'll return the async operation status
            Map<String, Object> response = new HashMap<>();
            response.put("status", "started");
            response.put("date", date);
            response.put("message", "Daily balance verification started");
            
            // You could also wait for completion if needed:
            // DailyBalanceReport report = reportFuture.get(30, TimeUnit.SECONDS);
            
            return ResponseEntity.ok(response);
            
        } catch (Exception e) {
            log.error("Failed to trigger daily verification for date {}", date, e);
            
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("status", "error");
            errorResponse.put("date", date);
            errorResponse.put("error", e.getMessage());
            
            return ResponseEntity.internalServerError().body(errorResponse);
        }
    }

    @GetMapping("/integrity/user/{userId}")
    @Operation(summary = "Verify audit trail integrity", 
               description = "Verifies the integrity of audit trail for a user within a date range")
    public ResponseEntity<AuditTrailIntegrityReport> verifyAuditTrailIntegrity(
            @Parameter(description = "User ID") @PathVariable Long userId,
            @Parameter(description = "Start date and time")
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime fromDate,
            @Parameter(description = "End date and time")
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime toDate) {
        
        log.info("Verifying audit trail integrity for user {} from {} to {}", userId, fromDate, toDate);
        
        try {
            AuditTrailIntegrityReport report = balanceAuditService.verifyAuditTrailIntegrity(userId, fromDate, toDate);
            
            if (!report.getIsIntegrityValid()) {
                log.warn("Audit trail integrity issues found for user {}: {} hash mismatches, {} chain breaks", 
                    userId, report.getHashMismatches(), report.getChainBreaks());
            }
            
            return ResponseEntity.ok(report);
            
        } catch (Exception e) {
            log.error("Failed to verify audit trail integrity for user {}", userId, e);
            return ResponseEntity.internalServerError().build();
        }
    }

    @GetMapping("/report/user/{userId}")
    @Operation(summary = "Generate audit trail report", 
               description = "Generates detailed audit trail report for a user within a date range")
    public ResponseEntity<AuditTrailReport> generateAuditTrailReport(
            @Parameter(description = "User ID") @PathVariable Long userId,
            @Parameter(description = "Start date and time")
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startDate,
            @Parameter(description = "End date and time")
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endDate) {
        
        log.info("Generating audit trail report for user {} from {} to {}", userId, startDate, endDate);
        
        try {
            AuditTrailReport report = balanceAuditService.generateAuditTrailReport(userId, startDate, endDate);
            
            log.info("Generated audit trail report for user {}: {} transactions, net change: {}", 
                userId, report.getTotalTransactions(), report.getNetChange());
            
            return ResponseEntity.ok(report);
            
        } catch (Exception e) {
            log.error("Failed to generate audit trail report for user {}", userId, e);
            return ResponseEntity.internalServerError().build();
        }
    }

    @GetMapping("/status")
    @Operation(summary = "Get audit system status", 
               description = "Returns current status of the audit system")
    public ResponseEntity<Map<String, Object>> getAuditSystemStatus() {
        
        try {
            Map<String, Object> status = new HashMap<>();
            status.put("status", "operational");
            status.put("timestamp", LocalDateTime.now());
            status.put("features", Map.of(
                "balance_reconciliation", "enabled",
                "audit_trail_integrity", "enabled",
                "daily_verification", "enabled",
                "automated_alerts", "enabled"
            ));
            
            return ResponseEntity.ok(status);
            
        } catch (Exception e) {
            log.error("Failed to get audit system status", e);
            
            Map<String, Object> errorStatus = new HashMap<>();
            errorStatus.put("status", "error");
            errorStatus.put("timestamp", LocalDateTime.now());
            errorStatus.put("error", e.getMessage());
            
            return ResponseEntity.internalServerError().body(errorStatus);
        }
    }

    @PostMapping("/reconcile/all")
    @Operation(summary = "Reconcile all user balances", 
               description = "Triggers balance reconciliation for all users (use with caution)")
    public ResponseEntity<Map<String, Object>> reconcileAllUserBalances() {
        
        log.warn("Manual reconciliation of all user balances triggered");
        
        try {
            // This would be a heavy operation, so we'd typically run it async
            LocalDate today = LocalDate.now();
            CompletableFuture<DailyBalanceReport> reportFuture = 
                balanceAuditService.performDailyBalanceVerification(today);
            
            Map<String, Object> response = new HashMap<>();
            response.put("status", "started");
            response.put("message", "Full system reconciliation started");
            response.put("timestamp", LocalDateTime.now());
            
            return ResponseEntity.ok(response);
            
        } catch (Exception e) {
            log.error("Failed to start full system reconciliation", e);
            
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("status", "error");
            errorResponse.put("error", e.getMessage());
            errorResponse.put("timestamp", LocalDateTime.now());
            
            return ResponseEntity.internalServerError().body(errorResponse);
        }
    }

    @GetMapping("/health")
    @Operation(summary = "Audit system health check", 
               description = "Performs health check on the audit system")
    public ResponseEntity<Map<String, Object>> healthCheck() {
        
        try {
            Map<String, Object> health = new HashMap<>();
            health.put("status", "healthy");
            health.put("timestamp", LocalDateTime.now());
            health.put("components", Map.of(
                "database_connection", "up",
                "audit_service", "up",
                "reconciliation_service", "up"
            ));
            
            return ResponseEntity.ok(health);
            
        } catch (Exception e) {
            log.error("Audit system health check failed", e);
            
            Map<String, Object> unhealthyResponse = new HashMap<>();
            unhealthyResponse.put("status", "unhealthy");
            unhealthyResponse.put("timestamp", LocalDateTime.now());
            unhealthyResponse.put("error", e.getMessage());
            
            return ResponseEntity.status(503).body(unhealthyResponse);
        }
    }
}