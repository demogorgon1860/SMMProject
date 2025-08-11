package com.smmpanel.service;

import com.smmpanel.dto.binom.BinomIntegrationRequest;
import com.smmpanel.dto.binom.BinomIntegrationResponse;
import com.smmpanel.dto.kafka.VideoProcessingMessage;
import com.smmpanel.entity.*;
import com.smmpanel.exception.VideoProcessingException;
import com.smmpanel.repository.jpa.*;
import com.smmpanel.service.kafka.VideoProcessingProducerService;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.AsyncResult;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * REFACTORED: YouTube Automation Service with proper transaction management SEPARATION OF CONCERNS:
 * - Fast transactional methods for database operations only - Async methods for long-running
 * external operations - Proper transaction propagation settings - No blocking operations
 * in @Transactional methods
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
    private final VideoProcessingProducerService videoProcessingProducerService;
    private final OrderStateManagementService orderStateManagementService;

    @Value("${app.youtube.clip-creation.enabled:true}")
    private boolean clipCreationEnabled;

    @Value("${app.youtube.clip-creation.coefficient:3.0}")
    private double clipCoefficient;

    @Value("${app.youtube.clip-creation.timeout:300000}")
    private long clipCreationTimeoutMs;

    private static final Pattern YOUTUBE_URL_PATTERN =
            Pattern.compile(
                    "^https?://(www\\.)?(youtube\\.com/watch\\?v=|youtu\\.be/|youtube\\.com/shorts/)([a-zA-Z0-9_-]{11}).*");

    /**
     * KAFKA INTEGRATION: Queue order for async processing via Kafka Fast method that validates
     * order and sends to Kafka queue with proper state management
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void queueYouTubeOrderForProcessing(Long orderId, Long userId) {
        try {
            log.info("Queuing YouTube order for async processing: orderId={}", orderId);

            // Get order and validate
            Order order =
                    orderRepository
                            .findById(orderId)
                            .orElseThrow(
                                    () ->
                                            new VideoProcessingException(
                                                    "Order not found: " + orderId));

            // Extract and validate video ID
            String videoId = extractVideoId(order.getLink());
            if (videoId == null) {
                throw new VideoProcessingException("Invalid YouTube URL: " + order.getLink());
            }

            // IMMEDIATE STATE VALIDATION AND UPDATE
            OrderValidationResult validationResult =
                    orderStateManagementService.validateAndUpdateOrderForProcessing(
                            orderId, videoId);

            if (!validationResult.isSuccess()) {
                throw new VideoProcessingException(
                        "Order validation failed: " + validationResult.getErrorMessage());
            }

            // UPDATE PROCESSING STATUS TO INDICATE QUEUING
            orderStateManagementService.updateProcessingStatus(
                    orderId,
                    OrderStateManagementService.ProcessingPhase.VALIDATION,
                    "Order validated and being queued for async processing");

            // Send to Kafka queue based on user type/priority
            VideoProcessingMessage message;
            if (isPremiumUser(userId)) {
                message =
                        VideoProcessingMessage.createHighPriorityMessage(
                                orderId, videoId, order.getLink(), order.getQuantity(), userId);
            } else {
                message =
                        VideoProcessingMessage.createStandardMessage(
                                orderId, videoId, order.getLink(), order.getQuantity(), userId);
            }

            // Add metadata for tracking
            message.addMetadata("queued-by", "youtube-automation-service");
            message.addMetadata("queue-timestamp", LocalDateTime.now().toString());
            message.addMetadata(
                    "service-id",
                    order.getService() != null ? order.getService().toString() : "unknown");
            message.addMetadata("processing-phase", "queuing");

            // Send to Kafka queue
            videoProcessingProducerService
                    .sendVideoProcessingMessage(message)
                    .whenComplete(
                            (result, ex) -> {
                                if (ex == null) {
                                    log.info(
                                            "Successfully queued order {} for processing", orderId);
                                    // Update processing status to indicate successful queuing
                                    orderStateManagementService.updateProcessingStatus(
                                            orderId,
                                            OrderStateManagementService.ProcessingPhase.VALIDATION,
                                            "Successfully queued in Kafka for async processing");
                                } else {
                                    log.error(
                                            "Failed to queue order {} for processing: {}",
                                            orderId,
                                            ex.getMessage(),
                                            ex);
                                    // Handle queue failure with proper state management
                                    orderStateManagementService.transitionToHolding(
                                            orderId, "Failed to queue order: " + ex.getMessage());
                                }
                            });

            log.info("Order {} queued successfully for async processing via Kafka", orderId);

        } catch (Exception e) {
            log.error(
                    "Failed to queue YouTube order processing {}: {}", orderId, e.getMessage(), e);
            // Use state management for error handling
            orderStateManagementService.transitionToHolding(
                    orderId, "Failed to queue order: " + e.getMessage());
            throw e;
        }
    }

    /**
     * MAIN ENTRY POINT: Fast orchestration method that delegates to appropriate handlers This
     * method is NOT @Transactional to avoid long-running operations in transactions
     */
    public void processYouTubeOrder(Long orderId) {
        try {
            log.info("Starting YouTube order processing for order: {}", orderId);

            // 1. Fast transactional validation and initial setup
            OrderProcessingContext context = initializeOrderProcessing(orderId);

            if (context == null) {
                log.warn("Order {} initialization failed or not eligible for processing", orderId);
                return;
            }

            // 2. Async processing of long-running operations
            processOrderAsync(context);

        } catch (Exception e) {
            log.error(
                    "CRITICAL ERROR: Failed to initiate YouTube order processing {}: {}",
                    orderId,
                    e.getMessage(),
                    e);
            handleProcessingErrorTransactional(orderId, e.getMessage());
        }
    }

    /**
     * FAST TRANSACTIONAL: Initialize order processing with database operations only NO external API
     * calls, NO long-running operations PROPAGATION.REQUIRED: Uses existing transaction or creates
     * new one
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public OrderProcessingContext initializeOrderProcessing(Long orderId) {
        try {
            // 1. Get order and validate
            Order order =
                    orderRepository
                            .findById(orderId)
                            .orElseThrow(
                                    () ->
                                            new VideoProcessingException(
                                                    "Order not found: " + orderId));

            if (!order.getStatus().equals(OrderStatus.PENDING)) {
                log.warn("Order {} is not in PENDING status: {}", orderId, order.getStatus());
                return null;
            }

            // 2. Extract and validate YouTube video ID (local operation, no external calls)
            String videoId = extractVideoId(order.getLink());
            if (videoId == null) {
                throw new VideoProcessingException("Invalid YouTube URL: " + order.getLink());
            }

            // 3. Update order to PROCESSING status immediately
            order.setYoutubeVideoId(videoId);
            order.setStatus(OrderStatus.PROCESSING);
            order.setUpdatedAt(LocalDateTime.now());
            orderRepository.save(order);

            // 4. Create video processing record
            VideoProcessing videoProcessing = createVideoProcessingRecord(order, videoId);

            log.info("Order {} initialized for processing. Video ID: {}", orderId, videoId);

            // Return context for async processing
            return OrderProcessingContext.builder()
                    .orderId(orderId)
                    .videoId(videoId)
                    .orderLink(order.getLink())
                    .targetQuantity(order.getQuantity())
                    .videoProcessingId(videoProcessing.getId())
                    .build();

        } catch (Exception e) {
            log.error("Failed to initialize order processing for {}: {}", orderId, e.getMessage());
            throw e;
        }
    }

    /**
     * ASYNC: Process long-running operations without blocking transactions External API calls,
     * Selenium automation, and integrations happen here WITH PROPER STATE MANAGEMENT AND
     * TRANSITIONS
     */
    @Async("videoProcessingExecutor")
    public Future<Void> processOrderAsync(OrderProcessingContext context) {
        try {
            log.info("Starting async processing for order: {}", context.getOrderId());

            // PHASE 1: VIDEO ANALYSIS - Get start count via YouTube API
            orderStateManagementService.updateProcessingStatus(
                    context.getOrderId(),
                    OrderStateManagementService.ProcessingPhase.VIDEO_ANALYSIS,
                    "Retrieving current view count from YouTube API");

            int startCount = youTubeService.getVideoViewCount(context.getVideoId());

            // Update order with start count (fast transaction)
            updateOrderStartCount(context.getOrderId(), startCount);

            // PHASE 2: CLIP CREATION - Create YouTube clip if enabled
            ClipCreationResult clipResult = null;
            if (clipCreationEnabled) {
                orderStateManagementService.updateProcessingStatus(
                        context.getOrderId(),
                        OrderStateManagementService.ProcessingPhase.CLIP_CREATION,
                        "Creating YouTube clip using Selenium automation");

                clipResult = createYouTubeClipAsync(context);
            }

            // Update video processing with clip info (fast transaction)
            updateVideoProcessingWithClip(context.getVideoProcessingId(), clipResult);

            // PHASE 3: BINOM INTEGRATION - Create Binom campaigns
            orderStateManagementService.updateProcessingStatus(
                    context.getOrderId(),
                    OrderStateManagementService.ProcessingPhase.BINOM_INTEGRATION,
                    "Setting up Binom campaigns and traffic routing");

            createBinomIntegrationAsync(context, clipResult);

            // PHASE 4: ACTIVATION - Transition to ACTIVE state
            orderStateManagementService.updateProcessingStatus(
                    context.getOrderId(),
                    OrderStateManagementService.ProcessingPhase.ACTIVATION,
                    "Finalizing order and transitioning to active state");

            // Use state management for final transition
            StateTransitionResult activationResult =
                    orderStateManagementService.transitionToActive(
                            context.getOrderId(), startCount);

            if (!activationResult.isSuccess()) {
                throw new VideoProcessingException(
                        "Failed to activate order: " + activationResult.getErrorMessage());
            }

            log.info("Async processing completed successfully for order: {}", context.getOrderId());
            return new AsyncResult<>(null);

        } catch (Exception e) {
            log.error(
                    "Async processing failed for order {}: {}",
                    context.getOrderId(),
                    e.getMessage(),
                    e);
            // Use state management for error handling
            orderStateManagementService.transitionToHolding(
                    context.getOrderId(), "Async processing failed: " + e.getMessage());
            return new AsyncResult<>(null);
        }
    }

    /**
     * FAST TRANSACTIONAL: Update order with start count PROPAGATION.REQUIRES_NEW: Independent
     * transaction
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void updateOrderStartCount(Long orderId, int startCount) {
        try {
            Order order =
                    orderRepository
                            .findById(orderId)
                            .orElseThrow(
                                    () ->
                                            new VideoProcessingException(
                                                    "Order not found: " + orderId));

            order.setStartCount(startCount);
            order.setRemains(order.getQuantity());
            order.setUpdatedAt(LocalDateTime.now());
            orderRepository.save(order);

            log.debug("Updated start count for order {}: {}", orderId, startCount);

        } catch (Exception e) {
            log.error("Failed to update start count for order {}: {}", orderId, e.getMessage());
            throw e;
        }
    }

    /**
     * ASYNC: Create YouTube clip using Selenium (long-running operation) NO @Transactional
     * annotation - external operations should not be in transactions
     */
    public ClipCreationResult createYouTubeClipAsync(OrderProcessingContext context) {
        try {
            log.info(
                    "Creating YouTube clip for order {} with video ID: {}",
                    context.getOrderId(),
                    context.getVideoId());

            // 1. Select available YouTube account (database read)
            YouTubeAccount account = selectAvailableYouTubeAccount();
            if (account == null) {
                log.warn("No available YouTube accounts for clip creation");
                return ClipCreationResult.failed("No available YouTube accounts");
            }

            // 2. Generate clip title (local operation)
            String clipTitle = generateClipTitle(context.getOrderId());

            // 3. Create clip using Selenium (long-running external operation)
            String originalVideoUrl = "https://www.youtube.com/watch?v=" + context.getVideoId();
            String clipUrl = seleniumService.createClip(originalVideoUrl, account, clipTitle);

            if (clipUrl == null) {
                return ClipCreationResult.failed("Selenium returned null");
            }

            // 4. Update account usage (fast transaction)
            updateAccountUsageTransactional(account.getId());

            log.info("Successfully created clip for order {}: {}", context.getOrderId(), clipUrl);
            return ClipCreationResult.success(clipUrl);

        } catch (Exception e) {
            log.error(
                    "Failed to create YouTube clip for order {}: {}",
                    context.getOrderId(),
                    e.getMessage());
            return ClipCreationResult.failed(e.getMessage());
        }
    }

    /**
     * FAST TRANSACTIONAL: Update account usage PROPAGATION.REQUIRES_NEW: Independent transaction to
     * avoid long locks
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void updateAccountUsageTransactional(Long accountId) {
        try {
            YouTubeAccount account =
                    youTubeAccountRepository
                            .findById(accountId)
                            .orElseThrow(
                                    () ->
                                            new VideoProcessingException(
                                                    "Account not found: " + accountId));

            account.setDailyClipsCount(account.getDailyClipsCount() + 1);
            account.setTotalClipsCreated(account.getTotalClipsCreated() + 1);
            account.setLastClipDate(LocalDateTime.now().toLocalDate());
            account.setUpdatedAt(LocalDateTime.now());
            youTubeAccountRepository.save(account);

            log.debug("Updated account usage for account: {}", accountId);

        } catch (Exception e) {
            log.error(
                    "Failed to update account usage for account {}: {}", accountId, e.getMessage());
            throw e;
        }
    }

    /**
     * FAST TRANSACTIONAL: Update video processing with clip information PROPAGATION.REQUIRES_NEW:
     * Independent transaction
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void updateVideoProcessingWithClip(
            Long videoProcessingId, ClipCreationResult clipResult) {
        try {
            VideoProcessing videoProcessing =
                    videoProcessingRepository
                            .findById(videoProcessingId)
                            .orElseThrow(
                                    () ->
                                            new VideoProcessingException(
                                                    "VideoProcessing not found: "
                                                            + videoProcessingId));

            if (clipResult != null && clipResult.isSuccess()) {
                videoProcessing.setClipCreated(true);
                videoProcessing.setClipUrl(clipResult.getClipUrl());
            } else {
                videoProcessing.setClipCreated(false);
                if (clipResult != null) {
                    videoProcessing.setErrorMessage(
                            "Clip creation failed: " + clipResult.getErrorMessage());
                }
            }

            videoProcessing.setProcessingStatus("COMPLETED");
            videoProcessing.setUpdatedAt(LocalDateTime.now());
            videoProcessingRepository.save(videoProcessing);

            log.debug("Updated video processing with clip info: {}", videoProcessingId);

        } catch (Exception e) {
            log.error(
                    "Failed to update video processing {}: {}", videoProcessingId, e.getMessage());
            throw e;
        }
    }

    /**
     * ASYNC: Create Binom integration (external API call) NO @Transactional annotation - external
     * operations should not be in transactions
     */
    public void createBinomIntegrationAsync(
            OrderProcessingContext context, ClipCreationResult clipResult) {
        try {
            log.info(
                    "Creating Binom integration for order {} with clip: {}",
                    context.getOrderId(),
                    clipResult != null && clipResult.isSuccess());

            // Use clip URL if created, otherwise use original video URL
            String finalTargetUrl =
                    (clipResult != null && clipResult.isSuccess())
                            ? clipResult.getClipUrl()
                            : context.getOrderLink();

            BinomIntegrationRequest binomRequest =
                    BinomIntegrationRequest.builder()
                            .orderId(context.getOrderId())
                            .targetViews(context.getTargetQuantity())
                            .targetUrl(finalTargetUrl)
                            .clipCreated(clipResult != null && clipResult.isSuccess())
                            .geoTargeting("US") // Default, can be configurable
                            .build();

            // External API call (not in transaction)
            BinomIntegrationResponse response = binomService.createBinomIntegration(binomRequest);

            if (!response.isSuccess()) {
                throw new VideoProcessingException(
                        "Binom integration failed: " + response.getErrorMessage());
            }

            log.info(
                    "Binom integration created successfully for order {}: {} campaigns, {} clicks"
                            + " required",
                    context.getOrderId(),
                    response.getCampaignIds().size(),
                    response.getTotalClicksRequired());

        } catch (Exception e) {
            log.error(
                    "Failed to create Binom integration for order {}: {}",
                    context.getOrderId(),
                    e.getMessage());
            throw new VideoProcessingException("Binom integration failed", e);
        }
    }

    /**
     * FAST TRANSACTIONAL: Finalize order processing to ACTIVE status PROPAGATION.REQUIRES_NEW:
     * Independent transaction
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void finalizeOrderProcessing(Long orderId) {
        try {
            Order order =
                    orderRepository
                            .findById(orderId)
                            .orElseThrow(
                                    () ->
                                            new VideoProcessingException(
                                                    "Order not found: " + orderId));

            order.setStatus(OrderStatus.ACTIVE);
            order.setUpdatedAt(LocalDateTime.now());
            orderRepository.save(order);

            log.info("Order {} finalized to ACTIVE status", orderId);

        } catch (Exception e) {
            log.error("Failed to finalize order processing for {}: {}", orderId, e.getMessage());
            throw e;
        }
    }

    /**
     * FAST TRANSACTIONAL: Handle processing errors PROPAGATION.REQUIRES_NEW: Independent
     * transaction for error handling
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void handleProcessingErrorTransactional(Long orderId, String errorMessage) {
        try {
            Order order = orderRepository.findById(orderId).orElse(null);
            if (order != null) {
                order.setStatus(OrderStatus.HOLDING);
                order.setErrorMessage(errorMessage);
                order.setUpdatedAt(LocalDateTime.now());
                orderRepository.save(order);

                // Update video processing record if exists
                Optional<VideoProcessing> processingOpt =
                        videoProcessingRepository.findByOrderId(orderId);
                if (processingOpt.isPresent()) {
                    VideoProcessing processing = processingOpt.get();
                    processing.setProcessingStatus("FAILED");
                    processing.setErrorMessage(errorMessage);
                    processing.setUpdatedAt(LocalDateTime.now());
                    videoProcessingRepository.save(processing);
                }

                log.info("Order {} marked as HOLDING due to error: {}", orderId, errorMessage);
            }
        } catch (Exception e) {
            log.error(
                    "Failed to handle processing error for order {}: {}", orderId, e.getMessage());
        }
    }

    /** Extract YouTube video ID from URL (local operation, no external calls) */
    private String extractVideoId(String url) {
        Matcher matcher = YOUTUBE_URL_PATTERN.matcher(url);
        if (matcher.matches()) {
            return matcher.group(3);
        }
        return null;
    }

    /**
     * FAST TRANSACTIONAL: Create video processing record PROPAGATION.MANDATORY: Must be called
     * within existing transaction
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public VideoProcessing createVideoProcessingRecord(Order order, String videoId) {
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

    /** Determine video type from URL (local operation) */
    private VideoType determineVideoType(String url) {
        if (url.contains("/shorts/")) {
            return VideoType.SHORTS;
        } else if (url.contains("live")) {
            return VideoType.LIVE;
        } else {
            return VideoType.STANDARD;
        }
    }

    /** Select available YouTube account for clip creation (database read only) */
    private YouTubeAccount selectAvailableYouTubeAccount() {
        // Use a reasonable default daily limit, e.g., 50, or fetch from config if needed
        int dailyLimit = 50;
        return youTubeAccountRepository
                .findFirstByStatusAndDailyClipsCountLessThan(YouTubeAccountStatus.ACTIVE, dailyLimit)
                .orElse(null);
    }

    /** Generate clip title for YouTube clip (local operation) */
    private String generateClipTitle(Long orderId) {
        String[] templates = {
            "Amazing moment from this video!",
            "Check out this highlight!",
            "Best part of the video",
            "Must see clip!",
            "Viral moment here"
        };

        int index = (int) (orderId % templates.length);
        return templates[index];
    }

    /**
     * FAST TRANSACTIONAL: Monitor and update order progress PROPAGATION.REQUIRES_NEW: Independent
     * transaction for monitoring
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void monitorOrderProgress(Long orderId) {
        try {
            Order order = orderRepository.findById(orderId).orElse(null);
            if (order == null || !order.getStatus().equals(OrderStatus.ACTIVE)) {
                return;
            }

            // Schedule async view count check to avoid blocking transaction
            checkViewCountAsync(orderId);

        } catch (Exception e) {
            log.error("Failed to schedule progress monitoring for {}: {}", orderId, e.getMessage());
        }
    }

    /** ASYNC: Check view count and update progress */
    @Async("lightweightAsyncExecutor")
    public Future<Void> checkViewCountAsync(Long orderId) {
        try {
            Order order = orderRepository.findById(orderId).orElse(null);
            if (order == null) {
                return new AsyncResult<>(null);
            }

            String videoId = order.getYoutubeVideoId();
            if (videoId != null) {
                // External API call (not in transaction)
                int currentViews = youTubeService.getVideoViewCount(videoId);

                // Fast transaction to update progress
                updateOrderProgressTransactional(orderId, currentViews);
            }

            return new AsyncResult<>(null);

        } catch (Exception e) {
            log.error("Failed to check view count for order {}: {}", orderId, e.getMessage());
            return new AsyncResult<>(null);
        }
    }

    /**
     * UPDATE ORDER PROGRESS: Uses state management for consistent progress updates Handles state
     * transitions automatically when targets are reached
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void updateOrderProgressTransactional(Long orderId, int currentViews) {
        try {
            log.debug("Updating progress for order {}: current views={}", orderId, currentViews);

            // Use state management service for progress updates
            ProgressUpdateResult progressResult =
                    orderStateManagementService.updateOrderProgress(orderId, currentViews);

            if (progressResult.isSuccess() && progressResult.isCompleted()) {
                log.info(
                        "Order {} completed via progress update. Views gained: {}",
                        orderId,
                        progressResult.getViewsGained());

                // Schedule async stop of Binom campaigns for completed orders
                stopBinomCampaignsAsync(orderId);
            }

        } catch (Exception e) {
            log.error("Failed to update order progress for {}: {}", orderId, e.getMessage());
        }
    }

    /** ASYNC: Stop Binom campaigns (external API call) */
    @Async("lightweightAsyncExecutor")
    public Future<Void> stopBinomCampaignsAsync(Long orderId) {
        try {
            binomService.stopAllCampaignsForOrder(orderId);
            log.info("Stopped Binom campaigns for completed order: {}", orderId);
            return new AsyncResult<>(null);
        } catch (Exception e) {
            log.error("Failed to stop Binom campaigns for order {}: {}", orderId, e.getMessage());
            return new AsyncResult<>(null);
        }
    }

    /**
     * FAST TRANSACTIONAL: Retry failed video processing PROPAGATION.REQUIRES_NEW: Independent
     * transaction
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void retryVideoProcessing(Long orderId) {
        try {
            Order order = orderRepository.findById(orderId).orElse(null);
            if (order == null) {
                return;
            }

            Optional<VideoProcessing> processingOpt =
                    videoProcessingRepository.findByOrderId(orderId);
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

                    // Send to processing queue (async message)
                    kafkaTemplate.send("smm.youtube.processing", orderId);

                    log.info(
                            "Retrying video processing for order {} (attempt {})",
                            orderId,
                            processing.getProcessingAttempts());
                } else {
                    log.warn("Max retry attempts reached for order {}", orderId);
                }
            }

        } catch (Exception e) {
            log.error("Failed to retry video processing for order {}: {}", orderId, e.getMessage());
        }
    }

    /** Get processing status for order (read-only, no transaction needed) */
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

    /** HELPER: Check if user is premium (for priority queue assignment) */
    private boolean isPremiumUser(Long userId) {
        // This would typically check user role or subscription status
        // For now, simplified implementation
        return userId != null && userId % 10 == 0; // Every 10th user is "premium"
    }

    /** HELPER: Handle Kafka queue failure */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void handleQueueFailure(Long orderId, String errorMessage) {
        try {
            Order order = orderRepository.findById(orderId).orElse(null);
            if (order != null) {
                order.setStatus(OrderStatus.HOLDING);
                order.setErrorMessage("Queue failure: " + errorMessage);
                order.setUpdatedAt(LocalDateTime.now());
                orderRepository.save(order);

                log.error(
                        "Order {} reverted to HOLDING due to queue failure: {}",
                        orderId,
                        errorMessage);
            }
        } catch (Exception e) {
            log.error(
                    "Failed to handle queue failure for order {}: {}", orderId, e.getMessage(), e);
        }
    }
}

// Supporting classes

/** DTO for video processing status */
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
