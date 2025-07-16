package com.smmpanel.service;

import com.smmpanel.dto.ServiceDto;
import com.smmpanel.entity.Service;
import com.smmpanel.repository.ServiceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class ServiceManagementService {

    private final ServiceRepository serviceRepository;

    @Cacheable(value = "services", key = "'active-services'")
    public List<ServiceDto> getAllActiveServices() {
        return serviceRepository.findByActiveTrue().stream()
                .map(this::mapToDto)
                .collect(Collectors.toList());
    }

    private ServiceDto mapToDto(Service service) {
        return ServiceDto.builder()
                .id(service.getId())
                .name(service.getName())
                .category(service.getCategory())
                .minOrder(service.getMinOrder())
                .maxOrder(service.getMaxOrder())
                .pricePer1000(service.getPricePer1000().toString())
                .description(service.getDescription())
                .build();
    }
}