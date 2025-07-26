package com.smmpanel.dto.admin;

import lombok.Data;
import java.util.ArrayList;
import java.util.List;

@Data
public class ValidationResult {
    private boolean valid;
    private int activeCampaignCount;
    private String message;
    private List<String> errors = new ArrayList<>();
} 