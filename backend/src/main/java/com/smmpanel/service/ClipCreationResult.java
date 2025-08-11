package com.smmpanel.service;

import lombok.Builder;
import lombok.Data;

/**
 * Result object for clip creation operations. Enhanced with Selenium integration fields and
 * processing metadata.
 */
@Builder
@Data
public class ClipCreationResult {
    private boolean success;
    private String clipUrl;
    private String errorMessage;

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

    public static ClipCreationResult success(String clipUrl) {
        return ClipCreationResult.builder()
                .success(true)
                .clipUrl(clipUrl)
                .clipUrls(java.util.List.of(clipUrl))
                .totalClipsCreated(1)
                .retryCount(0)
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
}
