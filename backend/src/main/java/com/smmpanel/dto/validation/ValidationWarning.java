package com.smmpanel.dto.validation;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class ValidationWarning {
    private String field;
    private String message;
}
