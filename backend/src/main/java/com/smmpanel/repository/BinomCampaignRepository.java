package com.smmpanel.repository;

import com.smmpanel.entity.BinomCampaign;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Repository for Binom campaigns management, including both regular and fixed campaigns
 */
@Repository
public interface BinomCampaignRepository extends JpaRepository<BinomCampaign, Long> {

    // Basic campaign queries
    @Query("SELECT bc FROM BinomCampaign bc WHERE bc.order.id = :orderId")
    List<BinomCampaign> findByOrderId(@Param("orderId") Long orderId);

    Optional<BinomCampaign> findByCampaignId(String campaignId);

    @Query("SELECT bc FROM BinomCampaign bc WHERE bc.status = :status")
    List<BinomCampaign> findByStatus(@Param("status") String status);

    @Query("SELECT bc FROM BinomCampaign bc WHERE bc.clicksDelivered < bc.clicksRequired AND bc.status = 'ACTIVE'")
    List<BinomCampaign> findActiveCampaignsNeedingClicks();

    @Query("SELECT bc FROM BinomCampaign bc JOIN bc.order o WHERE o.user.id = :userId")
    List<BinomCampaign> findByUserId(@Param("userId") Long userId);
    
    // Legacy methods for backward compatibility
    @Query("SELECT bc FROM BinomCampaign bc WHERE bc.order.id = :orderId AND bc.status = :status")
    List<BinomCampaign> findByOrderIdAndStatus(@Param("orderId") Long orderId, @Param("status") String status);
    
    // Fixed campaign specific queries
    @Query("SELECT b FROM BinomCampaign b WHERE b.isFixedCampaign = true AND b.status = 'ACTIVE' ORDER BY b.id")
    List<BinomCampaign> findAllActiveFixedCampaigns();

    @Query("SELECT b FROM BinomCampaign b WHERE b.campaignId = :campaignId AND b.isFixedCampaign = true AND b.status = 'ACTIVE'")
    Optional<BinomCampaign> findActiveFixedCampaignByCampaignId(@Param("campaignId") String campaignId);

    @Query("SELECT CASE WHEN COUNT(b) > 0 THEN true ELSE false END FROM BinomCampaign b WHERE b.campaignId = :campaignId AND b.isFixedCampaign = true AND b.status = 'ACTIVE'")
    boolean existsActiveFixedCampaignByCampaignId(@Param("campaignId") String campaignId);

    @Query("SELECT COUNT(b) FROM BinomCampaign b WHERE b.isFixedCampaign = true AND b.status = 'ACTIVE'")
    long countActiveFixedCampaigns();
    
    // Additional fixed campaign queries
    @Query("SELECT b FROM BinomCampaign b WHERE b.isFixedCampaign = true AND b.status = 'ACTIVE' " +
           "AND (b.geoTargeting = :geoTargeting OR b.geoTargeting = 'ALL') " +
           "ORDER BY b.priority ASC, b.weight DESC")
    List<BinomCampaign> findActiveFixedCampaignsByGeoTargeting(@Param("geoTargeting") String geoTargeting);
    
    @Query("SELECT b FROM BinomCampaign b WHERE b.isFixedCampaign = true AND b.status = 'ACTIVE' " +
           "AND b.trafficSource.id = :trafficSourceId")
    List<BinomCampaign> findActiveFixedCampaignsByTrafficSource(@Param("trafficSourceId") Long trafficSourceId);
    
    @Query("SELECT b FROM BinomCampaign b WHERE b.isFixedCampaign = true AND b.status = 'ACTIVE' " +
           "ORDER BY b.weight DESC")
    List<BinomCampaign> findActiveFixedCampaignsOrderByWeight();

    @Query("SELECT bc FROM BinomCampaign bc WHERE bc.order.id = :orderId AND bc.status = 'ACTIVE'")
    List<BinomCampaign> findByOrderIdAndActiveTrue(@Param("orderId") Long orderId);

    long countByCampaignIdAndStatus(String campaignId, String status);
}