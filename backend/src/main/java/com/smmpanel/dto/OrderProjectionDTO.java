package com.smmpanel.dto;

import com.smmpanel.entity.OrderStatus;
import com.smmpanel.entity.VideoProcessingStatus;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * PROJECTION DTO: Order Projection for API responses
 * 
 * Features:
 * 1. Complete order information with related data
 * 2. Prevents N+1 queries through joins
 * 3. Optimized for API responses and detailed views
 * 4. Includes video processing information
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class OrderProjectionDTO {
    
    // Order core fields
    private Long id;
    private String orderId;
    private String link;
    private Integer quantity;
    private BigDecimal charge;
    private Integer startCount;
    private Integer remains;
    private OrderStatus status;
    private String youtubeVideoId;
    private Integer targetViews;
    private BigDecimal coefficient;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    
    // User information (joined to prevent N+1)
    private String username;
    
    // Service information (joined to prevent N+1)
    private String serviceName;
    private String serviceCategory;
    
    // Video processing information (joined to prevent N+1)
    private Boolean clipCreated;
    private String clipUrl;
    private VideoProcessingStatus processingStatus;
    
    /**
     * Check if this is a YouTube order
     */
    public boolean isYouTubeOrder() {
        return youtubeVideoId != null && !youtubeVideoId.isEmpty();
    }
    
    /**
     * Get completion percentage
     */
    public double getCompletionPercentage() {
        if (quantity == null || quantity == 0) {
            return 0.0;
        }
        
        if (remains == null) {
            return 0.0;
        }
        
        int delivered = quantity - remains;
        return (double) delivered / quantity * 100.0;
    }
    
    /**
     * Check if order is overdelivered
     */
    public boolean isOverdelivered() {
        return remains != null && remains < 0;
    }
    
    /**
     * Get delivered count
     */
    public int getDeliveredCount() {
        if (quantity == null || remains == null) {
            return 0;
        }
        return Math.max(0, quantity - remains);
    }
    
    /**
     * Check if video processing is completed
     */
    public boolean isVideoProcessingCompleted() {
        return processingStatus == VideoProcessingStatus.COMPLETED;
    }
    
    /**
     * Check if clip was successfully created
     */
    public boolean hasClip() {
        return Boolean.TRUE.equals(clipCreated) && clipUrl != null && !clipUrl.isEmpty();
    }
    
    /**
     * Get processing duration in minutes
     */
    public long getProcessingDurationMinutes() {
        if (createdAt == null) {
            return 0;
        }
        
        LocalDateTime endTime = updatedAt != null ? updatedAt : LocalDateTime.now();
        return java.time.Duration.between(createdAt, endTime).toMinutes();
    }
    
    /**
     * Check if order is stale (processing too long)
     */
    public boolean isStale(int maxHours) {
        return getProcessingDurationMinutes() > (maxHours * 60) && 
               (status == OrderStatus.PROCESSING || status == OrderStatus.ACTIVE);
    }
    
    /**
     * Get effective target URL (clip URL if available, otherwise original link)
     */
    public String getEffectiveTargetUrl() {
        return hasClip() ? clipUrl : link;
    }
}