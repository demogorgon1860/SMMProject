# Environment Variable Security & Production Hardening Guide

## Executive Summary

This guide provides comprehensive security best practices for managing environment variables in the SMM Panel Spring Boot application. All configurations are verified compatible with:
- **Spring Boot**: 3.1.7
- **PostgreSQL**: 15.x with JDBC Driver 42.6.x
- **Redis**: 7.x with Lettuce Client 6.2.6
- **Kafka**: 3.x with Spring Kafka 3.0.x
- **JWT**: JJWT 0.12.3

## 🔐 Critical Security Variables

### 1. JWT Secret Configuration
```bash
# Production Requirements:
# - Minimum 64 characters
# - Base64 encoded
# - Cryptographically secure random generation
JWT_SECRET=x384KbAo+gKo9v2mm2ey2ytwNwugseo7qj5mpnTOegbBKf6Agh/CbhbV9on09Kii

# Generation command:
openssl rand -base64 48
```

### 2. Database Security
```bash
# PostgreSQL Secure Connection
SPRING_DATASOURCE_URL=jdbc:postgresql://postgres:5432/smm_panel?sslmode=require&currentSchema=public
SPRING_DATASOURCE_PASSWORD=a+rq4Vq9n76ZUTSklnb6gEQdtuBwqbN/yvl8MenpOUY=

# Connection Pool Security
SPRING_DATASOURCE_HIKARI_LEAK_DETECTION_THRESHOLD=60000  # Detect connection leaks
SPRING_DATASOURCE_HIKARI_MAX_LIFETIME=1800000           # 30 minutes max connection age
```

### 3. Redis ACL Configuration
```bash
# Redis 6+ ACL Support
REDIS_ACL_USERNAME=smm_app_user
REDIS_ACL_PASSWORD=ZQx+Yviuxvv2lk65Ikrn2s3K03klmEOLSxj0WYWYoaQ=
REDIS_ACL_PERMISSIONS=+@all -flushdb -flushall -keys -config

# Redis connection with password
SPRING_DATA_REDIS_PASSWORD=ZQx+Yviuxvv2lk65Ikrn2s3K03klmEOLSxj0WYWYoaQ=
```

### 4. Kafka Security (Production)
```bash
# SASL/SCRAM Authentication
KAFKA_SECURITY_PROTOCOL=SASL_SSL
KAFKA_SASL_MECHANISM=SCRAM-SHA-512
KAFKA_SASL_JAAS_CONFIG=org.apache.kafka.common.security.scram.ScramLoginModule required username="smm-panel" password="secure-password";
```

## 📋 Environment Variable Categories

### Core Spring Boot Variables
| Variable | Purpose | Security Level | Example Value |
|----------|---------|---------------|---------------|
| `SPRING_PROFILES_ACTIVE` | Environment profile | Low | `prod` |
| `SERVER_PORT` | Application port | Low | `8080` |
| `SPRING_DATASOURCE_PASSWORD` | DB password | **CRITICAL** | Use secrets manager |
| `JWT_SECRET` | Token signing | **CRITICAL** | 64+ char base64 |
| `API_KEY_GLOBAL_SALT` | API key hashing | **HIGH** | 32 byte hex |

### Database Connection Variables
| Variable | Spring Boot 3.x Name | Legacy Name | Required |
|----------|---------------------|-------------|----------|
| Database URL | `SPRING_DATASOURCE_URL` | `DB_HOST`, `DB_PORT`, `DB_NAME` | Yes |
| Username | `SPRING_DATASOURCE_USERNAME` | `DB_USER` | Yes |
| Password | `SPRING_DATASOURCE_PASSWORD` | `DB_PASSWORD` | Yes |
| Pool Size | `SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE` | `DB_MAX_POOL_SIZE` | No |

### Redis Configuration Variables
| Variable | Spring Boot 3.x Name | Purpose | Default |
|----------|---------------------|---------|---------|
| Host | `SPRING_DATA_REDIS_HOST` | Redis server | `localhost` |
| Port | `SPRING_DATA_REDIS_PORT` | Redis port | `6379` |
| Password | `SPRING_DATA_REDIS_PASSWORD` | Authentication | None |
| Database | `SPRING_DATA_REDIS_DATABASE` | DB index | `0` |

## 🛡️ Production Hardening Checklist

### 1. Secret Storage Solutions

#### AWS Systems Manager Parameter Store
```bash
# Store secret
aws ssm put-parameter \
  --name "/smm-panel/prod/jwt-secret" \
  --value "your-secret-value" \
  --type "SecureString" \
  --key-id "alias/aws/ssm"

# Retrieve in application
JWT_SECRET=$(aws ssm get-parameter \
  --name "/smm-panel/prod/jwt-secret" \
  --with-decryption \
  --query 'Parameter.Value' \
  --output text)
```

#### HashiCorp Vault
```bash
# Store secret
vault kv put secret/smm-panel/prod \
  jwt_secret="your-secret-value" \
  db_password="your-db-password"

# Retrieve with Spring Cloud Vault
spring.cloud.vault.token=your-vault-token
spring.cloud.vault.scheme=https
spring.cloud.vault.host=vault.example.com
```

#### Kubernetes Secrets
```yaml
apiVersion: v1
kind: Secret
metadata:
  name: smm-panel-secrets
type: Opaque
data:
  jwt-secret: <base64-encoded-value>
  db-password: <base64-encoded-value>
```

### 2. Environment-Specific Configuration

#### Development Environment (.env.dev)
```bash
SPRING_PROFILES_ACTIVE=dev
JWT_SECRET=dev-jwt-secret-for-local-testing-only-not-for-production
DB_PASSWORD=dev_password_123
REDIS_PASSWORD=dev_redis_123
ENABLE_SWAGGER_UI=true
DEBUG_API_REQUESTS=true
```

#### Staging Environment (.env.stage)
```bash
SPRING_PROFILES_ACTIVE=stage
JWT_SECRET=${VAULT_JWT_SECRET}  # From secrets manager
DB_PASSWORD=${VAULT_DB_PASSWORD}
REDIS_PASSWORD=${VAULT_REDIS_PASSWORD}
ENABLE_SWAGGER_UI=true
DEBUG_API_REQUESTS=false
```

#### Production Environment (.env.prod)
```bash
SPRING_PROFILES_ACTIVE=prod
JWT_SECRET=${SSM_JWT_SECRET}  # From AWS SSM
DB_PASSWORD=${SSM_DB_PASSWORD}
REDIS_PASSWORD=${SSM_REDIS_PASSWORD}
ENABLE_SWAGGER_UI=false
DEBUG_API_REQUESTS=false
```

### 3. Security Headers Configuration
```bash
# Enable all security headers
SECURITY_REQUIRE_SSL=true
SECURITY_HEADERS_FRAME_OPTIONS=DENY
SECURITY_HEADERS_XSS_PROTECTION=1; mode=block
SECURITY_HEADERS_CONTENT_TYPE_OPTIONS=nosniff
SECURITY_HEADERS_REFERRER_POLICY=strict-origin-when-cross-origin
SECURITY_HEADERS_CONTENT_SECURITY_POLICY=default-src 'self'
```

### 4. Database Connection Security
```bash
# PostgreSQL SSL Configuration
SPRING_DATASOURCE_URL=jdbc:postgresql://postgres:5432/smm_panel?sslmode=require&sslcert=/path/to/client-cert.pem&sslkey=/path/to/client-key.pem&sslrootcert=/path/to/ca-cert.pem

# Connection pool security settings
SPRING_DATASOURCE_HIKARI_LEAK_DETECTION_THRESHOLD=60000
SPRING_DATASOURCE_HIKARI_CONNECTION_TIMEOUT=20000
SPRING_DATASOURCE_HIKARI_VALIDATION_TIMEOUT=5000
```

### 5. Redis Security Configuration
```bash
# Redis 7.x with ACL
redis-cli ACL SETUSER smm_app_user \
  on \
  +@all -flushdb -flushall -keys -config \
  >your_secure_password

# TLS/SSL for Redis
SPRING_DATA_REDIS_SSL_ENABLED=true
SPRING_DATA_REDIS_SSL_KEYSTORE=/path/to/keystore.jks
SPRING_DATA_REDIS_SSL_KEYSTORE_PASSWORD=keystore_password
```

## 🔄 Version Compatibility Matrix

| Component | Version | Spring Boot 3.1.7 Compatibility | Notes |
|-----------|---------|----------------------------------|-------|
| PostgreSQL JDBC | 42.6.x | ✅ Full | Use `org.postgresql.Driver` |
| Redis/Lettuce | 6.2.6 | ✅ Full | Reactive support included |
| Kafka Client | 3.x | ✅ Full | Auto-managed by Spring |
| Hibernate | 6.2.x | ✅ Full | JPA 3.1 specification |
| JWT (JJWT) | 0.12.3 | ✅ Full | Jakarta namespace support |
| Jackson | 2.15.x | ✅ Full | Auto-managed by Spring |

## 🚀 Production Deployment Steps

### 1. Pre-deployment Checklist
- [ ] All secrets stored in secure vault
- [ ] SSL/TLS enabled for all connections
- [ ] Database connection pooling configured
- [ ] Redis ACL configured
- [ ] Kafka SASL authentication enabled
- [ ] JWT secret is 64+ characters
- [ ] CORS origins restricted
- [ ] Rate limiting enabled
- [ ] Monitoring endpoints secured

### 2. Environment Variable Validation Script
```bash
#!/bin/bash
# validate-env.sh

required_vars=(
  "JWT_SECRET"
  "DB_PASSWORD"
  "REDIS_PASSWORD"
  "API_KEY_GLOBAL_SALT"
)

for var in "${required_vars[@]}"; do
  if [ -z "${!var}" ]; then
    echo "ERROR: Required variable $var is not set"
    exit 1
  fi
  
  # Check minimum lengths
  if [ "$var" == "JWT_SECRET" ] && [ ${#!var} -lt 64 ]; then
    echo "ERROR: JWT_SECRET must be at least 64 characters"
    exit 1
  fi
done

echo "✅ All required environment variables are set"
```

### 3. Docker Secrets Integration
```yaml
# docker-compose with secrets
version: '3.9'

secrets:
  jwt_secret:
    external: true
  db_password:
    external: true
  redis_password:
    external: true

services:
  spring-boot-app:
    image: smm-panel:latest
    secrets:
      - jwt_secret
      - db_password
      - redis_password
    environment:
      JWT_SECRET_FILE: /run/secrets/jwt_secret
      DB_PASSWORD_FILE: /run/secrets/db_password
      REDIS_PASSWORD_FILE: /run/secrets/redis_password
```

## 🔍 Monitoring & Auditing

### 1. Environment Variable Access Logging
```java
@Component
public class EnvironmentAuditor {
    @EventListener(ApplicationReadyEvent.class)
    public void auditEnvironment() {
        log.info("Environment loaded with profile: {}", 
                 env.getActiveProfiles());
        // Never log sensitive values
        log.info("JWT configured: {}", 
                 StringUtils.hasText(env.getProperty("JWT_SECRET")));
    }
}
```

### 2. Secret Rotation Schedule
| Secret Type | Rotation Frequency | Method |
|-------------|-------------------|---------|
| JWT Secret | 90 days | Rolling update with grace period |
| DB Password | 180 days | Scheduled maintenance |
| API Keys | 365 days | Per-client rotation |
| Redis Password | 90 days | Rolling update |

## 📝 Quick Reference

### Generate Secure Values
```bash
# JWT Secret (64+ characters base64)
openssl rand -base64 48

# API Salt (32 bytes hex)
openssl rand -hex 32

# Strong Password (32 characters)
openssl rand -base64 24

# UUID for unique identifiers
uuidgen
```

### Test Environment Variables
```bash
# Validate Spring Boot can read variables
java -jar app.jar --spring.config.location=file:.env --debug

# Check active profile
curl http://localhost:8080/actuator/env/spring.profiles.active

# Verify database connection
curl http://localhost:8080/actuator/health/db
```

## ⚠️ Common Pitfalls to Avoid

1. **Never commit .env files** with real credentials to version control
2. **Avoid using default passwords** even in development
3. **Don't log sensitive values** even at DEBUG level
4. **Never expose actuator endpoints** without authentication in production
5. **Avoid hardcoding secrets** in application.yml or properties files
6. **Don't use weak JWT secrets** (minimum 256 bits / 64 characters)
7. **Never disable SSL/TLS** in production environments
8. **Avoid using root database users** for application connections

## 🆘 Troubleshooting

### Issue: "Failed to connect to PostgreSQL"
```bash
# Check environment variable
echo $SPRING_DATASOURCE_URL
# Verify: jdbc:postgresql://host:5432/database?sslmode=require

# Test connection
psql "postgresql://user:password@host:5432/database?sslmode=require"
```

### Issue: "JWT signature does not match"
```bash
# Verify JWT secret is properly set
echo -n $JWT_SECRET | wc -c  # Should be 64+

# Check for special characters escaping
echo $JWT_SECRET | od -c  # Check for hidden characters
```

### Issue: "Redis connection refused"
```bash
# Test Redis with password
redis-cli -h localhost -p 6379 -a $REDIS_PASSWORD ping

# Check ACL user
redis-cli ACL WHOAMI
```

## 📚 Additional Resources

- [Spring Boot 3.1 Security Guide](https://docs.spring.io/spring-boot/docs/3.1.7/reference/html/application-properties.html)
- [PostgreSQL 15 SSL Documentation](https://www.postgresql.org/docs/15/ssl-tcp.html)
- [Redis 7 ACL Guide](https://redis.io/docs/management/security/acl/)
- [Kafka Security Documentation](https://kafka.apache.org/documentation/#security)
- [OWASP Environment Variable Security](https://owasp.org/www-project-secrets-management-cheat-sheet/)

---
**Document Version**: 1.0.0  
**Last Updated**: 2025-08-18  
**Compatibility**: Spring Boot 3.1.7