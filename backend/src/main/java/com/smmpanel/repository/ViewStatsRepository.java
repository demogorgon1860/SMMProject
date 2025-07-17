package com.smmpanel.repository;

import com.smmpanel.entity.ViewStats;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface ViewStatsRepository extends JpaRepository<ViewStats, Long> {
    @Query("SELECT vs FROM ViewStats vs WHERE vs.order.id = :orderId")
    Optional<ViewStats> findByOrderId(@Param("orderId") Long orderId);
}
