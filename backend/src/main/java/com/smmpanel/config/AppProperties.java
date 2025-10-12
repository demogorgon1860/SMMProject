package com.smmpanel.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.Duration;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

/** Application Configuration Properties */
@Data
@Validated
@Component
@ConfigurationProperties(prefix = "app")
public class AppProperties {

    private Jwt jwt = new Jwt();
    private Security security = new Security();
    private Cors cors = new Cors();
    private RateLimit rateLimit = new RateLimit();
    private Binom binom = new Binom();
    private Youtube youtube = new Youtube();
    private Cryptomus cryptomus = new Cryptomus();
    private Selenium selenium = new Selenium();
    private Cache cache = new Cache();
    private File file = new File();
    private Error error = new Error();
    private Order order = new Order();
    private Video video = new Video();
    private Balance balance = new Balance();
    private Perfectpanel perfectpanel = new Perfectpanel();
    private Features features = new Features();
    private Monitoring monitoring = new Monitoring();
    private Mail mail = new Mail();
    private Notifications notifications = new Notifications();
    private Alerts alerts = new Alerts();

    @Data
    public static class Jwt {
        @NotBlank private String secret; // Loaded from JWT_SECRET env var

        @Min(1)
        private long expirationMs = 86400000;

        @Min(1)
        private long refreshExpirationMs = 604800000;

        private String issuer = "smmpanel";
    }

    @Data
    public static class Security {
        private ApiKey apiKey = new ApiKey();

        @Data
        public static class ApiKey {
            private boolean enabled = true;
            private String header = "X-API-Key";
            private String hashAlgorithm = "SHA-256";
        }
    }

    @Data
    public static class Cors {
        private String allowedOrigins = "http://localhost:3000,https://demo.perfectpanel.com";
        private String allowedMethods = "GET,POST,PUT,DELETE,OPTIONS";
        private String allowedHeaders = "*";
        private boolean allowCredentials = true;

        @Min(1)
        private int maxAge = 3600;
    }

    @Data
    public static class RateLimit {
        private boolean enabled = true;
        private Orders orders = new Orders();
        private Api api = new Api();
        private Auth auth = new Auth();

        @Data
        public static class Orders {
            @Min(1)
            private int perMinute = 10;

            @Min(1)
            private int perHour = 100;
        }

        @Data
        public static class Api {
            @Min(1)
            private int perMinute = 60;

            @Min(1)
            private int perHour = 1000;
        }

        @Data
        public static class Auth {
            @Min(1)
            private int perMinute = 5;
        }
    }

    @Data
    public static class Binom {
        private Api api = new Api();
        private double defaultCoefficient = 3.0;

        @Min(1)
        private int maxCampaignsPerOrder = 5;

        @Data
        public static class Api {
            @NotBlank private String url; // Loaded from BINOM_API_URL env var

            @NotBlank private String key; // Loaded from BINOM_API_KEY env var

            private String username; // Loaded from BINOM_USERNAME env var

            private String password; // Loaded from BINOM_PASSWORD env var

            @Min(1)
            private int timeout = 30000;

            @Min(1)
            private int retryAttempts = 3;
        }
    }

    @Data
    public static class Youtube {
        private Api api = new Api();
        private Quota quota = new Quota();
        private ClipCreation clipCreation = new ClipCreation();

        @Data
        public static class Api {
            @NotBlank private String key; // Loaded from YOUTUBE_API_KEY env var

            @Min(1)
            private int timeout = 15000;

            @Min(1)
            private int retryAttempts = 2;
        }

        @Data
        public static class Quota {
            @Min(1)
            private int dailyLimit = 10000;

            @Min(1)
            private int per100Seconds = 100;
        }

        @Data
        public static class ClipCreation {
            private boolean enabled = true;
            private double coefficient = 3.0;

            @Min(1)
            private long timeout = 300000;
        }
    }

    @Data
    public static class Cryptomus {
        private Api api = new Api();
        private Webhook webhook = new Webhook();

        @Min(1)
        private int timeout = 30000;

        @NotNull private BigDecimal minDeposit = new BigDecimal("5.00");

        @Data
        public static class Api {
            @NotBlank private String url; // Loaded from CRYPTOMUS_API_URL env var

            @NotBlank private String userKey; // Loaded from CRYPTOMUS_USER_API_KEY env var

            private String payoutKey; // Loaded from CRYPTOMUS_PAYOUT_API_KEY env var

            @NotBlank private String secret; // Loaded from CRYPTOMUS_API_SECRET env var
        }

        @Data
        public static class Webhook {
            @NotBlank private String secret; // Loaded from CRYPTOMUS_WEBHOOK_SECRET env var
        }
    }

    @Data
    public static class Selenium {
        private Driver driver = new Driver();
        private boolean headless = true;
        private Timeout timeout = new Timeout();

        @Min(1)
        private int maxConcurrentSessions = 5;

        private Hub hub = new Hub();
        private Browser browser = new Browser();
        private Retry retry = new Retry();

        @Data
        public static class Driver {
            private String path = "/usr/local/bin/chromedriver";
        }

        @Data
        public static class Timeout {
            @Min(1)
            private int pageLoad = 30;

            @Min(1)
            private int implicit = 10;
        }

        @Data
        public static class Hub {
            @NotBlank private String url = "http://selenium-hub:4444/wd/hub";

            @Min(1)
            private long timeout = 300000;

            @Min(1)
            private int maxSessions = 10;
        }

        @Data
        public static class Browser {
            private boolean headless = true;
        }

        @Data
        public static class Retry {
            @Min(1)
            private int attempts = 3;
        }
    }

    @Data
    public static class Cache {
        private Services services = new Services();
        private Users users = new Users();
        private ConversionCoefficients conversionCoefficients = new ConversionCoefficients();
        private YoutubeStats youtubeStats = new YoutubeStats();

        @Data
        public static class Services {
            @Min(1)
            private int ttl = 3600;
        }

        @Data
        public static class Users {
            @Min(1)
            private int ttl = 1800;
        }

        @Data
        public static class ConversionCoefficients {
            @Min(1)
            private int ttl = 7200;
        }

        @Data
        public static class YoutubeStats {
            @Min(1)
            private int ttl = 300;
        }
    }

    @Data
    public static class File {
        private Upload upload = new Upload();
        private Processing processing = new Processing();

        @Data
        public static class Upload {
            private String path = "/tmp/smm-panel/uploads";
        }

        @Data
        public static class Processing {
            @Min(1)
            private int timeout = 300;

            @Min(1)
            private int maxConcurrent = 3;
        }
    }

    @Data
    public static class Error {
        private boolean includeStackTrace = false;
        private boolean includeDebugInfo = false;
    }

    @Data
    public static class Order {
        private Processing processing = new Processing();

        @Data
        public static class Processing {
            @Min(1)
            private int batchSize = 100;

            @Min(0)
            private int maxRetries = 3;

            @Min(1)
            private int retryDelay = 5000;

            @Min(1)
            private int timeout = 300000;

            private ClipCreation clipCreation = new ClipCreation();

            @Data
            public static class ClipCreation {
                private boolean enabled = true;

                @NotNull private Duration timeout = Duration.ofMinutes(5);

                @Min(0)
                private int retryAttempts = 2;
            }
        }
    }

    @Data
    public static class Video {
        private Processing processing = new Processing();

        @Data
        public static class Processing {
            @Min(1)
            private int maxConcurrent = 5;

            @Min(1)
            private int timeout = 600000;

            @Min(1)
            private int clipLength = 60;

            private String quality = "medium";
        }
    }

    @Data
    public static class Balance {
        @NotNull private BigDecimal minimumDeposit = new BigDecimal("1.00");

        @NotNull private BigDecimal maximumDeposit = new BigDecimal("10000.00");

        @Min(1)
        private int transactionTimeout = 300000;
    }

    @Data
    public static class Perfectpanel {
        private boolean compatible = true;
        private String apiVersion = "2.0";
        private StatusMapping statusMapping = new StatusMapping();

        @Data
        public static class StatusMapping {
            private boolean enabled = true;
        }
    }

    @Data
    public static class Features {
        private boolean youtubeProcessing = true;
        private boolean binomIntegration = true;
        private boolean paymentProcessing = true;
        private boolean emailNotifications = true;
    }

    @Data
    public static class Monitoring {
        private boolean enabled = true;

        @Min(1)
        private int slowQueryThreshold = 1000;

        @Min(1)
        private int memoryThreshold = 80;

        @Min(1)
        private int cpuThreshold = 80;
    }

    @Data
    public static class Mail {
        private boolean enabled = true;
        private String from = "noreply@smmpanel.com";
    }

    @Data
    public static class Notifications {
        private Kafka kafka = new Kafka();

        @Data
        public static class Kafka {
            private boolean enabled = true;
        }
    }

    @Data
    public static class Alerts {
        private boolean enabled = true;
        private String adminEmail = "admin@smmpanel.com";
    }
}
