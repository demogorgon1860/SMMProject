package com.smmpanel.repository;

import com.smmpanel.entity.FixedBinomCampaign;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Repository for Fixed Binom Campaign management
 */
@Repository
public interface FixedBinomCampaignRepository extends JpaRepository<FixedBinomCampaign, Long> {

    @Query("SELECT fbc FROM FixedBinomCampaign fbc WHERE fbc.active = true ORDER BY fbc.priority ASC, fbc.weight DESC")
    List<FixedBinomCampaign> findAllActiveOrderedByPriorityAndWeight();

    @Query("SELECT fbc FROM FixedBinomCampaign fbc WHERE fbc.campaignId = :campaignId AND fbc.active = true")
    Optional<FixedBinomCampaign> findActiveByCampaignId(@Param("campaignId") String campaignId);

    @Query("SELECT fbc FROM FixedBinomCampaign fbc WHERE fbc.trafficSource.id = :trafficSourceId AND fbc.active = true")
    List<FixedBinomCampaign> findActiveByTrafficSourceId(@Param("trafficSourceId") Long trafficSourceId);

    @Query("SELECT fbc FROM FixedBinomCampaign fbc WHERE fbc.geoTargeting = :geoTargeting AND fbc.active = true")
    List<FixedBinomCampaign> findActiveByGeoTargeting(@Param("geoTargeting") String geoTargeting);

    @Query("SELECT COUNT(fbc) FROM FixedBinomCampaign fbc WHERE fbc.active = true")
    long countActive();
} 