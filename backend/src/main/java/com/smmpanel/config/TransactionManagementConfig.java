package com.smmpanel.config;

import jakarta.persistence.EntityManagerFactory;
import java.util.Properties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.aop.interceptor.CustomizableTraceInterceptor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.context.annotation.Primary;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;

@Slf4j
@Configuration
@EnableTransactionManagement
@EnableAspectJAutoProxy
public class TransactionManagementConfig {

    /** Enhanced transaction manager with custom properties */
    @Bean
    @Primary
    public PlatformTransactionManager transactionManager(
            EntityManagerFactory entityManagerFactory) {
        JpaTransactionManager transactionManager = new JpaTransactionManager();
        transactionManager.setEntityManagerFactory(entityManagerFactory);

        // Set transaction timeout (180 seconds for video processing with Selenium)
        transactionManager.setDefaultTimeout(180);

        // Enable nested transactions for complex operations
        transactionManager.setNestedTransactionAllowed(true);

        // Fail early on commit failures
        transactionManager.setFailEarlyOnGlobalRollbackOnly(true);

        log.info(
                "Enhanced transaction manager configured with 180s timeout and nested transaction"
                        + " support");
        return transactionManager;
    }

    /** Transaction template for programmatic transaction management */
    @Bean
    public TransactionTemplate transactionTemplate(PlatformTransactionManager transactionManager) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setTimeout(30);
        template.setIsolationLevel(TransactionTemplate.ISOLATION_REPEATABLE_READ);
        return template;
    }

    /** Balance-specific transaction template with stricter settings */
    @Bean("balanceTransactionTemplate")
    public TransactionTemplate balanceTransactionTemplate(
            PlatformTransactionManager transactionManager) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setTimeout(10); // Shorter timeout for balance operations
        template.setIsolationLevel(TransactionTemplate.ISOLATION_SERIALIZABLE); // Highest isolation
        template.setReadOnly(false);
        return template;
    }

    /** Read-only transaction template for queries */
    @Bean("readOnlyTransactionTemplate")
    public TransactionTemplate readOnlyTransactionTemplate(
            PlatformTransactionManager transactionManager) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setTimeout(15);
        template.setIsolationLevel(TransactionTemplate.ISOLATION_READ_COMMITTED);
        template.setReadOnly(true);
        return template;
    }

    /** Transaction interceptor with custom transaction attributes */
    @Bean("customTransactionInterceptor")
    public TransactionInterceptor transactionInterceptor(
            PlatformTransactionManager transactionManager) {
        Properties transactionAttributes = new Properties();

        // Balance operations with highest isolation
        transactionAttributes.setProperty(
                "*Balance*", "PROPAGATION_REQUIRED,ISOLATION_SERIALIZABLE,timeout_10,-Exception");
        transactionAttributes.setProperty(
                "*balance*", "PROPAGATION_REQUIRED,ISOLATION_SERIALIZABLE,timeout_10,-Exception");
        transactionAttributes.setProperty(
                "deduct*", "PROPAGATION_REQUIRED,ISOLATION_SERIALIZABLE,timeout_10,-Exception");
        transactionAttributes.setProperty(
                "add*", "PROPAGATION_REQUIRED,ISOLATION_SERIALIZABLE,timeout_10,-Exception");
        transactionAttributes.setProperty(
                "transfer*", "PROPAGATION_REQUIRED,ISOLATION_SERIALIZABLE,timeout_15,-Exception");
        transactionAttributes.setProperty(
                "adjust*", "PROPAGATION_REQUIRED,ISOLATION_SERIALIZABLE,timeout_10,-Exception");

        // Order operations with repeatable read
        transactionAttributes.setProperty(
                "*Order*", "PROPAGATION_REQUIRED,ISOLATION_REPEATABLE_READ,timeout_30,-Exception");
        transactionAttributes.setProperty(
                "create*", "PROPAGATION_REQUIRED,ISOLATION_REPEATABLE_READ,timeout_30,-Exception");
        transactionAttributes.setProperty(
                "cancel*", "PROPAGATION_REQUIRED,ISOLATION_REPEATABLE_READ,timeout_30,-Exception");
        transactionAttributes.setProperty(
                "refill*", "PROPAGATION_REQUIRED,ISOLATION_REPEATABLE_READ,timeout_30,-Exception");

        // Read operations
        transactionAttributes.setProperty(
                "get*", "PROPAGATION_SUPPORTS,ISOLATION_READ_COMMITTED,timeout_15,readOnly");
        transactionAttributes.setProperty(
                "find*", "PROPAGATION_SUPPORTS,ISOLATION_READ_COMMITTED,timeout_15,readOnly");
        transactionAttributes.setProperty(
                "list*", "PROPAGATION_SUPPORTS,ISOLATION_READ_COMMITTED,timeout_15,readOnly");
        transactionAttributes.setProperty(
                "search*", "PROPAGATION_SUPPORTS,ISOLATION_READ_COMMITTED,timeout_15,readOnly");

        TransactionInterceptor interceptor = new TransactionInterceptor();
        interceptor.setTransactionManager(transactionManager);
        interceptor.setTransactionAttributes(transactionAttributes);

        return interceptor;
    }

    /** Transaction trace interceptor for debugging (enabled in dev/test environments) */
    @Bean
    @ConditionalOnProperty(name = "app.transaction.trace.enabled", havingValue = "true")
    public CustomizableTraceInterceptor transactionTraceInterceptor() {
        CustomizableTraceInterceptor interceptor = new CustomizableTraceInterceptor();
        interceptor.setEnterMessage(
                "Entering method '$[methodName]' with transaction '$[transactionName]'");
        interceptor.setExitMessage(
                "Exiting method '$[methodName]' with transaction '$[transactionName]' in"
                        + " $[invocationTime]ms");
        interceptor.setExceptionMessage(
                "Exception in method '$[methodName]' with transaction '$[transactionName]':"
                        + " $[exception]");
        interceptor.setUseDynamicLogger(true);
        return interceptor;
    }
}
