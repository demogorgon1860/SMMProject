package com.smmpanel.security.validation;

import javax.validation.Constraint;
import javax.validation.Payload;
import java.lang.annotation.*;

@Documented
@Constraint(validatedBy = SqlInjectionValidatorImpl.class)
@Target({ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
public @interface SqlInjectionSafe {
    String message() default "Potentially malicious SQL content detected";
    Class<?>[] groups() default {};
    Class<? extends Payload>[] payload() default {};
}
