package com.smmpanel.service;

import com.smmpanel.entity.*;
import com.smmpanel.repository.jpa.*;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class StartupService implements ApplicationRunner {

    private final UserRepository userRepository;
    private final ServiceRepository serviceRepository;
    private final ConversionCoefficientRepository coefficientRepository;
    private final PasswordEncoder passwordEncoder;

    @Value("${ADMIN_USERNAME:admin}")
    private String adminUsername;

    @Value("${ADMIN_EMAIL:admin@smmpanel.local}")
    private String adminEmail;

    @Value("${ADMIN_PASSWORD:admin123}")
    private String adminPassword;

    @Value("${ADMIN_INITIAL_BALANCE:1000}")
    private BigDecimal adminInitialBalance;

    @Override
    @Transactional(propagation = Propagation.REQUIRED)
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
            admin.setUsername(adminUsername);
            admin.setEmail(adminEmail);
            admin.setPasswordHash(passwordEncoder.encode(adminPassword));
            admin.setRole(UserRole.ADMIN);
            admin.setBalance(adminInitialBalance);
            admin.setApiKey("sk_admin_" + UUID.randomUUID().toString().replace("-", ""));
            admin.setActive(true);

            userRepository.save(admin);
            log.info(
                    "Created default admin user: {} (password configured via environment)",
                    adminUsername);
        }
    }

    private void createDefaultServices() {
        if (serviceRepository.count() == 0) {
            // Service 1: YouTube Views (Standard)
            com.smmpanel.entity.Service service1 =
                    com.smmpanel.entity.Service.builder()
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
            com.smmpanel.entity.Service service2 =
                    com.smmpanel.entity.Service.builder()
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
            com.smmpanel.entity.Service service3 =
                    com.smmpanel.entity.Service.builder()
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
                coefficient.setCoefficient(new BigDecimal("3.5"));
                coefficient.setWithClip(new BigDecimal("3.0"));
                coefficient.setWithoutClip(new BigDecimal("4.0"));
                coefficientRepository.save(coefficient);
            }
        }
        log.info("Created default conversion coefficients");
    }
}
