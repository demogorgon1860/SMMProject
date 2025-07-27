package com.smmpanel.service;

import com.smmpanel.entity.*;
import com.smmpanel.repository.*;
import com.smmpanel.exception.VideoProcessingException;
import com.smmpanel.dto.binom.BinomIntegrationRequest;
import com.smmpanel.dto.binom.BinomIntegrationResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.kafka.core.KafkaTemplate;

import java.time.LocalDateTime;
import java.util.regex.Pattern;
import java.util.regex.Matcher;
import java.util.Optional;

/**
 * CRITICAL: Complete YouTube Automation Service
 * MUST handle automatic clip creation with coefficient 3.0 and start_count verification
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class YouTubeAutomationService {

    private final VideoProcessingRepository videoProcessingRepository;
    private final YouTubeAccountRepository youTubeAccountRepository;
    private final OrderRepository orderRepository;
    private final SeleniumService seleniumService;
    private final YouTubeService youTubeService;
    private final BinomService binomService;
    private final KafkaTemplate<String, Object> kafkaTemplate;

    @Value("${app.youtube.clip-creation.enabled:true}")
    private boolean clipCreationEnabled;

    @Value("${app.youtube.clip-creation.coefficient:3.0}")
    private double clipCoefficient;

    @Value("${app.youtube.clip-creation.timeout:300000}")
    private long clipCreationTimeoutMs;

    private static final Pattern YOUTUBE_URL_PATTERN = Pattern.compile(
            "^https?://(www\\.)?(youtube\\.com/watch\\?v=|youtu\\.be/|youtube\\.com/shorts/)([a-zA-Z0-9_-]{11}).*");

    /**
     * CRITICAL: Process YouTube order with automatic clip creation
     * MUST follow exact workflow: PENDING → PROCESSING → ACTIVE → COMPLETED
     */
    @Transactional
    public void processYouTubeOrder(Long orderId) {
        try {
            log.info("Starting YouTube order processing for order: {}", orderId);

            // 1. Get order and validate
            Order order = orderRepository.findById(orderId)
                    .orElseThrow(() -> new VideoProcessingException("Order not found: " + orderId));

            if (!order.getStatus().equals(OrderStatus.PENDING)) {
                log.warn("Order {} is not in PENDING status: {}", orderId, order.getStatus());
                return;
            }

            // 2. Extract and validate YouTube video ID
            String videoId = extractVideoId(order.getLink());
            if (videoId == null) {
                throw new VideoProcessingException("Invalid YouTube URL: " + order.getLink());
            }

            // 3. CRITICAL: Get start_count via YouTube API
            int startCount = youTubeService.getVideoViewCount(videoId);
            order.setStartCount(startCount);
            order.setYoutubeVideoId(videoId);
            order.setRemains(order.getQuantity());

            // 4. Update status to PROCESSING
            order.setStatus(OrderStatus.PROCESSING);
            order.setUpdatedAt(LocalDateTime.now());
            orderRepository.save(order);

            log.info("Order {} processing started. Video ID: {}, Start count: {}", 
                    orderId, videoId, startCount);

            // 5. Create video processing record
            VideoProcessing videoProcessing = createVideoProcessingRecord(order, videoId);

            // 6. CRITICAL: Create YouTube clip (coefficient 3.0)
            boolean clipCreated = false;
            String clipUrl = null;

            if (clipCreationEnabled) {
                try {
                    clipUrl = createYouTubeClip(order, videoId);
                    if (clipUrl != null) {
                        clipCreated = true;
                        videoProcessing.setClipCreated(true);
                        videoProcessing.setClipUrl(clipUrl);
                        log.info("Clip created successfully for order {}: {}", orderId, clipUrl);
                    }
                } catch (Exception e) {
                    log.error("Failed to create clip for order {}: {}", orderId, e.getMessage());
                    // Continue without clip (coefficient 4.0)
                }
            }

            // 7. Update video processing status
            videoProcessing.setProcessingStatus("COMPLETED");
            videoProcessing.setUpdatedAt(LocalDateTime.now());
            videoProcessingRepository.save(videoProcessing);

            // 8. CRITICAL: Create Binom integration with correct coefficient
            createBinomIntegration(order, videoId, clipCreated, clipUrl);

            // 9. Update order status to ACTIVE
            order.setStatus(OrderStatus.ACTIVE);
            order.setUpdatedAt(LocalDateTime.now());
            orderRepository.save(order);

            log.info("YouTube order {} processing completed successfully. Clip created: {}", 
                    orderId, clipCreated);

        } catch (Exception e) {
            log.error("CRITICAL ERROR: Failed to process YouTube order {}: {}", orderId, e.getMessage(), e);
            handleProcessingError(orderId, e.getMessage());
        }
    }

    /**
     * CRITICAL: Create YouTube clip using Selenium automation
     */
    private String createYouTubeClip(Order order, String videoId) {
        try {
            log.info("Creating YouTube clip for order {} with video ID: {}", order.getId(), videoId);

            // 1. Select available YouTube account
            YouTubeAccount account = selectAvailableYouTubeAccount();
            if (account == null) {
                throw new VideoProcessingException("No available YouTube accounts for clip creation");
            }

            // 2. Generate clip title
            String clipTitle = generateClipTitle(order);

            // 3. Create clip using Selenium
            String originalVideoUrl = "https://www.youtube.com/watch?v=" + videoId;
            String clipUrl = seleniumService.createClip(originalVideoUrl, account, clipTitle);

            if (clipUrl == null) {
                throw new VideoProcessingException("Failed to create clip - Selenium returned null");
            }

            // 4. Update account usage
            updateAccountUsage(account);

            log.info("Successfully created clip for order {}: {}", order.getId(), clipUrl);
            return clipUrl;

        } catch (Exception e) {
            log.error("Failed to create YouTube clip for order {}: {}", order.getId(), e.getMessage());
            throw new VideoProcessingException("Clip creation failed: " + e.getMessage(), e);
        }
    }

    /**
     * Extract YouTube video ID from URL
     */
    private String extractVideoId(String url) {
        Matcher matcher = YOUTUBE_URL_PATTERN.matcher(url);
        if (matcher.matches()) {
            return matcher.group(3);
        }
        return null;
    }

    /**
     * Create video processing record
     */
    private VideoProcessing createVideoProcessingRecord(Order order, String videoId) {
        VideoProcessing processing = new VideoProcessing();
        processing.setOrder(order);
        processing.setOriginalUrl(order.getLink());
        processing.setVideoId(videoId);
        processing.setVideoType(determineVideoType(order.getLink()));
        processing.setClipCreated(false);
        processing.setProcessingStatus("PROCESSING");
        processing.setProcessingAttempts(1);
        processing.setCreatedAt(LocalDateTime.now());
        processing.setUpdatedAt(LocalDateTime.now());

        return videoProcessingRepository.save(processing);
    }

    /**
     * Determine video type from URL
     */
    private VideoType determineVideoType(String url) {
        if (url.contains("/shorts/")) {
            return VideoType.SHORTS;
        } else if (url.contains("live")) {
            return VideoType.LIVE;
        } else {
            return VideoType.STANDARD;
        }
    }

    /**
     * Select available YouTube account for clip creation
     */
    private YouTubeAccount selectAvailableYouTubeAccount() {
        return youTubeAccountRepository.findFirstByStatusAndDailyClipsCountLessThanDailyLimit(
                YouTubeAccountStatus.ACTIVE).orElse(null);
    }

    /**
     * Update YouTube account usage statistics
     */
    private void updateAccountUsage(YouTubeAccount account) {
        account.setDailyClipsCount(account.getDailyClipsCount() + 1);
        account.setTotalClipsCreated(account.getTotalClipsCreated() + 1);
        account.setLastClipDate(LocalDateTime.now().toLocalDate());
        account.setUpdatedAt(LocalDateTime.now());
        youTubeAccountRepository.save(account);
    }

    /**
     * Generate clip title for YouTube clip
     */
    private String generateClipTitle(Order order) {
        String[] templates = {
                "Amazing moment from this video!",
                "Check out this highlight!",
                "Best part of the video",
                "Must see clip!",
                "Viral moment here"
        };
        
        int index = (int) (order.getId() % templates.length);
        return templates[index];
    }

    /**
     * CRITICAL: Create Binom integration with correct coefficient
     */
    private void createBinomIntegration(Order order, String videoId, boolean clipCreated, String targetUrl) {
        try {
            log.info("Creating Binom integration for order {} with clip: {}", order.getId(), clipCreated);

            // Use clip URL if created, otherwise use original video URL
            String finalTargetUrl = clipCreated && targetUrl != null ? targetUrl : order.getLink();

            BinomIntegrationRequest binomRequest = BinomIntegrationRequest.builder()
                    .orderId(order.getId())
                    .targetViews(order.getQuantity())
                    .targetUrl(finalTargetUrl)
                    .clipCreated(clipCreated)
                    .geoTargeting("US") // Default, can be configurable
                    .build();

            BinomIntegrationResponse response = binomService.createBinomIntegration(binomRequest);

            if (!response.isSuccess()) {
                throw new VideoProcessingException("Binom integration failed: " + response.getErrorMessage());
            }

            log.info("Binom integration created successfully for order {}: {} campaigns, {} clicks required", 
                    order.getId(), response.getCampaignIds().size(), response.getTotalClicksRequired());

        } catch (Exception e) {
            log.error("Failed to create Binom integration for order {}: {}", order.getId(), e.getMessage());
            throw new VideoProcessingException("Binom integration failed", e);
        }
    }

    /**
     * Handle processing errors
     */
    private void handleProcessingError(Long orderId, String errorMessage) {
        try {
            Order order = orderRepository.findById(orderId).orElse(null);
            if (order != null) {
                order.setStatus(OrderStatus.HOLDING);
                order.setErrorMessage(errorMessage);
                order.setUpdatedAt(LocalDateTime.now());
                orderRepository.save(order);

                // Update video processing record if exists
                Optional<VideoProcessing> processingOpt = videoProcessingRepository.findByOrderId(orderId);
                if (processingOpt.isPresent()) {
                    VideoProcessing processing = processingOpt.get();
                    processing.setProcessingStatus("FAILED");
                    processing.setErrorMessage(errorMessage);
                    processing.setUpdatedAt(LocalDateTime.now());
                    videoProcessingRepository.save(processing);
                }
            }
        } catch (Exception e) {
            log.error("Failed to handle processing error for order {}: {}", orderId, e.getMessage());
        }
    }

    /**
     * Monitor and update order progress
     */
    @Transactional
    public void monitorOrderProgress(Long orderId) {
        try {
            Order order = orderRepository.findById(orderId).orElse(null);
            if (order == null || !order.getStatus().equals(OrderStatus.ACTIVE)) {
                return;
            }

            // Get current view count
            String videoId = order.getYoutubeVideoId();
            if (videoId != null) {
                int currentViews = youTubeService.getVideoViewCount(videoId);
                int viewsGained = currentViews - order.getStartCount();
                int remains = Math.max(0, order.getQuantity() - viewsGained);

                order.setRemains(remains);
                order.setUpdatedAt(LocalDateTime.now());

                // Check if order is completed
                if (remains <= 0) {
                    order.setStatus(OrderStatus.COMPLETED);
                    order.setRemains(0);
                    
                    // Stop Binom campaigns
                    binomService.stopAllCampaignsForOrder(orderId);
                    
                    log.info("Order {} completed. Views gained: {}", orderId, viewsGained);
                }

                orderRepository.save(order);
            }

        } catch (Exception e) {
            log.error("Failed to monitor order progress for {}: {}", orderId, e.getMessage());
        }
    }

    /**
     * Retry failed video processing
     */
    @Transactional
    public void retryVideoProcessing(Long orderId) {
        try {
            Order order = orderRepository.findById(orderId).orElse(null);
            if (order == null) {
                return;
            }

            Optional<VideoProcessing> processingOpt = videoProcessingRepository.findByOrderId(orderId);
            if (processingOpt.isPresent()) {
                VideoProcessing processing = processingOpt.get();
                
                if (processing.getProcessingAttempts() < 3) {
                    processing.setProcessingAttempts(processing.getProcessingAttempts() + 1);
                    processing.setProcessingStatus("PENDING");
                    processing.setErrorMessage(null);
                    processing.setUpdatedAt(LocalDateTime.now());
                    videoProcessingRepository.save(processing);

                    // Reset order status to processing
                    order.setStatus(OrderStatus.PROCESSING);
                    order.setErrorMessage(null);
                    order.setUpdatedAt(LocalDateTime.now());
                    orderRepository.save(order);

                    // Send to processing queue
                    kafkaTemplate.send("smm.youtube.processing", orderId);
                    
                    log.info("Retrying video processing for order {} (attempt {})", 
                            orderId, processing.getProcessingAttempts());
                } else {
                    log.warn("Max retry attempts reached for order {}", orderId);
                }
            }

        } catch (Exception e) {
            log.error("Failed to retry video processing for order {}: {}", orderId, e.getMessage());
        }
    }

    /**
     * Get processing status for order
     */
    public VideoProcessingStatus getProcessingStatus(Long orderId) {
        Optional<VideoProcessing> processingOpt = videoProcessingRepository.findByOrderId(orderId);
        
        if (processingOpt.isPresent()) {
            VideoProcessing processing = processingOpt.get();
            return VideoProcessingStatus.builder()
                    .orderId(orderId)
                    .status(processing.getProcessingStatus())
                    .clipCreated(processing.isClipCreated())
                    .clipUrl(processing.getClipUrl())
                    .attempts(processing.getProcessingAttempts())
                    .errorMessage(processing.getErrorMessage())
                    .createdAt(processing.getCreatedAt())
                    .updatedAt(processing.getUpdatedAt())
                    .build();
        }
        
        return null;
    }
}

// Supporting DTO classes

@lombok.Builder
@lombok.Data
class VideoProcessingStatus {
    private Long orderId;
    private String status;
    private boolean clipCreated;
    private String clipUrl;
    private int attempts;
    private String errorMessage;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
} 