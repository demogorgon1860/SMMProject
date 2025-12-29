package com.smmpanel.dto.instagram;

import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Health check response from Instagram bot. */
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
