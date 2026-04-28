# Task 09 — `/admin/settings`: persist changes or remove the page

## Context

(See `_CONTEXT.md`.)

## What's wrong

Verified via Playwright at `https://smmworld.vip/admin/settings`. Page has four configurable sections with Save buttons that **don't save anything**:

- **Platform fees**: Min order charge $0.05, Markup on reseller pricing 15%, Cryptomus fee passthrough 1% — `Save fees` button does nothing.
- **Rate limits**: Orders/minute/user 20, API requests/minute 60, Max concurrent orders/user 10 — `Save limits` button does nothing.
- **Maintenance mode**: toggle that doesn't toggle anything (no maintenance enforcement anywhere in the codebase).
- **Feature flags**:
  - **Bot-02 round-robin** — references a bot that doesn't exist (only one bot at `45.142.211.90:8080`).
  - **GPT-4o comment service** — toggle exists, no enforcement (the bot itself decides, no `bot_settings` table).
  - **Automatic refunds** — toggle exists, but `OrderService` always processes refunds on partial/cancelled regardless of flag.
  - **Reseller portal (beta)** — feature doesn't exist anywhere.

`grep -rn 'app_settings\|AppSetting\|FeatureFlag' backend/src/main/java/` returns nothing — there is no settings store at all. The values shown in the inputs are component-level useState defaults.

## What to do

### Option A — implement real settings persistence (preferred if you want the page to ship)

1. New entity `AppSetting`: `key` (PK), `value` (TEXT), `valueType` (STRING / NUMBER / BOOLEAN), `updatedAt`, `updatedBy`. Liquibase changeset.
2. `AppSettingsService` with typed accessors: `getString(key)`, `getDecimal(key)`, `getBoolean(key)`, `getInt(key)`. Cached via `@Cacheable` (TTL 60s).
3. Endpoints:
   ```
   GET /api/v2/admin/settings              → flat map of all settings
   PUT /api/v2/admin/settings/{key}        → { value: ... }
   ```
4. **Wire the settings actually**:
   - **Min order charge**: enforce in `OrderService.validateOrder` — reject if `service.rate * quantity / 1000 < minOrderCharge`.
   - **Markup**: applied as a multiplier in `Service.getEffectiveRate()` — keep base rate in DB, render marked-up rate in `/v1/service/services`.
   - **Cryptomus fee passthrough**: applied to deposit amount before crediting balance.
   - **Rate limits**: replace hardcoded 60/min in `RateLimitService` with settings-driven values.
   - **Max concurrent orders**: enforce in `OrderService.createOrder` — count user's IN_PROGRESS/PROCESSING/PENDING orders, reject if >= max.
   - **Maintenance mode**: a Spring filter (`MaintenanceFilter`) that returns 503 with maintenance message on all non-admin requests when toggle is on.
5. Audit: every `PUT /v2/admin/settings/{key}` writes to admin audit log (key, oldValue, newValue, who, when).
6. Drop "Bot-02 round-robin" toggle (single bot) and "Reseller portal" toggle (doesn't exist). Keep only the flags that map to real enforcement.
7. Frontend: `frontend/src/pages/admin/Settings.tsx` — load values via `GET /v2/admin/settings`, dispatch `PUT` per Save button. Show success/error toasts.

### Option B — remove the page (fast, honest)

1. `frontend/src/components/shells/AdminShell.tsx` — drop the `/admin/settings` nav item.
2. `frontend/src/App.tsx` — remove the `<Route path="settings">` (the route resolves to `pages/admin/Settings.tsx`).
3. Delete `frontend/src/pages/admin/Settings.tsx`.
4. Re-route any deep links (CommandPalette palette items) elsewhere or delete them.

If you don't have a clear use case for the settings or time to implement enforcement, Option B is the right call — a Settings page that lies is worse than no Settings page.

## Verify (Option A)

1. Change Min order charge to $1, save → next order under $1 is rejected with "Order amount below minimum charge".
2. Change Max concurrent orders to 1 → user with 1 in-progress order tries to place a second → rejected.
3. Toggle Maintenance mode on → all `/api/v1/*` requests return 503 except admin endpoints. Admin can still flip it back off.
4. Settings persist across container restart.
5. Audit log shows every change with admin id.

## Production / best practices

Make sure everything is **maximum production-ready and follows best practices**. Settings drive real behavior (fees, rate limits, maintenance) — they need defensive defaults if a key is missing, type validation on PUT, and audit logging. Cache invalidation on PUT (`@CacheEvict`) is mandatory or you'll be debugging stale settings for hours. If you go Option B, don't leave dead nav items or imports — clean cut.
