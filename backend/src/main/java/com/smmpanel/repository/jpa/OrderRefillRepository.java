package com.smmpanel.repository.jpa;

import com.smmpanel.entity.OrderRefill;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface OrderRefillRepository extends JpaRepository<OrderRefill, Long> {

    /** Find all refills for an original order */
    List<OrderRefill> findByOriginalOrderIdOrderByRefillNumberAsc(Long originalOrderId);

    /**
     * Find the maximum refill number for an original order Used to determine the next refill number
     */
    @Query(
            "SELECT MAX(r.refillNumber) FROM OrderRefill r WHERE r.originalOrderId ="
                    + " :originalOrderId")
    Optional<Integer> findMaxRefillNumberByOriginalOrderId(
            @Param("originalOrderId") Long originalOrderId);

    /** Find a refill by the refill order ID */
    Optional<OrderRefill> findByRefillOrderId(Long refillOrderId);

    /** Count total refills for an original order */
    long countByOriginalOrderId(Long originalOrderId);
}
