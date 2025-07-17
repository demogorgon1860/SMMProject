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
            // Clickadoo Push US
            TrafficSource source1 = new TrafficSource();
            source1.setName("Clickadoo Push US");
            source1.setSourceId("clickadoo_push_us");
            source1.setWeight(10);
            source1.setDailyLimit(10000);
            source1.setGeoTargeting("US");
            source1.setActive(true);
            source1.setPerformanceScore(new BigDecimal("95.5"));
            trafficSourceRepository.save(source1);

            // Clickadoo Native EU
            TrafficSource source2 = new TrafficSource();
            source2.setName("Clickadoo Native EU");
            source2.setSourceId("clickadoo_native_eu");
            source2.setWeight(8);
            source2.setDailyLimit(8000);
            source2.setGeoTargeting("EU");
            source2.setActive(true);
            source2.setPerformanceScore(new BigDecimal("88.2"));
            trafficSourceRepository.save(source2);

            // Clickadoo Display Global
            TrafficSource source3 = new TrafficSource();
            source3.setName("Clickadoo Display Global");
            source3.setSourceId("clickadoo_display_global");
            source3.setWeight(6);
            source3.setDailyLimit(15000);
            source3.setGeoTargeting("GLOBAL");
            source3.setActive(true);
            source3.setPerformanceScore(new BigDecimal("82.7"));
            trafficSourceRepository.save(source3);

            log.info("Created default traffic sources");
        }
    }
}