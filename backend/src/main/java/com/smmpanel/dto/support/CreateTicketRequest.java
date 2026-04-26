package com.smmpanel.dto.support;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateTicketRequest {

    /**
     * Topic key. Whitelisted to a small set of operational categories so the admin panel can filter
     * by topic without sanitizing user-controlled HTML in the bucket label.
     */
    @NotBlank(message = "Topic is required")
    @Pattern(
            regexp = "billing|order|technical|account|abuse|other",
            message = "Topic must be one of: billing, order, technical, account, abuse, other")
    private String topic;

    @NotBlank(message = "Subject is required")
    @Size(min = 3, max = 200)
    private String subject;

    @NotBlank(message = "Description is required")
    @Size(min = 10, max = 8000)
    private String description;

    /** Optional reference to an order. */
    private Long orderId;
}
