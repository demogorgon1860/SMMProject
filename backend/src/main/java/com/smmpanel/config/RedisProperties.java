package com.smmpanel.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import java.time.Duration;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Redis Configuration Properties */
@Data
@Validated
@ConfigurationProperties(prefix = "spring.data.redis")
public class RedisProperties {

    @NotBlank private String host = "localhost";

    @Min(1)
    private int port = 6379;

    private String password;

    @Min(0)
    private int database = 0;

    private Duration timeout = Duration.ofMillis(5000);

    private Jedis jedis = new Jedis();
    private Cache cache = new Cache();

    @Data
    public static class Jedis {
        private Pool pool = new Pool();

        @Data
        public static class Pool {
            private boolean enabled = true;

            @Min(1)
            private int maxActive = 50;

            @Min(1)
            private int maxIdle = 20;

            @Min(1)
            private int minIdle = 5;

            @Min(1)
            private int maxWait = 2000;
        }
    }

    @Data
    public static class Cache {
        @Min(1)
        private int timeToLive = 300000;

        private String keyPrefix = "smm:cache:";

        private boolean useKeyPrefix = true;

        private boolean cacheNullValues = false;
    }
}
