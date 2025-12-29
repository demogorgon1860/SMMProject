package com.smmpanel.dto.instagram;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * Health check response from Instagram bot.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InstagramHealthResponse {

    private String status;

    private Map<String, Object> components;

    private String version;

    private Long uptime;
}
