package com.smmpanel.dto.admin;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One row in the admin "Live system status" strip. Serializes to the shape the frontend dashboard
 * expects: {@code { name, status, latency, meta }}.
 *
 * <p>{@code status} is one of {@code "up" | "degraded" | "down"}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SystemHealthComponent {
    private String name;
    private String status;
    private Long latency;
    private String meta;
}
