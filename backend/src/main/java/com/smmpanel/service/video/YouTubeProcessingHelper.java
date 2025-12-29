package com.smmpanel.service.video;

import com.smmpanel.entity.Order;
import com.smmpanel.entity.VideoType;
import com.smmpanel.entity.YouTubeAccount;
import com.smmpanel.entity.YouTubeAccountStatus;
import com.smmpanel.repository.jpa.YouTubeAccountRepository;
import java.util.List;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * YouTube Processing Helper Class
 *
 * <p>Contains extracted common utilities for YouTube video processing: - Video ID extraction -
 * Video type determination - YouTube account selection and management - Clip title generation - URL
 * validation
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class YouTubeProcessingHelper {

    private final YouTubeAccountRepository youTubeAccountRepository;
    private final Random random = new Random();

    // YouTube URL pattern for video ID extraction
    private static final Pattern YOUTUBE_URL_PATTERN =
            Pattern.compile(
                    "^https?://(www\\.)?(youtube\\.com/watch\\?v=|youtu\\.be/|youtube\\.com/shorts/|youtube\\.com/live/)([a-zA-Z0-9_-]{11}).*");

    // Clip title templates
    private static final String[] CLIP_TITLE_TEMPLATES = {
        "Amazing moment from this video!",
        "Check out this highlight!",
        "Best part of the video",
        "Must see clip!",
        "Viral moment here",
        "Epic moment compilation",
        "You won't believe this!",
        "Incredible highlight reel"
    };

    /**
     * Extract YouTube video ID from URL Supports: youtube.com/watch?v=, youtu.be/,
     * youtube.com/shorts/, youtube.com/live/
     *
     * @param url YouTube video URL
     * @return 11-character video ID or null if invalid
     */
    public String extractVideoId(String url) {
        if (url == null || url.trim().isEmpty()) {
            return null;
        }

        try {
            Matcher matcher = YOUTUBE_URL_PATTERN.matcher(url.trim());
            if (matcher.matches()) {
                return matcher.group(3);
            }

            log.debug("Failed to extract video ID from URL: {}", url);
            return null;
        } catch (Exception e) {
            log.warn("Error extracting video ID from URL {}: {}", url, e.getMessage());
            return null;
        }
    }

    /**
     * Determine video type from YouTube URL
     *
     * @param url YouTube video URL
     * @return VideoType enum value
     */
    public VideoType determineVideoType(String url) {
        if (url == null) {
            return VideoType.STANDARD;
        }

        String lowerUrl = url.toLowerCase();

        if (lowerUrl.contains("/shorts/")) {
            return VideoType.SHORTS;
        } else if (lowerUrl.contains("/live/")
                || lowerUrl.contains("live_stream")
                || lowerUrl.contains("live")) {
            return VideoType.LIVE;
        } else {
            return VideoType.STANDARD;
        }
    }

    /**
     * Check if URL is a valid YouTube URL
     *
     * @param url URL to validate
     * @return true if valid YouTube URL
     */
    public boolean isValidYouTubeUrl(String url) {
        return extractVideoId(url) != null;
    }

    /**
     * Check if order is a YouTube order
     *
     * @param order Order to check
     * @return true if YouTube order
     */
    public boolean isYouTubeOrder(Order order) {
        return order != null
                && order.getLink() != null
                && (order.getLink().contains("youtube.com")
                        || order.getLink().contains("youtu.be"));
    }

    /**
     * Select available YouTube account for clip creation Considers daily limits and account status
     *
     * @return Available YouTube account or null if none available
     */
    public YouTubeAccount selectAvailableYouTubeAccount() {
        List<YouTubeAccount> activeAccounts =
                youTubeAccountRepository.findByStatus(YouTubeAccountStatus.ACTIVE);

        if (activeAccounts.isEmpty()) {
            log.warn("No active YouTube accounts available for clip creation");
            return null;
        }

        // Select random available account for load balancing
        return activeAccounts.get(random.nextInt(activeAccounts.size()));
    }

    /**
     * Alternative account selection using repository method (for compatibility)
     *
     * @return Available YouTube account or null
     */
    public YouTubeAccount selectAvailableYouTubeAccountLegacy() {
        return youTubeAccountRepository.findFirstByStatus(YouTubeAccountStatus.ACTIVE).orElse(null);
    }

    /**
     * Update YouTube account usage after clip creation
     *
     * @param account Account to update
     */
    public void updateAccountUsage(YouTubeAccount account) {
        if (account == null) {
            log.warn("Cannot update usage for null account");
            return;
        }

        try {
            account.setTotalClipsCreated(account.getTotalClipsCreated() + 1);
            youTubeAccountRepository.save(account);

            log.debug(
                    "Updated account usage for account {}: total clips = {}",
                    account.getId(),
                    account.getTotalClipsCreated());
        } catch (Exception e) {
            log.error(
                    "Failed to update account usage for account {}: {}",
                    account.getId(),
                    e.getMessage(),
                    e);
            throw e;
        }
    }

    /**
     * Generate clip title for YouTube clip Uses templates with order-based selection for
     * consistency
     *
     * @param orderId Order ID for consistent title selection
     * @return Generated clip title
     */
    public String generateClipTitle(Long orderId) {
        if (orderId == null) {
            return CLIP_TITLE_TEMPLATES[random.nextInt(CLIP_TITLE_TEMPLATES.length)];
        }

        // Use order ID for consistent title selection
        int index = (int) (orderId % CLIP_TITLE_TEMPLATES.length);
        return CLIP_TITLE_TEMPLATES[index];
    }

    /**
     * Generate clip title from order object
     *
     * @param order Order object
     * @return Generated clip title
     */
    public String generateClipTitle(Order order) {
        return generateClipTitle(order != null ? order.getId() : null);
    }

    /**
     * Generate random clip title
     *
     * @return Random clip title
     */
    public String generateRandomClipTitle() {
        return CLIP_TITLE_TEMPLATES[random.nextInt(CLIP_TITLE_TEMPLATES.length)];
    }

    /**
     * Check if video type allows clip creation
     *
     * @param videoType Video type to check
     * @return true if clips can be created
     */
    public boolean canCreateClipForVideoType(VideoType videoType) {
        // Cannot create clips from Shorts or Live streams
        return videoType != VideoType.SHORTS && videoType != VideoType.LIVE;
    }

    /**
     * Check if video allows clip creation based on URL
     *
     * @param url YouTube video URL
     * @return true if clips can be created
     */
    public boolean canCreateClipForUrl(String url) {
        VideoType videoType = determineVideoType(url);
        return canCreateClipForVideoType(videoType);
    }

    /**
     * Validate YouTube video URL format and extractability
     *
     * @param url URL to validate
     * @return true if URL is valid and video ID can be extracted
     */
    public boolean validateYouTubeUrl(String url) {
        if (url == null || url.trim().isEmpty()) {
            return false;
        }

        // Check URL format and video ID extraction
        String videoId = extractVideoId(url);
        if (videoId == null) {
            return false;
        }

        // Basic validation of video ID format (11 characters, alphanumeric + _ -)
        return videoId.length() == 11 && videoId.matches("[a-zA-Z0-9_-]+");
    }

    /**
     * Build YouTube video URL from video ID
     *
     * @param videoId 11-character YouTube video ID
     * @return Full YouTube URL
     */
    public String buildYouTubeUrl(String videoId) {
        if (videoId == null || videoId.length() != 11) {
            throw new IllegalArgumentException("Invalid video ID: " + videoId);
        }

        return "https://www.youtube.com/watch?v=" + videoId;
    }

    /**
     * Extract domain from YouTube URL for logging/analytics
     *
     * @param url YouTube URL
     * @return Domain (youtube.com, youtu.be, etc.)
     */
    public String extractDomain(String url) {
        if (url == null) {
            return null;
        }

        try {
            if (url.contains("youtube.com")) {
                return "youtube.com";
            } else if (url.contains("youtu.be")) {
                return "youtu.be";
            } else {
                return "unknown";
            }
        } catch (Exception e) {
            log.warn("Error extracting domain from URL {}: {}", url, e.getMessage());
            return "unknown";
        }
    }

    /**
     * Get account availability status
     *
     * @return Summary of account availability
     */
    public AccountAvailabilitySummary getAccountAvailabilitySummary() {
        List<YouTubeAccount> allAccounts = youTubeAccountRepository.findAll();
        long activeAccounts =
                allAccounts.stream()
                        .filter(acc -> acc.getStatus() == YouTubeAccountStatus.ACTIVE)
                        .count();

        return AccountAvailabilitySummary.builder()
                .totalAccounts(allAccounts.size())
                .activeAccounts((int) activeAccounts)
                .availableAccounts((int) activeAccounts)
                .build();
    }

    /** Account availability summary DTO */
    @lombok.Builder
    @lombok.Data
    public static class AccountAvailabilitySummary {
        private int totalAccounts;
        private int activeAccounts;
        private int availableAccounts;
    }
}
