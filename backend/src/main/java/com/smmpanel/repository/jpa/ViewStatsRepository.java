package com.smmpanel.repository.jpa;

import com.smmpanel.entity.ViewStats;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface ViewStatsRepository extends JpaRepository<ViewStats, Long> {
    @Query("SELECT vs FROM ViewStats vs WHERE vs.order.id = :orderId")
    Optional<ViewStats> findByOrderId(@Param("orderId") Long orderId);
}
