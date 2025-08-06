package com.smmpanel.security.validation;

import javax.validation.Constraint;
import javax.validation.Payload;
import javax.validation.constraints.Pattern;
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
