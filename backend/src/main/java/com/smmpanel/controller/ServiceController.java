package com.smmpanel.controller;

import com.smmpanel.dto.response.PerfectPanelResponse;
import com.smmpanel.entity.User;
import com.smmpanel.repository.jpa.UserRepository;
import com.smmpanel.service.core.ServiceService;
import java.security.Principal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequestMapping("/api/v1/service")
@RequiredArgsConstructor
public class ServiceController {

    private final ServiceService serviceService;
    private final UserRepository userRepository;

    /**
     * Catalog endpoint. Reachable both authenticated (returns the per-user filtered list — admins
     * and tier-unrestricted users see everything) and anonymous (returns all active services for
     * the public /services-list catalog page).
     *
     * <p>The previous implementation NPE'd on anonymous calls because it dereferenced {@code
     * principal.getName()} unconditionally. Public landing pages need to render the catalog
     * without a JWT, so we now treat a missing/anonymous principal as "show me everything active".
     */
    @GetMapping("/services")
    public ResponseEntity<PerfectPanelResponse> getServices(Principal principal) {
        if (principal == null || principal.getName() == null) {
            return ResponseEntity.ok(
                    PerfectPanelResponse.success(serviceService.getAllActiveServices()));
        }
        User user = userRepository.findByUsername(principal.getName()).orElse(null);
        if (user == null) {
            // Token decoded to a username we no longer have — fall through to the public list
            // rather than 500'ing the catalog page.
            log.warn(
                    "ServiceController: authenticated principal '{}' not in DB, serving"
                            + " anonymous catalog instead",
                    principal.getName());
            return ResponseEntity.ok(
                    PerfectPanelResponse.success(serviceService.getAllActiveServices()));
        }
        return ResponseEntity.ok(
                PerfectPanelResponse.success(serviceService.getActiveServicesForUser(user)));
    }
}
