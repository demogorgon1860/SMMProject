package com.smmpanel.service.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.smmpanel.dto.ServiceResponse;
import com.smmpanel.entity.Service;
import com.smmpanel.entity.User;
import com.smmpanel.entity.UserRole;
import com.smmpanel.exception.OrderValidationException;
import com.smmpanel.repository.jpa.ServiceRepository;
import com.smmpanel.service.settings.AppSettingsService;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Per-user service-visibility rule ({@link ServiceService}).
 *
 * <ul>
 *   <li>A user with &ge; 1 {@code user_service_access} row sees/ orders ONLY the assigned services.
 *   <li>A user with 0 rows is unrestricted — sees every active service.
 *   <li>Admins always bypass.
 * </ul>
 *
 * <p>Listing ({@code getActiveServicesForUser}) and per-order access checks
 * ({@code validateUserAccessToService}) MUST share the same rule so a client can never see a
 * service it cannot order, nor be blocked from ordering a service it can see.
 */
@ExtendWith(MockitoExtension.class)
class ServiceServiceTest {

    @Mock private ServiceRepository serviceRepository;
    @Mock private AppSettingsService appSettingsService;

    @InjectMocks private ServiceService service;

    private User user(long id, UserRole role) {
        return User.builder().id(id).username("u" + id).role(role).build();
    }

    private Service svc(long id) {
        return Service.builder()
                .id(id)
                .name("svc" + id)
                .category("INSTAGRAM_LIKES")
                .minOrder(10)
                .maxOrder(1000)
                .pricePer1000(new BigDecimal("1.00"))
                .active(true)
                .build();
    }

    /** Zero markup so toServiceResponse maps 1:1 — only stubbed on the paths that actually map. */
    private void noMarkup() {
        when(appSettingsService.getDecimal(eq(AppSettingsService.KEY_MARKUP_PERCENT), any()))
                .thenReturn(BigDecimal.ZERO);
    }

    // ---------------- getActiveServicesForUser ----------------

    @Test
    @DisplayName("admin sees ALL active services, never consults the access list")
    void admin_sees_all() {
        noMarkup();
        when(serviceRepository.findByActiveOrderByIdAsc(true))
                .thenReturn(List.of(svc(1), svc(2), svc(3)));

        List<ServiceResponse> out = service.getActiveServicesForUser(user(1, UserRole.ADMIN));

        assertThat(out).extracting(ServiceResponse::getId).containsExactly(1L, 2L, 3L);
        verify(serviceRepository, never()).countAccessEntriesForUser(anyLong());
        verify(serviceRepository, never()).findActiveServicesForUser(anyLong());
    }

    @Test
    @DisplayName("user with 0 access rows is unrestricted — sees ALL active services")
    void zero_access_sees_all() {
        noMarkup();
        when(serviceRepository.countAccessEntriesForUser(5L)).thenReturn(0L);
        when(serviceRepository.findByActiveOrderByIdAsc(true)).thenReturn(List.of(svc(1), svc(2)));

        List<ServiceResponse> out = service.getActiveServicesForUser(user(5, UserRole.USER));

        assertThat(out).hasSize(2);
        verify(serviceRepository, never()).findActiveServicesForUser(anyLong());
    }

    @Test
    @DisplayName("user with >=1 access row sees ONLY the assigned services (not the full catalog)")
    void restricted_sees_only_assigned() {
        noMarkup();
        when(serviceRepository.countAccessEntriesForUser(5L)).thenReturn(2L);
        when(serviceRepository.findActiveServicesForUser(5L)).thenReturn(List.of(svc(7), svc(8)));

        List<ServiceResponse> out = service.getActiveServicesForUser(user(5, UserRole.USER));

        assertThat(out).extracting(ServiceResponse::getId).containsExactly(7L, 8L);
        verify(serviceRepository, never()).findByActiveOrderByIdAsc(anyBoolean());
    }

    // -------- validateUserAccessToService (must mirror the listing rule exactly) --------

    @Test
    @DisplayName("validate: admin always allowed, no DB lookup")
    void validate_admin_ok() {
        service.validateUserAccessToService(user(1, UserRole.ADMIN), 99L);
        verifyNoInteractions(serviceRepository);
    }

    @Test
    @DisplayName("validate: 0 access rows -> unrestricted, allowed without a per-service check")
    void validate_zero_access_ok() {
        when(serviceRepository.countAccessEntriesForUser(5L)).thenReturn(0L);

        service.validateUserAccessToService(user(5, UserRole.USER), 99L);

        verify(serviceRepository, never()).hasAccessToService(anyLong(), anyLong());
    }

    @Test
    @DisplayName("validate: restricted user WITH access to the service is allowed")
    void validate_has_access_ok() {
        when(serviceRepository.countAccessEntriesForUser(5L)).thenReturn(3L);
        when(serviceRepository.hasAccessToService(5L, 99L)).thenReturn(true);

        service.validateUserAccessToService(user(5, UserRole.USER), 99L);
    }

    @Test
    @DisplayName("validate: restricted user WITHOUT access to the service is rejected")
    void validate_no_access_throws() {
        when(serviceRepository.countAccessEntriesForUser(5L)).thenReturn(3L);
        when(serviceRepository.hasAccessToService(5L, 7L)).thenReturn(false);

        assertThatThrownBy(() -> service.validateUserAccessToService(user(5, UserRole.USER), 7L))
                .isInstanceOf(OrderValidationException.class);
    }
}
