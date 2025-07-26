package com.smmpanel.repository;

import com.smmpanel.entity.BalanceTransaction;
import com.smmpanel.entity.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface BalanceTransactionRepository extends JpaRepository<BalanceTransaction, Long> {
    Page<BalanceTransaction> findByUserOrderByCreatedAtDesc(User user, Pageable pageable);
    Page<BalanceTransaction> findByUserId(Long userId, Pageable pageable);
}
