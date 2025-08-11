package com.smmpanel.security.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.*;

/**
 * Custom validation annotation for balance operation amounts
 *
 * <p>VALIDATION FEATURES: - Enforces minimum/maximum transaction amounts - Currency-specific
 * validation - Fraud detection for suspicious amounts - Daily limit enforcement - Precision
 * validation for crypto currencies
 */
@Documented
@Constraint(validatedBy = BalanceAmountValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
public @interface BalanceAmount {
    String message() default "Invalid balance operation amount";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    /** Operation type (DEPOSIT, WITHDRAWAL, TRANSFER) */
    OperationType operation() default OperationType.DEPOSIT;

    /** Currency field name for currency-specific validation */
    String currencyField() default "currency";

    /** Whether to validate daily limits */
    boolean validateDailyLimits() default true;

    /** Whether to apply fraud detection */
    boolean enableFraudDetection() default true;

    /** Whether to validate decimal precision */
    boolean validatePrecision() default true;

    /** Custom minimum amount (overrides defaults) */
    String customMin() default "";

    /** Custom maximum amount (overrides defaults) */
    String customMax() default "";

    public enum OperationType {
        DEPOSIT,
        WITHDRAWAL,
        TRANSFER,
        CONVERSION
    }
}
