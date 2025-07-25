package com.smmpanel.repository;

import com.smmpanel.entity.TrafficSource;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface TrafficSourceRepository extends JpaRepository<TrafficSource, Long> {

    @Query("SELECT ts FROM TrafficSource ts WHERE ts.active = true " +
           "AND (ts.dailyLimit IS NULL OR ts.clicksUsedToday < ts.dailyLimit) " +
           "ORDER BY ts.weight DESC, ts.performanceScore DESC")
    List<TrafficSource> findActiveSourcesWithCapacity();

    @Query("SELECT ts FROM TrafficSource ts WHERE ts.active = true ORDER BY ts.weight DESC")
    List<TrafficSource> findActiveSourcesOrderByWeight();

    List<TrafficSource> findByActiveTrue();

    TrafficSource findBySourceId(String sourceId);

    @Query("SELECT ts FROM TrafficSource ts WHERE ts.active = true AND ts.geoTargeting = :geoTargeting")
    List<TrafficSource> findActiveByGeoTargeting(@Param("geoTargeting") String geoTargeting);
    
    // Legacy methods for backward compatibility
    List<TrafficSource> findByActiveTrueOrderByWeightDesc();
    List<TrafficSource> findByActiveTrueAndQualityLevel(String qualityLevel);
    List<TrafficSource> findByQualityLevelOrderByWeightDesc(String qualityLevel);
}
