package com.smmpanel.service;

import com.smmpanel.entity.*;
import com.smmpanel.repository.VideoProcessingRepository;
import com.smmpanel.repository.YouTubeAccountRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class VideoProcessingService {

    private final VideoProcessingRepository videoProcessingRepository;
    private final YouTubeAccountRepository youTubeAccountRepository;
    private final SeleniumService seleniumService;
    private final YouTubeService youTubeService;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final Random random = new Random();

    @Transactional
    public VideoProcessing createVideoProcessing(Order order) {
        VideoProcessing processing = new VideoProcessing();
        processing.setOrder(order);
        processing.setOriginalUrl(order.getLink());
        processing.setVideoType(determineVideoType(order.getLink()));
        processing.setProcessingStatus("PENDING");

        processing = videoProcessingRepository.save(processing);

        // Send to Kafka for async processing
        kafkaTemplate.send("video-processing", processing.getId());

        log.info("Created video processing record {} for order {}", processing.getId(), order.getId());
        return processing;
    }

    @Transactional
    public void processVideo(Long processingId) {
        try {
            VideoProcessing processing = videoProcessingRepository.findById(processingId)
                    .orElseThrow(() -> new IllegalArgumentException("Video processing not found: " + processingId));

            if (!"PENDING".equals(processing.getProcessingStatus())) {
                log.info("Video processing {} already processed, skipping", processingId);
                return;
            }

            processing.setProcessingStatus("PROCESSING");
            processing.setProcessingAttempts(processing.getProcessingAttempts() + 1);
            videoProcessingRepository.save(processing);

            // Try to create clip
            boolean clipCreated = false;
            String clipUrl = null;

            if (canCreateClip(processing)) {
                YouTubeAccount account = selectYouTubeAccount();
                if (account != null) {
                    clipUrl = seleniumService.createClip(
                            processing.getOriginalUrl(), 
                            account, 
                            generateClipTitle(processing.getOrder())
                    );
                    
                    if (clipUrl != null) {
                        clipCreated = true;
                        processing.setClipCreated(true);
                        processing.setClipUrl(clipUrl);
                        processing.setYoutubeAccount(account);
                        
                        // Update account usage
                        updateAccountUsage(account);
                        
                        log.info("Successfully created clip {} for video processing {}", clipUrl, processingId);
                    }
                }
            }

            processing.setProcessingStatus("COMPLETED");
            videoProcessingRepository.save(processing);

            // Continue with Binom campaign creation
            String targetUrl = clipCreated ? clipUrl : processing.getOriginalUrl();
            kafkaTemplate.send("binom-campaign-creation", Map.of(
                    "orderId", processing.getOrder().getId(),
                    "targetUrl", targetUrl,
                    "hasClip", clipCreated
            ));

            log.info("Completed video processing {} with clip: {}", processingId, clipCreated);

        } catch (Exception e) {
            log.error("Failed to process video {}: {}", processingId, e.getMessage(), e);
            
            VideoProcessing processing = videoProcessingRepository.findById(processingId).orElse(null);
            if (processing != null) {
                processing.setProcessingStatus("FAILED");
                processing.setErrorMessage(e.getMessage());
                videoProcessingRepository.save(processing);
                
                // If max attempts reached, continue without clip
                if (processing.getProcessingAttempts() >= 3) {
                    log.warn("Max attempts reached for video processing {}, continuing without clip", processingId);
                    kafkaTemplate.send("binom-campaign-creation", Map.of(
                            "orderId", processing.getOrder().getId(),
                            "targetUrl", processing.getOriginalUrl(),
                            "hasClip", false
                    ));
                } else {
                    // Retry after delay
                    kafkaTemplate.send("video-processing-retry", processing.getId());
                }
            }
        }
    }

    private VideoType determineVideoType(String url) {
        if (url.contains("/shorts/")) {
            return VideoType.SHORTS;
        } else if (url.contains("/live/") || url.contains("live_stream")) {
            return VideoType.LIVE;
        } else {
            return VideoType.STANDARD;
        }
    }

    private boolean canCreateClip(VideoProcessing processing) {
        // Can't create clips from Shorts or certain Live streams
        if (processing.getVideoType() == VideoType.SHORTS) {
            return false;
        }
        
        // Check if video allows clipping
        try {
            String videoId = youTubeService.extractVideoId(processing.getOriginalUrl());
            // Additional checks can be added here via YouTube API
            return true;
        } catch (Exception e) {
            log.warn("Cannot determine if video allows clipping: {}", e.getMessage());
            return false;
        }
    }

    private YouTubeAccount selectYouTubeAccount() {
        List<YouTubeAccount> activeAccounts = youTubeAccountRepository.findByStatus(YouTubeAccountStatus.ACTIVE);
        
        if (activeAccounts.isEmpty()) {
            log.warn("No active YouTube accounts available for clip creation");
            return null;
        }

        LocalDate today = LocalDate.now();
        
        // Filter accounts that haven't reached daily limits
        List<YouTubeAccount> availableAccounts = activeAccounts.stream()
                .filter(account -> {
                    // Reset daily counter if needed
                    if (account.getLastClipDate() == null || account.getLastClipDate().isBefore(today)) {
                        account.setDailyClipsCount(0);
                        account.setLastClipDate(today);
                        youTubeAccountRepository.save(account);
                    }
                    
                    return account.getDailyClipsCount() < account.getDailyLimit();
                })
                .toList();

        if (availableAccounts.isEmpty()) {
            log.warn("All YouTube accounts have reached daily clip limits");
            return null;
        }

        // Select random account
        return availableAccounts.get(random.nextInt(availableAccounts.size()));
    }

    private void updateAccountUsage(YouTubeAccount account) {
        account.setDailyClipsCount(account.getDailyClipsCount() + 1);
        account.setTotalClipsCreated(account.getTotalClipsCreated() + 1);
        account.setLastClipDate(LocalDate.now());
        youTubeAccountRepository.save(account);
    }

    private String generateClipTitle(Order order) {
        String[] templates = {
                "Amazing moment from this video!",
                "Check out this highlight!",
                "Best part of the video",
                "Must see clip!",
                "Viral moment here"
        };
        
        return templates[random.nextInt(templates.length)];
    }

    public Optional<VideoProcessing> findByOrderId(Long orderId) {
        return videoProcessingRepository.findByOrderId(orderId);
    }

    public void retryProcessing(Long processingId) {
        VideoProcessing processing = videoProcessingRepository.findById(processingId)
                .orElseThrow(() -> new IllegalArgumentException("Video processing not found: " + processingId));
        
        if (processing.getProcessingAttempts() < 3) {
            processing.setProcessingStatus("PENDING");
            processing.setErrorMessage(null);
            videoProcessingRepository.save(processing);
            
            kafkaTemplate.send("video-processing", processingId);
            log.info("Retrying video processing {}", processingId);
        }
    }
}