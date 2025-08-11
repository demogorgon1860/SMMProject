package com.smmpanel.dto.validation;

import java.util.List;
import lombok.Getter;

@Getter
public class ValidationException extends RuntimeException {
    private final List<ValidationError> errors;

    public ValidationException(String message, List<ValidationError> errors) {
        super(message);
        this.errors = errors != null ? errors : List.of();
    }

    public ValidationException(List<ValidationError> errors) {
        this("Validation failed", errors);
    }
}
