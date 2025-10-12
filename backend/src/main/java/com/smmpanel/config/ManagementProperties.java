package com.smmpanel.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Management Configuration Properties */
@Data
@Validated
@ConfigurationProperties(prefix = "management")
public class ManagementProperties {

    private Endpoints endpoints = new Endpoints();
    private Endpoint endpoint = new Endpoint();
    private Metrics metrics = new Metrics();
    private Health health = new Health();

    @Data
    public static class Endpoints {
        private Web web = new Web();

        @Data
        public static class Web {
            private Exposure exposure = new Exposure();
            private String basePath = "/actuator";

            @Data
            public static class Exposure {
                private String include = "health,info,metrics,prometheus,loggers";
            }
        }
    }

    @Data
    public static class Endpoint {
        private Health health = new Health();

        @Data
        public static class Health {
            private String showDetails = "when-authorized";
            private String showComponents = "always";
        }
    }

    @Data
    public static class Metrics {
        private Export export = new Export();

        @Data
        public static class Export {
            private Prometheus prometheus = new Prometheus();

            @Data
            public static class Prometheus {
                private boolean enabled = true;
            }
        }
    }

    @Data
    public static class Health {
        private boolean livenessState = true;
        private boolean readinessState = true;
        private boolean db = true;
        private boolean redis = true;
        private boolean kafka = true;
    }
}
