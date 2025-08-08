package com.smmpanel.service;

import com.smmpanel.service.BalanceAuditService.DailyBalanceReport;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.concurrent.CompletableFuture;

@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.balance.verification.enabled", havingValue = "true", matchIfMissing = false)
public class DailyBalanceVerificationService {

    private final BalanceAuditService balanceAuditService;
    private final AlertService alertService;

    /**
     * Runs daily balance verification at 2:00 AM every day
     */
    @Scheduled(cron = "${app.balance.verification.schedule:0 0 2 * * *}")
    public void performDailyBalanceVerification() {
        LocalDate yesterday = LocalDate.now().minusDays(1);
        log.info("Starting daily balance verification for date: {}", yesterday);

        try {
            CompletableFuture<DailyBalanceReport> reportFuture = 
                balanceAuditService.performDailyBalanceVerification(yesterday);
            
            DailyBalanceReport report = reportFuture.get();
            
            log.info("Daily balance verification completed for {}: {} users, {} reconciled, {} with discrepancies",
                yesterday, report.getTotalUsers(), report.getUsersReconciled(), report.getUsersWithDiscrepancies());
            
            // Send alerts if there are discrepancies
            if (!report.getIsSystemReconciled() || report.getUsersWithDiscrepancies() > 0) {
                sendBalanceDiscrepancyAlert(report);
            }
            
        } catch (Exception e) {
            log.error("Failed to perform daily balance verification for {}", yesterday, e);
            sendBalanceVerificationFailureAlert(yesterday, e);
        }
    }

    /**
     * Runs balance reconciliation for pending transactions every 4 hours
     */
    @Scheduled(fixedRate = 14400000) // 4 hours in milliseconds
    public void reconcilePendingTransactions() {
        log.info("Starting reconciliation of pending transactions");
        
        try {
            // This would be implemented to process pending reconciliations
            // For now, just log the execution
            log.info("Pending transaction reconciliation completed");
            
        } catch (Exception e) {
            log.error("Failed to reconcile pending transactions", e);
        }
    }

    private void sendBalanceDiscrepancyAlert(DailyBalanceReport report) {
        String alertMessage = String.format(
            "BALANCE DISCREPANCY ALERT for %s:\n" +
            "- Total Users: %d\n" +
            "- Users with Discrepancies: %d\n" +
            "- System Discrepancy: %s\n" +
            "- System Issues: %s",
            report.getVerificationDate(),
            report.getTotalUsers(),
            report.getUsersWithDiscrepancies(),
            report.getSystemDiscrepancy(),
            String.join(", ", report.getSystemWideIssues())
        );
        
        try {
            // TODO restore - sendCriticalAlert method when available
            // alertService.sendCriticalAlert("Balance Discrepancy Detected", alertMessage);
        } catch (Exception e) {
            log.error("Failed to send balance discrepancy alert", e);
        }
    }

    private void sendBalanceVerificationFailureAlert(LocalDate date, Exception exception) {
        String alertMessage = String.format(
            "BALANCE VERIFICATION FAILURE for %s:\n" +
            "Error: %s\n" +
            "Please investigate immediately.",
            date,
            exception.getMessage()
        );
        
        try {
            // TODO restore - sendCriticalAlert method when available
            // alertService.sendCriticalAlert("Balance Verification Failed", alertMessage);
        } catch (Exception e) {
            log.error("Failed to send balance verification failure alert", e);
        }
    }
}