package com.smmpanel.converter;

import com.smmpanel.entity.VideoType;
import jakarta.persistence.Converter;

/**
 * JPA converter for VideoType enum to PostgreSQL video_type type. Handles the conversion between
 * Java VideoType enum and PostgreSQL enum type.
 */
@Converter(autoApply = false)
public class VideoTypeConverter extends PostgreSQLEnumConverter<VideoType> {

    public VideoTypeConverter() {
        super(VideoType.class, "video_type");
    }
}
