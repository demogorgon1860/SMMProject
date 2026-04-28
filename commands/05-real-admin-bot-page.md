# Task 07 — Real `/admin/bot` page (Instagram Bot fleet view)

## Context

(See `_CONTEXT.md`.)

The Instagram bot is the Go service at `c:\Users\user\Desktop\instagramBot`, prod at `http://45.142.211.90:8080`. There is **one** instance currently. Bot's existing HTTP API:

| Endpoint | Method | Returns |
|---|---|---|
| `/api/health` | GET | health + version + uptime |
| `/api/orders` | GET | full queue snapshot |
| `/api/orders/get?id=X` | GET | one order |
| `/api/orders/stats` | GET | queueDepth, inProgress, workers count |
| `/api/orders/workers` | POST `{action: start\|stop, count?}` | scale workers |
| `/api/orders/cancel`, `/resume` | POST | per-order operations |

Existing panel ↔ bot communication:
- RabbitMQ queue `instagram_results` consumed by `InstagramResultConsumer`
- HTTP webhook `POST /api/webhook/instagram` handled by `InstagramService`
- Outbound: `InstagramBotClient` (Spring → bot) wraps `createOrder`, `cancelOrder`, `resumeOrder` with round-robin across instances

## What's wrong

Verified via Playwright at `https://smmworld.vip/admin/bot`:

The page is **entirely fake**. Two instance cards (`bot-01` at `45.142.211.90:8080` Up, `bot-02` at `45.142.211.91:8080` Degraded) — but the second bot does not exist. AdsPower group counts (Success 142/18/4 errors, Start_count 24/2/0) are hardcoded numbers. The "Recent bot webhooks" log at the bottom shows fake events (`igb_j8x2k1`, `ext=1028523`, `circuit breaker: 10 consecutive errors`).

`frontend/src/pages/admin/Bot.tsx` doesn't fetch anything — every value is a string literal in the component.

## What to do

### Backend

New `BotController.java` (or extend `AdminController.java`) under `/api/v2/admin/bot/*`. All endpoints `@PreAuthorize("hasAnyRole('ADMIN', 'OPERATOR')")`. Use the existing `InstagramBotClient` for HTTP calls into the bot, with 2-second timeouts.

```
GET  /v2/admin/bot/status
  → { id, baseUrl, version, uptime, queueDepth, inProgress, workers, heartbeatAt, online }
  Implementation: GET http://45.142.211.90:8080/api/health + /api/orders/stats, merge.
  If bot offline: { online: false, baseUrl, lastError }, no fake "up".

POST /v2/admin/bot/workers/scale
  Body: { delta: +1 | -1 } OR { count: N }
  Proxies to /api/orders/workers.
  Returns the new state.

POST /v2/admin/bot/workers/drain
  Graceful drain: stop accepting new dispatches, finish in-flight.
  Requires a NEW endpoint in the Go bot (`/api/orders/drain`) — see Bot side below.

POST /v2/admin/bot/reload
  Reload bot config without restart.
  Requires NEW endpoint in the Go bot (`/api/admin/reload`) — see Bot side below.

GET /v2/admin/bot/queue?limit=50&status=pending
  Last N orders in the bot's queue, filterable by status.
  Proxies /api/orders.

GET /v2/admin/bot/webhooks/recent?limit=50
  Last N webhook events received from the bot, newest first.
  Source: Redis LIST `bot:webhooks:recent`. Maintained by:
    - InstagramService.handleWebhook() — push event JSON before processing
    - InstagramResultConsumer — same
  LPUSH + LTRIM 0 199 to keep last 200.
  Each event: { ts, externalId, type, status, completed?, raw }

GET /v2/admin/bot/webhooks/stream
  SSE endpoint that publishes new webhook events live.
  Implementation: Spring SSE emitter subscribed to Redis pub/sub channel `bot:webhooks:stream`.
  InstagramService and InstagramResultConsumer publish to this channel after they push to the LIST.
```

### Bot side (Go, `c:\Users\user\Desktop\instagramBot`)

If `/api/orders/drain` and `/api/admin/reload` don't exist, add them. Drain semantics: set a "draining" flag, stop pulling from queue, return when in_progress reaches 0.

### Frontend

`frontend/src/pages/admin/Bot.tsx` — full rewrite:

1. **Single Instance card** (not two). Shows real version, uptime, queue depth, in-progress, workers, last heartbeat. Auto-refresh every 10s. If `online: false`, render an "Offline" badge + last error.

2. **Workers controls**: `Scale up` / `Scale down` / `Drain` / `Reload` buttons that hit the new endpoints. Confirm modal on Drain (requires typing `DRAIN`). Show toast on success/failure.

3. **AdsPower groups**: this data is NOT exposed by the bot's current API. Two options:
   - (a) Add `/api/profiles/groups` to the Go bot, then proxy via `/v2/admin/bot/profiles`
   - (b) Read-only honest empty state: "AdsPower stats not exposed by bot" — don't render fake numbers.

   Pick (b) for now unless you're also touching the Go bot in this PR.

4. **Live webhook tail**: SSE subscription to `/v2/admin/bot/webhooks/stream`, last 50 events. New events animate in from the top with fade-in. "Pause" toggle to freeze the stream while reading. Each row: timestamp, externalId, event type colored by severity (`order.completed` green, `order.error` red, `order.pending_cancel` orange), completed count.

5. **Queue snapshot**: small table of last 10 pending/in-progress orders from `/v2/admin/bot/queue`. Click an entry → drawer with details (or link to `/admin/orders/{id}`).

## Verify

1. `/admin/bot` shows ONE instance card. The "bot-02" card is gone forever.
2. The numbers match `curl http://45.142.211.90:8080/api/orders/stats` from the prod server.
3. Scale up → workers count increments by 1, bot's `/api/orders/workers` was called.
4. Place a test order via `/new-order` → SSE tail shows `order.created` → `order.progress` → `order.completed` events live.
5. Stop the bot container → instance card flips to "Offline" within 12s, no fake metrics shown.
6. Restart the bot → instance card flips back to "Up" within 12s.

## Production / best practices

Make sure everything is **maximum production-ready and follows best practices**. The whole point of this page is real situational awareness for the operator — never display invented numbers, never show "Up" without a verified heartbeat, never silently swallow a bot timeout. Two-second timeouts on every proxy call. SSE connections must clean up on unmount (no leaked Redis subscriptions). Drain operation must be reversible (a re-enable endpoint or auto-resume after graceful complete).
