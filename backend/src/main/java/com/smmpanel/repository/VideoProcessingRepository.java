package com.smmpanel.repository;

import com.smmpanel.entity.VideoProcessing;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Repository for managing video processing records
 */
@Repository
public interface VideoProcessingRepository extends JpaRepository<VideoProcessing, Long> {

    Optional<VideoProcessing> findByOrderId(Long orderId);
    List<VideoProcessing> findByProcessingStatusOrderByCreatedAtAsc(String processingStatus);
    List<VideoProcessing> findByVideoIdOrderByCreatedAtDesc(String videoId);
    @Query("SELECT vp FROM VideoProcessing vp WHERE vp.clipCreated = true AND vp.createdAt >= :date")
    List<VideoProcessing> findClipsCreatedAfter(@Param("date") java.time.LocalDateTime date);
    @Query("SELECT vp FROM VideoProcessing vp WHERE vp.processingStatus = 'FAILED' AND vp.processingAttempts < 3")
    List<VideoProcessing> findFailedProcessingForRetry();
    @Query("SELECT COUNT(vp) FROM VideoProcessing vp WHERE vp.clipCreated = true AND vp.createdAt >= :date")
    Long countClipsCreatedAfter(@Param("date") java.time.LocalDateTime date);
    @Query("SELECT vp.videoType, COUNT(vp) FROM VideoProcessing vp WHERE vp.createdAt >= :date GROUP BY vp.videoType")
    List<Object[]> getProcessingCountByVideoType(@Param("date") java.time.LocalDateTime date);
    List<VideoProcessing> findByYoutubeAccountIdAndCreatedAtAfter(Long youtubeAccountId, java.time.LocalDateTime date);

    @Query("SELECT vp FROM VideoProcessing vp WHERE vp.processingStatus = :status")
    List<VideoProcessing> findByProcessingStatus(@Param("status") String status);

    @Query("SELECT vp FROM VideoProcessing vp WHERE vp.processingStatus = 'PENDING' " +
           "OR (vp.processingStatus = 'PROCESSING' AND vp.updatedAt < :timeoutThreshold)")
    List<VideoProcessing> findStuckProcessing(@Param("timeoutThreshold") LocalDateTime timeoutThreshold);

    @Query("SELECT vp FROM VideoProcessing vp WHERE vp.clipCreated = true")
    List<VideoProcessing> findWithClipsCreated();

    @Query("SELECT vp FROM VideoProcessing vp WHERE vp.youtubeAccount.id = :accountId")
    List<VideoProcessing> findByYoutubeAccountId(@Param("accountId") Long accountId);

    @Query("SELECT COUNT(vp) FROM VideoProcessing vp WHERE vp.clipCreated = true " +
           "AND vp.createdAt >= :startDate AND vp.createdAt <= :endDate")
    long countClipsCreatedBetween(@Param("startDate") LocalDateTime startDate, 
                                  @Param("endDate") LocalDateTime endDate);
}
