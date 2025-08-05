# API Key Security Requirements & Implementation Guide

## Overview

This document outlines the comprehensive security requirements, implementation details, and best practices for API key authentication in the SMM Panel application.

## Security Architecture

### Core Security Principles

1. **Defense in Depth**: Multiple layers of security protection
2. **Principle of Least Privilege**: Minimal access rights by default
3. **Fail Secure**: Security failures should deny access, not grant it
4. **Zero Trust**: Verify every request regardless of source
5. **Performance Security**: Security that doesn't compromise performance

## API Key Generation

### Cryptographic Requirements

```
- Algorithm: Cryptographically Secure Pseudo Random Number Generator (CSPRNG)
- Key Length: 64 characters (alphanumeric)
- Character Set: [a-zA-Z0-9] (62 possible characters per position)
- Entropy: ~380 bits (log2(62^64))
- Generation Rate: Unlimited (no practical collision risk)
```

### Implementation Details

```java
// Secure generation using SecureRandom
private static final SecureRandom SECURE_RANDOM = new SecureRandom();
private static final int API_KEY_LENGTH = 64;

public String generateApiKey() {
    return RandomStringUtils.random(API_KEY_LENGTH, 0, 0, true, true, null, SECURE_RANDOM);
}
```

### Security Properties

- **Unpredictability**: Cannot be guessed or predicted
- **Uniqueness**: Collision probability < 2^-380
- **Non-sequential**: No pattern in generation
- **High entropy**: Maximum randomness per character

## Salt Generation

### Requirements

```
- Algorithm: Cryptographically secure random bytes
- Salt Length: 32 bytes (256 bits)
- Encoding: Base64 for storage
- Uniqueness: Every API key gets unique salt
- Storage: Separate column from hash
```

### Implementation

```java
public String generateSalt() {
    byte[] salt = new byte[SALT_LENGTH];
    SECURE_RANDOM.nextBytes(salt);
    return Base64.getEncoder().encodeToString(salt);
}
```

### Security Benefits

- **Rainbow Table Protection**: Pre-computed attacks impossible
- **Dictionary Attack Mitigation**: Same password produces different hashes
- **Collision Resistance**: Unique salt per key prevents hash collisions

## Hash Function Security

### Algorithm: SHA-512

```
- Output Size: 512 bits (128 hex characters)
- Security Level: 256-bit security against collision attacks
- Performance: ~1000 hashes/second per core
- NIST Approval: FIPS 180-4 approved
```

### Dual-Salt Architecture

#### 1. Global Salt (Lookup Hash)
```java
// For database index performance
@Value("${app.security.api-key.global-salt:smm-panel-secure-salt-2024}")
private String globalSalt;

public String hashApiKeyForLookup(String apiKey) {
    return hashApiKey(apiKey, globalSalt);
}
```

**Purpose**: Fast database lookups while preventing rainbow table attacks

#### 2. User Salt (Verification Hash)
```java
// For secure verification
public boolean verifyApiKey(String apiKey, String storedHash, String userSalt) {
    String computedHash = hashApiKey(apiKey, userSalt);
    return MessageDigest.isEqual(computedHash.getBytes(), storedHash.getBytes());
}
```

**Purpose**: Individual user security with unique salts

### Security Analysis

- **Collision Resistance**: 2^256 operations to find collision
- **Preimage Resistance**: 2^512 operations to reverse hash
- **Avalanche Effect**: 50% bit change from 1-bit input change
- **Deterministic**: Same input always produces same output

## Constant-Time Comparison

### Timing Attack Prevention

```java
// SECURE: Uses MessageDigest.isEqual() for constant-time comparison
return MessageDigest.isEqual(
    computedHash.getBytes(StandardCharsets.UTF_8), 
    storedHash.getBytes(StandardCharsets.UTF_8)
);

// INSECURE: Vulnerable to timing attacks
// return computedHash.equals(storedHash);
```

### Security Benefits

- **Timing Attack Immunity**: Comparison time independent of input
- **Side-Channel Resistance**: No information leakage through timing
- **Brute Force Protection**: Cannot optimize attacks using timing

## Rate Limiting Architecture

### Multi-Layer Rate Limiting

#### 1. IP-Based Rate Limiting
```
- Window: 15 minutes
- Max Attempts: 5 failed attempts
- Lockout: 30 minutes
- Scope: Per IP address
```

#### 2. API Key Prefix Rate Limiting
```
- Identifier: First 8 characters of API key hash
- Window: 15 minutes  
- Max Attempts: 5 failed attempts
- Lockout: 30 minutes
```

#### 3. Combined Identifier Rate Limiting
```
- Format: IP:API_KEY_PREFIX
- Example: "192.168.1.100:api:abcd1234"
- Prevents both IP and key-based attacks
```

### Implementation Details

```java
public boolean verifyApiKeyOnly(String apiKey, String apiKeyHash, 
                               String apiKeySalt, String clientIdentifier) {
    // Check rate limiting first
    if (rateLimitService.isRateLimited(clientIdentifier)) {
        return false;
    }
    
    boolean isValid = apiKeyGenerator.verifyApiKey(apiKey, apiKeyHash, apiKeySalt);
    
    if (isValid) {
        rateLimitService.recordSuccessfulAttempt(clientIdentifier);
    } else {
        rateLimitService.recordFailedAttempt(clientIdentifier);
    }
    
    return isValid;
}
```

### Redis Storage Schema

```
Keys:
- auth:rate_limit:{identifier} → attempt count (TTL: 15 min)
- auth:lockout:{identifier} → locked status (TTL: 30 min)

Values:
- Attempt count: Integer (1-5)
- Lockout: String "locked"
```

## Database Security

### Storage Requirements

```sql
-- User table columns for API key security
api_key_hash VARCHAR(128) NOT NULL,    -- SHA-512 hash with global salt
api_key_salt VARCHAR(64) NOT NULL,     -- Base64 encoded user salt
api_key_last_rotated TIMESTAMP,        -- Key rotation tracking
last_api_access_at TIMESTAMP,          -- Access pattern monitoring
```

### Index Strategy

```sql
-- Partial index for active users only (performance optimization)
CREATE INDEX CONCURRENTLY idx_users_api_key_hash_active 
ON users(api_key_hash) 
WHERE is_active = true AND api_key_hash IS NOT NULL;

-- Composite index for lookup with active filtering
CREATE INDEX CONCURRENTLY idx_users_api_key_lookup 
ON users(api_key_hash, is_active) 
WHERE api_key_hash IS NOT NULL;
```

### Security Benefits

- **Partial Indexes**: Reduced index size and faster lookups
- **No Plain Text Storage**: API keys never stored in readable form
- **Separation of Concerns**: Lookup hash ≠ verification hash

## Performance Considerations

### Authentication Flow Timing

```
1. API Key Hashing: ~0.1ms
2. Database Lookup: ~1-5ms (with optimized indexes)
3. Rate Limit Check: ~0.1ms (Redis lookup)
4. Hash Verification: ~0.1ms
5. Total: ~1.3-5.3ms per authentication
```

### Optimization Strategies

1. **No Database Writes**: Authentication doesn't update database
2. **Optimized Indexes**: Partial indexes for active users only
3. **Redis Caching**: Rate limit data in memory
4. **Async Tracking**: Access logging happens asynchronously

### Performance Monitoring

```java
StopWatch stopWatch = new StopWatch("API Key Authentication");

stopWatch.start("Hash API Key");
String hashedApiKey = apiKeyService.hashApiKeyForLookup(apiKey);
stopWatch.stop();

stopWatch.start("Database Lookup");
Optional<User> userOpt = userRepository.findByApiKeyHashAndIsActiveTrue(hashedApiKey);
stopWatch.stop();

stopWatch.start("API Key Validation");
boolean isValid = validateApiKeyWithRateLimit(apiKey, user, clientIdentifier);
stopWatch.stop();

log.info("Authentication timing: {}", stopWatch.prettyPrint());
```

## Threat Model & Mitigations

### 1. Brute Force Attacks

**Threat**: Automated attempts to guess API keys
**Mitigation**: 
- Rate limiting (5 attempts per 15 minutes)
- Progressive lockouts (30 minutes)
- High entropy keys (2^380 keyspace)

### 2. Rainbow Table Attacks

**Threat**: Pre-computed hash tables for common passwords
**Mitigation**:
- Unique salts per API key
- Global salt for lookup hashes
- SHA-512 with sufficient salt length

### 3. Timing Attacks

**Threat**: Information leakage through response timing
**Mitigation**:
- Constant-time comparison using MessageDigest.isEqual()
- Consistent error responses
- Performance monitoring to detect anomalies

### 4. Database Compromise

**Threat**: Attacker gains access to database
**Mitigation**:
- No plaintext API keys stored
- Separate lookup and verification hashes
- Salt separation prevents mass cracking

### 5. Side-Channel Attacks

**Threat**: Information leakage through system behavior
**Mitigation**:
- Constant-time operations
- Rate limiting prevents probing
- Minimal error information disclosure

### 6. Replay Attacks

**Threat**: Intercepted API keys used maliciously
**Mitigation**:
- HTTPS enforcement for all API communication
- API key rotation capabilities
- Access pattern monitoring

## Compliance & Standards

### Industry Standards

- **NIST SP 800-63B**: Digital Identity Guidelines (Authentication)
- **OWASP ASVS**: Application Security Verification Standard
- **ISO 27001**: Information Security Management
- **PCI DSS**: Payment Card Industry Data Security Standard

### Implementation Compliance

```
✅ Strong authentication factors (NIST 800-63B Level 2)
✅ Cryptographically random tokens (NIST SP 800-90A)
✅ Secure storage of authentication credentials
✅ Rate limiting and account lockout (OWASP ASVS V2.2)
✅ Constant-time comparison (OWASP ASVS V2.3)
✅ Comprehensive logging and monitoring
```

## Security Testing Requirements

### Automated Security Tests

1. **Randomness Testing**: Verify key generation entropy
2. **Collision Testing**: Test hash collision resistance
3. **Timing Analysis**: Validate constant-time operations
4. **Rate Limit Testing**: Verify lockout mechanisms
5. **Performance Testing**: Ensure security doesn't impact performance

### Penetration Testing Scenarios

1. **Brute Force Testing**: Automated key guessing attempts
2. **Timing Attack Testing**: Response time analysis
3. **Rate Limit Bypass**: Distributed attack simulation
4. **Hash Analysis**: Collision and preimage testing

## Configuration Security

### Environment Variables

```bash
# Production settings
APP_SECURITY_API_KEY_GLOBAL_SALT=your-unique-global-salt-here
APP_SECURITY_RATE_LIMIT_MAX_ATTEMPTS=5
APP_SECURITY_RATE_LIMIT_WINDOW_MINUTES=15
APP_SECURITY_RATE_LIMIT_LOCKOUT_MINUTES=30
```

### Security Hardening

```yaml
app:
  security:
    api-key:
      enabled: true
      global-salt: ${APP_SECURITY_API_KEY_GLOBAL_SALT}
    rate-limit:
      max-attempts: ${APP_SECURITY_RATE_LIMIT_MAX_ATTEMPTS:5}
      window-minutes: ${APP_SECURITY_RATE_LIMIT_WINDOW_MINUTES:15}
      lockout-minutes: ${APP_SECURITY_RATE_LIMIT_LOCKOUT_MINUTES:30}
```

## Monitoring & Alerting

### Security Metrics

1. **Authentication Success Rate**: Monitor for anomalies
2. **Rate Limit Triggers**: Track blocked attempts
3. **Response Time Distribution**: Detect timing attacks
4. **Failed Authentication Patterns**: Identify attack campaigns

### Alert Thresholds

```
- Failed authentication rate > 10% → Warning
- Rate limit triggers > 100/hour → Alert
- Authentication response time > 100ms → Investigation
- Unusual geographic patterns → Security review
```

## Incident Response

### Security Incident Types

1. **Suspected Brute Force**: High failed authentication rates
2. **Timing Anomalies**: Unusual response time patterns  
3. **Rate Limit Exhaustion**: Excessive lockouts
4. **Database Compromise**: Unauthorized access to hashes

### Response Procedures

1. **Immediate**: Activate additional rate limiting
2. **Short-term**: Force API key rotation for affected users
3. **Long-term**: Review and enhance security controls
4. **Post-incident**: Update threat model and defenses

## Maintenance & Updates

### Regular Security Tasks

1. **Monthly**: Review authentication logs and patterns
2. **Quarterly**: Update global salt and security parameters
3. **Annually**: Full security assessment and penetration testing
4. **As needed**: Emergency API key rotation procedures

### Version Control Security

- API key security configuration in version control
- Security test coverage requirements
- Code review requirements for security changes
- Documentation updates with security changes

---

**Document Version**: 1.0  
**Last Updated**: 2024  
**Review Schedule**: Quarterly  
**Owner**: Security Team  
**Approvers**: CTO, Security Officer