package com.smmpanel.config;

import jakarta.validation.constraints.Min;
import java.time.Duration;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Resilience4j Configuration Properties */
@Data
@Validated
@ConfigurationProperties(prefix = "resilience4j")
public class Resilience4jProperties {

    private Circuitbreaker circuitbreaker = new Circuitbreaker();
    private Retry retry = new Retry();

    @Data
    public static class Circuitbreaker {
        private Instances instances = new Instances();

        @Data
        public static class Instances {
            private BinomApi binomApi = new BinomApi();
            private YoutubeApi youtubeApi = new YoutubeApi();
            private PaymentApi paymentApi = new PaymentApi();

            @Data
            public static class BinomApi {
                @Min(1)
                private int failureRateThreshold = 50;

                @Min(1)
                private int slowCallRateThreshold = 50;

                private Duration slowCallDurationThreshold = Duration.ofSeconds(5);
                private Duration waitDurationInOpenState = Duration.ofSeconds(30);

                @Min(1)
                private int minimumNumberOfCalls = 10;

                @Min(1)
                private int slidingWindowSize = 20;
            }

            @Data
            public static class YoutubeApi {
                @Min(1)
                private int failureRateThreshold = 60;

                private Duration slowCallDurationThreshold = Duration.ofSeconds(10);
                private Duration waitDurationInOpenState = Duration.ofMinutes(2);
            }

            @Data
            public static class PaymentApi {
                @Min(1)
                private int failureRateThreshold = 40;

                private Duration slowCallDurationThreshold = Duration.ofSeconds(8);
                private Duration waitDurationInOpenState = Duration.ofMinutes(1);
            }
        }
    }

    @Data
    public static class Retry {
        private Instances instances = new Instances();

        @Data
        public static class Instances {
            private BinomApi binomApi = new BinomApi();
            private YoutubeApi youtubeApi = new YoutubeApi();
            private PaymentApi paymentApi = new PaymentApi();

            @Data
            public static class BinomApi {
                @Min(1)
                private int maxAttempts = 3;

                private Duration waitDuration = Duration.ofSeconds(1);

                @Min(1)
                private double exponentialBackoffMultiplier = 2.0;
            }

            @Data
            public static class YoutubeApi {
                @Min(1)
                private int maxAttempts = 2;

                private Duration waitDuration = Duration.ofSeconds(2);
            }

            @Data
            public static class PaymentApi {
                @Min(1)
                private int maxAttempts = 2;

                private Duration waitDuration = Duration.ofSeconds(3);
            }
        }
    }
}
