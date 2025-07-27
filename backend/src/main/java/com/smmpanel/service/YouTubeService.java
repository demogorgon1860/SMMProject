package com.smmpanel.service;

import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.youtube.YouTube;
import com.google.api.services.youtube.model.Video;
import com.google.api.services.youtube.model.VideoListResponse;
import com.smmpanel.exception.YouTubeApiException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.Collections;

@Slf4j
@Service
@Transactional(readOnly = true)
public class YouTubeService {

    private static final Pattern VIDEO_ID_PATTERN = Pattern.compile(
        "(?:youtube\\.com/(?:[^/]+/.+/|(?:v|e(?:mbed)?)/|.*[?&]v=)|youtu\\.be/)([^\"&?/\\s]{11})"
    );

    @Value("${app.youtube.api.key}")
    private String apiKey;

    private final YouTube youtube;

    public YouTubeService() throws Exception {
        this.youtube = new YouTube.Builder(
            GoogleNetHttpTransport.newTrustedTransport(),
            GsonFactory.getDefaultInstance(),
            null
        ).setApplicationName("SMM Panel").build();
    }

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

    @Cacheable(value = "youtube-views", key = "#videoId")
    public Long getViewCount(String videoId) {
        if (videoId == null || videoId.trim().isEmpty()) {
            throw new YouTubeApiException("Video ID cannot be null or empty");
        }
        
        try {
            YouTube.Videos.List request = youtube.videos()
                .list(Collections.singletonList("statistics"))
                .setId(Collections.singletonList(videoId))
                .setKey(apiKey);

            VideoListResponse response = request.execute();
            
            if (!response.getItems().isEmpty()) {
                Video video = response.getItems().get(0);
                return video.getStatistics().getViewCount().longValue();
            } else {
                log.warn("No video found with ID: {}", videoId);
                return 0L;
            }
        } catch (Exception e) {
            log.error("Error fetching view count for video: {}", videoId, e);
            throw new YouTubeApiException("Failed to fetch view count for video: " + videoId, e);
        }
    }

    // Alias for compatibility
    public int getVideoViewCount(String videoId) {
        return getViewCount(videoId).intValue();
    }
}