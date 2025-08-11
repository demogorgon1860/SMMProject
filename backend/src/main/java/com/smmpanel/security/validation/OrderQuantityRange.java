package com.smmpanel.security.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.*;

/**
 * Custom validation annotation for order quantity limits
 * 
 * VALIDATION FEATURES:
 * - Enforces minimum quantity of 100
 * - Enforces maximum quantity of 1,000,000
 * - Service-specific limit validation
 * - Bulk order protection
 * - Fraud detection integration
 */
@Documented
@Constraint(validatedBy = OrderQuantityRangeValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
public @interface OrderQuantityRange {
    String message() default "Order quantity must be between 100 and 1,000,000";
    Class<?>[] groups() default {};
    Class<? extends Payload>[] payload() default {};

    /**
     * Minimum allowed quantity
     */
    int min() default 100;
    
    /**
     * Maximum allowed quantity
     */
    int max() default 1_000_000;
    
    /**
     * Whether to validate against service-specific limits
     */
    boolean validateServiceLimits() default false;
    
    /**
     * Service ID field name for service-specific validation
     */
    String serviceIdField() default "serviceId";
    
    /**
     * Whether to apply fraud detection rules
     */
    boolean enableFraudDetection() default true;
}