package com.smmpanel.repository;

import com.smmpanel.entity.BinomCampaign;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Repository для работы с кампаниями Binom
 */
@Repository
public interface BinomCampaignRepository extends JpaRepository<BinomCampaign, Long> {
    Optional<BinomCampaign> findByCampaignId(String campaignId);
    
    @Query("SELECT bc FROM BinomCampaign bc WHERE bc.order.id = :orderId AND bc.status = :status")
    List<BinomCampaign> findByOrderIdAndStatus(@Param("orderId") Long orderId, @Param("status") String status);
    
    List<BinomCampaign> findByStatus(String status);
    
    /**
     * Получить все активные фиксированные кампании
     */
    @Query("SELECT b FROM BinomCampaign b WHERE b.isFixedCampaign = true AND b.status = 'ACTIVE' ORDER BY b.id")
    List<BinomCampaign> findAllActiveFixedCampaigns();

    /**
     * Найти активную фиксированную кампанию по campaign_id
     */
    @Query("SELECT b FROM BinomCampaign b WHERE b.campaignId = :campaignId AND b.isFixedCampaign = true AND b.status = 'ACTIVE'")
    Optional<BinomCampaign> findActiveFixedCampaignByCampaignId(@Param("campaignId") String campaignId);

    /**
     * Проверить существование активной фиксированной кампании по campaign_id
     */
    @Query("SELECT CASE WHEN COUNT(b) > 0 THEN true ELSE false END FROM BinomCampaign b WHERE b.campaignId = :campaignId AND b.isFixedCampaign = true AND b.status = 'ACTIVE'")
    boolean existsActiveFixedCampaignByCampaignId(@Param("campaignId") String campaignId);

    /**
     * Получить количество активных фиксированных кампаний
     */
    @Query("SELECT COUNT(b) FROM BinomCampaign b WHERE b.isFixedCampaign = true AND b.status = 'ACTIVE'")
    long countActiveFixedCampaigns();
}