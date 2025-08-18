package com.smmpanel.service;

import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.youtube.YouTube;
import com.google.api.services.youtube.model.Video;
import com.google.api.services.youtube.model.VideoListResponse;
import com.smmpanel.exception.YouTubeApiException;
import java.util.Collections;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Enhanced YouTube API Service Provides YouTube video verification and view count retrieval with
 * caching
 */
@Slf4j
@Service
@Transactional(readOnly = true)
public class YouTubeService {

    private static final Pattern VIDEO_ID_PATTERN =
            Pattern.compile(
                    "(?:youtube\\.com/(?:[^/]+/.+/|(?:v|e(?:mbed)?)/|.*[?&]v=)|youtu\\.be/)([^\"&?/\\s]{11})");

    @Value("${app.youtube.api.key}")
    private String apiKey;

    @Value("${app.youtube.cache.ttl:2592000}") // 30 days default
    private long cacheTtlSeconds;

    private final YouTube youtube;
    private final RedisTemplate<String, Object> redisTemplate;

    public YouTubeService(RedisTemplate<String, Object> redisTemplate) throws Exception {
        this.redisTemplate = redisTemplate;
        this.youtube =
                new YouTube.Builder(
                                GoogleNetHttpTransport.newTrustedTransport(),
                                GsonFactory.getDefaultInstance(),
                                null)
                        .setApplicationName("SMM Panel")
                        .build();
    }

    /** Extract video ID from YouTube URL */
    public String extractVideoId(String url) {
        if (url == null || url.trim().isEmpty()) {
            throw new YouTubeApiException("YouTube URL cannot be null or empty");
        }

        Matcher matcher = VIDEO_ID_PATTERN.matcher(url);
        if (matcher.find()) {
            return matcher.group(1);
        }
        throw new YouTubeApiException("Invalid YouTube URL format: " + url);
    }

    /** Get view count for video with Redis caching */
    @Cacheable(value = "youtube-views", key = "#videoId")
    public Long getViewCount(String videoId) {
        if (videoId == null || videoId.trim().isEmpty()) {
            throw new YouTubeApiException("Video ID cannot be null or empty");
        }

        // Check Redis cache first
        String cacheKey = "youtube:views:" + videoId;
        Object cachedValue = redisTemplate.opsForValue().get(cacheKey);
        if (cachedValue != null) {
            log.debug("Cache hit for video view count: {}", videoId);
            return Long.valueOf(cachedValue.toString());
        }

        try {
            YouTube.Videos.List request =
                    youtube.videos()
                            .list(Collections.singletonList("statistics"))
                            .setId(Collections.singletonList(videoId))
                            .setKey(apiKey);

            VideoListResponse response = request.execute();

            if (!response.getItems().isEmpty()) {
                Video video = response.getItems().get(0);
                Long viewCount = video.getStatistics().getViewCount().longValue();

                // Cache the result
                cacheViewCount(videoId, viewCount);

                log.info("Retrieved view count for video: {} = {}", videoId, viewCount);
                return viewCount;
            } else {
                log.warn("No video found with ID: {}", videoId);
                return 0L;
            }
        } catch (Exception e) {
            log.error("Error fetching view count for video: {}", videoId, e);
            throw new YouTubeApiException("Failed to fetch view count for video: " + videoId, e);
        }
    }

    /** Cache view count in Redis */
    private void cacheViewCount(String videoId, Long viewCount) {
        try {
            String cacheKey = "youtube:views:" + videoId;
            redisTemplate.opsForValue().set(cacheKey, viewCount, cacheTtlSeconds, TimeUnit.SECONDS);
            log.debug("Cached view count for video: {} = {}", videoId, viewCount);
        } catch (Exception e) {
            log.warn("Failed to cache view count for video: {}", videoId, e);
        }
    }

    /** Verify video exists and is public */
    public boolean verifyVideoExists(String videoId) {
        if (videoId == null || videoId.trim().isEmpty()) {
            return false;
        }

        try {
            YouTube.Videos.List request =
                    youtube.videos()
                            .list(Collections.singletonList("status"))
                            .setId(Collections.singletonList(videoId))
                            .setKey(apiKey);

            VideoListResponse response = request.execute();

            if (!response.getItems().isEmpty()) {
                Video video = response.getItems().get(0);
                String privacyStatus = video.getStatus().getPrivacyStatus();
                boolean isPublic = "public".equals(privacyStatus);

                log.info(
                        "Video verification: id={}, privacy={}, exists={}",
                        videoId,
                        privacyStatus,
                        isPublic);

                return isPublic;
            } else {
                log.warn("Video not found: {}", videoId);
                return false;
            }
        } catch (Exception e) {
            log.error("Error verifying video: {}", videoId, e);
            return false;
        }
    }

    // Alias for compatibility
    public int getVideoViewCount(String videoId) {
        return getViewCount(videoId).intValue();
    }

    /** Get video details including title and channel */
    public VideoDetails getVideoDetails(String videoId) {
        if (videoId == null || videoId.trim().isEmpty()) {
            throw new YouTubeApiException("Video ID cannot be null or empty");
        }

        try {
            YouTube.Videos.List request =
                    youtube.videos()
                            .list(Collections.singletonList("snippet"))
                            .setId(Collections.singletonList(videoId))
                            .setKey(apiKey);

            VideoListResponse response = request.execute();

            if (!response.getItems().isEmpty()) {
                Video video = response.getItems().get(0);
                return VideoDetails.builder()
                        .videoId(videoId)
                        .title(video.getSnippet().getTitle())
                        .channelTitle(video.getSnippet().getChannelTitle())
                        .publishedAt(video.getSnippet().getPublishedAt().toString())
                        .build();
            } else {
                throw new YouTubeApiException("Video not found: " + videoId);
            }
        } catch (Exception e) {
            log.error("Error fetching video details: {}", videoId, e);
            throw new YouTubeApiException("Failed to fetch video details: " + videoId, e);
        }
    }

    /** Video Details DTO */
    public static class VideoDetails {
        private String videoId;
        private String title;
        private String channelTitle;
        private String publishedAt;

        public VideoDetails() {}

        public VideoDetails(String videoId, String title, String channelTitle, String publishedAt) {
            this.videoId = videoId;
            this.title = title;
            this.channelTitle = channelTitle;
            this.publishedAt = publishedAt;
        }

        public static Builder builder() {
            return new Builder();
        }

        public String getVideoId() {
            return videoId;
        }

        public void setVideoId(String videoId) {
            this.videoId = videoId;
        }

        public String getTitle() {
            return title;
        }

        public void setTitle(String title) {
            this.title = title;
        }

        public String getChannelTitle() {
            return channelTitle;
        }

        public void setChannelTitle(String channelTitle) {
            this.channelTitle = channelTitle;
        }

        public String getPublishedAt() {
            return publishedAt;
        }

        public void setPublishedAt(String publishedAt) {
            this.publishedAt = publishedAt;
        }

        public static class Builder {
            private String videoId;
            private String title;
            private String channelTitle;
            private String publishedAt;

            public Builder videoId(String videoId) {
                this.videoId = videoId;
                return this;
            }

            public Builder title(String title) {
                this.title = title;
                return this;
            }

            public Builder channelTitle(String channelTitle) {
                this.channelTitle = channelTitle;
                return this;
            }

            public Builder publishedAt(String publishedAt) {
                this.publishedAt = publishedAt;
                return this;
            }

            public VideoDetails build() {
                return new VideoDetails(videoId, title, channelTitle, publishedAt);
            }
        }
    }
}
