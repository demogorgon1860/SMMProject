package com.smmpanel.dto.validation;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ValidationResult {
    @Builder.Default
    private List<ValidationError> errors = new ArrayList<>();
    
    @Builder.Default
    private List<ValidationWarning> warnings = new ArrayList<>();
    
    public boolean hasErrors() {
        return !errors.isEmpty();
    }
    
    public boolean hasWarnings() {
        return !warnings.isEmpty();
    }
    
    public void addAll(List<ValidationError> newErrors) {
        this.errors.addAll(newErrors);
    }
    
    public void addError(String field, String message) {
        this.errors.add(new ValidationError(field, message));
    }
    
    public void addWarning(String field, String message) {
        this.warnings.add(new ValidationWarning(field, message));
    }
    
    /**
     * Get formatted error messages as a single string
     * Useful for exception messages and logging
     */
    public String getErrorMessages() {
        return errors.stream()
                .map(error -> error.getField() + ": " + error.getMessage())
                .collect(Collectors.joining("; "));
    }
}
