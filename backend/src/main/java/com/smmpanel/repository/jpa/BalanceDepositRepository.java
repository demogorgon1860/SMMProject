package com.smmpanel.repository.jpa;

import com.smmpanel.entity.BalanceDeposit;
import com.smmpanel.entity.User;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface BalanceDepositRepository extends JpaRepository<BalanceDeposit, Long> {
    Optional<BalanceDeposit> findByOrderId(String orderId);

    Optional<BalanceDeposit> findByOrderIdAndUser(String orderId, User user);

    Page<BalanceDeposit> findByUserOrderByCreatedAtDesc(User user, Pageable pageable);
}
