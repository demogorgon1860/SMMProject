# Task 02 — Implement real `/v2/admin/system/health` endpoint

## Context

(See `_CONTEXT.md`.)

## What's wrong

Verified by curl against prod with admin token:

```
$ curl /api/v2/admin/system/health -H "Authorization: Bearer ..."
504 Gateway Timeout (Cloudflare HTML)
```

The endpoint **either doesn't exist or hangs** on the backend, and the request times out at the Cloudflare → origin layer.

Frontend impact: `frontend/src/pages/admin/Dashboard.tsx` calls `adminAPI.systemHealth()` on mount. The catch swallows the error, so the "Live system status" strip on the admin Dashboard renders empty (which is the honest fallback — that strip used to be hardcoded with fake "Spring Boot 14ms / IG Bot secondary degraded 312ms", we removed that in commit `7d3ff920`). But the strip should show real data, not nothing.

Frontend call site:
```ts
adminAPI.systemHealth()  // → GET /api/v2/admin/system/health
```

## What to do

Add the endpoint to `backend/src/main/java/com/smmpanel/controller/AdminController.java` (or a new `SystemMonitoringController.java`):

```
GET /api/v2/admin/system/health
@PreAuthorize("hasAnyRole('ADMIN', 'OPERATOR')")
```

Response shape (must match what `Dashboard.tsx` already expects):
```json
[
  { "name": "Spring Boot",  "status": "up",       "latency": 8,   "meta": "v2.x · 12h uptime" },
  { "name": "PostgreSQL",   "status": "up",       "latency": 3,   "meta": "<conn-count> conn · <db-size> GB" },
  { "name": "Redis",        "status": "up",       "latency": 1,   "meta": "lettuce" },
  { "name": "RabbitMQ",     "status": "up",       "latency": 6,   "meta": "<queue-count> queues · <dlq-count> DLQ" },
  { "name": "Instagram Bot","status": "up",       "latency": 41,  "meta": "<workers> workers" },
  { "name": "Cryptomus",    "status": "up",       "latency": 120, "meta": "API reachable" }
]
```

Implementation rules:

1. **Hard 2-second timeout per check.** The whole endpoint must respond in ≤4 seconds. Use `CompletableFuture.supplyAsync(...).orTimeout(2, TimeUnit.SECONDS)` per check, then collect.
2. **Spring Boot** — always `up`, latency = `0` or measured request time. `meta` from `Runtime.getRuntime().freeMemory()` and uptime from `ManagementFactory.getRuntimeMXBean().getUptime()`.
3. **PostgreSQL** — `SELECT 1` via injected `DataSource`. Latency = round-trip ms. Meta from `pg_stat_activity` count + `pg_database_size(current_database())`.
4. **Redis** — `PING` via injected `RedisTemplate`. Latency = round-trip. Meta = `"lettuce · "+ INFO memory used_memory_human`.
5. **RabbitMQ** — `AmqpAdmin.getQueueProperties("instagram_orders")` + count DLQ messages. Use the existing `AmqpAdmin` bean.
6. **Instagram Bot** — `GET http://45.142.211.90:8080/api/health` via the existing `InstagramBotClient` (or new `WebClient`). Set 2s timeout. If the bot returns version + workers, stuff into `meta`.
7. **Cryptomus** — optional, lower priority. `GET https://api.cryptomus.com/v1/payment/info` with a known invalid id; expect 4xx fast → "up" if response received, "down" if timeout.

For each check, status is `up` if it returned within timeout, `down` if timeout/exception, `degraded` if latency > some threshold (e.g. >500ms).

Frontend already wires this — no frontend changes needed (it'll start showing the strip once data flows).

## Verify

1. `curl -H "Authorization: Bearer <admin>" https://smmworld.vip/api/v2/admin/system/health` returns the array within 4s.
2. Force one component to fail (stop Redis container, rerun curl): that component shows `status: "down"` but the rest still come back, and the whole endpoint still returns 200 within 4s.
3. Open `/admin` in a browser — the bottom "Live system status" strip renders 6 tiles.
4. Auto-refresh every 10s works (already wired by the page subtitle "auto-refresh · 10s").

## Production / best practices

Make sure everything is **maximum production-ready and follows best practices**. The endpoint must NEVER hang the request thread or take more than 4s end-to-end — that was the original bug. Use bounded thread pools, timeouts on every external call, and structured logging on failure. Don't return fake "up" if a check threw.
