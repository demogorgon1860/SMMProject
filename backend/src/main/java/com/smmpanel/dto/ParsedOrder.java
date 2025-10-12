package com.smmpanel.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Represents a single parsed order from mass order text */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ParsedOrder {
    @NotNull(message = "Service ID is required") private Long serviceId;

    @NotBlank(message = "Link is required")
    private String link;

    @NotNull(message = "Quantity is required") @Positive(message = "Quantity must be positive") private Integer quantity;

    // Line number in the original input (for error reporting)
    private Integer lineNumber;

    // Original line text (for error reporting)
    private String originalLine;

    // Validation status
    private boolean valid;

    // Validation error message if any
    private String errorMessage;
}
