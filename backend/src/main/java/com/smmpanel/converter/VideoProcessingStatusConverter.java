package com.smmpanel.converter;

import com.smmpanel.entity.VideoProcessingStatus;
import jakarta.persistence.Converter;

/**
 * JPA converter for VideoProcessingStatus enum to PostgreSQL video_processing_status type. Handles
 * the conversion between Java VideoProcessingStatus enum and PostgreSQL enum type.
 */
@Converter(autoApply = false)
public class VideoProcessingStatusConverter extends PostgreSQLEnumConverter<VideoProcessingStatus> {

    public VideoProcessingStatusConverter() {
        super(VideoProcessingStatus.class, "video_processing_status");
    }
}
