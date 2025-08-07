package com.smmpanel.service;

import com.smmpanel.dto.binom.BinomIntegrationRequest;
import com.smmpanel.dto.binom.BinomIntegrationResponse;
import com.smmpanel.dto.kafka.VideoProcessingMessage;
import com.smmpanel.entity.*;
import com.smmpanel.exception.VideoProcessingException;
import com.smmpanel.exception.YouTubeApiException;
import com.smmpanel.repository.*;
import com.smmpanel.service.kafka.VideoProcessingProducerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.AsyncResult;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;

/**
 * UNIFIED YouTube Processing Service
 * 
 * Consolidates functionality from:
 * - YouTubeAutomationService (transaction management, async processing)
 * - YouTubeOrderProcessor (order verification, Binom integration) 
 * - VideoProcessingService (clip creation, error handling)
 * 
 * Architecture:
 * - Fast transactional methods for database operations
 * - Async methods for long-running external operations
 * - Proper transaction propagation and error handling
 * - State management integration
 * - Kafka message queue processing
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class YouTubeProcessingService {

    // Core dependencies
    private final VideoProcessingRepository videoProcessingRepository;
    private final YouTubeAccountRepository youTubeAccountRepository;
    private final OrderRepository orderRepository;
    
    // External services
    private final SeleniumService seleniumService;
    private final YouTubeService youTubeService;
    private final YouTubeApiService youTubeApiService;
    private final BinomService binomService;
    private final OrderStateManagementService orderStateManagementService;
    
    // Messaging and utilities
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final VideoProcessingProducerService videoProcessingProducerService;
    private final YouTubeProcessingHelper youTubeProcessingHelper;

    // Configuration
    @Value("${app.youtube.clip-creation.enabled:true}")
    private boolean clipCreationEnabled;

    @Value("${app.youtube.clip-creation.coefficient:3.0}")
    private double clipCoefficient;

    @Value("${app.youtube.clip-creation.timeout:300000}")
    private long clipCreationTimeoutMs;

    @Value("${app.order.processing.clip-creation.retry-attempts:2}")
    private int clipCreationRetryAttempts;

    // ========================================
    // MAIN ENTRY POINTS
    // ========================================

    /**
     * MAIN ENTRY POINT: Queue YouTube order for async processing via Kafka
     * Fast method with proper state management and validation
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void queueYouTubeOrderForProcessing(Long orderId, Long userId) {
        try {
            log.info("Queuing YouTube order for async processing: orderId={}", orderId);

            // Get and validate order
            Order order = orderRepository.findById(orderId)
                    .orElseThrow(() -> new VideoProcessingException("Order not found: " + orderId));

            // Extract and validate video ID using helper
            String videoId = youTubeProcessingHelper.extractVideoId(order.getLink());
            if (videoId == null) {
                throw new VideoProcessingException("Invalid YouTube URL: " + order.getLink());
            }

            // Validate order for processing
            OrderValidationResult validationResult = orderStateManagementService
                    .validateAndUpdateOrderForProcessing(orderId, videoId);

            if (!validationResult.isSuccess()) {
                throw new VideoProcessingException("Order validation failed: " + validationResult.getErrorMessage());
            }

            // Update processing status
            orderStateManagementService.updateProcessingStatus(orderId, 
                    OrderStateManagementService.ProcessingPhase.VALIDATION, 
                    "Order validated and being queued for async processing");

            // Create and send Kafka message
            VideoProcessingMessage message = createProcessingMessage(orderId, videoId, order, userId);
            
            videoProcessingProducerService.sendVideoProcessingMessage(message)
                    .whenComplete((result, ex) -> handleQueueResult(orderId, ex));

            log.info("Order {} queued successfully for async processing via Kafka", orderId);

        } catch (Exception e) {
            log.error("Failed to queue YouTube order processing {}: {}", orderId, e.getMessage(), e);
            orderStateManagementService.transitionToHolding(orderId, 
                    "Failed to queue order: " + e.getMessage());
            throw e;
        }
    }

    /**
     * MAIN PROCESSING: Process YouTube order with complete workflow
     * Orchestrates validation, clip creation, and Binom integration
     */
    public void processYouTubeOrder(Long orderId) {
        try {
            log.info("Starting YouTube order processing for order: {}", orderId);

            // Fast transactional initialization
            OrderProcessingContext context = initializeOrderProcessing(orderId);
            
            if (context == null) {
                log.warn("Order {} initialization failed or not eligible for processing", orderId);
                return;
            }

            // Async processing of long-running operations
            processOrderAsync(context);

        } catch (Exception e) {
            log.error("CRITICAL ERROR: Failed to initiate YouTube order processing {}: {}", orderId, e.getMessage(), e);
            handleProcessingErrorTransactional(orderId, e.getMessage());
        }
    }

    /**
     * LEGACY COMPATIBILITY: Process order from event (YouTubeOrderProcessor compatibility)
     */
    @Async
    @Transactional
    public void processOrderFromEvent(Long orderId, Long userId) {
        try {
            log.info("Processing YouTube order from event: orderId={}, userId={}", orderId, userId);
            
            Order order = orderRepository.findById(orderId).orElse(null);
            if (order == null) {
                log.error("Order not found: orderId={}", orderId);
                return;
            }
            
            // Check if YouTube order
            if (!youTubeProcessingHelper.isYouTubeOrder(order)) {
                log.debug("Not a YouTube order, skipping: orderId={}", orderId);
                return;
            }
            
            // Process YouTube verification and setup
            processYouTubeVerificationLegacy(order);
            
            // Calculate required clicks
            calculateRequiredClicks(order);
            
            // Create Binom campaign
            createBinomCampaignLegacy(order);
            
            // Update order status
            updateOrderStatus(order, OrderStatus.ACTIVE);
            
            log.info("Successfully processed YouTube order: orderId={}", orderId);
            
        } catch (Exception e) {
            log.error("Failed to process YouTube order: orderId={}", orderId, e);
            handleProcessingError(orderId, e);
        }
    }

    // ========================================
    // FAST TRANSACTIONAL METHODS
    // ========================================

    /**
     * FAST TRANSACTIONAL: Initialize order processing
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public OrderProcessingContext initializeOrderProcessing(Long orderId) {
        try {
            // Get and validate order
            Order order = orderRepository.findById(orderId)
                    .orElseThrow(() -> new VideoProcessingException("Order not found: " + orderId));

            if (!order.getStatus().equals(OrderStatus.PENDING)) {
                log.warn("Order {} is not in PENDING status: {}", orderId, order.getStatus());
                return null;
            }

            // Extract and validate YouTube video ID
            String videoId = youTubeProcessingHelper.extractVideoId(order.getLink());
            if (videoId == null) {
                throw new VideoProcessingException("Invalid YouTube URL: " + order.getLink());
            }

            // Update order to PROCESSING status
            order.setYoutubeVideoId(videoId);
            order.setStatus(OrderStatus.PROCESSING);
            order.setUpdatedAt(LocalDateTime.now());
            orderRepository.save(order);

            // Create video processing record
            VideoProcessing videoProcessing = createVideoProcessingRecord(order, videoId);

            log.info("Order {} initialized for processing. Video ID: {}", orderId, videoId);

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
     * FAST TRANSACTIONAL: Create video processing record
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public VideoProcessing createVideoProcessingRecord(Order order, String videoId) {
        VideoProcessing processing = new VideoProcessing();
        processing.setOrder(order);
        processing.setOriginalUrl(order.getLink());
        processing.setVideoId(videoId);
        processing.setVideoType(youTubeProcessingHelper.determineVideoType(order.getLink()));
        processing.setClipCreated(false);
        processing.setProcessingStatus("PROCESSING");
        processing.setProcessingAttempts(1);
        processing.setCreatedAt(LocalDateTime.now());
        processing.setUpdatedAt(LocalDateTime.now());

        return videoProcessingRepository.save(processing);
    }

    /**
     * FAST TRANSACTIONAL: Update order start count
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void updateOrderStartCount(Long orderId, int startCount) {
        try {
            Order order = orderRepository.findById(orderId)
                    .orElseThrow(() -> new VideoProcessingException("Order not found: " + orderId));

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
     * FAST TRANSACTIONAL: Update video processing with clip info
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void updateVideoProcessingWithClip(Long videoProcessingId, ClipCreationResult clipResult) {
        try {
            VideoProcessing videoProcessing = videoProcessingRepository.findById(videoProcessingId)
                    .orElseThrow(() -> new VideoProcessingException("VideoProcessing not found: " + videoProcessingId));

            if (clipResult != null && clipResult.isSuccess()) {
                videoProcessing.setClipCreated(true);
                videoProcessing.setClipUrl(clipResult.getClipUrl());
            } else {
                videoProcessing.setClipCreated(false);
                if (clipResult != null) {
                    videoProcessing.setErrorMessage("Clip creation failed: " + clipResult.getErrorMessage());
                }
            }

            videoProcessing.setProcessingStatus("COMPLETED");
            videoProcessing.setUpdatedAt(LocalDateTime.now());
            videoProcessingRepository.save(videoProcessing);

            log.debug("Updated video processing with clip info: {}", videoProcessingId);

        } catch (Exception e) {
            log.error("Failed to update video processing {}: {}", videoProcessingId, e.getMessage());
            throw e;
        }
    }

    // ========================================
    // ASYNC PROCESSING METHODS
    // ========================================

    /**
     * ASYNC: Process order with long-running operations
     */
    @Async("videoProcessingExecutor")
    public Future<Void> processOrderAsync(OrderProcessingContext context) {
        try {
            log.info("Starting async processing for order: {}", context.getOrderId());

            // PHASE 1: VIDEO ANALYSIS
            orderStateManagementService.updateProcessingStatus(context.getOrderId(),
                    OrderStateManagementService.ProcessingPhase.VIDEO_ANALYSIS,
                    "Retrieving current view count from YouTube API");

            int startCount = youTubeService.getVideoViewCount(context.getVideoId());
            updateOrderStartCount(context.getOrderId(), startCount);

            // PHASE 2: CLIP CREATION
            ClipCreationResult clipResult = null;
            if (clipCreationEnabled) {
                orderStateManagementService.updateProcessingStatus(context.getOrderId(),
                        OrderStateManagementService.ProcessingPhase.CLIP_CREATION,
                        "Creating YouTube clip using Selenium automation");

                clipResult = createYouTubeClipAsync(context);
            }

            updateVideoProcessingWithClip(context.getVideoProcessingId(), clipResult);

            // PHASE 3: BINOM INTEGRATION
            orderStateManagementService.updateProcessingStatus(context.getOrderId(),
                    OrderStateManagementService.ProcessingPhase.BINOM_INTEGRATION,
                    "Setting up Binom campaigns and traffic routing");

            createBinomIntegrationAsync(context, clipResult);

            // PHASE 4: ACTIVATION
            orderStateManagementService.updateProcessingStatus(context.getOrderId(),
                    OrderStateManagementService.ProcessingPhase.ACTIVATION,
                    "Finalizing order and transitioning to active state");

            StateTransitionResult activationResult = orderStateManagementService
                    .transitionToActive(context.getOrderId(), startCount);

            if (!activationResult.isSuccess()) {
                throw new VideoProcessingException("Failed to activate order: " + activationResult.getErrorMessage());
            }

            log.info("Async processing completed successfully for order: {}", context.getOrderId());
            return new AsyncResult<>(null);

        } catch (Exception e) {
            log.error("Async processing failed for order {}: {}", context.getOrderId(), e.getMessage(), e);
            orderStateManagementService.transitionToHolding(context.getOrderId(),
                    "Async processing failed: " + e.getMessage());
            return new AsyncResult<>(null);
        }
    }

    /**
     * ASYNC: Create YouTube clip using Selenium
     */
    public ClipCreationResult createYouTubeClipAsync(OrderProcessingContext context) {
        try {
            log.info("Creating YouTube clip for order {} with video ID: {}", 
                context.getOrderId(), context.getVideoId());

            // Check if clip can be created for this video type
            if (!youTubeProcessingHelper.canCreateClipForUrl(context.getOrderLink())) {
                log.warn("Cannot create clip for video type: {}", context.getOrderLink());
                return ClipCreationResult.failed("Video type does not support clip creation");
            }

            // Select available YouTube account
            YouTubeAccount account = youTubeProcessingHelper.selectAvailableYouTubeAccount();
            if (account == null) {
                log.warn("No available YouTube accounts for clip creation");
                return ClipCreationResult.failed("No available YouTube accounts");
            }

            // Generate clip title
            String clipTitle = youTubeProcessingHelper.generateClipTitle(context.getOrderId());

            // Create clip using Selenium
            String originalVideoUrl = "https://www.youtube.com/watch?v=" + context.getVideoId();
            String clipUrl = seleniumService.createClip(originalVideoUrl, account, clipTitle);

            if (clipUrl == null) {
                return ClipCreationResult.failed("Selenium returned null");
            }

            // Update account usage
            updateAccountUsageTransactional(account.getId());

            log.info("Successfully created clip for order {}: {}", context.getOrderId(), clipUrl);
            return ClipCreationResult.success(clipUrl);

        } catch (Exception e) {
            log.error("Failed to create YouTube clip for order {}: {}", context.getOrderId(), e.getMessage());
            return ClipCreationResult.failed(e.getMessage());
        }
    }

    /**
     * ASYNC: Create Binom integration
     */
    public void createBinomIntegrationAsync(OrderProcessingContext context, ClipCreationResult clipResult) {
        try {
            log.info("Creating Binom integration for order {} with clip: {}", 
                context.getOrderId(), clipResult != null && clipResult.isSuccess());

            // Use clip URL if created, otherwise use original video URL
            String finalTargetUrl = (clipResult != null && clipResult.isSuccess()) 
                ? clipResult.getClipUrl() 
                : context.getOrderLink();

            BinomIntegrationRequest binomRequest = BinomIntegrationRequest.builder()
                    .orderId(context.getOrderId())
                    .targetViews(context.getTargetQuantity())
                    .targetUrl(finalTargetUrl)
                    .clipCreated(clipResult != null && clipResult.isSuccess())
                    .geoTargeting("US") // Default, can be configurable
                    .build();

            // External API call
            BinomIntegrationResponse response = binomService.createBinomIntegration(binomRequest);

            if (!response.isSuccess()) {
                throw new VideoProcessingException("Binom integration failed: " + response.getErrorMessage());
            }

            log.info("Binom integration created successfully for order {}: {} campaigns, {} clicks required", 
                    context.getOrderId(), response.getCampaignIds().size(), response.getTotalClicksRequired());

        } catch (Exception e) {
            log.error("Failed to create Binom integration for order {}: {}", context.getOrderId(), e.getMessage());
            throw new VideoProcessingException("Binom integration failed", e);
        }
    }

    // ========================================
    // LEGACY COMPATIBILITY METHODS
    // ========================================

    /**
     * LEGACY: Process YouTube verification (YouTubeOrderProcessor compatibility)
     */
    private void processYouTubeVerificationLegacy(Order order) {
        try {
            String videoId = youTubeApiService.extractVideoId(order.getLink());
            
            // Verify video exists and is public
            if (!youTubeApiService.verifyVideoExists(videoId)) {
                throw new YouTubeApiException("Video does not exist or is not public: " + videoId);
            }
            
            // Get current view count
            Long viewCount = youTubeApiService.getViewCount(videoId);
            
            // Update order with video details
            order.setYoutubeVideoId(videoId);
            order.setStartCount(viewCount.intValue());
            orderRepository.save(order);
            
            log.info("YouTube verification completed: orderId={}, videoId={}, startCount={}", 
                    order.getId(), videoId, viewCount);
                    
        } catch (Exception e) {
            log.error("YouTube verification failed: orderId={}", order.getId(), e);
            throw new YouTubeApiException("YouTube verification failed for order: " + order.getId(), e);
        }
    }

    /**
     * LEGACY: Calculate required clicks (YouTubeOrderProcessor compatibility)
     */
    private void calculateRequiredClicks(Order order) {
        try {
            BigDecimal coefficient = order.getCoefficient();
            if (coefficient == null) {
                coefficient = determineDefaultCoefficient(order);
                order.setCoefficient(coefficient);
            }
            
            int targetViews = order.getQuantity();
            int requiredClicks = calculateRequiredClicks(targetViews, coefficient);
            
            order.setTargetViews(requiredClicks);
            orderRepository.save(order);
            
            log.info("Calculated required clicks: orderId={}, targetViews={}, coefficient={}, requiredClicks={}", 
                    order.getId(), targetViews, coefficient, requiredClicks);
                    
        } catch (Exception e) {
            log.error("Failed to calculate required clicks: orderId={}", order.getId(), e);
            throw new RuntimeException("Failed to calculate required clicks", e);
        }
    }

    /**
     * LEGACY: Create Binom campaign (YouTubeOrderProcessor compatibility)
     */
    private void createBinomCampaignLegacy(Order order) {
        try {
            boolean hasClip = order.getCoefficient().compareTo(new BigDecimal("3.0")) == 0;
            
            binomService.createCampaign(order, order.getLink(), hasClip);
            
            log.info("Binom campaign created: orderId={}, hasClip={}", order.getId(), hasClip);
            
        } catch (Exception e) {
            log.error("Failed to create Binom campaign: orderId={}", order.getId(), e);
            throw new RuntimeException("Failed to create Binom campaign", e);
        }
    }

    // ========================================
    // VIDEO PROCESSING SERVICE COMPATIBILITY
    // ========================================

    /**
     * Create video processing (VideoProcessingService compatibility)
     */
    @Transactional
    public VideoProcessing createVideoProcessing(Order order) {
        try {
            log.info("Creating video processing for order: {}", order.getId());

            VideoProcessing processing = VideoProcessing.builder()
                    .order(order)
                    .originalUrl(order.getLink())
                    .videoType(youTubeProcessingHelper.determineVideoType(order.getLink()))
                    .status(VideoProcessingStatus.PENDING)
                    .processingAttempts(0)
                    .clipCreated(false)
                    .createdAt(LocalDateTime.now())
                    .build();

            processing = videoProcessingRepository.save(processing);

            // Send to Kafka for async processing
            kafkaTemplate.send("smm.video.processing", processing.getId());

            log.info("Created video processing record {} for order {}", processing.getId(), order.getId());
            return processing;

        } catch (Exception e) {
            log.error("Failed to create video processing for order {}: {}", order.getId(), e.getMessage(), e);
            throw new VideoProcessingException("Failed to create video processing", e);
        }
    }

    /**
     * Process video asynchronously (VideoProcessingService compatibility)
     */
    @Async("asyncExecutor")
    @Transactional
    @Retryable(value = {Exception.class}, maxAttempts = 3, backoff = @Backoff(delay = 5000))
    public CompletableFuture<Void> processVideo(Long processingId) {
        log.info("Starting video processing for ID: {}", processingId);
        
        VideoProcessing processing = videoProcessingRepository.findById(processingId)
                .orElseThrow(() -> new VideoProcessingException("Video processing not found: " + processingId));

        try {
            if (!"PENDING".equals(processing.getProcessingStatus())) {
                log.warn("Video processing {} is not in PENDING state. Current status: {}", 
                        processingId, processing.getProcessingStatus());
                return CompletableFuture.completedFuture(null);
            }

            // Update status to PROCESSING
            processing.setProcessingStatus("PROCESSING");
            processing.setProcessingAttempts(processing.getProcessingAttempts() + 1);
            processing.setLastProcessedAt(LocalDateTime.now());
            processing = videoProcessingRepository.save(processing);
            
            // Process video and create clip
            String targetUrl = processVideoAndCreateClip(processing);
            
            // Create Binom campaigns
            createBinomCampaigns(processing, targetUrl);
            
            // Update status to COMPLETED
            processing.setProcessingStatus("COMPLETED");
            processing.setCompletedAt(LocalDateTime.now());
            videoProcessingRepository.save(processing);
            
            log.info("Successfully completed video processing for ID: {}", processingId);
            return CompletableFuture.completedFuture(null);
            
        } catch (Exception e) {
            log.error("Error processing video {}: {}", processingId, e.getMessage(), e);
            handleProcessingFailure(processing, e);
            throw e;
        }
    }

    // ========================================
    // HELPER METHODS
    // ========================================

    /**
     * FAST TRANSACTIONAL: Update account usage
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void updateAccountUsageTransactional(Long accountId) {
        try {
            YouTubeAccount account = youTubeAccountRepository.findById(accountId)
                    .orElseThrow(() -> new VideoProcessingException("Account not found: " + accountId));

            youTubeProcessingHelper.updateAccountUsage(account);

            log.debug("Updated account usage for account: {}", accountId);

        } catch (Exception e) {
            log.error("Failed to update account usage for account {}: {}", accountId, e.getMessage());
            throw e;
        }
    }

    /**
     * FAST TRANSACTIONAL: Handle processing errors
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
                Optional<VideoProcessing> processingOpt = videoProcessingRepository.findByOrderId(orderId);
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
            log.error("Failed to handle processing error for order {}: {}", orderId, e.getMessage());
        }
    }

    private VideoProcessingMessage createProcessingMessage(Long orderId, String videoId, Order order, Long userId) {
        VideoProcessingMessage message;
        if (isPremiumUser(userId)) {
            message = VideoProcessingMessage.createHighPriorityMessage(
                    orderId, videoId, order.getLink(), order.getQuantity(), userId);
        } else {
            message = VideoProcessingMessage.createStandardMessage(
                    orderId, videoId, order.getLink(), order.getQuantity(), userId);
        }

        message.addMetadata("queued-by", "youtube-processing-service");
        message.addMetadata("queue-timestamp", LocalDateTime.now().toString());
        message.addMetadata("service-id", order.getService() != null ? order.getService().toString() : "unknown");
        message.addMetadata("processing-phase", "queuing");

        return message;
    }

    private void handleQueueResult(Long orderId, Throwable ex) {
        if (ex == null) {
            log.info("Successfully queued order {} for processing", orderId);
            orderStateManagementService.updateProcessingStatus(orderId,
                    OrderStateManagementService.ProcessingPhase.VALIDATION,
                    "Successfully queued in Kafka for async processing");
        } else {
            log.error("Failed to queue order {} for processing: {}", orderId, ex.getMessage(), ex);
            orderStateManagementService.transitionToHolding(orderId, 
                    "Failed to queue order: " + ex.getMessage());
        }
    }

    private String processVideoAndCreateClip(VideoProcessing processing) {
        String targetUrl = processing.getOriginalUrl();
        
        if (clipCreationEnabled && youTubeProcessingHelper.canCreateClipForVideoType(processing.getVideoType())) {
            log.info("Attempting to create clip for processing ID: {}", processing.getId());
            
            for (int attempt = 1; attempt <= clipCreationRetryAttempts; attempt++) {
                try {
                    YouTubeAccount account = youTubeProcessingHelper.selectAvailableYouTubeAccount();
                    if (account == null) {
                        log.warn("No available YouTube accounts for clip creation");
                        break;
                    }
                    
                    String clipUrl = seleniumService.createClip(
                            processing.getOriginalUrl(),
                            account,
                            youTubeProcessingHelper.generateClipTitle(processing.getOrder())
                    );
                    
                    if (clipUrl != null) {
                        processing.setClipCreated(true);
                        processing.setClipUrl(clipUrl);
                        processing.setYoutubeAccountId(account.getId());
                        youTubeProcessingHelper.updateAccountUsage(account);
                        
                        log.info("Successfully created clip {} for processing ID: {}", clipUrl, processing.getId());
                        return clipUrl;
                    }
                } catch (Exception e) {
                    log.warn("Attempt {}/{} failed to create clip for processing ID {}: {}", 
                            attempt, clipCreationRetryAttempts, processing.getId(), e.getMessage());
                    
                    if (attempt == clipCreationRetryAttempts) {
                        throw new VideoProcessingException("Failed to create clip after " + clipCreationRetryAttempts + " attempts", e);
                    }
                    
                    try {
                        Thread.sleep(2000);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new VideoProcessingException("Clip creation interrupted", ie);
                    }
                }
            }
        }
        
        return targetUrl;
    }

    private void createBinomCampaigns(VideoProcessing processing, String targetUrl) {
        try {
            log.info("Creating Binom campaigns for processing ID: {}", processing.getId());
            
            BinomIntegrationRequest request = BinomIntegrationRequest.builder()
                    .orderId(processing.getOrder().getId())
                    .targetUrl(targetUrl)
                    .targetViews(processing.getOrder().getTargetViews())
                    .coefficient(processing.getOrder().getCoefficient())
                    .clipCreated(processing.isClipCreated())
                    .clipUrl(processing.getClipUrl())
                    .geoTargeting(processing.getOrder().getTargetCountry())
                    .build();
            
            BinomIntegrationResponse response = binomService.createBinomIntegration(request);
            
            if (!"SUCCESS".equals(response.getStatus())) {
                throw new VideoProcessingException("Failed to create Binom campaigns: " + response.getMessage());
            }
            
            log.info("Successfully created Binom campaigns for processing ID: {}", processing.getId());
            
        } catch (Exception e) {
            log.error("Error creating Binom campaigns for processing ID {}: {}", 
                    processing.getId(), e.getMessage(), e);
            throw new VideoProcessingException("Failed to create Binom campaigns", e);
        }
    }

    private void handleProcessingFailure(VideoProcessing processing, Exception e) {
        try {
            processing.setProcessingStatus("FAILED");
            processing.setErrorMessage(e.getMessage());
            processing.setLastErrorAt(LocalDateTime.now());
            videoProcessingRepository.save(processing);
            
            if (processing.getProcessingAttempts() >= 3) {
                log.warn("Max attempts reached for video processing {}, continuing without clip", processing.getId());
                try {
                    createBinomCampaigns(processing, processing.getOriginalUrl());
                } catch (Exception ex) {
                    log.error("Failed to create Binom campaigns after max retries: {}", ex.getMessage(), ex);
                }
            }
        } catch (Exception ex) {
            log.error("Error handling processing failure for ID {}: {}", processing.getId(), ex.getMessage(), ex);
        }
    }

    private BigDecimal determineDefaultCoefficient(Order order) {
        if (order.getService() != null && order.getService().getName() != null) {
            String serviceName = order.getService().getName().toLowerCase();
            if (serviceName.contains("clip")) {
                return new BigDecimal("3.0");
            }
        }
        return new BigDecimal("4.0");
    }

    private int calculateRequiredClicks(int targetViews, BigDecimal coefficient) {
        BigDecimal requiredClicks = new BigDecimal(targetViews).multiply(coefficient);
        return requiredClicks.setScale(0, BigDecimal.ROUND_UP).intValue();
    }

    private void updateOrderStatus(Order order, OrderStatus newStatus) {
        OrderStatus oldStatus = order.getStatus();
        order.setStatus(newStatus);
        orderRepository.save(order);
        
        log.info("Order status updated: orderId={}, oldStatus={}, newStatus={}", 
                order.getId(), oldStatus, newStatus);
    }

    private void handleProcessingError(Long orderId, Exception e) {
        try {
            Optional<Order> orderOpt = orderRepository.findById(orderId);
            if (orderOpt.isPresent()) {
                Order order = orderOpt.get();
                order.setErrorMessage("YouTube processing failed: " + e.getMessage());
                order.setStatus(OrderStatus.HOLDING);
                orderRepository.save(order);
                
                log.error("Order processing failed and marked as holding: orderId={}, error={}", 
                        orderId, e.getMessage());
            }
        } catch (Exception saveError) {
            log.error("Failed to update order error status: orderId={}", orderId, saveError);
        }
    }

    private boolean isPremiumUser(Long userId) {
        return userId != null && userId % 10 == 0;
    }

    // ========================================
    // PUBLIC API METHODS
    // ========================================

    public Optional<VideoProcessing> findByOrderId(Long orderId) {
        return videoProcessingRepository.findByOrderId(orderId);
    }

    public VideoProcessing getById(Long id) {
        return videoProcessingRepository.findById(id).orElse(null);
    }

    public VideoProcessing createProcessingRecord(Order order) {
        return createVideoProcessing(order);
    }

    public void startClipCreation(VideoProcessing videoProcessing) {
        log.info("Starting clip creation for video processing {} (delegated)", videoProcessing.getId());
        // Delegate to async processing
        kafkaTemplate.send("smm.video.processing", videoProcessing.getId());
    }

    public void retryProcessing(Long processingId) {
        VideoProcessing processing = videoProcessingRepository.findById(processingId)
                .orElseThrow(() -> new IllegalArgumentException("Video processing not found: " + processingId));
        
        if (processing.getProcessingAttempts() < 3) {
            processing.setProcessingStatus("PENDING");
            processing.setErrorMessage(null);
            videoProcessingRepository.save(processing);
            
            kafkaTemplate.send("smm.video.processing", processingId);
            log.info("Retrying video processing {}", processingId);
        }
    }
}

// ========================================
// SUPPORTING CLASSES - Note: These classes are also used by YouTubeAutomationService
// ========================================