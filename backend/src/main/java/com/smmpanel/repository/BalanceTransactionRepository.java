package com.smmpanel.repository;

import com.smmpanel.entity.BalanceTransaction;
import com.smmpanel.entity.User;
import com.smmpanel.entity.TransactionType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface BalanceTransactionRepository extends JpaRepository<BalanceTransaction, Long> {
    Page<BalanceTransaction> findByUserOrderByCreatedAtDesc(User user, Pageable pageable);
    Page<BalanceTransaction> findByUserId(Long userId, Pageable pageable);
    Page<BalanceTransaction> findByUser_UsernameOrderByCreatedAtDesc(String username, Pageable pageable);
    List<BalanceTransaction> findByUser_UsernameAndTransactionTypeOrderByCreatedAtDesc(String username, TransactionType transactionType);
    @Query("SELECT COALESCE(SUM(bt.amount), 0) FROM BalanceTransaction bt WHERE bt.user.username = :username AND bt.transactionType = :type")
    java.math.BigDecimal sumAmountByUsernameAndType(@Param("username") String username, @Param("type") TransactionType type);
    @Query("SELECT COALESCE(SUM(bt.amount), 0) FROM BalanceTransaction bt WHERE bt.user.username = :username AND bt.createdAt >= :date")
    java.math.BigDecimal sumAmountByUsernameAndCreatedAtAfter(@Param("username") String username, @Param("date") java.time.LocalDateTime date);
    List<BalanceTransaction> findByOrderIdOrderByCreatedAtDesc(Long orderId);
    @Query("SELECT bt.transactionType, COUNT(bt), COALESCE(SUM(bt.amount), 0) FROM BalanceTransaction bt WHERE bt.createdAt >= :date GROUP BY bt.transactionType")
    List<Object[]> getTransactionStatistics(@Param("date") java.time.LocalDateTime date);
}
