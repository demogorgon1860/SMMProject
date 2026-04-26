package com.smmpanel.dto.support;

import jakarta.validation.constraints.NotBlank;
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

    @NotBlank(message = "Topic is required")
    @Size(max = 40)
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
