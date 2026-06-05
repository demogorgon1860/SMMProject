package com.smmpanel.config;

import jakarta.validation.constraints.Min;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

@Data
@Validated
@Component
@ConfigurationProperties(prefix = "app.order.quota-check")
public class OrderQuotaCheckProperties {

    private boolean enabled = true;

    @Min(1)
    private int windowDays = 90;
}
