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
    private final TrafficSourceRepository trafficSourceRepository;
    private final PasswordEncoder passwordEncoder;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        log.info("Starting application initialization...");

        createDefaultAdmin();
        createDefaultServices();
        createDefaultCoefficients();
        createDefaultTrafficSources();

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

    private void createDefaultTrafficSources() {
        if (trafficSourceRepository.count() == 0) {
            // STANDARD источники для Service ID 1 ($1)
            createTrafficSource("Clickadoo Push STANDARD", "clickadoo_push_std", 
                10, 10000, "US", "STANDARD", 92.5);
            createTrafficSource("Clickadoo Native STANDARD", "clickadoo_native_std", 
                8, 8000, "EU", "STANDARD", 85.0);
            createTrafficSource("Clickadoo Display STANDARD", "clickadoo_display_std", 
                6, 15000, "GLOBAL", "STANDARD", 78.3);

            // PREMIUM источники для Service ID 2 ($2)
            createTrafficSource("Clickadoo Push PREMIUM", "clickadoo_push_prem", 
                12, 7000, "US", "PREMIUM", 96.8);
            createTrafficSource("Clickadoo Native PREMIUM", "clickadoo_native_prem", 
                10, 6000, "EU", "PREMIUM", 94.2);
            createTrafficSource("Clickadoo Display PREMIUM", "clickadoo_display_prem", 
                8, 10000, "GLOBAL", "PREMIUM", 91.5);

            // HIGH_QUALITY источники для Service ID 3 ($3)
            createTrafficSource("Clickadoo Push HQ", "clickadoo_push_hq", 
                15, 5000, "US", "HIGH_QUALITY", 99.2);
            createTrafficSource("Clickadoo Native HQ", "clickadoo_native_hq", 
                13, 4000, "EU", "HIGH_QUALITY", 98.7);
            createTrafficSource("Clickadoo Display HQ", "clickadoo_display_hq", 
                10, 6000, "GLOBAL", "HIGH_QUALITY", 97.3);

            log.info("Created default traffic sources with quality differentiation");
        }
    }

    private void createTrafficSource(String name, String sourceId, int weight, 
                                   int dailyLimit, String geo, String qualityLevel, 
                                   double performanceScore) {
        TrafficSource source = new TrafficSource();
        source.setName(name);
        source.setSourceId(sourceId);
        source.setWeight(weight);
        source.setDailyLimit(dailyLimit);
        source.setGeoTargeting(geo);
        source.setQualityLevel(qualityLevel);
        source.setActive(true);
        source.setPerformanceScore(new BigDecimal(performanceScore));
        source.setClicksUsedToday(0);
        source.setLastResetDate(LocalDate.now());
        trafficSourceRepository.save(source);
    }
}