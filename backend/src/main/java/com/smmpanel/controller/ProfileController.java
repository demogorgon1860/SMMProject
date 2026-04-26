package com.smmpanel.controller;

import com.smmpanel.dto.profile.ChangePasswordRequest;
import com.smmpanel.dto.profile.ProfileMeResponse;
import com.smmpanel.dto.profile.UpdateProfileRequest;
import com.smmpanel.service.profile.ProfileService;
import jakarta.validation.Valid;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.bind.annotation.*;

/**
 * Self-service profile endpoints under {@code /api/v1/me/*}.
 *
 * <p>Phase 3 ships GET/PATCH me, change password, notifications. Sessions, IP allow-list, 2FA,
 * security score, export and delete are intentionally not implemented yet — the UI degrades
 * gracefully when their endpoints 404.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/me")
@RequiredArgsConstructor
public class ProfileController {

    private final ProfileService profileService;

    @GetMapping
    public ResponseEntity<ProfileMeResponse> getMe() {
        return ResponseEntity.ok(profileService.getMe());
    }

    @PatchMapping
    public ResponseEntity<ProfileMeResponse> patchMe(@Valid @RequestBody UpdateProfileRequest request) {
        return ResponseEntity.ok(profileService.updateMe(request));
    }

    @PostMapping("/password")
    public ResponseEntity<Void> changePassword(@Valid @RequestBody ChangePasswordRequest request) {
        try {
            profileService.changePassword(request);
            return ResponseEntity.noContent().build();
        } catch (BadCredentialsException e) {
            log.info("Change-password rejected: bad current password");
            return ResponseEntity.status(401).build();
        } catch (IllegalArgumentException e) {
            log.info("Change-password rejected: {}", e.getMessage());
            return ResponseEntity.status(400).build();
        }
    }

    @GetMapping("/notifications")
    public ResponseEntity<Map<String, Boolean>> getNotifications() {
        return ResponseEntity.ok(profileService.getNotifications());
    }

    @PatchMapping("/notifications")
    public ResponseEntity<Map<String, Boolean>> patchNotifications(
            @RequestBody Map<String, Boolean> patch) {
        return ResponseEntity.ok(profileService.updateNotifications(patch));
    }
}
