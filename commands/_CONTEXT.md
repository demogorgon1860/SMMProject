# Shared context — paste before any task file

## Repo

`c:\Users\user\Desktop\Projects\` (monorepo, branch `main`, deploy directly to main):
- `backend/` — Spring Boot 3.1.7, Java 17, JPA/Hibernate, Liquibase migrations only (NOT Flyway), Redis (Lettuce, not Jedis), RabbitMQ, JWT auth + refresh-token-in-HttpOnly-cookie
- `frontend/` — Vite + React 18 + TypeScript + Tailwind + Zustand. Builds to `frontend/dist/`. Bundle currently ~95 kB gzip.
- `commands/` — task queue, this folder.

Bot lives **outside the monorepo** at `c:\Users\user\Desktop\instagramBot` (Go 1.21+, browser automation via rod, antidetect via AdsPower, AI comments via OpenAI GPT-4o Vision). Bot's prod instance: `http://45.142.211.90:8080`. Bot communicates with the panel two ways that must stay in sync: RabbitMQ queue `instagram_results` consumed by `InstagramResultConsumer`, and HTTP webhook `POST /api/webhook/instagram` handled by `InstagramService`.

## Production

- Server: `45.142.211.90`, SSH login `Админ`, panel at `C:\SMMPanel`
- Postgres: container `smm_postgres`, user `smm_admin`, db `smm_panel`
- Deploy: `git pull && docker-compose up -d --build` on the server (frontend rebuild via Vite during the docker build)
- Liquibase migrations only — `backend/src/main/resources/db/changelog/`. NEVER run `./gradlew flywayMigrate` or `flywayValidate`
- Cloudflare in front (Origin SSL cert active)

## Test creds

- Admin: `admin / Admin@2025!Secure`
- User: `mmknshnk / master1860` ($828.47 balance, real history)

## Critical conventions

1. **Money fields** are `BigDecimal` on the backend, serialized as JSON strings. Frontend must coerce with `Number.parseFloat` before arithmetic. There's a `toNum()` helper duplicated in 3 files — extract to `frontend/src/lib/utils.ts` if you touch it.
2. **Order status filter** accepts both native enum names (`IN_PROGRESS`) and PerfectPanel human strings (`"in progress"`). See `OrderService.mapFromPerfectPanelStatus` for the canonical resolver.
3. **Two result paths** from the Instagram bot — RabbitMQ + HTTP webhook. Any change to result handling must update both `InstagramResultConsumer.java` and `InstagramService.java`.
4. **Refresh token** lives ONLY in an HttpOnly cookie; the access token is in localStorage. Never persist refresh token client-side.
5. **`@PreAuthorize("hasAnyRole('ADMIN', 'OPERATOR')")`** is the standard guard for admin endpoints. Apply it consistently.
6. **Spotless** runs in CI: `cd backend && ./gradlew spotlessApply` before commit.
7. **Frontend build**: `cd frontend && npm run build`. Type-check separately with `npx tsc --noEmit`.
8. **Liquibase migrations**: every changeset must include a `<rollback>` block.

## Recent state (HEAD as of writing)

`b0b04895`. Multi-bot is on the radar but currently one bot only. YouTube and Binom code is still in the repo as dead weight (separate cleanup tasks). Email delivery is gated by `EMAIL_ENABLED` and `RESEND_API_KEY` env vars on prod — if not set, all transactional mail is logged and silently dropped (a startup banner now shouts about it).

---
