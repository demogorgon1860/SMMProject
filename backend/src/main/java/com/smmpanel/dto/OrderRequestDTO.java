package com.smmpanel.dto;

import com.smmpanel.security.validation.SqlInjectionSafe;
import com.smmpanel.security.validation.YouTubeUrl;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import lombok.Data;

@Data
public class OrderRequestDTO {
    @NotNull(message = "Service ID is required") @Positive(message = "Service ID must be positive") private Long serviceId;

    @NotNull(message = "Quantity is required") @Min(value = 1, message = "Minimum quantity is 1")
    @Max(value = 100000, message = "Maximum quantity is 100,000")
    private Integer quantity;

    @YouTubeUrl(message = "Invalid YouTube URL")
    @NotBlank(message = "URL is required")
    private String url;

    @SqlInjectionSafe
    @Size(max = 500, message = "Comments cannot exceed 500 characters")
    @Pattern(regexp = "^[\\p{L}\\p{N}\\s.,!?-]*$", message = "Comments contain invalid characters")
    private String comments;

    @Email(message = "Invalid email format")
    @Size(max = 255, message = "Email cannot exceed 255 characters")
    private String notificationEmail;

    @DecimalMin(value = "0.0", inclusive = false, message = "Amount must be greater than 0")
    @Digits(integer = 10, fraction = 2, message = "Invalid amount format")
    private BigDecimal amount;
}
