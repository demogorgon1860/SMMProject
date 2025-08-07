package com.smmpanel.security.validation;

import org.apache.commons.validator.routines.UrlValidator;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import java.util.regex.Pattern;

public class YouTubeUrlValidatorImpl implements ConstraintValidator<YouTubeUrl, String> {
    
    private static final String YOUTUBE_PATTERN = 
        "^(https?://)?((www\\.)?youtube\\.com/watch\\?v=[\\w-]{11}|" +
        "(www\\.)?youtube\\.com/embed/[\\w-]{11}|" +
        "(www\\.)?youtube\\.com/v/[\\w-]{11}|" +
        "youtu\\.be/[\\w-]{11})$";
    
    private static final Pattern YOUTUBE_URL_PATTERN = Pattern.compile(YOUTUBE_PATTERN);
    private static final UrlValidator URL_VALIDATOR = new UrlValidator(new String[]{"http", "https"});
    
    private boolean allowShortLinks;

    @Override
    public void initialize(YouTubeUrl constraintAnnotation) {
        this.allowShortLinks = constraintAnnotation.allowShortLinks();
    }

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        if (value == null || value.trim().isEmpty()) {
            return true; // Use @NotNull or @NotEmpty if field is required
        }

        // Basic URL validation
        if (!URL_VALIDATOR.isValid(value)) {
            addConstraintViolation(context, "Invalid URL format");
            return false;
        }

        // YouTube specific validation
        if (!YOUTUBE_URL_PATTERN.matcher(value).matches()) {
            addConstraintViolation(context, "Invalid YouTube URL format");
            return false;
        }

        // Short link validation
        if (!allowShortLinks && value.contains("youtu.be")) {
            addConstraintViolation(context, "Short YouTube links are not allowed");
            return false;
        }

        return true;
    }

    private void addConstraintViolation(ConstraintValidatorContext context, String message) {
        context.disableDefaultConstraintViolation();
        context.buildConstraintViolationWithTemplate(message)
               .addConstraintViolation();
    }
}

