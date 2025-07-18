package com.smmpanel.service;

import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.youtube.YouTube;
import com.google.api.services.youtube.model.Video;
import com.google.api.services.youtube.model.VideoListResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
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
        Matcher matcher = VIDEO_ID_PATTERN.matcher(url);
        if (matcher.find()) {
            return matcher.group(1);
        }
        throw new IllegalArgumentException("Invalid YouTube URL");
    }

    @Cacheable(value = "youtube-views", key = "#videoId")
    public Long getViewCount(String videoId) {
        try {
            YouTube.Videos.List request = youtube.videos()
                .list("statistics")
                .setId(videoId)
                .setKey(apiKey);

            VideoListResponse response = request.execute();
            
            if (!response.getItems().isEmpty()) {
                Video video = response.getItems().get(0);
                return video.getStatistics().getViewCount().longValue();
            }
        } catch (Exception e) {
            log.error("Error fetching view count for video: {}", videoId, e);
        }
        return 0L;
    }
}