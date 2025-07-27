package com.smmpanel.service;

import com.smmpanel.entity.*;
import com.smmpanel.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class StartupService implements ApplicationRunner {

    private final UserRepository userRepository;
    private final ServiceRepository serviceRepository;
    private final ConversionCoefficientRepository coefficientRepository;
    private final PasswordEncoder passwordEncoder;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        log.info("Starting application initialization...");

        createDefaultAdmin();
        createDefaultServices();
        createDefaultCoefficients();

        log.info("Application initialization completed successfully");
    }

    private void createDefaultAdmin() {
        if (userRepository.findByRole(UserRole.ADMIN).isEmpty()) {
            User admin = new User();
            admin.setUsername("admin");
            admin.setEmail("admin@smmpanel.local");
            admin.setPasswordHash(passwordEncoder.encode("admin123"));
            admin.setRole(UserRole.ADMIN);
            admin.setBalance(BigDecimal.valueOf(1000)); // Starting balance
            admin.setApiKey("sk_admin_" + UUID.randomUUID().toString().replace("-", ""));
            admin.setActive(true);

            userRepository.save(admin);
            log.info("Created default admin user: admin / admin123");
        }
    }

    private void createDefaultServices() {
        if (serviceRepository.count() == 0) {
            // Service 1: YouTube Views (Standard)
            com.smmpanel.entity.Service service1 = com.smmpanel.entity.Service.builder()
                    .id(1L)
                    .name("YouTube Views (Standard)")
                    .category("YouTube")
                    .minOrder(100)
                    .maxOrder(1000000)
                    .pricePer1000(new BigDecimal("1.0000"))
                    .description("Standard YouTube views delivery")
                    .active(true)
                    .build();
            serviceRepository.save(service1);

            // Service 2: YouTube Views (Premium)
            com.smmpanel.entity.Service service2 = com.smmpanel.entity.Service.builder()
                    .id(2L)
                    .name("YouTube Views (Premium)")
                    .category("YouTube")
                    .minOrder(100)
                    .maxOrder(1000000)
                    .pricePer1000(new BigDecimal("2.0000"))
                    .description("Premium YouTube views with higher quality")
                    .active(true)
                    .build();
            serviceRepository.save(service2);

            // Service 3: YouTube Views (High Quality)
            com.smmpanel.entity.Service service3 = com.smmpanel.entity.Service.builder()
                    .id(3L)
                    .name("YouTube Views (High Quality)")
                    .category("YouTube")
                    .minOrder(100)
                    .maxOrder(1000000)
                    .pricePer1000(new BigDecimal("3.0000"))
                    .description("High quality YouTube views with best retention")
                    .active(true)
                    .build();
            serviceRepository.save(service3);

            log.info("Created default services");
        }
    }

    private void createDefaultCoefficients() {
        for (long serviceId = 1; serviceId <= 3; serviceId++) {
            if (coefficientRepository.findByServiceId(serviceId).isEmpty()) {
                ConversionCoefficient coefficient = new ConversionCoefficient();
                coefficient.setServiceId(serviceId);
                coefficient.setWithClip(new BigDecimal("3.0"));
                coefficient.setWithoutClip(true);
                coefficientRepository.save(coefficient);
            }
        }
        log.info("Created default conversion coefficients");
    }


}