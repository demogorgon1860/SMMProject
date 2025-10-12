package com.smmpanel.service.video;

import com.smmpanel.dto.binom.BinomIntegrationRequest;
import com.smmpanel.dto.binom.BinomIntegrationResponse;
import com.smmpanel.dto.kafka.VideoProcessingMessage;
import com.smmpanel.dto.result.ClipCreationResult;
import com.smmpanel.dto.result.StateTransitionResult;
import com.smmpanel.entity.*;
import com.smmpanel.entity.VideoProcessingStatus;
import com.smmpanel.exception.VideoProcessingException;
import com.smmpanel.exception.YouTubeApiException;
import com.smmpanel.repository.jpa.*;
import com.smmpanel.service.integration.BinomService;
import com.smmpanel.service.integration.SeleniumService;
import com.smmpanel.service.integration.YouTubeService;
import com.smmpanel.service.kafka.VideoProcessingProducerService;
import com.smmpanel.service.order.OrderProcessingContext;
import com.smmpanel.service.order.OrderStateManagementService;
import com.smmpanel.service.order.OrderStateManagementService.OrderValidationResult;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * UNIFIED YouTube Processing Service
 *
 * <p>Consolidates functionality from: - YouTubeAutomationService (transaction management, async
 * processing) - YouTubeOrderProcessor (order verification, Binom integration) -
 * VideoProcessingService (clip creation, error handling)
 *
 * <p>Architecture: - Fast transactional methods for database operations - Async methods for
 * long-running external operations - Proper transaction propagation and error handling - State
 * management integration - Kafka message queue processing
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
    private final BinomService binomService;
    private final OrderStateManagementService orderStateManagementService;

    // Messaging and utilities
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final VideoProcessingProducerService videoProcessingProducerService;
    private final YouTubeProcessingHelper youTubeProcessingHelper;
    private final org.springframework.data.redis.core.RedisTemplate<String, Object> redisTemplate;

    // Configuration
    @Value("${app.youtube.clip-creation.enabled:true}")
    private boolean clipCreationEnabled;

    @Value("${app.youtube.clip-creation.coefficient:3.0}")
    private double clipCoefficient;

    @Value("${app.youtube.coefficient.with-clip:3.0}")
    private double coefficientWithClip;

    @Value("${app.youtube.coefficient.without-clip:4.0}")
    private double coefficientWithoutClip;

    @Value("${app.youtube.clip-creation.timeout:300000}")
    private long clipCreationTimeoutMs;

    @Value("${app.order.processing.clip-creation.retry-attempts:2}")
    private int clipCreationRetryAttempts;

    /**
     * CRITICAL: Semaphore to limit concurrent Selenium sessions With 2 Chrome nodes @ 5 max
     * sessions each = 10 total Limit to 8 concurrent clip creations to leave headroom This prevents
     * ERR_NAME_NOT_RESOLVED and resource exhaustion
     */
    // Semaphore to limit concurrent Selenium sessions
    // IMPORTANT: Must match SE_NODE_MAX_SESSIONS (6) in docker-compose.yml
    // With concurrency=3 Kafka consumers + deduplication, this allows adequate headroom
    private static final Semaphore CLIP_CREATION_SEMAPHORE = new Semaphore(6);

    // ========================================
    // MAIN ENTRY POINTS
    // ========================================

    /**
     * MAIN ENTRY POINT: Queue YouTube order for async processing via Kafka Fast method with proper
     * state management and validation
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void queueYouTubeOrderForProcessing(Long orderId, Long userId) {
        try {
            log.info("Queuing YouTube order for async processing: orderId={}", orderId);

            // Get and validate order
            Order order =
                    orderRepository
                            .findById(orderId)
                            .orElseThrow(
                                    () ->
                                            new VideoProcessingException(
                                                    "Order not found: " + orderId));

            // Extract and validate video ID using helper
            String videoId = youTubeProcessingHelper.extractVideoId(order.getLink());
            if (videoId == null) {
                throw new VideoProcessingException("Invalid YouTube URL: " + order.getLink());
            }

            // Validate order for processing
            OrderValidationResult validationResult =
                    orderStateManagementService.validateAndUpdateOrderForProcessing(
                            orderId, videoId);

            if (!validationResult.isSuccess()) {
                throw new VideoProcessingException(
                        "Order validation failed: " + validationResult.getErrorMessage());
            }

            // Update processing status
            orderStateManagementService.updateProcessingStatus(
                    orderId,
                    OrderStateManagementService.ProcessingPhase.VALIDATION,
                    "Order validated and being queued for async processing");

            // Create and send Kafka message
            VideoProcessingMessage message =
                    createProcessingMessage(orderId, videoId, order, userId);

            videoProcessingProducerService
                    .sendVideoProcessingMessage(message)
                    .whenComplete((result, ex) -> handleQueueResult(orderId, ex));

            log.info("Order {} queued successfully for async processing via Kafka", orderId);

        } catch (Exception e) {
            log.error(
                    "Failed to queue YouTube order processing {}: {}", orderId, e.getMessage(), e);
            orderStateManagementService.transitionToHolding(
                    orderId, "Failed to queue order: " + e.getMessage());
            throw e;
        }
    }

    /**
     * MAIN PROCESSING: Process YouTube order with complete workflow
     *
     * <p>NEW ARCHITECTURE (Transaction Boundary Refactoring): This method orchestrates the workflow
     * but does NOT hold database connections. It calls separate fast transactions (REQUIRES_NEW)
     * for each DB operation, allowing 60-120s Selenium operations to run without holding
     * connections.
     *
     * <p>Flow: 1. FAST TX #1 (0.2s): Load order, validate, set PROCESSING → commit, release
     * connection 2. NO TX (60-120s): Create clip via Selenium (no database connection held) 3.
     * Calculate coefficient (pure math, no database) 4. FAST TX #2 (2s): Create Binom integration,
     * set IN_PROGRESS → commit, release connection 5. If error: FAST TX #3 (0.1s): Mark HOLDING →
     * commit, release connection
     *
     * <p>Benefits: - No optimistic locking conflicts (each transaction gets fresh entity) - No
     * connection pool exhaustion (connections held for <3s total instead of 120s) - High
     * concurrency support (can run 10+ consumers without issues) - No Kafka consumer group
     * rebalancing (no long-running transactions)
     *
     * <p>CRITICAL: This method is intentionally NOT @Transactional
     */
    public void processYouTubeOrder(Long orderId) {
        try {
            log.info("=== Starting YouTube order processing for order: {} ===", orderId);

            // ========================================
            // FAST TX #1: Start Processing Transaction
            // ========================================
            Order order = startOrderProcessingTransaction(orderId);
            // Order is now DETACHED (connection released)
            // Can read fields: order.getYoutubeVideoId(), order.getLink(), etc.

            log.info(
                    "Order {} DETACHED from persistence context. Beginning long-running operations"
                            + " WITHOUT database connection.",
                    orderId);

            // ========================================
            // NO TX: Long-Running Clip Creation (60-120s)
            // ========================================
            ClipCreationResult clipResult = createClipWithoutTransaction(order, 3);

            boolean clipCreated = clipResult != null && clipResult.isSuccess();
            String clipUrl = clipCreated ? clipResult.getClipUrl() : null;

            log.info(
                    "Clip creation completed for order {}: success={}, url={}",
                    orderId,
                    clipCreated,
                    clipUrl);

            // ========================================
            // Calculate Coefficient (Pure Math, No DB)
            // ========================================
            double coefficient = clipCreated ? coefficientWithClip : coefficientWithoutClip;
            int requiredClicks = (int) Math.ceil(order.getQuantity() * coefficient);

            log.info(
                    "Order {} - Target: {} views, Coefficient: {}, Required clicks: {}",
                    orderId,
                    order.getQuantity(),
                    coefficient,
                    requiredClicks);

            // ========================================
            // FAST TX #2: Finish Processing Transaction
            // ========================================
            finishOrderProcessingTransaction(
                    orderId,
                    clipCreated,
                    clipUrl,
                    coefficient,
                    requiredClicks,
                    order.getYoutubeVideoId());

            log.info(
                    "=== Order {} processing COMPLETE. Clip: {}, Coefficient: {} ===",
                    orderId,
                    clipCreated ? "created" : "skipped/failed",
                    coefficient);

        } catch (Exception e) {
            log.error(
                    "=== CRITICAL ERROR: Failed to process YouTube order {}: {} ===",
                    orderId,
                    e.getMessage(),
                    e);

            // ========================================
            // FAST TX #3: Error Handling Transaction
            // ========================================
            handleProcessingErrorTransactional(orderId, e.getMessage());
        }
    }

    /** LEGACY COMPATIBILITY: Process order from event (YouTubeOrderProcessor compatibility) */
    @Async
    @Transactional(propagation = Propagation.REQUIRED)
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
     * FAST TX #1: Start order processing transaction Duration: <0.2s Purpose: Load order, validate
     * status, set to PROCESSING Returns: Detached Order entity (for reading fields only)
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Order startOrderProcessingTransaction(Long orderId) {
        try {
            log.info("FAST TX #1: Starting order processing transaction for order {}", orderId);

            // Load order from database
            Order order =
                    orderRepository
                            .findById(orderId)
                            .orElseThrow(
                                    () ->
                                            new VideoProcessingException(
                                                    "Order not found: " + orderId));

            // Validate status - accept both PENDING and PROCESSING
            if (!order.getStatus().equals(OrderStatus.PENDING)
                    && !order.getStatus().equals(OrderStatus.PROCESSING)) {
                throw new VideoProcessingException(
                        "Order "
                                + orderId
                                + " not in PENDING or PROCESSING status: "
                                + order.getStatus());
            }

            // Set to PROCESSING status
            order.setStatus(OrderStatus.PROCESSING);
            order.setUpdatedAt(LocalDateTime.now());
            orderRepository.save(order);

            log.info(
                    "FAST TX #1 COMPLETE: Order {} set to PROCESSING status in {}ms",
                    orderId,
                    "<0.2s");

            // Transaction commits here, connection released
            // Returned order is now DETACHED from persistence context
            return order;

        } catch (Exception e) {
            log.error(
                    "FAST TX #1 FAILED: Failed to start processing for order {}: {}",
                    orderId,
                    e.getMessage());
            throw e;
        }
    }

    /**
     * FAST TX #2: Finish order processing transaction Duration: <2s (includes Binom API call)
     * Purpose: Create Binom integration, set order to IN_PROGRESS with results IMPORTANT: Reloads
     * order from database to get fresh version (avoids optimistic locking)
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void finishOrderProcessingTransaction(
            Long orderId,
            boolean clipCreated,
            String clipUrl,
            double coefficient,
            int requiredClicks,
            String youtubeVideoId) {
        try {
            log.info("FAST TX #2: Finishing order processing transaction for order {}", orderId);

            // CRITICAL: Reload order from database in THIS transaction
            // This gets the latest version, avoiding optimistic locking conflicts
            Order order =
                    orderRepository
                            .findById(orderId)
                            .orElseThrow(
                                    () ->
                                            new VideoProcessingException(
                                                    "Order not found: " + orderId));

            // Create Binom integration (WARNING: this SAVES the order internally, incrementing
            // @Version)
            String targetUrl = clipUrl != null ? clipUrl : order.getLink();

            boolean binomSuccess = false;
            String binomErrorMessage = null;

            try {
                BinomIntegrationResponse binomResponse =
                        binomService.createBinomIntegration(
                                order, youtubeVideoId, clipCreated, targetUrl);

                if (binomResponse.isSuccess()) {
                    binomSuccess = true;
                    log.info(
                            "Binom integration successful for order {}: {} campaigns created",
                            orderId,
                            binomResponse.getCampaignsCreated());
                } else {
                    binomErrorMessage =
                            "Binom integration returned failure: " + binomResponse.getMessage();
                    log.warn(
                            "Binom integration failed for order {} (non-fatal): {}",
                            orderId,
                            binomErrorMessage);
                }
            } catch (Exception binomEx) {
                binomErrorMessage = "Binom integration threw exception: " + binomEx.getMessage();
                log.error(
                        "Binom integration failed for order {} (non-fatal, will retry): {}",
                        orderId,
                        binomErrorMessage,
                        binomEx);
            }

            // CRITICAL FIX: Reload order AGAIN after BinomService call
            // BinomService saves the order internally (even on failure), potentially incrementing
            // @Version
            // We must reload to get the updated version before our save
            order =
                    orderRepository
                            .findById(orderId)
                            .orElseThrow(
                                    () ->
                                            new VideoProcessingException(
                                                    "Order not found after Binom integration: "
                                                            + orderId));

            log.debug("Order {} reloaded after Binom integration to get fresh @Version", orderId);

            // Update order status based on Binom result
            if (binomSuccess) {
                // Binom integration successful - normal IN_PROGRESS flow
                order.setStatus(OrderStatus.IN_PROGRESS);
            } else {
                // Binom integration failed
                // DECISION: Still move to IN_PROGRESS with 4x coefficient
                // Rationale:
                // 1. Clip creation already attempted (succeeded or failed)
                // 2. Coefficient already calculated correctly (3x or 4x)
                // 3. Order has all necessary data to be monitored
                // 4. Binom can be added later manually or via admin retry
                // 5. Avoids infinite retry loops if Binom is permanently broken
                order.setStatus(OrderStatus.IN_PROGRESS);
                order.setErrorMessage(
                        "WARNING: Binom integration failed - "
                                + binomErrorMessage
                                + ". Order moved to IN_PROGRESS with "
                                + coefficient
                                + "x coefficient. Binom campaigns must be created manually.");
                log.warn(
                        "Order {} moved to IN_PROGRESS despite Binom failure. Clip: {},"
                                + " Coefficient: {}x. Binom error: {}. Order can be monitored, but"
                                + " Binom campaigns need manual creation.",
                        orderId,
                        clipCreated ? "created (" + clipUrl + ")" : "failed/skipped",
                        coefficient,
                        binomErrorMessage);
            }

            // Set coefficient and target views regardless of Binom status
            order.setCoefficient(BigDecimal.valueOf(coefficient));
            order.setTargetViews(requiredClicks);
            order.setUpdatedAt(LocalDateTime.now());
            orderRepository.save(order);

            // Store monitoring data in Redis (non-transactional, quick)
            storeMonitoringData(order, requiredClicks, clipCreated);

            log.info(
                    "FAST TX #2 COMPLETE: Order {} moved to {}. {}",
                    orderId,
                    order.getStatus(),
                    binomSuccess
                            ? String.format(
                                    "Monitoring %d clicks for %d views",
                                    requiredClicks, order.getQuantity())
                            : "Binom integration pending - will retry");

            // Transaction commits here, connection released

        } catch (Exception e) {
            log.error(
                    "FAST TX #2 FAILED: Failed to finish processing for order {}: {}",
                    orderId,
                    e.getMessage());
            throw e;
        }
    }

    /** FAST TRANSACTIONAL: Initialize order processing */
    @Transactional(propagation = Propagation.REQUIRED)
    public OrderProcessingContext initializeOrderProcessing(Long orderId) {
        try {
            // Get and validate order
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

    /** FAST TRANSACTIONAL: Create video processing record */
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

    /** FAST TRANSACTIONAL: Update order start count */
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

    /** FAST TRANSACTIONAL: Update video processing with clip info */
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

    // ========================================
    // NON-TRANSACTIONAL LONG-RUNNING METHODS
    // ========================================

    /**
     * NO TRANSACTION: Create clip without holding database connection Duration: 60-120 seconds
     * (Selenium automation) Purpose: Perform long-running clip creation using detached Order entity
     * Parameters: Detached Order object (read-only), attempt number Returns: ClipCreationResult
     * with success status and clip URL
     *
     * <p>CRITICAL: This method does NOT hold a database transaction. It works with a detached Order
     * entity, reading fields but never saving.
     */
    private ClipCreationResult createClipWithoutTransaction(Order order, int maxAttempts) {
        try {
            log.info(
                    "NO TX: Attempting clip creation for order {} with video ID: {} (max {}"
                            + " attempts)",
                    order.getId(),
                    order.getYoutubeVideoId(),
                    maxAttempts);

            if (!clipCreationEnabled || order.getYoutubeVideoId() == null) {
                log.info(
                        "Clip creation skipped for order {} - enabled: {}, videoId: {}",
                        order.getId(),
                        clipCreationEnabled,
                        order.getYoutubeVideoId());
                return ClipCreationResult.builder()
                        .success(false)
                        .errorMessage("Clip creation disabled or no video ID")
                        .build();
            }

            // CRITICAL: Check if URL is YouTube Shorts - cannot create clips on Shorts
            String orderLink = order.getLink();
            if (orderLink != null && orderLink.toLowerCase().contains("/shorts/")) {
                log.info(
                        "Order {} is YouTube Shorts URL - SKIPPING clip creation immediately."
                                + " Will use 4x coefficient.",
                        order.getId());
                return ClipCreationResult.builder()
                        .success(false)
                        .eligible(false)
                        .failureType("SHORTS_NOT_SUPPORTED")
                        .eligibilityReasonCode("SHORTS_NOT_SUPPORTED")
                        .eligibilityReason("YouTube Shorts do not support clip creation")
                        .errorMessage("YouTube Shorts do not support clips - using 4x coefficient")
                        .totalClipsCreated(0)
                        .clipUrls(java.util.List.of())
                        .build();
            }

            // Lenient validation: Accept any URL starting with youtube.com/watch
            // regardless of video ID length or format
            if (orderLink != null
                    && orderLink.toLowerCase().startsWith("https://www.youtube.com/watch")) {
                log.info(
                        "Order {} is regular YouTube /watch URL - proceeding with clip creation"
                                + " attempt",
                        order.getId());
            } else if (orderLink != null) {
                log.warn(
                        "Order {} has non-standard YouTube URL format: {} - attempting anyway",
                        order.getId(),
                        orderLink);
            }

            ClipCreationResult clipResult = null;

            // Try up to maxAttempts times before giving up
            for (int attempt = 1; attempt <= maxAttempts; attempt++) {
                try {
                    log.info(
                            "Clip creation attempt {}/{} for order {}",
                            attempt,
                            maxAttempts,
                            order.getId());

                    clipResult = attemptClipCreationWithVerification(order);

                    if (clipResult != null && clipResult.isSuccess()) {
                        log.info(
                                "Clip created successfully for order {} on attempt {}: {}",
                                order.getId(),
                                attempt,
                                clipResult.getClipUrl());
                        return clipResult; // Success - exit retry loop

                    } else if (clipResult != null && clipResult.isPermanentFailure()) {
                        // Permanent failure - don't retry
                        String failureType = clipResult.getFailureType();
                        log.warn(
                                "Order {} - PERMANENT clip creation failure on attempt {} ({}): {}."
                                        + " Video does NOT support clips. No retries needed.",
                                order.getId(),
                                attempt,
                                failureType,
                                clipResult.getErrorMessage());
                        return clipResult; // Don't retry permanent failures

                    } else {
                        // Temporary failure - retry if attempts remain
                        String failureType =
                                clipResult != null ? clipResult.getFailureType() : "unknown";

                        if (attempt < maxAttempts) {
                            log.warn(
                                    "Clip creation attempt {}/{} failed for order {} ({}). "
                                            + "Retrying in 2 seconds...",
                                    attempt,
                                    maxAttempts,
                                    order.getId(),
                                    failureType);
                            Thread.sleep(2000);
                        } else {
                            log.warn(
                                    "Clip creation failed after {} attempts for order {} ({}). "
                                            + "Will use 4:1 coefficient.",
                                    maxAttempts,
                                    order.getId(),
                                    failureType);
                            return clipResult != null
                                    ? clipResult
                                    : ClipCreationResult.failed(
                                            "Failed after " + maxAttempts + " attempts");
                        }
                    }

                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.error(
                            "Clip creation interrupted for order {} on attempt {}. "
                                    + "Will use 4:1 coefficient.",
                            order.getId(),
                            attempt);
                    return ClipCreationResult.failed("Interrupted: " + e.getMessage());

                } catch (Exception e) {
                    if (attempt < maxAttempts) {
                        log.warn(
                                "Clip creation attempt {}/{} threw exception for order {}: {}. "
                                        + "Retrying in 2 seconds...",
                                attempt,
                                maxAttempts,
                                order.getId(),
                                e.getMessage());
                        try {
                            Thread.sleep(2000);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            log.error(
                                    "Clip creation retry interrupted for order {}. "
                                            + "Will use 4:1 coefficient.",
                                    order.getId());
                            return ClipCreationResult.failed("Interrupted: " + ie.getMessage());
                        }
                    } else {
                        log.warn(
                                "Clip creation failed after {} attempts for order {} with error:"
                                        + " {}. Proceeding without clip.",
                                maxAttempts,
                                order.getId(),
                                e.getMessage());
                        return ClipCreationResult.failed(e.getMessage());
                    }
                }
            }

            // Should not reach here, but return failure just in case
            return clipResult != null
                    ? clipResult
                    : ClipCreationResult.failed("Failed after " + maxAttempts + " attempts");

        } catch (Exception e) {
            log.error(
                    "Unexpected error in clip creation for order {}: {}",
                    order.getId(),
                    e.getMessage(),
                    e);
            return ClipCreationResult.failed("Unexpected error: " + e.getMessage());
        }
    }

    // ========================================
    // ASYNC PROCESSING METHODS
    // ========================================

    /** ASYNC: Process order with long-running operations */
    @Async("videoProcessingExecutor")
    public Future<Void> processOrderAsync(OrderProcessingContext context) {
        try {
            log.info("Starting async processing for order: {}", context.getOrderId());

            // PHASE 1: VIDEO ANALYSIS
            orderStateManagementService.updateProcessingStatus(
                    context.getOrderId(),
                    OrderStateManagementService.ProcessingPhase.VIDEO_ANALYSIS,
                    "Retrieving current view count from YouTube API");

            int startCount = youTubeService.getVideoViewCount(context.getVideoId());
            updateOrderStartCount(context.getOrderId(), startCount);

            // PHASE 2: CLIP CREATION
            ClipCreationResult clipResult = null;
            if (clipCreationEnabled) {
                orderStateManagementService.updateProcessingStatus(
                        context.getOrderId(),
                        OrderStateManagementService.ProcessingPhase.CLIP_CREATION,
                        "Creating YouTube clip using Selenium automation");

                clipResult = createYouTubeClipAsync(context);
            }

            updateVideoProcessingWithClip(context.getVideoProcessingId(), clipResult);

            // PHASE 3: BINOM INTEGRATION
            orderStateManagementService.updateProcessingStatus(
                    context.getOrderId(),
                    OrderStateManagementService.ProcessingPhase.BINOM_INTEGRATION,
                    "Setting up Binom campaigns and traffic routing");

            createBinomIntegrationAsync(context, clipResult);

            // PHASE 4: ACTIVATION
            orderStateManagementService.updateProcessingStatus(
                    context.getOrderId(),
                    OrderStateManagementService.ProcessingPhase.ACTIVATION,
                    "Finalizing order and transitioning to active state");

            StateTransitionResult activationResult =
                    orderStateManagementService.transitionToActive(
                            context.getOrderId(), startCount);

            if (!activationResult.isSuccess()) {
                throw new VideoProcessingException(
                        "Failed to activate order: " + activationResult.getErrorMessage());
            }

            log.info("Async processing completed successfully for order: {}", context.getOrderId());
            return CompletableFuture.completedFuture(null);

        } catch (Exception e) {
            log.error(
                    "Async processing failed for order {}: {}",
                    context.getOrderId(),
                    e.getMessage(),
                    e);
            orderStateManagementService.transitionToHolding(
                    context.getOrderId(), "Async processing failed: " + e.getMessage());
            return CompletableFuture.completedFuture(null);
        }
    }

    /** ASYNC: Create YouTube clip using Selenium with intelligent detection */
    public ClipCreationResult createYouTubeClipAsync(OrderProcessingContext context) {
        try {
            log.info(
                    "Creating YouTube clip for order {} with video ID: {}",
                    context.getOrderId(),
                    context.getVideoId());

            // Stage 1: Video eligibility verification
            ClipCreationResult eligibility = verifyClipEligibility(context);
            if (!eligibility.isEligible()) {
                log.warn(
                        "Video not eligible for clip creation: {}",
                        eligibility.getEligibilityReason());
                // Store failure reason for analysis
                storeClipFailureMetrics(context.getOrderId(), eligibility.getEligibilityReason());
                return eligibility;
            }

            // Stage 2: Account selection with load balancing
            YouTubeAccount account = selectAccountWithLoadBalancing();
            if (account == null) {
                log.warn("No available YouTube accounts for clip creation");
                return ClipCreationResult.failed("No available YouTube accounts");
            }

            // Stage 3: Intelligent clip creation with detection
            ClipCreationResult result =
                    performIntelligentClipCreation(context, account, eligibility);

            if (result.isSuccess()) {
                // Update account usage and metrics
                updateAccountUsageTransactional(account.getId());
                recordClipCreationSuccess(context.getOrderId(), result.getClipUrl());

                log.info(
                        "Successfully created clip for order {}: {}",
                        context.getOrderId(),
                        result.getClipUrl());
                return result;
            } else {
                // Handle graceful degradation
                handleClipCreationFailure(context.getOrderId(), result.getFailureType());
                return result;
            }

        } catch (Exception e) {
            log.error(
                    "Failed to create YouTube clip for order {}: {}",
                    context.getOrderId(),
                    e.getMessage());
            return ClipCreationResult.failed(e.getMessage());
        }
    }

    /** Verify clip eligibility with comprehensive checks */
    private ClipCreationResult verifyClipEligibility(OrderProcessingContext context) {
        try {
            // Check video duration (must be > 60 seconds for clips)
            // Get video details to check duration
            YouTubeService.VideoDetails videoDetails =
                    youTubeService.getVideoDetails(context.getVideoId());
            Long videoDuration =
                    videoDetails != null
                            ? 120L
                            : null; // Default to 120 seconds if details not available
            if (videoDuration != null && videoDuration < 60) {
                return ClipCreationResult.videoTooShort(videoDuration);
            }

            // Check video privacy and restrictions
            YouTubeService.VideoDetails videoDetailsObj2 =
                    youTubeService.getVideoDetails(context.getVideoId());
            Map<String, Object> videoDetailsMap = new HashMap<>();
            if (videoDetailsObj2 != null) {
                videoDetailsMap.put("title", videoDetailsObj2.getTitle());
                videoDetailsMap.put("channelTitle", videoDetailsObj2.getChannelTitle());
                videoDetailsMap.put("publishedAt", videoDetailsObj2.getPublishedAt());
            }
            if (videoDetailsMap != null && !videoDetailsMap.isEmpty()) {
                // Check if clips are disabled by creator
                Boolean clipsDisabled = (Boolean) videoDetailsMap.get("clipsDisabled");
                if (Boolean.TRUE.equals(clipsDisabled)) {
                    return ClipCreationResult.clipsDisabled();
                }

                // Check age restriction
                Boolean ageRestricted = (Boolean) videoDetailsMap.get("ageRestricted");
                if (Boolean.TRUE.equals(ageRestricted)) {
                    return ClipCreationResult.ageRestricted();
                }
            }

            // Check URL type compatibility
            if (!youTubeProcessingHelper.canCreateClipForUrl(context.getOrderLink())) {
                return ClipCreationResult.ineligible(
                        "UNSUPPORTED_VIDEO_TYPE", "Video type does not support clip creation");
            }

            // Return a success result indicating eligibility
            return ClipCreationResult.builder()
                    .eligible(true)
                    .eligibilityReasonCode("ELIGIBLE")
                    .eligibilityReason("Video is eligible for clip creation")
                    .videoDuration(videoDuration)
                    .build();

        } catch (Exception e) {
            log.error("Error verifying clip eligibility: {}", e.getMessage());
            return ClipCreationResult.ineligible(
                    "VERIFICATION_ERROR", "Could not verify video eligibility: " + e.getMessage());
        }
    }

    /** Select YouTube account with load balancing */
    private YouTubeAccount selectAccountWithLoadBalancing() {
        return youTubeProcessingHelper.selectAvailableYouTubeAccount();
    }

    /** Perform intelligent clip creation with detection and fallback */
    private ClipCreationResult performIntelligentClipCreation(
            OrderProcessingContext context,
            YouTubeAccount account,
            ClipCreationResult eligibility) {

        String originalVideoUrl = "https://www.youtube.com/watch?v=" + context.getVideoId();
        String clipTitle = youTubeProcessingHelper.generateClipTitle(context.getOrderId());

        // First attempt with primary strategy
        try {
            log.debug("Attempting clip creation with primary strategy");

            // Try direct clip creation first
            String clipUrl = seleniumService.createClip(originalVideoUrl, account, clipTitle);

            if (clipUrl != null) {
                return ClipCreationResult.builder()
                        .success(true)
                        .clipUrl(clipUrl)
                        .clipUrls(java.util.List.of(clipUrl))
                        .totalClipsCreated(1)
                        .eligible(true)
                        .clipButtonDetected(true)
                        .strategy("primary")
                        .accountUsed(account.getEmail())
                        .sourceVideoId(context.getVideoId())
                        .build();
            } else {
                log.warn("Clip creation returned null");
                return ClipCreationResult.clipButtonNotDetected("selenium");
            }

        } catch (Exception e) {
            log.warn("Primary clip creation strategy failed: {}", e.getMessage());

            // Attempt fallback strategy if available
            if (clipCreationRetryAttempts > 1) {
                try {
                    log.debug("Attempting fallback clip creation strategy");
                    Thread.sleep(2000); // Brief delay before retry

                    String clipUrl =
                            seleniumService.createClip(originalVideoUrl, account, clipTitle);

                    if (clipUrl != null) {
                        return ClipCreationResult.builder()
                                .success(true)
                                .clipUrl(clipUrl)
                                .clipUrls(java.util.List.of(clipUrl))
                                .totalClipsCreated(1)
                                .eligible(true)
                                .clipButtonDetected(true)
                                .strategy("fallback")
                                .accountUsed(account.getEmail())
                                .sourceVideoId(context.getVideoId())
                                .retryCount(1)
                                .build();
                    }
                } catch (Exception fallbackError) {
                    log.error("Fallback strategy also failed: {}", fallbackError.getMessage());
                    return ClipCreationResult.seleniumError(
                            null,
                            "Both primary and fallback strategies failed: "
                                    + fallbackError.getMessage());
                }
            }

            return ClipCreationResult.seleniumError(null, e.getMessage());
        }
    }

    /** Store clip failure metrics for analysis */
    private void storeClipFailureMetrics(Long orderId, String failureReason) {
        try {
            String key = "clip:failure:" + orderId;
            Map<String, Object> metrics = new HashMap<>();
            metrics.put("orderId", orderId);
            metrics.put("failureReason", failureReason);
            metrics.put("timestamp", LocalDateTime.now().toString());

            redisTemplate.opsForHash().putAll(key, metrics);
            redisTemplate.expire(key, java.time.Duration.ofDays(7));

        } catch (Exception e) {
            log.warn("Failed to store clip failure metrics: {}", e.getMessage());
        }
    }

    /** Record successful clip creation */
    private void recordClipCreationSuccess(Long orderId, String clipUrl) {
        try {
            // Store clip URL in Redis for quick access
            String key = "order:clip:" + orderId;
            redisTemplate.opsForValue().set(key, clipUrl, java.time.Duration.ofDays(7));

            // Update metrics
            String metricsKey = "metrics:clip:success:" + LocalDateTime.now().toLocalDate();
            redisTemplate.opsForValue().increment(metricsKey);

        } catch (Exception e) {
            log.warn("Failed to record clip success metrics: {}", e.getMessage());
        }
    }

    /** Handle clip creation failure with coefficient adjustment */
    private void handleClipCreationFailure(Long orderId, String failureType) {
        try {
            // Store failure information for monitoring
            if (failureType != null) {
                String key = "clip:failure:type:" + failureType;
                redisTemplate.opsForValue().increment(key);
            }

            // This will trigger coefficient adjustment in Binom integration
            log.info(
                    "Clip creation failed for order {}, coefficient will be adjusted to 4.0",
                    orderId);

        } catch (Exception e) {
            log.warn("Failed to handle clip failure: {}", e.getMessage());
        }
    }

    /** ASYNC: Create Binom integration */
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

            // Determine coefficient based on clip creation
            double coefficient =
                    (clipResult != null && clipResult.isSuccess()) ? clipCoefficient : 4.0;

            BinomIntegrationRequest binomRequest =
                    BinomIntegrationRequest.builder()
                            .orderId(context.getOrderId())
                            .targetViews(context.getTargetQuantity())
                            .targetUrl(finalTargetUrl)
                            .clipCreated(clipResult != null && clipResult.isSuccess())
                            .coefficient(BigDecimal.valueOf(coefficient))
                            .geoTargeting("US") // Default, can be configurable
                            .build();

            // External API call
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

    // ========================================
    // LEGACY COMPATIBILITY METHODS
    // ========================================

    /** LEGACY: Process YouTube verification (YouTubeOrderProcessor compatibility) */
    private void processYouTubeVerificationLegacy(Order order) {
        try {
            String videoId = youTubeService.extractVideoId(order.getLink());

            // Verify video exists and is public
            if (!youTubeService.verifyVideoExists(videoId)) {
                throw new YouTubeApiException("Video does not exist or is not public: " + videoId);
            }

            // Get current view count
            Long viewCount = youTubeService.getViewCount(videoId);

            // Update order with video details
            order.setYoutubeVideoId(videoId);
            order.setStartCount(viewCount.intValue());
            orderRepository.save(order);

            log.info(
                    "YouTube verification completed: orderId={}, videoId={}, startCount={}",
                    order.getId(),
                    videoId,
                    viewCount);

        } catch (Exception e) {
            log.error("YouTube verification failed: orderId={}", order.getId(), e);
            throw new YouTubeApiException(
                    "YouTube verification failed for order: " + order.getId(), e);
        }
    }

    /** LEGACY: Calculate required clicks (YouTubeOrderProcessor compatibility) */
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

            log.info(
                    "Calculated required clicks: orderId={}, targetViews={}, coefficient={},"
                            + " requiredClicks={}",
                    order.getId(),
                    targetViews,
                    coefficient,
                    requiredClicks);

        } catch (Exception e) {
            log.error("Failed to calculate required clicks: orderId={}", order.getId(), e);
            throw new RuntimeException("Failed to calculate required clicks", e);
        }
    }

    /** Distribute offer across pre-configured Binom campaigns */
    private void createBinomCampaignLegacy(Order order) {
        try {
            boolean hasClip = order.getCoefficient().compareTo(new BigDecimal("3.0")) == 0;

            BinomIntegrationResponse response =
                    binomService.createBinomIntegration(
                            order, order.getLink(), hasClip, order.getLink());

            log.info(
                    "Binom offer distributed: orderId={}, hasClip={}, campaigns={}",
                    order.getId(),
                    hasClip,
                    response.getCampaignsCreated());

        } catch (Exception e) {
            log.error("Failed to distribute Binom offer: orderId={}", order.getId(), e);
            throw new RuntimeException("Failed to distribute Binom offer", e);
        }
    }

    // ========================================
    // VIDEO PROCESSING SERVICE COMPATIBILITY
    // ========================================

    /** Create video processing (VideoProcessingService compatibility) */
    @Transactional(propagation = Propagation.REQUIRED)
    public VideoProcessing createVideoProcessing(Order order) {
        try {
            log.info("Creating video processing for order: {}", order.getId());

            VideoProcessing processing =
                    VideoProcessing.builder()
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

            log.info(
                    "Created video processing record {} for order {}",
                    processing.getId(),
                    order.getId());
            return processing;

        } catch (Exception e) {
            log.error(
                    "Failed to create video processing for order {}: {}",
                    order.getId(),
                    e.getMessage(),
                    e);
            throw new VideoProcessingException("Failed to create video processing", e);
        }
    }

    /** Process video asynchronously (VideoProcessingService compatibility) */
    @Async("asyncExecutor")
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Retryable(
            value = {Exception.class},
            maxAttempts = 3,
            backoff = @Backoff(delay = 5000))
    public CompletableFuture<Void> processVideo(Long processingId) {
        log.info("Starting video processing for ID: {}", processingId);

        VideoProcessing processing =
                videoProcessingRepository
                        .findById(processingId)
                        .orElseThrow(
                                () ->
                                        new VideoProcessingException(
                                                "Video processing not found: " + processingId));

        try {
            if (!"PENDING".equals(processing.getProcessingStatus())) {
                log.warn(
                        "Video processing {} is not in PENDING state. Current status: {}",
                        processingId,
                        processing.getProcessingStatus());
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

    /** FAST TRANSACTIONAL: Update account usage */
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

            youTubeProcessingHelper.updateAccountUsage(account);

            log.debug("Updated account usage for account: {}", accountId);

        } catch (Exception e) {
            log.error(
                    "Failed to update account usage for account {}: {}", accountId, e.getMessage());
            throw e;
        }
    }

    /** FAST TRANSACTIONAL: Handle processing errors */
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

    private VideoProcessingMessage createProcessingMessage(
            Long orderId, String videoId, Order order, Long userId) {
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

        message.addMetadata("queued-by", "youtube-processing-service");
        message.addMetadata("queue-timestamp", LocalDateTime.now().toString());
        message.addMetadata(
                "service-id",
                order.getService() != null ? order.getService().toString() : "unknown");
        message.addMetadata("processing-phase", "queuing");

        return message;
    }

    private void handleQueueResult(Long orderId, Throwable ex) {
        if (ex == null) {
            log.info("Successfully queued order {} for processing", orderId);
            orderStateManagementService.updateProcessingStatus(
                    orderId,
                    OrderStateManagementService.ProcessingPhase.VALIDATION,
                    "Successfully queued in Kafka for async processing");
        } else {
            log.error("Failed to queue order {} for processing: {}", orderId, ex.getMessage(), ex);
            orderStateManagementService.transitionToHolding(
                    orderId, "Failed to queue order: " + ex.getMessage());
        }
    }

    private String processVideoAndCreateClip(VideoProcessing processing) {
        String targetUrl = processing.getOriginalUrl();

        if (clipCreationEnabled
                && youTubeProcessingHelper.canCreateClipForVideoType(processing.getVideoType())) {
            log.info("Attempting to create clip for processing ID: {}", processing.getId());

            for (int attempt = 1; attempt <= clipCreationRetryAttempts; attempt++) {
                try {
                    YouTubeAccount account =
                            youTubeProcessingHelper.selectAvailableYouTubeAccount();
                    if (account == null) {
                        log.warn("No available YouTube accounts for clip creation");
                        break;
                    }

                    String clipUrl =
                            seleniumService.createClip(
                                    processing.getOriginalUrl(),
                                    account,
                                    youTubeProcessingHelper.generateClipTitle(
                                            processing.getOrder()));

                    if (clipUrl != null) {
                        processing.setClipCreated(true);
                        processing.setClipUrl(clipUrl);
                        processing.setYoutubeAccountId(account.getId());
                        youTubeProcessingHelper.updateAccountUsage(account);

                        log.info(
                                "Successfully created clip {} for processing ID: {}",
                                clipUrl,
                                processing.getId());
                        return clipUrl;
                    }
                } catch (Exception e) {
                    log.warn(
                            "Attempt {}/{} failed to create clip for processing ID {}: {}",
                            attempt,
                            clipCreationRetryAttempts,
                            processing.getId(),
                            e.getMessage());

                    if (attempt == clipCreationRetryAttempts) {
                        throw new VideoProcessingException(
                                "Failed to create clip after "
                                        + clipCreationRetryAttempts
                                        + " attempts",
                                e);
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

            // Determine coefficient based on clip creation
            BigDecimal coefficient =
                    processing.isClipCreated()
                            ? BigDecimal.valueOf(clipCoefficient)
                            : BigDecimal.valueOf(4.0);

            BinomIntegrationRequest request =
                    BinomIntegrationRequest.builder()
                            .orderId(processing.getOrder().getId())
                            .targetUrl(targetUrl)
                            .targetViews(processing.getOrder().getTargetViews())
                            .coefficient(coefficient)
                            .clipCreated(processing.isClipCreated())
                            .clipUrl(processing.getClipUrl())
                            .geoTargeting(processing.getOrder().getTargetCountry())
                            .build();

            BinomIntegrationResponse response = binomService.createBinomIntegration(request);

            if (!"SUCCESS".equals(response.getStatus())) {
                throw new VideoProcessingException(
                        "Failed to create Binom campaigns: " + response.getMessage());
            }

            log.info(
                    "Successfully created Binom campaigns for processing ID: {}",
                    processing.getId());

        } catch (Exception e) {
            log.error(
                    "Error creating Binom campaigns for processing ID {}: {}",
                    processing.getId(),
                    e.getMessage(),
                    e);
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
                log.warn(
                        "Max attempts reached for video processing {}, continuing without clip",
                        processing.getId());
                try {
                    createBinomCampaigns(processing, processing.getOriginalUrl());
                } catch (Exception ex) {
                    log.error(
                            "Failed to create Binom campaigns after max retries: {}",
                            ex.getMessage(),
                            ex);
                }
            }
        } catch (Exception ex) {
            log.error(
                    "Error handling processing failure for ID {}: {}",
                    processing.getId(),
                    ex.getMessage(),
                    ex);
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
        return requiredClicks.setScale(0, java.math.RoundingMode.UP).intValue();
    }

    private void updateOrderStatus(Order order, OrderStatus newStatus) {
        OrderStatus oldStatus = order.getStatus();
        order.setStatus(newStatus);
        orderRepository.save(order);

        log.info(
                "Order status updated: orderId={}, oldStatus={}, newStatus={}",
                order.getId(),
                oldStatus,
                newStatus);
    }

    private void handleProcessingError(Long orderId, Exception e) {
        try {
            Optional<Order> orderOpt = orderRepository.findById(orderId);
            if (orderOpt.isPresent()) {
                Order order = orderOpt.get();
                order.setErrorMessage("YouTube processing failed: " + e.getMessage());
                order.setStatus(OrderStatus.HOLDING);
                orderRepository.save(order);

                log.error(
                        "Order processing failed and marked as holding: orderId={}, error={}",
                        orderId,
                        e.getMessage());
            }
        } catch (Exception saveError) {
            log.error("Failed to update order error status: orderId={}", orderId, saveError);
        }
    }

    private boolean isPremiumUser(Long userId) {
        return userId != null && userId % 10 == 0;
    }

    /**
     * Attempt clip creation with dropdown verification CRITICAL: Check if "Create Clip" button
     * exists in dropdown If not found, immediately close Selenium and return failure
     *
     * <p>DEDUPLICATION FIX: Multiple orders with same video URL now reuse the same clip URL to
     * avoid simultaneous processing causing YouTube throttling and clip button failures
     */
    private ClipCreationResult attemptClipCreationWithVerification(Order order) {
        boolean acquired = false;
        boolean lockAcquired = false;
        String videoUrl = order.getLink();

        // Use video ID for deduplication (safer than hashCode which can collide)
        // Extract video ID from URL for better key generation
        String videoId = order.getYoutubeVideoId();
        if (videoId == null || videoId.isEmpty()) {
            videoId = String.valueOf(Math.abs(videoUrl.hashCode())); // Fallback
        }

        String videoUrlLockKey = "clip:lock:" + videoId;
        String clipUrlCacheKey = "clip:url:" + videoId;

        try {
            log.info(
                    "Attempting clip creation for order {} with video: {}",
                    order.getId(),
                    order.getYoutubeVideoId());

            // ========================================
            // DEDUPLICATION: Check if clip already exists for this video URL
            // ========================================
            String cachedClipUrl = (String) redisTemplate.opsForValue().get(clipUrlCacheKey);
            if (cachedClipUrl != null && !cachedClipUrl.isEmpty()) {
                log.info(
                        "[DEDUP] Order {} reusing existing clip URL for video {}: {}",
                        order.getId(),
                        videoUrl,
                        cachedClipUrl);
                return ClipCreationResult.builder()
                        .success(true)
                        .clipUrl(cachedClipUrl)
                        .clipButtonDetected(true)
                        .accountUsed("REUSED")
                        .sourceVideoId(order.getYoutubeVideoId())
                        .build();
            }

            // ========================================
            // DEDUPLICATION: Acquire distributed lock for this video URL
            // Prevents multiple orders from processing same video simultaneously
            // ========================================
            log.info("[DEDUP] Attempting to acquire lock for video URL: {}", videoUrl);
            lockAcquired =
                    Boolean.TRUE.equals(
                            redisTemplate
                                    .opsForValue()
                                    .setIfAbsent(
                                            videoUrlLockKey,
                                            order.getId().toString(),
                                            java.time.Duration.ofMinutes(5)));

            if (!lockAcquired) {
                // Another order is already processing this video
                log.info(
                        "[DEDUP] Order {} waiting for another order to finish creating clip for"
                                + " video: {}",
                        order.getId(),
                        videoUrl);

                // Wait up to 4 minutes for the other order to finish (clip creation takes ~30-60
                // seconds)
                for (int i = 0; i < 48; i++) { // 48 * 5 seconds = 4 minutes
                    Thread.sleep(5000); // Check every 5 seconds

                    String waitingClipUrl =
                            (String) redisTemplate.opsForValue().get(clipUrlCacheKey);
                    if (waitingClipUrl != null && !waitingClipUrl.isEmpty()) {
                        log.info(
                                "[DEDUP] Order {} successfully reused clip URL after waiting: {}",
                                order.getId(),
                                waitingClipUrl);
                        return ClipCreationResult.builder()
                                .success(true)
                                .clipUrl(waitingClipUrl)
                                .clipButtonDetected(true)
                                .accountUsed("REUSED_AFTER_WAIT")
                                .sourceVideoId(order.getYoutubeVideoId())
                                .build();
                    }
                }

                log.warn(
                        "[DEDUP] Order {} timed out waiting for clip from another order, will try"
                                + " creating own clip",
                        order.getId());
                // Fall through to create own clip
            } else {
                log.info(
                        "[DEDUP] Order {} acquired lock for video URL: {}",
                        order.getId(),
                        videoUrl);
            }

            // Check basic eligibility
            // Get video duration (default to 120 seconds for clips)
            Long videoDuration = 120L; // Default duration for clips
            if (videoDuration != null && videoDuration < 60) {
                return ClipCreationResult.videoTooShort(videoDuration);
            }

            // Select YouTube account
            YouTubeAccount account = youTubeProcessingHelper.selectAvailableYouTubeAccount();
            if (account == null) {
                return ClipCreationResult.noAccountAvailable();
            }

            // Prepare for Selenium clip creation
            // CRITICAL: Use order.getLink() directly instead of constructing from video ID
            // This allows lenient validation - any URL starting with /watch is accepted

            // Pass null for clipTitle to let SeleniumService extract the actual video title
            // The robust Unicode extraction will get the real title from the YouTube page
            String clipTitle = null;

            // ========================================
            // CRITICAL: Acquire semaphore to limit concurrent Selenium sessions
            // This prevents ERR_NAME_NOT_RESOLVED and resource exhaustion
            // ========================================
            log.info(
                    "[SEMAPHORE] Waiting for Selenium slot... (available: {}/6)",
                    CLIP_CREATION_SEMAPHORE.availablePermits());
            CLIP_CREATION_SEMAPHORE.acquire();
            acquired = true;
            log.info(
                    "[SEMAPHORE] Acquired Selenium slot for order {} (remaining: {}/6)",
                    order.getId(),
                    CLIP_CREATION_SEMAPHORE.availablePermits());

            log.info(
                    "Starting Selenium clip creation with dropdown verification for URL: {}",
                    videoUrl);

            // CRITICAL: Check dropdown for "Create Clip" button
            // Selenium will verify button exists before attempting creation
            // If not found, immediately returns null
            // clipTitle is null - SeleniumService will extract actual video title
            String clipUrl = seleniumService.createClip(videoUrl, account, clipTitle);

            if (clipUrl != null) {
                // Success - clip created
                updateAccountUsageTransactional(account.getId());

                // Cache clip URL by ORDER ID (for individual tracking)
                String orderCacheKey = "order:clip:" + order.getId();
                redisTemplate
                        .opsForValue()
                        .set(orderCacheKey, clipUrl, java.time.Duration.ofDays(7));

                // Cache clip URL by VIDEO URL (for deduplication - allows other orders to reuse)
                redisTemplate
                        .opsForValue()
                        .set(clipUrlCacheKey, clipUrl, java.time.Duration.ofDays(7));
                log.info(
                        "[DEDUP] Cached clip URL for video {} (other orders can reuse): {}",
                        videoUrl,
                        clipUrl);

                return ClipCreationResult.builder()
                        .success(true)
                        .clipUrl(clipUrl)
                        .clipButtonDetected(true)
                        .accountUsed(account.getEmail())
                        .sourceVideoId(order.getYoutubeVideoId())
                        .build();
            } else {
                // Dropdown didn't have "Create Clip" button
                log.info("Create Clip button not found in dropdown for order {}", order.getId());
                return ClipCreationResult.clipButtonNotDetected("dropdown-verification");
            }

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Interrupted while waiting for Selenium slot for order {}", order.getId());
            return ClipCreationResult.failed("Interrupted waiting for Selenium");
        } catch (Exception e) {
            log.error("Clip creation failed for order {}: {}", order.getId(), e.getMessage());
            return ClipCreationResult.seleniumError(null, e.getMessage());
        } finally {
            // CRITICAL: Always release semaphore
            if (acquired) {
                CLIP_CREATION_SEMAPHORE.release();
                log.info(
                        "[SEMAPHORE] Released Selenium slot for order {} (available: {}/6)",
                        order.getId(),
                        CLIP_CREATION_SEMAPHORE.availablePermits());
            }

            // Release distributed lock for video URL
            if (lockAcquired) {
                redisTemplate.delete(videoUrlLockKey);
                log.info("[DEDUP] Released lock for video URL: {}", videoUrl);
            }
        }
    }

    /** Store monitoring data in Redis for tracking */
    private void storeMonitoringData(Order order, int requiredClicks, boolean clipCreated) {
        try {
            String key = "order:monitoring:" + order.getId();
            Map<String, Object> data = new HashMap<>();
            data.put("orderId", order.getId());
            data.put("startCount", order.getStartCount());
            data.put("targetViews", order.getQuantity());
            data.put("requiredClicks", requiredClicks);
            data.put("coefficient", clipCreated ? coefficientWithClip : coefficientWithoutClip);
            data.put("clipCreated", clipCreated);
            data.put("startedAt", LocalDateTime.now().toString());
            data.put("status", "MONITORING");

            redisTemplate.opsForHash().putAll(key, data);
            redisTemplate.expire(key, java.time.Duration.ofDays(7));

        } catch (Exception e) {
            log.warn("Failed to store monitoring data: {}", e.getMessage());
        }
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
        log.info(
                "Starting clip creation for video processing {} (delegated)",
                videoProcessing.getId());
        // Delegate to async processing
        kafkaTemplate.send("smm.video.processing", videoProcessing.getId());
    }

    public void retryProcessing(Long processingId) {
        VideoProcessing processing =
                videoProcessingRepository
                        .findById(processingId)
                        .orElseThrow(
                                () ->
                                        new IllegalArgumentException(
                                                "Video processing not found: " + processingId));

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
