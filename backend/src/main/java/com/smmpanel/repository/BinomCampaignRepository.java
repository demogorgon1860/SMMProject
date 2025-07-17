package com.smmpanel.repository;

import com.smmpanel.entity.BinomCampaign;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface BinomCampaignRepository extends JpaRepository<BinomCampaign, Long> {
    Optional<BinomCampaign> findByCampaignId(String campaignId);
    
    @Query("SELECT bc FROM BinomCampaign bc WHERE bc.order.id = :orderId AND bc.status = :status")
    List<BinomCampaign> findByOrderIdAndStatus(@Param("orderId") Long orderId, @Param("status") String status);
    
    List<BinomCampaign> findByStatus(String status);
}