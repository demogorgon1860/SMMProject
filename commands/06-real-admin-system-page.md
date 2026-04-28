# Task 08 — Real `/admin/system` page (Logs / Errors / Queues / Cache)

## Context

(See `_CONTEXT.md`.)

## What's wrong

Verified via Playwright at `https://smmworld.vip/admin/system`. Four tabs (Logs, Errors, Queues, Cache), all completely fake. Sample lines from the deployed Logs tab:

```
14:32:08.442  INFO  OrderService          Order 1028523 status transition: in_progress → completed
14:32:07.912  INFO  InstagramResultConsumer  Received result webhook for igb_j8x2k1 (completed=1000)
14:32:04.218  WARN  InstagramBotClient    Bot #2 latency 312ms — circuit half-open
14:32:01.004  INFO  BalanceService        Charged $4.40 from user 10011 for order 1028523
```

These IDs don't exist (real max order id is ~6800, real users 851-878). "Bot #2" doesn't exist. The page header even admits: "Phase 3 backend wires SSE / Redis stats" — so it's literally placeholder.

`frontend/src/pages/admin/System.tsx` is the source of the static lines. The "Errors `5`" badge on the tab is also a hardcoded number.

## What to do

Each of the four tabs needs a real backend source.

### Tab 1: Logs

Backend:
- Add `LogbackRedisAppender` (custom or use `logstash-logback-encoder` + a Redis bridge). Every log event pushes JSON to:
  - Redis LIST `app:logs:recent` (capped at 500 via LTRIM 0 499)
  - Redis pub/sub channel `app:logs:stream`
- New endpoints:
  ```
  GET /api/v2/admin/system/logs?level=info&limit=200&since=...
    Returns last N log entries from Redis LIST, filtered.
  GET /api/v2/admin/system/logs/stream
    Spring SSE emitter, subscribes to pub/sub channel.
  ```
- Log entry shape: `{ ts, level, source, msg, mdc?: { correlationId, userId, ... } }`.

Frontend:
- Replace static array with fetch from `/v2/admin/system/logs`.
- Subscribe to `/v2/admin/system/logs/stream` via `EventSource`.
- New entries fade in from the top.
- Filter controls: log level dropdown, search-by-text, source-class filter.
- "Pause" toggle freezes the stream.

### Tab 2: Errors

Backend:
- Reuse the same `app:logs:recent` LIST. New endpoint:
  ```
  GET /api/v2/admin/system/errors?since=24h
    Returns ERROR-level entries grouped by message hash.
    Response: [{ msgHash, sample: "...", count, firstSeen, lastSeen, sources: ["..."] }]
  ```
- Group by SHA-1 of normalized message (strip line numbers, ids).

Frontend:
- Render the grouped errors table. Click a row to expand: full stack traces of recent occurrences.
- The "Errors 5" badge on the tab — make it the real count over last 24h.

### Tab 3: Queues

Backend:
- Use `RabbitAdmin` (or call RabbitMQ HTTP Management API at `http://rabbit:15672/api/queues/%2F`). Config: `RABBITMQ_MANAGEMENT_USER` / `_PASS`.
- Endpoint:
  ```
  GET /api/v2/admin/system/queues
    Returns all queues with: name, depth, consumers, in-flight, deliveryRate, ackRate, dlqDepth.
  ```
- Highlight DLQs (any queue ending `.dlq` or with `dead-letter-exchange` arg).

Frontend:
- Table of queues. DLQ rows tinted red. "Purge DLQ" button per DLQ row (admin only, with confirm).

### Tab 4: Cache

Backend:
- Inject `RedisTemplate`, run `INFO memory`, `INFO stats`, `DBSIZE`. Endpoint:
  ```
  GET /api/v2/admin/system/cache
    Returns: { used_memory, used_memory_peak, total_keys, hit_rate, ops_per_sec,
               evicted_keys, expired_keys, connected_clients, uptime }
  ```

Frontend:
- KPI strip + sparkline of ops/sec (build from periodic polls).
- "Flush cache" button — admin only, requires typed `FLUSH` confirmation. Calls a new `POST /api/v2/admin/system/cache/flush` that's loud-logged.

## Verify

1. Open `/admin/system` → Logs tab shows REAL log lines from the running Spring container, with real order ids and real user ids.
2. `docker-compose logs spring-boot-app --since 1m` matches what the page shows.
3. Trigger an exception (e.g. malformed admin action) → Errors tab shows it within seconds.
4. Queues tab shows `instagram_orders`, `instagram_results`, `cryptomus_deposits`, etc. with real depths.
5. Cache tab numbers match `docker exec smm_redis redis-cli INFO`.
6. Stop Redis temporarily → Logs/Errors/Cache tabs render an honest "Source unreachable" empty state. No fake fallback.

## Production / best practices

Make sure everything is **maximum production-ready and follows best practices**. SSE connections must time out and reconnect cleanly (`EventSource` does this natively, but the backend emitter has to release server resources on disconnect). Logging the logs creates a feedback loop — make sure the Redis appender writes async and doesn't log its own failures. Don't expose secrets via log MDC fields. Flush-cache button must be loud-logged with admin id.
