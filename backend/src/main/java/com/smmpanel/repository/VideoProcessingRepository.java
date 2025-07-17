package com.smmpanel.repository;

import com.smmpanel.entity.VideoProcessing;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface VideoProcessingRepository extends JpaRepository<VideoProcessing, Long> {
    @Query("SELECT vp FROM VideoProcessing vp WHERE vp.order.id = :orderId")
    Optional<VideoProcessing> findByOrderId(@Param("orderId") Long orderId);
}
