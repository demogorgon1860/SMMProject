package com.smmpanel.repository.jpa;

import com.smmpanel.entity.BalanceDeposit;
import com.smmpanel.entity.User;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface BalanceDepositRepository extends JpaRepository<BalanceDeposit, Long> {
    Optional<BalanceDeposit> findByOrderId(String orderId);

    Optional<BalanceDeposit> findByOrderIdAndUser(String orderId, User user);

    Page<BalanceDeposit> findByUserOrderByCreatedAtDesc(User user, Pageable pageable);

    List<BalanceDeposit> findByUser_IdOrderByCreatedAtDesc(Long userId);

    // Get all deposits for admin, ordered by creation date
    Page<BalanceDeposit> findAllByOrderByCreatedAtDesc(Pageable pageable);

    // Find by payment ID (maps to orderId which stores transaction IDs)
    default Optional<BalanceDeposit> findByPaymentId(String paymentId) {
        return findByOrderId(paymentId);
    }

    /**
     * Atomic compare-and-set: flip {@code status} from PENDING to COMPLETED in a single UPDATE.
     * Returns the number of rows affected — 1 means this caller is the unique winner of the
     * transition and is responsible for crediting the wallet, 0 means another thread (or a webhook
     * retry) already won and the caller must NOT credit again.
     *
     * <p>This replaces the previous in-memory {@code ConcurrentHashMap} dedup hack which was lost
     * across restarts, ineffective across multiple JVMs, and racy with the load+update pattern in
     * {@link com.smmpanel.service.integration.CryptomusService#processWebhook}.
     */
    @Modifying
    @Query(
            "UPDATE BalanceDeposit d SET d.status = com.smmpanel.entity.PaymentStatus.COMPLETED,"
                    + " d.confirmedAt = :confirmedAt WHERE d.id = :id AND d.status <>"
                    + " com.smmpanel.entity.PaymentStatus.COMPLETED")
    int markCompletedIfNotAlready(
            @Param("id") Long id, @Param("confirmedAt") LocalDateTime confirmedAt);
}
