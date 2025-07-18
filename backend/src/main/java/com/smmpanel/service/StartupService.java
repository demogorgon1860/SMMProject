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
            admin.setIsActive(true);

            userRepository.save(admin);
            log.info("Created default admin user: admin / admin123");
        }
    }

    private void createDefaultServices() {
        if (serviceRepository.count() == 0) {
            // Service 1: YouTube Views (Standard)
            Service service1 = new Service();
            service1.setId(1L);
            service1.setName("YouTube Views (Standard)");
            service1.setCategory("YouTube");
            service1.setMinOrder(100);
            service1.setMaxOrder(1000000);
            service1.setPricePer1000(new BigDecimal("1.0000"));
            service1.setDescription("Standard YouTube views delivery");
            service1.setActive(true);
            serviceRepository.save(service1);

            // Service 2: YouTube Views (Premium)
            Service service2 = new Service();
            service2.setId(2L);
            service2.setName("YouTube Views (Premium)");
            service2.setCategory("YouTube");
            service2.setMinOrder(100);
            service2.setMaxOrder(1000000);
            service2.setPricePer1000(new BigDecimal("2.0000"));
            service2.setDescription("Premium YouTube views with higher quality");
            service2.setActive(true);
            serviceRepository.save(service2);

            // Service 3: YouTube Views (High Quality)
            Service service3 = new Service();
            service3.setId(3L);
            service3.setName("YouTube Views (High Quality)");
            service3.setCategory("YouTube");
            service3.setMinOrder(100);
            service3.setMaxOrder(1000000);
            service3.setPricePer1000(new BigDecimal("3.0000"));
            service3.setDescription("High quality YouTube views with best retention");
            service3.setActive(true);
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
                coefficient.setWithoutClip(new BigDecimal("4.0"));
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