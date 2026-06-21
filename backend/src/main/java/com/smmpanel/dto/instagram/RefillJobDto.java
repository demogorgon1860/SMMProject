package com.smmpanel.dto.instagram;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** A refill job snapshot from the bot ({@code GET /api/refill/status?job_id=...} → {@code job}). */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class RefillJobDto {

    private String id;

    @JsonProperty("created_at")
    private String createdAt;

    /** queued | running | done */
    private String status;

    private Integer total;
    private Integer done;

    private List<RefillReportDto> reports;
}
