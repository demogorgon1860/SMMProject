package com.smmpanel.security.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import jakarta.validation.constraints.Pattern;
import java.lang.annotation.*;

@Documented
@Constraint(validatedBy = YouTubeUrlValidatorImpl.class)
@Target({ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
public @interface YouTubeUrl {
    String message() default "Invalid YouTube URL format";
    Class<?>[] groups() default {};
    Class<? extends Payload>[] payload() default {};

    boolean allowShortLinks() default true;
}

