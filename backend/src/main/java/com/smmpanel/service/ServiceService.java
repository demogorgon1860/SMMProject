package com.smmpanel.service;

import com.smmpanel.dto.ServiceResponse;
import com.smmpanel.entity.Service;
import com.smmpanel.repository.ServiceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;

import java.util.List;

@Slf4j
@org.springframework.stereotype.Service
@RequiredArgsConstructor
public class ServiceService {

    private final ServiceRepository serviceRepository;

    @Cacheable(value = "services", cacheManager = "redisCacheManager", key = "'active-services'")
    public List<ServiceResponse> getAllActiveServices() {
        List<Service> services = serviceRepository.findByActiveOrderByIdAsc(true);
        return services.stream()
                .map(this::toServiceResponse)
                .toList();
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