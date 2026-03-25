package com.smmpanel.service.core;

import com.smmpanel.dto.ServiceResponse;
import com.smmpanel.entity.Service;
import com.smmpanel.entity.User;
import com.smmpanel.exception.OrderValidationException;
import com.smmpanel.repository.jpa.ServiceRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@org.springframework.stereotype.Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ServiceService {

    private final ServiceRepository serviceRepository;

    @Cacheable(value = "services", key = "'active-services'")
    public List<ServiceResponse> getAllActiveServices() {
        List<Service> services = serviceRepository.findByActiveOrderByIdAsc(true);
        return services.stream().map(this::toServiceResponse).toList();
    }

    @Cacheable(value = "services", key = "'active-services-cached'")
    public List<ServiceResponse> getAllActiveServicesCached() {
        return getAllActiveServices();
    }

    /** Returns services visible to the given user. Admin and unrestricted users see all. */
    public List<ServiceResponse> getActiveServicesForUser(User user) {
        if (user.isAdmin()) {
            return getAllActiveServices();
        }
        long accessCount = serviceRepository.countAccessEntriesForUser(user.getId());
        if (accessCount == 0) {
            return getAllActiveServices();
        }
        return getFilteredServicesForUser(user.getId());
    }

    @Cacheable(value = "services", key = "'user-services-' + #userId")
    public List<ServiceResponse> getFilteredServicesForUser(Long userId) {
        List<Service> services = serviceRepository.findActiveServicesForUser(userId);
        return services.stream().map(this::toServiceResponse).toList();
    }

    /** Validates that user has access to a specific service. Throws if not. */
    public void validateUserAccessToService(User user, Long serviceId) {
        if (user.isAdmin()) return;
        long accessCount = serviceRepository.countAccessEntriesForUser(user.getId());
        if (accessCount == 0) return;
        if (!serviceRepository.hasAccessToService(user.getId(), serviceId)) {
            throw new OrderValidationException("Service not available");
        }
    }

    private ServiceResponse toServiceResponse(Service service) {
        return ServiceResponse.builder()
                .id(service.getId())
                .name(service.getName())
                .category(service.getCategory())
                .minOrder(service.getMinOrder())
                .maxOrder(service.getMaxOrder())
                .pricePer1000(service.getPricePer1000())
                .description(service.getDescription())
                .active(service.getActive())
                .createdAt(service.getCreatedAt())
                .updatedAt(service.getUpdatedAt())
                .build();
    }
}
