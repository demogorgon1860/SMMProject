package com.smmpanel.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** JPA Configuration Properties */
@Data
@Validated
@ConfigurationProperties(prefix = "spring.jpa")
public class JpaProperties {

    private Hibernate hibernate = new Hibernate();
    private boolean showSql = false;
    private boolean openInView = false;
    private Properties properties = new Properties();

    @Data
    public static class Hibernate {
        private String ddlAuto = "validate";
        private Naming naming = new Naming();

        @Data
        public static class Naming {
            private String physicalStrategy =
                    "org.hibernate.boot.model.naming.PhysicalNamingStrategyStandardImpl";
        }
    }

    @Data
    public static class Properties {
        private Hibernate hibernate = new Hibernate();

        @Data
        public static class Hibernate {
            private String dialect = "org.hibernate.dialect.PostgreSQLDialect";
            private boolean formatSql = false;
            private Jdbc jdbc = new Jdbc();
            private boolean orderInserts = true;
            private boolean orderUpdates = true;
            private boolean generateStatistics = true;

            @Data
            public static class Jdbc {
                private int batchSize = 25;
                private boolean batchVersionedData = true;
            }
        }
    }
}
