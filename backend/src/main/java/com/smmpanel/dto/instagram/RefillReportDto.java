package com.smmpanel.dto.instagram;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One per-(order, action) result from the bot refill checker, mirroring the Go {@code
 * refill.Report} struct. v1 sends a single-action order, so a job carries exactly one of these.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class RefillReportDto {

    @JsonProperty("order_id")
    private String orderId;

    @JsonProperty("external_id")
    private String externalId;

    @JsonProperty("target_url")
    private String targetUrl;

    /** follow | comment | like */
    private String type;

    /** done | error | unsupported */
    private String status;

    private String error;
    private String note;

    /** Original ordered count the bot sized the refill against. */
    private Integer count;

    private Integer delivered;
    private Integer matchable;
    private Integer present;
    private Integer dropped;

    /** Authoritative re-deliver count = ordered − present (clamped ≥ 0). */
    @JsonProperty("refill_needed")
    private Integer refillNeeded;

    /** For like orders only: the post's current like count. */
    @JsonProperty("current_count")
    private Integer currentCount;

    private Integer scanned;
    private Integer pages;

    /** True when the scan stopped early — {@code refill_needed} is then a conservative minimum. */
    @JsonProperty("early_stopped")
    private Boolean earlyStopped;
}
