package com.smmpanel.dto.admin;

import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CampaignValidationResult {
    private boolean valid;
    private String message;
    private int activeCampaignCount;
    @Builder.Default private List<String> errors = new ArrayList<>();
    @Builder.Default private List<String> warnings = new ArrayList<>();

    public boolean isSuccess() {
        return valid && errors.isEmpty();
    }

    public void addError(String error) {
        this.errors.add(error);
        this.valid = false;
    }

    public void addWarning(String warning) {
        this.warnings.add(warning);
    }
}
