package com.smmpanel.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * Entity for tracking order refills. When an order underdelivers, a refill creates a new order for
 * the remaining quantity while maintaining the relationship to the original order.
 */
@Entity
@Table(name = "order_refills")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OrderRefill {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "original_order_id", nullable = false)
    private Long originalOrderId;

    @Column(name = "refill_order_id", nullable = false)
    private Long refillOrderId;

    @Column(name = "refill_number", nullable = false)
    private Integer refillNumber;

    @Column(name = "original_quantity", nullable = false)
    private Integer originalQuantity;

    @Column(name = "delivered_quantity", nullable = false)
    private Integer deliveredQuantity;

    @Column(name = "refill_quantity", nullable = false)
    private Integer refillQuantity;

    @Column(name = "start_count_at_refill", nullable = false)
    private Long startCountAtRefill;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
