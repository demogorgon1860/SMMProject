package com.smmpanel.converter;

import com.smmpanel.entity.YouTubeAccountStatus;
import jakarta.persistence.Converter;

/**
 * JPA converter for YouTubeAccountStatus enum to PostgreSQL youtube_account_status type. Handles
 * the conversion between Java YouTubeAccountStatus enum and PostgreSQL enum type.
 */
@Converter(autoApply = false)
public class YouTubeAccountStatusConverter extends PostgreSQLEnumConverter<YouTubeAccountStatus> {

    public YouTubeAccountStatusConverter() {
        super(YouTubeAccountStatus.class, "youtube_account_status");
    }
}
