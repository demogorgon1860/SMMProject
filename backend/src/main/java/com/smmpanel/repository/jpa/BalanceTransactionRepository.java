package com.smmpanel.repository.jpa;

import com.smmpanel.entity.BalanceTransaction;
import com.smmpanel.entity.TransactionType;
import com.smmpanel.entity.User;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface BalanceTransactionRepository extends JpaRepository<BalanceTransaction, Long> {
    Page<BalanceTransaction> findByUserOrderByCreatedAtDesc(User user, Pageable pageable);

    Page<BalanceTransaction> findByUserId(Long userId, Pageable pageable);

    Page<BalanceTransaction> findByUserIdOrderByCreatedAtDesc(Long userId, Pageable pageable);

    Page<BalanceTransaction> findByUser_UsernameOrderByCreatedAtDesc(
            String username, Pageable pageable);

    List<BalanceTransaction> findByUser_UsernameAndTransactionTypeOrderByCreatedAtDesc(
            String username, TransactionType transactionType);

    @Query(
            "SELECT COALESCE(SUM(bt.amount), 0) FROM BalanceTransaction bt WHERE bt.user.username ="
                    + " :username AND bt.transactionType = :type")
    java.math.BigDecimal sumAmountByUsernameAndType(
            @Param("username") String username, @Param("type") TransactionType type);

    @Query(
            "SELECT COALESCE(SUM(bt.amount), 0) FROM BalanceTransaction bt WHERE bt.user.username ="
                    + " :username AND bt.createdAt >= :date")
    java.math.BigDecimal sumAmountByUsernameAndCreatedAtAfter(
            @Param("username") String username, @Param("date") java.time.LocalDateTime date);

    List<BalanceTransaction> findByOrderIdOrderByCreatedAtDesc(Long orderId);

    @Query(
            "SELECT bt.transactionType, COUNT(bt), COALESCE(SUM(bt.amount), 0) FROM"
                + " BalanceTransaction bt WHERE bt.createdAt >= :date GROUP BY bt.transactionType")
    List<Object[]> getTransactionStatistics(@Param("date") java.time.LocalDateTime date);

    // Audit trail specific methods
    List<BalanceTransaction> findByUserOrderByCreatedAtAsc(User user);

    Page<BalanceTransaction> findByUserAndCreatedAtBetween(
            User user, LocalDateTime startDate, LocalDateTime endDate, Pageable pageable);

    List<BalanceTransaction> findByUserAndCreatedAtBetweenOrderByCreatedAtAsc(
            User user, LocalDateTime startDate, LocalDateTime endDate);

    BalanceTransaction findTopByUserOrderByCreatedAtDesc(User user);

    @Query(
            "SELECT DISTINCT u FROM User u JOIN BalanceTransaction bt ON u.id = bt.user.id WHERE"
                    + " bt.createdAt BETWEEN :startDate AND :endDate")
    List<User> findUsersWithTransactionsBetween(
            @Param("startDate") LocalDateTime startDate, @Param("endDate") LocalDateTime endDate);

    @Query(
            "SELECT bt FROM BalanceTransaction bt WHERE bt.user IS NULL AND bt.createdAt BETWEEN"
                    + " :startDate AND :endDate")
    List<BalanceTransaction> findOrphanedTransactions(
            @Param("startDate") LocalDateTime startDate, @Param("endDate") LocalDateTime endDate);

    @Query(
            "SELECT bt.transactionId FROM BalanceTransaction bt WHERE bt.createdAt BETWEEN"
                    + " :startDate AND :endDate GROUP BY bt.transactionId HAVING"
                    + " COUNT(bt.transactionId) > 1")
    List<String> findDuplicateTransactionIds(
            @Param("startDate") LocalDateTime startDate, @Param("endDate") LocalDateTime endDate);

    @Query("SELECT bt FROM BalanceTransaction bt WHERE bt.reconciliationStatus = :status")
    List<BalanceTransaction> findByReconciliationStatus(
            @Param("status") BalanceTransaction.ReconciliationStatus status);

    @Query(
            "SELECT COUNT(bt) FROM BalanceTransaction bt WHERE bt.user.id = :userId AND"
                    + " bt.createdAt BETWEEN :startDate AND :endDate")
    Long countTransactionsByUserAndDateRange(
            @Param("userId") Long userId,
            @Param("startDate") LocalDateTime startDate,
            @Param("endDate") LocalDateTime endDate);

    @Query("SELECT bt FROM BalanceTransaction bt WHERE bt.auditHash IS NULL OR bt.auditHash = ''")
    List<BalanceTransaction> findTransactionsWithMissingHashes();

    @Query(
            "SELECT SUM(bt.amount) FROM BalanceTransaction bt WHERE bt.user.id = :userId AND"
                + " bt.transactionType = :type AND bt.createdAt BETWEEN :startDate AND :endDate")
    java.math.BigDecimal sumAmountByUserAndTypeAndDateRange(
            @Param("userId") Long userId,
            @Param("type") TransactionType type,
            @Param("startDate") LocalDateTime startDate,
            @Param("endDate") LocalDateTime endDate);

    @Query("SELECT bt FROM BalanceTransaction bt WHERE bt.transactionId = :transactionId")
    Optional<BalanceTransaction> findByTransactionId(@Param("transactionId") String transactionId);

    @Query("SELECT bt FROM BalanceTransaction bt WHERE bt.referenceId = :referenceId")
    List<BalanceTransaction> findByReferenceId(@Param("referenceId") String referenceId);

    // Reconciliation queries
    @Query(
            "SELECT bt FROM BalanceTransaction bt WHERE bt.reconciliationStatus = 'PENDING' AND"
                    + " bt.createdAt < :cutoffDate")
    List<BalanceTransaction> findPendingReconciliationTransactions(
            @Param("cutoffDate") LocalDateTime cutoffDate);

    @Query(
            "SELECT DATE(bt.createdAt) as date, COUNT(bt) as count, SUM(bt.amount) as total FROM"
                + " BalanceTransaction bt WHERE bt.createdAt BETWEEN :startDate AND :endDate GROUP"
                + " BY DATE(bt.createdAt) ORDER BY DATE(bt.createdAt)")
    List<Object[]> getDailyTransactionSummary(
            @Param("startDate") LocalDateTime startDate, @Param("endDate") LocalDateTime endDate);

    // Integrity verification queries
    @Query(
            "SELECT COUNT(bt) FROM BalanceTransaction bt WHERE bt.user.id = :userId AND"
                    + " (bt.balanceAfter != bt.balanceBefore + bt.amount)")
    Long countTransactionsWithCalculationErrors(@Param("userId") Long userId);
}
