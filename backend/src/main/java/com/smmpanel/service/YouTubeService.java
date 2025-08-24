package com.smmpanel.service;

import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.youtube.YouTube;
import com.google.api.services.youtube.model.*;
import com.smmpanel.exception.YouTubeApiException;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
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

    @Value("${app.youtube.cache.ttl:600}") // 10 minutes default for stats
    private long cacheTtlSeconds;

    @Value("${app.youtube.quota.daily-limit:10000}")
    private int dailyQuotaLimit;

    @Value("${app.youtube.quota.warning-threshold:0.8}")
    private double quotaWarningThreshold;

    private final AtomicLong quotaUsed = new AtomicLong(0);
    private static final int QUOTA_COST_LIST = 1;
    private static final int QUOTA_COST_SEARCH = 100;
    private static final int QUOTA_COST_INSERT = 1600;
    private static final int QUOTA_COST_UPDATE = 50;
    private static final int QUOTA_COST_RATE = 50;

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

    // ==================== NEW YOUTUBE API V3 METHODS ====================

    /**
     * Enhanced videos.list - Get comprehensive video information Supports all parts: snippet,
     * statistics, status, contentDetails Quota cost: 1 unit
     */
    @Cacheable(value = "youtube-videos", key = "#videoIds.toString() + '-' + #parts.toString()")
    public List<VideoInfo> listVideos(List<String> videoIds, List<String> parts) {
        checkQuota(QUOTA_COST_LIST);

        if (videoIds.size() > 50) {
            throw new YouTubeApiException("Maximum 50 video IDs allowed per request");
        }

        try {
            YouTube.Videos.List request =
                    youtube.videos().list(parts).setId(videoIds).setKey(apiKey).setMaxResults(50L);

            VideoListResponse response = request.execute();
            consumeQuota(QUOTA_COST_LIST);

            return response.getItems().stream()
                    .map(this::mapVideoToInfo)
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.error("Failed to list videos: {}", e.getMessage(), e);
            throw new YouTubeApiException("Failed to list videos", e);
        }
    }

    /** search.list - Search for videos, channels, or playlists Quota cost: 100 units */
    @Cacheable(value = "youtube-search", key = "#query + '-' + #type + '-' + #maxResults")
    public SearchResultList searchYouTube(String query, String type, int maxResults) {
        checkQuota(QUOTA_COST_SEARCH);

        try {
            YouTube.Search.List search =
                    youtube.search()
                            .list(Collections.singletonList("snippet"))
                            .setQ(query)
                            .setKey(apiKey)
                            .setMaxResults((long) Math.min(maxResults, 50));

            if (type != null) {
                search.setType(Collections.singletonList(type));
            }

            SearchListResponse response = search.execute();
            consumeQuota(QUOTA_COST_SEARCH);

            SearchResultList results = new SearchResultList();
            results.setItems(response.getItems());
            results.setNextPageToken(response.getNextPageToken());
            return results;
        } catch (Exception e) {
            log.error("Search failed: {}", e.getMessage(), e);
            throw new YouTubeApiException("Search failed", e);
        }
    }

    /** channels.list - Get channel information Quota cost: 1 unit */
    @Cacheable(value = "youtube-channels", key = "#channelId")
    public ChannelInfo getChannelInfo(String channelId) {
        checkQuota(QUOTA_COST_LIST);

        try {
            YouTube.Channels.List request =
                    youtube.channels()
                            .list(Arrays.asList("snippet", "statistics", "contentDetails"))
                            .setId(Collections.singletonList(channelId))
                            .setKey(apiKey);

            ChannelListResponse response = request.execute();
            consumeQuota(QUOTA_COST_LIST);

            if (!response.getItems().isEmpty()) {
                Channel channel = response.getItems().get(0);
                return mapChannelToInfo(channel);
            }
            return null;
        } catch (Exception e) {
            log.error("Failed to get channel info: {}", e.getMessage(), e);
            throw new YouTubeApiException("Failed to get channel info", e);
        }
    }

    /** playlistItems.list - Get videos from a playlist Quota cost: 1 unit */
    @Cacheable(value = "youtube-playlist-items", key = "#playlistId + '-' + #maxResults")
    public List<PlaylistItem> getPlaylistItems(String playlistId, int maxResults) {
        checkQuota(QUOTA_COST_LIST);

        try {
            YouTube.PlaylistItems.List request =
                    youtube.playlistItems()
                            .list(Arrays.asList("snippet", "contentDetails"))
                            .setPlaylistId(playlistId)
                            .setMaxResults((long) Math.min(maxResults, 50))
                            .setKey(apiKey);

            PlaylistItemListResponse response = request.execute();
            consumeQuota(QUOTA_COST_LIST);

            return response.getItems();
        } catch (Exception e) {
            log.error("Failed to get playlist items: {}", e.getMessage(), e);
            throw new YouTubeApiException("Failed to get playlist items", e);
        }
    }

    /** commentThreads.list - Get top-level comments for a video Quota cost: 1 unit */
    @Cacheable(value = "youtube-comments", key = "#videoId + '-' + #maxResults")
    public List<CommentThread> getVideoComments(String videoId, int maxResults) {
        checkQuota(QUOTA_COST_LIST);

        try {
            YouTube.CommentThreads.List request =
                    youtube.commentThreads()
                            .list(Arrays.asList("snippet", "replies"))
                            .setVideoId(videoId)
                            .setMaxResults((long) Math.min(maxResults, 100))
                            .setKey(apiKey);

            CommentThreadListResponse response = request.execute();
            consumeQuota(QUOTA_COST_LIST);

            return response.getItems();
        } catch (Exception e) {
            log.error("Failed to get video comments: {}", e.getMessage(), e);
            throw new YouTubeApiException("Failed to get video comments", e);
        }
    }

    /** Get channel uploads (using playlist approach) Each channel has an "uploads" playlist */
    public List<PlaylistItem> getChannelUploads(String channelId, int maxVideos) {
        ChannelInfo channelInfo = getChannelInfo(channelId);
        if (channelInfo != null && channelInfo.getUploadsPlaylistId() != null) {
            return getPlaylistItems(channelInfo.getUploadsPlaylistId(), maxVideos);
        }
        return Collections.emptyList();
    }

    /**
     * Batch get video statistics - optimized for multiple videos Reduces quota usage by batching
     * requests
     */
    public Map<String, VideoStatistics> batchGetVideoStatistics(List<String> videoIds) {
        Map<String, VideoStatistics> statsMap = new HashMap<>();

        // Process in batches of 50 (API limit)
        for (int i = 0; i < videoIds.size(); i += 50) {
            List<String> batch = videoIds.subList(i, Math.min(i + 50, videoIds.size()));
            List<VideoInfo> videos = listVideos(batch, Arrays.asList("statistics", "snippet"));

            for (VideoInfo video : videos) {
                statsMap.put(video.getVideoId(), video.getStatistics());
            }
        }

        return statsMap;
    }

    // ==================== QUOTA MANAGEMENT ====================

    private void checkQuota(int units) {
        long currentUsage = quotaUsed.get();
        if (currentUsage + units > dailyQuotaLimit) {
            throw new YouTubeApiException(
                    "YouTube API quota limit exceeded. Used: "
                            + currentUsage
                            + "/"
                            + dailyQuotaLimit);
        }

        if (currentUsage + units > dailyQuotaLimit * quotaWarningThreshold) {
            log.warn(
                    "YouTube API quota usage high: {}/{} ({}%)",
                    currentUsage + units,
                    dailyQuotaLimit,
                    ((currentUsage + units) * 100) / dailyQuotaLimit);
        }
    }

    private void consumeQuota(int units) {
        long newUsage = quotaUsed.addAndGet(units);
        log.debug("Consumed {} quota units. Total today: {}/{}", units, newUsage, dailyQuotaLimit);

        // Store in Redis for persistence
        String quotaKey = "youtube:quota:" + java.time.LocalDate.now();
        redisTemplate.opsForValue().set(quotaKey, newUsage, 25, TimeUnit.HOURS);
    }

    @Scheduled(cron = "0 0 0 * * *") // Reset at midnight
    public void resetDailyQuota() {
        quotaUsed.set(0);
        log.info("YouTube API daily quota reset");
    }

    public long getQuotaUsed() {
        return quotaUsed.get();
    }

    public int getQuotaRemaining() {
        return dailyQuotaLimit - quotaUsed.intValue();
    }

    // ==================== HELPER METHODS ====================

    private VideoInfo mapVideoToInfo(Video video) {
        VideoInfo info = new VideoInfo();
        info.setVideoId(video.getId());

        if (video.getSnippet() != null) {
            info.setTitle(video.getSnippet().getTitle());
            info.setDescription(video.getSnippet().getDescription());
            info.setChannelId(video.getSnippet().getChannelId());
            info.setChannelTitle(video.getSnippet().getChannelTitle());
            info.setPublishedAt(
                    video.getSnippet().getPublishedAt() != null
                            ? video.getSnippet().getPublishedAt().toString()
                            : null);
            info.setTags(video.getSnippet().getTags());
        }

        if (video.getStatistics() != null) {
            VideoStatistics stats = new VideoStatistics();
            stats.setViewCount(
                    video.getStatistics().getViewCount() != null
                            ? video.getStatistics().getViewCount().longValue()
                            : 0L);
            stats.setLikeCount(
                    video.getStatistics().getLikeCount() != null
                            ? video.getStatistics().getLikeCount().longValue()
                            : 0L);
            stats.setCommentCount(
                    video.getStatistics().getCommentCount() != null
                            ? video.getStatistics().getCommentCount().longValue()
                            : 0L);
            info.setStatistics(stats);
        }

        if (video.getStatus() != null) {
            info.setPrivacyStatus(video.getStatus().getPrivacyStatus());
        }

        if (video.getContentDetails() != null) {
            info.setDuration(video.getContentDetails().getDuration());
        }

        return info;
    }

    private ChannelInfo mapChannelToInfo(Channel channel) {
        ChannelInfo info = new ChannelInfo();
        info.setChannelId(channel.getId());

        if (channel.getSnippet() != null) {
            info.setTitle(channel.getSnippet().getTitle());
            info.setDescription(channel.getSnippet().getDescription());
        }

        if (channel.getStatistics() != null) {
            info.setSubscriberCount(
                    channel.getStatistics().getSubscriberCount() != null
                            ? channel.getStatistics().getSubscriberCount().longValue()
                            : 0L);
            info.setVideoCount(
                    channel.getStatistics().getVideoCount() != null
                            ? channel.getStatistics().getVideoCount().longValue()
                            : 0L);
            info.setViewCount(
                    channel.getStatistics().getViewCount() != null
                            ? channel.getStatistics().getViewCount().longValue()
                            : 0L);
        }

        if (channel.getContentDetails() != null
                && channel.getContentDetails().getRelatedPlaylists() != null) {
            info.setUploadsPlaylistId(
                    channel.getContentDetails().getRelatedPlaylists().getUploads());
        }

        return info;
    }

    // ==================== INNER CLASSES ====================

    public static class VideoInfo {
        private String videoId;
        private String title;
        private String description;
        private String channelId;
        private String channelTitle;
        private String publishedAt;
        private List<String> tags;
        private VideoStatistics statistics;
        private String privacyStatus;
        private String duration;

        // Getters and setters
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

        public String getDescription() {
            return description;
        }

        public void setDescription(String description) {
            this.description = description;
        }

        public String getChannelId() {
            return channelId;
        }

        public void setChannelId(String channelId) {
            this.channelId = channelId;
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

        public List<String> getTags() {
            return tags;
        }

        public void setTags(List<String> tags) {
            this.tags = tags;
        }

        public VideoStatistics getStatistics() {
            return statistics;
        }

        public void setStatistics(VideoStatistics statistics) {
            this.statistics = statistics;
        }

        public String getPrivacyStatus() {
            return privacyStatus;
        }

        public void setPrivacyStatus(String privacyStatus) {
            this.privacyStatus = privacyStatus;
        }

        public String getDuration() {
            return duration;
        }

        public void setDuration(String duration) {
            this.duration = duration;
        }
    }

    public static class VideoStatistics {
        private Long viewCount;
        private Long likeCount;
        private Long commentCount;

        public Long getViewCount() {
            return viewCount;
        }

        public void setViewCount(Long viewCount) {
            this.viewCount = viewCount;
        }

        public Long getLikeCount() {
            return likeCount;
        }

        public void setLikeCount(Long likeCount) {
            this.likeCount = likeCount;
        }

        public Long getCommentCount() {
            return commentCount;
        }

        public void setCommentCount(Long commentCount) {
            this.commentCount = commentCount;
        }
    }

    public static class SearchResultList {
        private List<SearchResult> items;
        private String nextPageToken;

        public List<SearchResult> getItems() {
            return items;
        }

        public void setItems(List<SearchResult> items) {
            this.items = items;
        }

        public String getNextPageToken() {
            return nextPageToken;
        }

        public void setNextPageToken(String nextPageToken) {
            this.nextPageToken = nextPageToken;
        }
    }

    public static class ChannelInfo {
        private String channelId;
        private String title;
        private String description;
        private Long subscriberCount;
        private Long videoCount;
        private Long viewCount;
        private String uploadsPlaylistId;

        public String getChannelId() {
            return channelId;
        }

        public void setChannelId(String channelId) {
            this.channelId = channelId;
        }

        public String getTitle() {
            return title;
        }

        public void setTitle(String title) {
            this.title = title;
        }

        public String getDescription() {
            return description;
        }

        public void setDescription(String description) {
            this.description = description;
        }

        public Long getSubscriberCount() {
            return subscriberCount;
        }

        public void setSubscriberCount(Long subscriberCount) {
            this.subscriberCount = subscriberCount;
        }

        public Long getVideoCount() {
            return videoCount;
        }

        public void setVideoCount(Long videoCount) {
            this.videoCount = videoCount;
        }

        public Long getViewCount() {
            return viewCount;
        }

        public void setViewCount(Long viewCount) {
            this.viewCount = viewCount;
        }

        public String getUploadsPlaylistId() {
            return uploadsPlaylistId;
        }

        public void setUploadsPlaylistId(String uploadsPlaylistId) {
            this.uploadsPlaylistId = uploadsPlaylistId;
        }
    }
}
