# Task 14 — Production hardening (logging, Sentry, health, shutdown, headers, secrets, backups)

## Context

(See `_CONTEXT.md`.)

A grouped pass on the operational layer — the things that don't matter on day 1 but bite hard on month 6. None individually deserves its own PR; all together they take the panel from "works" to "operates".

## What's wrong + what to do

### 14.1 Logging — make production logs structured + observable

Current state: default Spring Boot text format with no correlation IDs, debug-level chattiness in some packages. Hard to grep, harder to ship to a log aggregator.

**What to do:**

1. Add `logstash-logback-encoder` dependency.
2. `backend/src/main/resources/logback-spring.xml`:
   ```xml
   <configuration>
     <springProfile name="prod">
       <appender name="JSON" class="ch.qos.logback.core.ConsoleAppender">
         <encoder class="net.logstash.logback.encoder.LogstashEncoder">
           <includeMdcKeyName>correlationId</includeMdcKeyName>
           <includeMdcKeyName>userId</includeMdcKeyName>
         </encoder>
       </appender>
       <root level="INFO"><appender-ref ref="JSON"/></root>
     </springProfile>
     <springProfile name="default,dev">
       <!-- pretty console for local dev -->
     </springProfile>
   </configuration>
   ```
3. Add a `CorrelationIdFilter` that puts a UUID in MDC for every request (and propagates incoming `X-Correlation-Id` header if present). All logs carry the id.
4. Set per-package levels in `application.yml`:
   ```yaml
   logging:
     level:
       root: INFO
       com.smmpanel: INFO
       org.hibernate.SQL: WARN          # stop SQL spam in prod
       org.springframework.security: INFO
   ```
5. Sensitive field redaction: never log `passwordHash`, `apiKeyHash`, `password`, `RESEND_API_KEY`, or full Cryptomus webhook bodies. Add a Logback filter or sanitize in code.

### 14.2 Sentry (or similar) — actually catch errors

There is no error reporting today. A 500 in the admin panel goes to `docker-compose logs` and nowhere else.

**What to do:**

1. Backend: `sentry-spring-boot-starter`.
   - Set `SENTRY_DSN` in `.env.docker` (env var only, never in `application.yml`).
   - Add a `@ControllerAdvice` for unhandled exceptions that captures to Sentry + returns a sanitized 500 JSON.
2. Frontend: `@sentry/react`.
   - Initialize in `frontend/src/main.tsx` with the same DSN (separate project for frontend in Sentry).
   - Wrap the existing `<ErrorBoundary>` from `react-error-boundary` to also report.
   - Track release version by build hash so source-map debugging works.
3. Alerts: Sentry rules for: 5xx rate > 1%, payment-failure rate > 5%, bot circuit-breaker triggers.

### 14.3 Actuator health: liveness vs readiness

Single `/actuator/health` exists. For docker rolling restart you want separate liveness (is the process alive?) and readiness (is it ready to serve traffic?).

**What to do:**

1. `application.yml`:
   ```yaml
   management:
     endpoint:
       health:
         probes:
           enabled: true
         group:
           liveness:
             include: livenessState
           readiness:
             include: readinessState, db, redis, rabbit
     health:
       livenessState:
         enabled: true
       readinessState:
         enabled: true
   ```
2. `docker-compose.yml`: separate healthcheck for the spring-boot-app:
   ```yaml
   healthcheck:
     test: curl -f http://localhost:8080/actuator/health/readiness || exit 1
     start_period: 60s
     interval: 10s
     timeout: 5s
     retries: 3
   ```
3. Liveness should NEVER fail on transient deps — only if the JVM is actually broken.

### 14.4 Graceful shutdown

Default Spring shutdown is "drop in-flight requests immediately" — bad during deploys.

**What to do:**

1. `application.yml`:
   ```yaml
   server:
     shutdown: graceful
   spring:
     lifecycle:
       timeout-per-shutdown-phase: 30s
   ```
2. `docker-compose.yml` for the spring service: `stop_grace_period: 35s`.
3. Verify: deploy during a slow request → response completes before container exits.

### 14.5 Connection pool tuning

`HikariCP` defaults are usually fine but worth a sanity check.

**What to do:**

```yaml
spring:
  datasource:
    hikari:
      maximum-pool-size: 20            # cap based on pg max_connections / replicas
      minimum-idle: 5
      connection-timeout: 5000
      idle-timeout: 300000
      max-lifetime: 600000              # < pg connection timeout
      validation-timeout: 1500
      leak-detection-threshold: 30000  # log leaks after 30s
  data:
    redis:
      lettuce:
        pool:
          max-active: 16
          max-idle: 8
          min-idle: 2
```

Verify on prod with `pg_stat_activity` count vs pool size.

### 14.6 Security headers (nginx)

SSH to prod, edit `C:\SMMPanel\nginx.conf` (or wherever the cert + reverse-proxy lives).

```
add_header Strict-Transport-Security "max-age=31536000; includeSubDomains" always;
add_header X-Content-Type-Options "nosniff" always;
add_header X-Frame-Options "SAMEORIGIN" always;
add_header Referrer-Policy "strict-origin-when-cross-origin" always;
add_header Permissions-Policy "geolocation=(), camera=(), microphone=()" always;
add_header Content-Security-Policy "default-src 'self'; script-src 'self' 'unsafe-inline' https://static.cloudflareinsights.com; style-src 'self' 'unsafe-inline'; img-src 'self' data: https:; connect-src 'self' https://api.smmworld.vip; frame-ancestors 'self';" always;
```

Test with [securityheaders.com](https://securityheaders.com/) — aim for A or A+.

CSP needs tuning — Tailwind's runtime-injected styles need `'unsafe-inline'` for now (or hash them). Cloudflare's beacon needs whitelisting if RUM is on.

### 14.7 Secrets handling

Verify on prod:
1. SSH in, `docker-compose config` (with `--no-interpolate`) — no plaintext secrets in compose. They should come from `.env.docker` only.
2. `git log --all -p | grep -E 'RESEND_API_KEY=re_|CRYPTOMUS_API_KEY=|JWT_SECRET=|TELEGRAM_BOT_TOKEN='` — if anything matches, that secret is **already leaked** and must be rotated.
3. Set up rotation calendar:
   - JWT_SECRET — every 90d (with grace period, dual-validation during rollover)
   - RESEND_API_KEY — every 90d (or on suspicion)
   - CRYPTOMUS_API_KEY/SECRET — every 90d
4. Long-term: move to HashiCorp Vault or AWS SSM, mount at runtime.

### 14.8 Verify EMAIL_ENABLED + RESEND_API_KEY on prod

Customer reported "registered, no code came" (Task 03 of fixed work). The fix `874acb29` added a startup banner — go check the logs:

```
ssh Админ@45.142.211.90
docker-compose logs spring-boot-app | grep -A5 "EMAIL DELIVERY"
```

If you see "EMAIL DELIVERY DISABLED": set in `.env.docker`:
```
EMAIL_ENABLED=true
RESEND_API_KEY=re_<actual-key>
EMAIL_FROM=hello@smmworld.vip
```
Restart: `docker-compose up -d --build spring-boot-app`. Confirm the next startup logs "Email delivery active via Resend".

Same audit for `TELEGRAM_BOT_TOKEN` + `TELEGRAM_CHAT_ID` (Task 16 covers).

### 14.9 Backups

Verify backup strategy. If none:

1. Cron job on prod:
   ```
   0 3 * * * docker exec smm_postgres pg_dump -U smm_admin -F c smm_panel | \
     gzip > /backups/smm_panel-$(date +%F).sql.gz
   # then upload to S3-compatible store (Backblaze B2 / R2 / Wasabi)
   ```
2. Retention: 30 daily, 12 monthly.
3. **Test restore quarterly**: actually run `pg_restore` to a fresh container, smoke-test the panel against it. A backup that never restored isn't a backup.

## Verify

1. Logs in prod are JSON, every line has correlationId + level. `docker-compose logs spring-boot-app | head -1 | jq` parses.
2. Trigger an exception → Sentry receives it within 30s with stack trace + correlation id.
3. `curl /actuator/health/readiness` returns `{status: 'UP'}`. `curl /actuator/health/liveness` likewise.
4. Deploy during an in-flight request → request completes, no error.
5. `pg_stat_activity` count stays below `maximum-pool-size` × instances.
6. securityheaders.com → A grade.
7. `grep -E 'RESEND_API_KEY=re_' git log --all -p` → 0 matches (or all matches are pre-rotation).
8. EMAIL_ENABLED check passes on prod (no startup warning).
9. Restore one backup to a fresh container, log in as admin, see real data.

## Production / best practices

Make sure everything is **maximum production-ready and follows best practices**. This is the section where "good enough" comes back to bite. Logs without correlation ids are hard to debug. Sentry without release tracking is hard to triage. Backups without restore tests are theater. Don't ship 14.6 (CSP) without testing every page — a wrong directive can wipe out your stylesheet on prod. Security headers should fail-closed: tighten progressively, don't auto-publish a default that allows everything.
