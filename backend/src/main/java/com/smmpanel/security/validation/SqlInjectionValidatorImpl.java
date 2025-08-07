package com.smmpanel.security.validation;

import lombok.extern.slf4j.Slf4j;
import lombok.RequiredArgsConstructor;
import org.springframework.util.StringUtils;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

@Slf4j
@RequiredArgsConstructor
public class SqlInjectionValidatorImpl implements ConstraintValidator<SqlInjectionSafe, String> {
    
    private static final List<Pattern> SQL_INJECTION_PATTERNS = Arrays.asList(
        // Basic SQL injection patterns
        Pattern.compile("(?i)(\\b)*(SELECT|INSERT|UPDATE|DELETE|DROP|TRUNCATE|ALTER|EXEC|UNION|CREATE|DECLARE)(\\b)*"),
        Pattern.compile("(?i);.*"),  // Semicolon followed by any command
        Pattern.compile("(?i)/\\*.*\\*/"),  // Multi-line comments
        Pattern.compile("(?i)--.*"),  // Single line comments
        Pattern.compile("(?i)\\b(OR|AND)\\s+\\d+\\s*=\\s*\\d+\\b"),  // OR/AND boolean conditions
        Pattern.compile("(?i)'\\s*OR\\s*'\\w+'\\s*=\\s*'\\w+'"),  // String equality tautologies
        Pattern.compile("(?i)\\bXP_\\w+"),  // Extended stored procedures
        Pattern.compile("(?i)WAITFOR\\s+DELAY\\s+'\\d+:\\d+:\\d+'"),  // Time-based attacks
        Pattern.compile("(?i)0x[0-9a-fA-F]+"),  // Hex encoded values
        
        // Advanced patterns
        Pattern.compile("(?i)@@\\w+"),  // System variables
        Pattern.compile("(?i)CHAR\\(\\d+\\)"),  // CHAR function
        Pattern.compile("(?i)CONVERT\\(.*\\)"),  // CONVERT function
        Pattern.compile("(?i)CAST\\(.*\\)"),  // CAST function
        Pattern.compile("(?i)LOAD_FILE\\(.*\\)")  // File operations
    );

    @Override
    public void initialize(SqlInjectionSafe constraintAnnotation) {
        // Initialization not needed
    }

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        if (!StringUtils.hasText(value)) {
            return true; // Empty values are handled by @NotEmpty/@NotBlank
        }

        // Check for SQL injection patterns
        for (Pattern pattern : SQL_INJECTION_PATTERNS) {
            if (pattern.matcher(value).find()) {
                addConstraintViolation(context, 
                    "Potentially harmful content detected. Please remove special characters and SQL keywords.");
                return false;
            }
        }

        // Additional checks for common evasion techniques
        String normalizedValue = value.replaceAll("\\s+", "") // Remove whitespace
                                    .replaceAll("/\\*.*?\\*/", "") // Remove comments
                                    .toLowerCase();

        if (containsSuspiciousPatterns(normalizedValue)) {
            addConstraintViolation(context, 
                "Input contains suspicious patterns. Please review and remove any special characters.");
            return false;
        }

        return true;
    }

    private boolean containsSuspiciousPatterns(String value) {
        // Check for unicode evasion
        if (value.contains("u0022") || value.contains("u0027") || value.contains("u003d")) {
            return true;
        }

        // Check for concatenation attempts
        if (value.contains("concat(") || value.contains("chr(") || value.contains("char(")) {
            return true;
        }

        // Check for numeric operations in strings
        if (Pattern.compile("\\d+\\s*[+\\-*/]\\s*\\d+").matcher(value).find()) {
            return true;
        }

        // Check for typical command chain attempts
        return value.contains(";;") || value.contains("||") || value.contains("&&");
    }

    private void addConstraintViolation(ConstraintValidatorContext context, String message) {
        context.disableDefaultConstraintViolation();
        context.buildConstraintViolationWithTemplate(message)
               .addConstraintViolation();
    }
}

