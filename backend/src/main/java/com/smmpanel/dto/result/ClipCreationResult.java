package com.smmpanel.dto.result;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Comprehensive result object for clip creation operations. Includes eligibility verification,
 * detection results, and creation attempts.
 */
@Builder
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ClipCreationResult {
    private boolean success;
    private String clipUrl;
    private String errorMessage;
    private String failureType;

    // Enhanced fields for Selenium integration
    private String seleniumSessionId;
    private String processingNode;
    private Integer retryCount;
    private java.time.LocalDateTime lastRetryTimestamp;

    // Additional processing metadata
    private String sourceVideoId;
    private java.util.List<String> clipUrls;
    private Integer totalClipsCreated;
    private Long processingTimeMs;

    // Eligibility verification fields
    private boolean eligible;
    private String eligibilityReasonCode;
    private String eligibilityReason;
    private Long videoDuration;
    private Boolean clipsDisabledByCreator;
    private Boolean ageRestricted;

    // Detection result fields
    private boolean clipButtonDetected;
    private String detectionMethod;
    private String menuXPath;
    private String clipButtonXPath;
    private Long detectionTimeMs;

    // Creation attempt tracking
    private int attemptNumber;
    private String strategy; // "primary" or "fallback"
    private String accountUsed;

    // Static factory methods for different scenarios

    public static ClipCreationResult success(String clipUrl) {
        return ClipCreationResult.builder()
                .success(true)
                .clipUrl(clipUrl)
                .clipUrls(java.util.List.of(clipUrl))
                .totalClipsCreated(1)
                .retryCount(0)
                .eligible(true)
                .clipButtonDetected(true)
                .build();
    }

    public static ClipCreationResult failed(String errorMessage) {
        return ClipCreationResult.builder()
                .success(false)
                .errorMessage(errorMessage)
                .totalClipsCreated(0)
                .clipUrls(java.util.List.of())
                .retryCount(0)
                .build();
    }

    public static ClipCreationResult failed(String failureType, String errorMessage) {
        return ClipCreationResult.builder()
                .success(false)
                .failureType(failureType)
                .errorMessage(errorMessage)
                .totalClipsCreated(0)
                .clipUrls(java.util.List.of())
                .retryCount(0)
                .build();
    }

    // Eligibility-specific factory methods

    public static ClipCreationResult ineligible(String reasonCode, String reason) {
        return ClipCreationResult.builder()
                .success(false)
                .eligible(false)
                .eligibilityReasonCode(reasonCode)
                .eligibilityReason(reason)
                .failureType("INELIGIBLE")
                .errorMessage(reason)
                .totalClipsCreated(0)
                .clipUrls(java.util.List.of())
                .build();
    }

    public static ClipCreationResult videoTooShort(Long duration) {
        return ClipCreationResult.builder()
                .success(false)
                .eligible(false)
                .eligibilityReasonCode("VIDEO_TOO_SHORT")
                .eligibilityReason("Video must be at least 60 seconds for clip creation")
                .videoDuration(duration)
                .failureType("VIDEO_TOO_SHORT")
                .errorMessage("Video duration " + duration + "s is too short for clips")
                .totalClipsCreated(0)
                .clipUrls(java.util.List.of())
                .build();
    }

    public static ClipCreationResult clipsDisabled() {
        return ClipCreationResult.builder()
                .success(false)
                .eligible(false)
                .eligibilityReasonCode("CLIPS_DISABLED")
                .eligibilityReason("Creator has disabled clips for this video")
                .clipsDisabledByCreator(true)
                .failureType("CLIPS_DISABLED")
                .errorMessage("Clips are disabled by the video creator")
                .totalClipsCreated(0)
                .clipUrls(java.util.List.of())
                .build();
    }

    public static ClipCreationResult ageRestricted() {
        return ClipCreationResult.builder()
                .success(false)
                .eligible(false)
                .eligibilityReasonCode("AGE_RESTRICTED")
                .eligibilityReason("Cannot create clips for age-restricted content")
                .ageRestricted(true)
                .failureType("AGE_RESTRICTED")
                .errorMessage("Video is age-restricted")
                .totalClipsCreated(0)
                .clipUrls(java.util.List.of())
                .build();
    }

    // Detection-specific factory methods

    public static ClipCreationResult clipButtonNotDetected(String detectionMethod) {
        return ClipCreationResult.builder()
                .success(false)
                .eligible(true)
                .clipButtonDetected(false)
                .detectionMethod(detectionMethod)
                .failureType("BUTTON_NOT_DETECTED")
                .errorMessage("Clip creation button not found on page")
                .totalClipsCreated(0)
                .clipUrls(java.util.List.of())
                .build();
    }

    public static ClipCreationResult detectionTimeout(long detectionTimeMs) {
        return ClipCreationResult.builder()
                .success(false)
                .eligible(true)
                .clipButtonDetected(false)
                .detectionTimeMs(detectionTimeMs)
                .failureType("DETECTION_TIMEOUT")
                .errorMessage("Timeout while detecting clip button after " + detectionTimeMs + "ms")
                .totalClipsCreated(0)
                .clipUrls(java.util.List.of())
                .build();
    }

    // Selenium-specific factory methods

    public static ClipCreationResult seleniumError(String sessionId, String error) {
        return ClipCreationResult.builder()
                .success(false)
                .seleniumSessionId(sessionId)
                .failureType("SELENIUM_ERROR")
                .errorMessage("Selenium error: " + error)
                .totalClipsCreated(0)
                .clipUrls(java.util.List.of())
                .build();
    }

    public static ClipCreationResult noAccountAvailable() {
        return ClipCreationResult.builder()
                .success(false)
                .failureType("NO_ACCOUNT_AVAILABLE")
                .errorMessage("No YouTube accounts available for clip creation")
                .totalClipsCreated(0)
                .clipUrls(java.util.List.of())
                .build();
    }

    // Helper methods

    public boolean isEligible() {
        return eligible;
    }

    public boolean shouldAdjustCoefficient() {
        // Coefficient should be adjusted to 4.0 when clip creation fails
        return !success && eligible;
    }

    public boolean isRetryable() {
        // Determine if the failure is retryable
        return failureType != null
                && (failureType.equals("SELENIUM_ERROR")
                        || failureType.equals("DETECTION_TIMEOUT")
                        || failureType.equals("BUTTON_NOT_DETECTED"));
    }

    public boolean isPermanentFailure() {
        // These failures won't change with retries
        return failureType != null
                && (failureType.equals("VIDEO_TOO_SHORT")
                        || failureType.equals("CLIPS_DISABLED")
                        || failureType.equals("AGE_RESTRICTED")
                        || failureType.equals("INELIGIBLE")
                        || failureType.equals("SHORTS_NOT_SUPPORTED"));
    }
}
