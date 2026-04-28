# Task 14 — Production hardening RUNBOOK (manual on-prod steps)

This is the operator-facing companion to `14-production-hardening.md`. Code-side
changes (logback profile, Sentry wiring, health probe groups, graceful shutdown,
docker-compose tweaks, hardcoded-secret removal) are merged in this branch — they
ship with the next `git pull && docker-compose up -d --build` on prod.

The steps below cannot be done from the repo and need to be executed by a human
on `45.142.211.90`. **Per repo memory, never edit prod `.env.docker` via a remote
script — print the lines, edit them by hand on the server.**

---

## Pre-deploy checklist (after pulling this branch)

The branch removed inlined defaults for `TELEGRAM_BOT_TOKEN`, `TELEGRAM_CHAT_ID`,
and `TELEGRAM_PROXY_*`. If those env vars are not set on prod, the Telegram
integration silently no-ops on boot. Confirm they're present:

```bash
ssh Админ@45.142.211.90
cd C:\SMMPanel
grep -E "^(TELEGRAM_BOT_TOKEN|TELEGRAM_CHAT_ID|TELEGRAM_PROXY_)" .env.docker
```

If any are missing, add them by hand. Then deploy:

```bash
git pull
docker-compose up -d --build spring-boot-app
docker-compose logs --tail=200 spring-boot-app | grep -i "Email delivery\|EMAIL DELIVERY\|Sentry\|webhook"
```

---

## 14.6 nginx security headers (host-level vhost)

The in-repo `nginx/conf.d/smm-panel.conf` is the *docker-internal* nginx (sits in
front of the Spring app on the docker bridge). The headers visible to real
clients are set by the **host nginx vhost** that terminates TLS — typically at
`C:\SMMPanel\nginx.conf` or `C:\nginx\conf\sites\smmworld.vip.conf`.

Apply the same headers at the host level:

```nginx
# Inside the `server { listen 443 ssl; server_name smmworld.vip ... }` block.
add_header Strict-Transport-Security "max-age=31536000; includeSubDomains" always;
add_header X-Content-Type-Options "nosniff" always;
add_header X-Frame-Options "SAMEORIGIN" always;
add_header Referrer-Policy "strict-origin-when-cross-origin" always;
add_header Permissions-Policy "geolocation=(), camera=(), microphone=(), interest-cohort=()" always;
add_header Content-Security-Policy "default-src 'self'; script-src 'self' 'unsafe-inline' 'unsafe-eval' https://static.cloudflareinsights.com; style-src 'self' 'unsafe-inline'; img-src 'self' data: https:; font-src 'self' data:; connect-src 'self' ws: wss: https://api.smmworld.vip https://*.sentry.io; frame-ancestors 'self'; base-uri 'self'; form-action 'self';" always;
```

Validate before reloading:

```bash
nginx -t
nginx -s reload
```

Verify externally with `https://securityheaders.com/?q=https://smmworld.vip` — target grade is A.

**CSP gotcha**: the SPA uses Tailwind which ships runtime-injected styles, so
`'unsafe-inline'` for styles is mandatory until we wire up nonce-based CSP. The
`'unsafe-eval'` on scripts is there because some lazy-loaded chunks evaluate
runtime templates — drop it once the legacy bundle path is gone.

---

## 14.7 Secrets — found leaks in git history, must rotate

Local git scan found two real values committed in earlier history:

| Secret           | Value (truncated)                          | Status                      |
|------------------|--------------------------------------------|-----------------------------|
| `JWT_SECRET`     | `Mhd6YElbFvskMPTHMGYa...` (base64, 88c)   | **LEAKED — must rotate**    |
| `TELEGRAM_BOT_TOKEN` | `8577578909:AAGhCFEUm_y6...`           | **LEAKED — must rotate**    |
| `TELEGRAM_PROXY_*` | host=`isp.decodo.com`, user `sp6fe1b086`, password committed | **LEAKED — must rotate** |

The branch already strips the inline defaults, so future commits will not re-leak.
But the values themselves are public, and rotating them is mandatory.

### JWT_SECRET rotation

Rotating `JWT_SECRET` invalidates every active access token (users get logged
out — they can log back in immediately). Refresh tokens are also re-signed.

Generate a fresh 64-byte secret:

```bash
openssl rand -base64 64 | tr -d '\n'
```

On prod:

```bash
ssh Админ@45.142.211.90
cd C:\SMMPanel
# Print the line, edit by hand — never have a script touch this file.
grep -n "^JWT_SECRET=" .env.docker
# Open .env.docker, replace the value, save.
docker-compose up -d --build spring-boot-app
```

Verify by logging in via the SPA — old sessions should require re-login, new
JWT tokens should be issued.

### TELEGRAM_BOT_TOKEN rotation

1. Open Telegram → `@BotFather` → `/revoke` → select the bot → confirm. The
   old token is dead immediately.
2. `/token` → select the bot → BotFather replies with the new token.
3. Update `.env.docker` (`TELEGRAM_BOT_TOKEN=<new value>`).
4. `docker-compose up -d --build spring-boot-app`.
5. The webhook re-registers automatically on startup
   (`TelegramWebhookRegistrar`); confirm in logs:
   `grep "Telegram webhook" docker-compose logs`.

### Telegram proxy credential rotation

If the Decodo account is still in use:

1. Log in to the proxy provider, regenerate the per-port credential.
2. Update `TELEGRAM_PROXY_USERNAME` / `TELEGRAM_PROXY_PASSWORD` in `.env.docker`.
3. Redeploy.

If the proxy is no longer needed, set `TELEGRAM_PROXY_ENABLED=false` and remove
the variables.

### Rotation calendar

Add to your team's calendar / scheduled-agent rotation:

| Secret               | Cadence    | Notes                                              |
|----------------------|------------|----------------------------------------------------|
| `JWT_SECRET`         | 90 days    | One-time logout each rotation; no grace needed     |
| `RESEND_API_KEY`     | 90 days    | Resend dashboard → API keys                        |
| `CRYPTOMUS_PAYMENT_API_KEY` / `CRYPTOMUS_API_SECRET` / `CRYPTOMUS_WEBHOOK_SECRET` | 90 days | Cryptomus dashboard → integrations |
| `TELEGRAM_BOT_TOKEN` | on suspicion or every 180 days | `@BotFather` → `/revoke`            |
| `REDIS_PASSWORD` / `RABBITMQ_PASSWORD` / `DB_PASSWORD` | 365 days | requires container restart of dependents |
| `API_KEY_GLOBAL_SALT` | NEVER unless breach | Rotating invalidates every user's API key — full lockout |

Long-term: move to HashiCorp Vault or AWS SSM, mount at runtime. Not a Q1 task.

---

## 14.8 EMAIL_ENABLED — verify on prod

The `EmailService` startup banner (`874acb29`) shouts about misconfiguration. Check:

```bash
ssh Админ@45.142.211.90
cd C:\SMMPanel
docker-compose logs spring-boot-app | grep -A1 "EMAIL DELIVERY\|Email delivery"
```

Expected good state:
```
Email delivery active via Resend (from=SMMWorld <hello@smmworld.vip>, base-url=https://smmworld.vip)
```

If you see `EMAIL DELIVERY DISABLED` or `EMAIL DELIVERY ENABLED BUT RESEND_API_KEY IS BLANK`:

```bash
# Print the offending lines so you can edit by hand.
grep -n "^EMAIL_ENABLED=\|^RESEND_API_KEY=\|^EMAIL_FROM=" .env.docker
```

Set:

```
EMAIL_ENABLED=true
RESEND_API_KEY=re_<actual-key>
EMAIL_FROM=hello@smmworld.vip
```

Then redeploy and re-check the banner.

---

## 14.9 PostgreSQL backups

If no backup is currently running, set up the cron + offsite upload now.

### Cron job (on prod host)

Add to crontab:

```
# /etc/crontab or `crontab -e` (running as root or a user in the docker group)
0 3 * * * docker exec smm_postgres pg_dump -U smm_admin -F c smm_panel | gzip > /backups/smm_panel-$(date +\%F).sql.gz 2>> /backups/backup.log
# Cleanup: keep 30 daily, 12 monthly (the first of every month).
5 3 * * * find /backups -maxdepth 1 -name 'smm_panel-*.sql.gz' -mtime +30 ! -name 'smm_panel-*-01.sql.gz' -delete
```

`/backups` should be on a separate volume (or mounted from the host, not the
postgres container's internal volume — a `docker volume rm` shouldn't be able to
nuke your backups).

### Offsite upload (Backblaze B2 / S3 / Wasabi)

Pick a provider, generate scoped write-only credentials, install `rclone` or
`aws-cli`, and add a second cron line:

```
30 3 * * * rclone copy /backups/smm_panel-$(date +\%F).sql.gz b2:smm-panel-backups/$(date +\%Y/\%m)/ --b2-hard-delete
```

### Test restore — quarterly

A backup that has never been restored is theater. Every quarter:

```bash
docker run -d --name pg-restore-test -e POSTGRES_PASSWORD=test postgres:15-alpine
gunzip -c /backups/smm_panel-2026-01-15.sql.gz | docker exec -i pg-restore-test pg_restore -U postgres -d postgres -C
docker exec pg-restore-test psql -U postgres -d smm_panel -c "SELECT count(*) FROM users; SELECT max(created_at) FROM orders;"
docker rm -f pg-restore-test
```

If the counts/timestamps match prod's last known state, the backup is good.
File the restore-test result in your team channel so there's an audit trail.

---

## Verify (end-to-end after deploy)

1. `curl -s http://45.142.211.90:8080/actuator/health/readiness` → `{"status":"UP"}`.
2. `curl -s http://45.142.211.90:8080/actuator/health/liveness` → `{"status":"UP"}`.
3. `docker-compose logs --tail=20 spring-boot-app | head -1 | jq .` parses (JSON encoder active under `prod` profile).
4. Trigger a 500 in stage (e.g. POST a malformed body to a known-broken stub):
   Sentry receives the event with `correlationId` matching the response header
   `X-Correlation-Id` within ~30s.
5. `docker-compose logs spring-boot-app | grep -A1 "Email delivery"` → "active via Resend".
6. `git log --all -p -- .env.docker 2>/dev/null` should be empty (the file is `.gitignore`d).
7. `pg_dump` cron has produced today's `/backups/smm_panel-YYYY-MM-DD.sql.gz`.
