package com.smmpanel.dto.profile;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Body for {@code DELETE /api/v1/me/account}. Both fields are required and validated server-side
 * even though the modal already enforces them on the client — never trust client validation for an
 * irreversible action.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DeleteAccountRequest {

    /** Must be the literal string "DELETE" — matches the modal's typed-confirmation prompt. */
    @NotBlank(message = "Confirmation is required")
    private String confirmation;

    /** Current account password — verified against the stored hash before any side effect. */
    @NotBlank(message = "Password is required")
    @Size(max = 128, message = "Password is too long")
    private String password;
}
