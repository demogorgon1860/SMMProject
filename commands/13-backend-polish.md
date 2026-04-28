# Task 13 — Backend polish (caching, indexes, N+1, webhook signature, rate limit, CSRF)

## Context

(See `_CONTEXT.md`.)

Five backend hygiene items grouped into one PR — they're all "the code works but isn't quite production-grade".

## What's wrong + what to do

### 13.1 No caching on heavy KPI queries

`AdminService.getDashboardStats` and `AdminService.getDailyStats` run multiple count/sum queries on every call. The admin Dashboard auto-refreshes — every 10s the page hits these. With one admin and a small DB, fine. With 5 admins or partitioned tables growing, not fine.

**What to do:**

- Add `@EnableCaching` if not already enabled in `Application.java`.
- Configure Caffeine (or Redis-backed) cache via `application.yml`:
  ```yaml
  spring:
    cache:
      type: caffeine
      cache-names: dashboard-stats, daily-stats, public-stats, services-list, user-lifetime-stats
      caffeine:
        spec: maximumSize=200,expireAfterWrite=30s
  ```
- Annotate methods:
  ```java
  @Cacheable("dashboard-stats")
  public DashboardStats getDashboardStats() { ... }

  @Cacheable(value = "daily-stats", key = "#days")
  public List<DailyStatPoint> getDailyStats(int days) { ... }
  ```
- `services/list` returns 49 rows — 60s TTL there too.
- `user-lifetime-stats` keyed by username, 60s TTL.

### 13.2 DB indexes

Run `EXPLAIN ANALYZE` against prod for the slow queries, add indexes only where missing:

```sql
-- Run on prod psql:
EXPLAIN (ANALYZE, BUFFERS) SELECT * FROM orders WHERE user_id = 852 ORDER BY created_at DESC LIMIT 100;
EXPLAIN (ANALYZE, BUFFERS) SELECT * FROM orders WHERE status = 'IN_PROGRESS' ORDER BY created_at DESC;
EXPLAIN (ANALYZE, BUFFERS) SELECT * FROM balance_transactions WHERE user_id = 852 ORDER BY created_at DESC LIMIT 50;
EXPLAIN (ANALYZE, BUFFERS) SELECT * FROM deposits WHERE user_id = 868 AND status = 'COMPLETED';
```

Indexes likely needed (verify with EXPLAIN before adding — don't index speculatively):
- `orders(user_id, created_at DESC)` — user Orders page
- `orders(status, created_at DESC)` — admin Orders status filter
- `balance_transactions(user_id, created_at DESC)` — Transactions page
- `deposits(user_id, status, created_at DESC)` — Add Funds recent deposits
- `refresh_tokens(user_id, last_used_at DESC)` — Sessions tab (Task 03)

Add via Liquibase changeset, with `<rollback>` blocks. **Note**: `orders` is partitioned — index needs `IF NOT EXISTS` per partition, or use `ON ONLY` syntax depending on PG version. Verify the partition strategy before writing the changeset.

### 13.3 Fix N+1 in `AdminService.getAllOrders`

Verified — `getAllOrders` paginates orders but each row's `user` and `service` lazy-load on serialization. With 100 rows per page, that's 200 extra queries per response.

**What to do:**

In `OrderRepository`, change the admin search query to use `JOIN FETCH`:
```java
@Query("SELECT o FROM Order o JOIN FETCH o.user JOIN FETCH o.service " +
       "WHERE (:status IS NULL OR o.status = :status) " +
       "AND (:search IS NULL OR ...)")
Page<Order> adminSearch(...);
```

Or use Spring Data's `@EntityGraph(attributePaths = {"user", "service"})`.

Verify with Hibernate stats — set `spring.jpa.properties.hibernate.generate_statistics=true` temporarily, hit the endpoint, check log: query count should drop from ~201 to 1-2 per page.

### 13.4 Cryptomus webhook signature verification

Audit `backend/.../controller/CryptomusWebhookController.java`. The webhook handler must verify the HMAC signature on every incoming POST — otherwise an attacker can spoof a "deposit confirmed" callback and credit themselves.

**What to do:**

Verify the existing implementation:
1. Cryptomus signs every webhook with the merchant's API key (HMAC-SHA-256 over the request body).
2. Header is typically `Sign` or `X-CRYPTOMUS-SIGNATURE`.
3. The controller MUST:
   - Read the raw request body (not the parsed JSON — signature is over bytes)
   - Compute HMAC with the configured `CRYPTOMUS_API_KEY`
   - Constant-time-compare against the header
   - Reject 401 if mismatch
4. Test: send a webhook with a deliberately wrong signature → 401, no credit applied.

If signature verification is missing or weak (e.g. JSON re-serialization breaks the byte comparison), fix it. This is one of those "works fine until someone notices" bugs.

### 13.5 Rate limiting verification

`OrderController` has `@RateLimited` (or similar) annotations claiming 60/min/user. Verify they actually fire.

**What to do:**

1. Hammer `/v1/orders` 70 times in 60 seconds with one user's API key.
2. Last 10 should return 429 Too Many Requests.
3. If they don't, fix `RateLimitService` — likely a bucket key issue (per-IP vs per-user vs per-API-key).
4. Same check on `/v1/auth/login` (per-IP, 5/min recommended).
5. Same on `/v1/auth/register` (per-IP, 3/hour recommended) — see also Task 16 forgotten checks.

### 13.6 CSRF audit

The panel uses JWT in localStorage + refresh token in HttpOnly cookie. The cookie-based refresh path is the CSRF target.

**What to do:**

1. Verify `SecurityConfig` either:
   - Sets `SameSite=Strict` on the refresh cookie (modern browsers honor this), AND
   - Refresh endpoint requires the access token in `Authorization` header (which CSRF can't inject)
2. If a user can refresh tokens via just the cookie alone, that's a CSRF vector. Require the access token (or a CSRF token) for the refresh endpoint.
3. State-changing endpoints (POST/PUT/DELETE) all use `Authorization: Bearer ...` — those are immune to CSRF (Bearer can't be auto-injected by browser).

## Verify

1. `@Cacheable` works: hit `/v2/admin/dashboard` twice in a row — second call < 5ms server time. Check Spring metrics or add a temporary log.
2. `EXPLAIN ANALYZE` on the post-index queries shows index scan, not seq scan.
3. Hibernate query count on `/v2/admin/orders?size=100` is ≤2, not ~201.
4. Cryptomus webhook with wrong signature → 401.
5. 70 orders in 60s → last 10 are 429.
6. Refresh endpoint without `Authorization` header → 401, even with valid cookie.

## Production / best practices

Make sure everything is **maximum production-ready and follows best practices**. Cache keys must be safe — never include user-mutable input as a cache key (cache poisoning). Indexes are non-trivial on partitioned tables; test on a copy of prod first. Webhook signature verification must use `MessageDigest.isEqual(...)` (constant-time) not `Arrays.equals` (timing attack). Rate limit bucket keys must be normalized — `192.168.1.1` and `192.168.001.001` should bucket together; same with usernames/email casing.
