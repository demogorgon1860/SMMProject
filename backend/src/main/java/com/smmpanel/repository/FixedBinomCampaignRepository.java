package com.smmpanel.repository;

import com.smmpanel.entity.FixedBinomCampaign;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * CRITICAL: Repository for Fixed Binom Campaigns
 * MUST support exactly 3 campaign distribution for Perfect Panel compatibility
 */
@Repository
public interface FixedBinomCampaignRepository extends JpaRepository<FixedBinomCampaign, Long> {

    /**
     * CRITICAL: Get exactly 3 active campaigns by geo targeting
     */
    @Query("SELECT f FROM FixedBinomCampaign f WHERE f.active = true " +
           "AND (f.geoTargeting IS NULL OR f.geoTargeting LIKE %:geoTargeting%) " +
           "ORDER BY f.priority ASC, f.id ASC")
    List<FixedBinomCampaign> findActiveByGeoTargeting(@Param("geoTargeting") String geoTargeting);

    /**
     * CRITICAL: Get top 3 active campaigns (fallback)
     */
    @Query("SELECT f FROM FixedBinomCampaign f WHERE f.active = true ORDER BY f.priority ASC, f.weight DESC")
    List<FixedBinomCampaign> findTop3ByActiveTrue(Pageable pageable);

    /**
     * Find all active campaigns
     */
    List<FixedBinomCampaign> findByActiveTrue();

    /**
     * Find all active campaigns (alias for findByActiveTrue)
     */
    default List<FixedBinomCampaign> findAllActiveCampaigns() {
        return findByActiveTrue();
    }

    /**
     * Find campaign by Binom campaign ID
     */
    Optional<FixedBinomCampaign> findByCampaignId(String campaignId);

    /**
     * Check if campaign exists by ID
     */
    boolean existsByCampaignId(String campaignId);

    @Query("SELECT COUNT(f) FROM FixedBinomCampaign f WHERE f.active = true")
    long countActiveCampaigns();
} 