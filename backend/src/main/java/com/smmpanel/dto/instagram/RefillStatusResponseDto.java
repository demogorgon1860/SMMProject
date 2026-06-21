package com.smmpanel.dto.instagram;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Envelope for the bot's {@code GET /api/refill/status?job_id=...} response. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class RefillStatusResponseDto {
    private Boolean success;
    private RefillJobDto job;
    private String error;
}
