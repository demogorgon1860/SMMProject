package com.smmpanel.controller;

import com.smmpanel.dto.admin.DailyStatPoint;
import com.smmpanel.dto.profile.ChangePasswordRequest;
import com.smmpanel.dto.profile.ProfileMeResponse;
import com.smmpanel.dto.profile.UpdateProfileRequest;
import com.smmpanel.entity.User;
import com.smmpanel.repository.jpa.UserRepository;
import com.smmpanel.service.admin.AdminService;
import com.smmpanel.service.profile.ProfileService;
import jakarta.validation.Valid;
import java.security.Principal;
import java.util.List;
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
    private final AdminService adminService;
    private final UserRepository userRepository;

    @GetMapping
    public ResponseEntity<ProfileMeResponse> getMe() {
        return ResponseEntity.ok(profileService.getMe());
    }

    /**
     * Daily order/spend series for the user's own dashboard charts. Returns one entry per day
     * (zero-filled for days with no orders) so the frontend doesn't have to bucket on the client
     * or fetch 100+ orders just to draw a 12-day sparkline.
     */
    @GetMapping("/stats/daily")
    public ResponseEntity<List<DailyStatPoint>> getDailyStats(
            @RequestParam(defaultValue = "30") int days, Principal principal) {
        int safeDays = Math.max(1, Math.min(days, 90));
        User user =
                userRepository
                        .findByUsername(principal.getName())
                        .orElseThrow(() -> new IllegalStateException("User not found"));
        return ResponseEntity.ok(adminService.getDailyStatsForUser(user.getId(), safeDays));
    }

    @PatchMapping
    public ResponseEntity<ProfileMeResponse> patchMe(
            @Valid @RequestBody UpdateProfileRequest request) {
        return ResponseEntity.ok(profileService.updateMe(request));
    }

    @PostMapping("/password")
    public ResponseEntity<Void> changePassword(@Valid @RequestBody ChangePasswordRequest request) {
        try {
            profileService.changePassword(request);
            return ResponseEntity.noContent().build();
        } catch (BadCredentialsException e) {
            // 422 (not 401) — the user is authenticated, the *current password input* is wrong.
            // Returning 401 here would trip the global axios interceptor and force-logout the
            // session, kicking the user out of the app for a typo.
            log.info("Change-password rejected: bad current password");
            return ResponseEntity.status(422).build();
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
