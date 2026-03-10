package com.smmpanel.service.startup;

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
            String apiKey = "sk_admin_" + UUID.randomUUID().toString().replace("-", "");
            admin.setApiKeyHash(hashApiKey(apiKey));
            admin.setActive(true);

            userRepository.save(admin);
            log.info(
                    "Created default admin user: {} (password configured via environment)",
                    adminUsername);
        }
    }

    private void createDefaultServices() {
        boolean youtubeExists = serviceRepository.findAll().stream()
                .anyMatch(s -> s.getName() != null && s.getName().startsWith("YouTube"));
        if (!youtubeExists) {
            com.smmpanel.entity.Service youtubeStandard =
                    com.smmpanel.entity.Service.builder()
                            .name("YouTube Views (Standard)")
                            .category("YouTube")
                            .minOrder(100)
                            .maxOrder(1000000)
                            .pricePer1000(new BigDecimal("1.0000"))
                            .description("Standard YouTube views delivery")
                            .active(true)
                            .build();
            serviceRepository.save(youtubeStandard);
            log.info("Created default YouTube service");
        }
    }

    private void createDefaultCoefficients() {
        serviceRepository.findAll().stream()
                .filter(s -> Boolean.TRUE.equals(s.getActive()))
                .forEach(service -> {
                    if (coefficientRepository.findByServiceId(service.getId()).isEmpty()) {
                        ConversionCoefficient coefficient = new ConversionCoefficient();
                        coefficient.setServiceId(service.getId());
                        coefficient.setCoefficient(new BigDecimal("3.5"));
                        coefficient.setWithClip(new BigDecimal("3.0"));
                        coefficient.setWithoutClip(new BigDecimal("4.0"));
                        coefficientRepository.save(coefficient);
                        log.info("Created default conversion coefficient for service id={} name={}",
                                service.getId(), service.getName());
                    }
                });
        log.info("Conversion coefficients verified for all active services");
    }

    private String hashApiKey(String apiKey) {
        if (apiKey == null) {
            return null;
        }
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(apiKey.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 algorithm not found", e);
        }
    }
}
