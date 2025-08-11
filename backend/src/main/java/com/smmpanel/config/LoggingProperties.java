package com.smmpanel.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Logging Configuration Properties */
@Data
@Validated
@ConfigurationProperties(prefix = "logging")
public class LoggingProperties {

    private Level level = new Level();
    private Pattern pattern = new Pattern();
    private File file = new File();
    private Logback logback = new Logback();

    @Data
    public static class Level {
        private String root = "INFO";
        private String comSmmpanel = "DEBUG";
        private String orgSpringframeworkSecurity = "WARN";
        private String orgHibernateSQL = "WARN";
        private String orgHibernateTypeDescriptorSqlBasicBinder = "WARN";
        private String orgSpringframeworkWeb = "INFO";
        private String orgSpringframeworkKafka = "INFO";
    }

    @Data
    public static class Pattern {
        private String console = "%d{yyyy-MM-dd HH:mm:ss} [%thread] %-5level %logger{36} - %msg%n";
        private String file = "%d{yyyy-MM-dd HH:mm:ss} [%thread] %-5level %logger{36} - %msg%n";
    }

    @Data
    public static class File {
        private String name = "/var/log/smm-panel/application.log";
    }

    @Data
    public static class Logback {
        private Rollingpolicy rollingpolicy = new Rollingpolicy();

        @Data
        public static class Rollingpolicy {
            private String maxFileSize = "100MB";
            private String totalSizeCap = "1GB";
            private int maxHistory = 30;
        }
    }
}
