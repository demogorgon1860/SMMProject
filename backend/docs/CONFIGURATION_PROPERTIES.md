# Configuration Properties Documentation

## Overview
This document outlines all the `@ConfigurationProperties` classes that have been implemented to properly bind configuration from `application.yml` to strongly-typed Java objects.

## Configuration Properties Classes

### 1. AppProperties
**Prefix**: `app`

Comprehensive application configuration properties that bind all `app.*` configuration sections.

**Key Sections**:
- **JWT**: Token configuration (secret, expiration, issuer)
- **Security**: API key authentication settings
- **CORS**: Cross-origin resource sharing configuration
- **Rate Limit**: Rate limiting for orders, API, and auth
- **External APIs**: Binom, YouTube, Cryptomus, Selenium configurations
- **Cache**: TTL settings for different cache types
- **File**: Upload and processing settings
- **Error**: Error handling configuration
- **Order**: Order processing settings
- **Video**: Video processing configuration
- **Balance**: Balance management settings
- **Perfectpanel**: Compatibility settings
- **Features**: Feature flags
- **Monitoring**: System monitoring thresholds
- **Mail**: Email configuration
- **Notifications**: Kafka notification settings
- **Alerts**: Alert system configuration
- **Slack**: Slack integration settings

### 2. KafkaProperties
**Prefix**: `spring.kafka`

Kafka configuration properties for producers and consumers.

**Key Sections**:
- **Producer**: Serializers, acks, retries, batch settings
- **Consumer**: Deserializers, group ID, offset reset, polling settings
- **Properties**: Type mappings and serialization settings

### 3. RedisProperties
**Prefix**: `spring.data.redis`

Redis configuration properties for connection and caching.

**Key Sections**:
- **Connection**: Host, port, password, database, timeout
- **Jedis Pool**: Connection pool settings
- **Cache**: TTL, key prefix, null value handling

### 4. DatabaseProperties
**Prefix**: `spring.datasource`

Database connection configuration properties.

**Key Sections**:
- **Connection**: URL, username, password, driver
- **Hikari**: Connection pool settings (timeouts, pool sizes, leak detection)

### 5. JpaProperties
**Prefix**: `spring.jpa`

JPA and Hibernate configuration properties.

**Key Sections**:
- **Hibernate**: DDL auto, naming strategy
- **Properties**: Dialect, SQL formatting, JDBC batch settings

### 6. ServerProperties
**Prefix**: `server`

Server configuration properties.

**Key Sections**:
- **Port**: Server port
- **Servlet**: Context path
- **Compression**: Response compression settings
- **HTTP/2**: HTTP/2 support
- **Tomcat**: Connection and thread pool settings
- **Error**: Error handling configuration

### 7. ManagementProperties
**Prefix**: `management`

Spring Boot Actuator configuration properties.

**Key Sections**:
- **Endpoints**: Web exposure settings
- **Endpoint**: Individual endpoint configuration
- **Metrics**: Prometheus export settings
- **Health**: Health check configuration

### 8. LoggingProperties
**Prefix**: `logging`

Logging configuration properties.

**Key Sections**:
- **Level**: Log levels for different packages
- **Pattern**: Log format patterns
- **File**: Log file configuration
- **Logback**: Rolling policy settings

### 9. TaskProperties
**Prefix**: `spring.task`

Async task execution configuration properties.

**Key Sections**:
- **Execution**: Async task executor settings
- **Scheduling**: Scheduled task executor settings

### 10. Resilience4jProperties
**Prefix**: `resilience4j`

Circuit breaker and retry configuration properties.

**Key Sections**:
- **Circuitbreaker**: Failure thresholds, timeouts, window sizes
- **Retry**: Retry attempts, delays, backoff multipliers

### 11. OrderProcessingProperties
**Prefix**: `app.order.processing`

Order processing specific configuration.

**Key Sections**:
- **Batch Size**: Number of orders to process in batch
- **Thread Pool**: Thread pool configuration
- **Retry**: Retry settings for failed processing
- **Clip Creation**: Video clip creation settings
- **State Transition**: State transition timeouts

### 12. SlaMonitoringProperties
**Prefix**: `app.order.sla`

SLA monitoring configuration properties.

**Key Sections**:
- **Interval**: Monitoring interval settings
- **Thread Pool**: Monitoring thread pool
- **Thresholds**: Warning and critical thresholds
- **Completion**: Order completion thresholds

### 13. FraudDetectionProperties
**Prefix**: `app.order.fraud.detection`

Fraud detection configuration properties.

**Key Sections**:
- **Rate Limit**: Rate limiting for fraud detection
- **Duplicate Detection**: Duplicate order detection
- **Suspicious Patterns**: Pattern-based fraud detection
- **User Verification**: User verification requirements

## Usage Examples

### Injecting Configuration Properties

```java
@Service
@RequiredArgsConstructor
public class OrderService {
    private final AppProperties appProperties;
    private final KafkaProperties kafkaProperties;
    
    public void processOrder() {
        // Access configuration
        int batchSize = appProperties.getOrder().getProcessing().getBatchSize();
        String bootstrapServers = kafkaProperties.getBootstrapServers();
    }
}
```

### Configuration Validation

All configuration properties classes use `@Validated` and validation annotations:

```java
@Data
@Validated
@ConfigurationProperties(prefix = "app")
public class AppProperties {
    @NotBlank
    private String jwtSecret;
    
    @Min(1)
    private int maxRetries;
    
    @NotNull
    private Duration timeout;
}
```

### Environment Variable Override

Configuration can be overridden using environment variables:

```bash
# Override Kafka bootstrap servers
export SPRING_KAFKA_BOOTSTRAP_SERVERS=kafka:9092

# Override database URL
export SPRING_DATASOURCE_URL=jdbc:postgresql://prod-db:5432/smm_panel

# Override JWT secret
export APP_JWT_SECRET=your-production-secret
```

## Benefits

1. **Type Safety**: Strongly-typed configuration prevents runtime errors
2. **Validation**: Built-in validation ensures configuration correctness
3. **IDE Support**: Full IDE support with autocomplete and refactoring
4. **Documentation**: Self-documenting configuration structure
5. **Maintainability**: Centralized configuration management
6. **Testing**: Easy to test with different configuration values

## Migration from @Value

**Before** (using `@Value`):
```java
@Value("${app.jwt.secret}")
private String jwtSecret;

@Value("${app.rate-limit.orders.per-minute:10}")
private int ordersPerMinute;
```

**After** (using `@ConfigurationProperties`):
```java
@Service
@RequiredArgsConstructor
public class OrderService {
    private final AppProperties appProperties;
    
    public void processOrder() {
        String jwtSecret = appProperties.getJwt().getSecret();
        int ordersPerMinute = appProperties.getRateLimit().getOrders().getPerMinute();
    }
}
```

## Configuration Hierarchy

```
app/
├── jwt/
├── security/
├── cors/
├── rate-limit/
├── binom/
├── youtube/
├── cryptomus/
├── selenium/
├── cache/
├── file/
├── error/
├── order/
├── video/
├── balance/
├── perfectpanel/
├── features/
├── monitoring/
├── mail/
├── notifications/
├── alerts/
└── slack/

spring/
├── kafka/
├── data.redis/
├── datasource/
├── jpa/
├── task/
└── servlet/

server/
├── port
├── servlet/
├── compression/
├── http2/
├── tomcat/
└── error/

management/
├── endpoints/
├── endpoint/
├── metrics/
└── health/

logging/
├── level/
├── pattern/
├── file/
└── logback/

resilience4j/
├── circuitbreaker/
└── retry/
```

## Best Practices

1. **Use Validation**: Always add validation annotations to configuration properties
2. **Provide Defaults**: Set sensible default values for all properties
3. **Group Related Properties**: Use nested classes to group related configuration
4. **Document Properties**: Add JavaDoc comments to explain configuration options
5. **Environment-Specific**: Use profiles for environment-specific configuration
6. **Security**: Never commit sensitive configuration to version control
7. **Testing**: Test configuration with different property values 