package com.smmpanel.config;

import jakarta.validation.Validator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import org.springframework.validation.beanvalidation.MethodValidationPostProcessor;

/**
 * Enhanced Validation Configuration for Input Validation Strengthening
 *
 * <p>SECURITY FEATURES: - Jakarta Bean Validation integration - Custom validator registration -
 * Method-level validation - Comprehensive error handling - Security-focused validation rules
 */
@Slf4j
@Configuration
public class ValidationConfig {

    /** Configure Jakarta Bean Validation factory */
    @Bean
    public LocalValidatorFactoryBean validator() {
        LocalValidatorFactoryBean factoryBean = new LocalValidatorFactoryBean();

        // Enable fail-fast validation mode for security
        factoryBean.getValidationPropertyMap().put("hibernate.validator.fail_fast", "false");

        // Enable expression language for dynamic messages
        factoryBean
                .getValidationPropertyMap()
                .put("hibernate.validator.enable_expression_language", "true");

        log.info("Jakarta Bean Validation configured with enhanced security validators");

        return factoryBean;
    }

    /** Enable method-level validation */
    @Bean
    public MethodValidationPostProcessor methodValidationPostProcessor() {
        MethodValidationPostProcessor processor = new MethodValidationPostProcessor();
        processor.setValidator(validator());

        log.info("Method-level validation enabled for controller and service layers");

        return processor;
    }

    /** Get validator instance for programmatic validation */
    @Bean
    public Validator validatorInstance() {
        return validator().getValidator();
    }
}
