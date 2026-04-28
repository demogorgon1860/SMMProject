# Task 03 — Real Profile features (Sessions + Lifetime stats + Danger Zone)

## Context

(See `_CONTEXT.md`.)

## What's wrong

Verified via Playwright on `/profile` while logged in as `mmknshnk` — three sections show fake or non-functional data, all in the same page (`frontend/src/pages/app/Profile.tsx`):

### Sessions tab (P0 — false security info)

Renders two hardcoded rows:
```
Chrome on macOS · Berlin, DE · 94.130.x.x · last active 0s ago    [this session]
iOS Safari · Berlin, DE · 94.130.x.x · last active 1d ago         [Revoke]
```
But the user is logged in via Playwright from Windows. Source: `SessionsTab` component catches the 404 from `profileAPI.sessions()` (which is `notImplemented` in `frontend/src/services/api.ts`) and falls back to a hardcoded array. **This can panic a user** ("who logged into my account from Berlin?") or give them false comfort if a real attacker is on the account.

The data exists in the `refresh_tokens` table (created_at, user_agent, ip_address, last_used_at). Just not exposed.

### Account → Lifetime stats (P1)

Account tab shows:
```
ORDERS  —      SPENT   $0.00
TICKETS 0      MEMBER  —
```
Even for `mmknshnk` who has 17+ transactions and a real $828.47 balance with history. The card uses literal `'—'` and `'$0.00'` strings — there's no endpoint backing it.

### Danger Zone (P1 — buttons reject immediately)

Three buttons in the Danger Zone tab. All call `profileAPI.{exportData|pauseApi|deleteAccount}` which in `frontend/src/services/api.ts` are literal `Promise.reject(new Error('... is not implemented in this release'))`. Click → reject → toast "Couldn't…". The Privacy Policy meanwhile **promises** that "Profile → Danger Zone" deletes accounts and purges data within 30 days.

Account deletion is a separate task (Task 04) because it's a real backend feature with state-machine + cron. This task covers **Export account data** and **Pause API** plus the Sessions and Lifetime stats endpoints.

## What to do

### Backend

#### 3.1 Sessions

New endpoints in `backend/src/main/java/com/smmpanel/controller/ProfileController.java`:
```
GET    /api/v1/me/sessions               → SessionDto[]
DELETE /api/v1/me/sessions/{tokenId}     → 204 (or 409 if it is the current session)
POST   /api/v1/me/sessions/sign-out-others → revoke all except current
```

`SessionDto`:
```java
{
  Long id;                 // refresh_token row id
  String userAgent;        // raw UA — frontend prettifies
  String ipAddress;        // anonymize last octet ("94.130.x.x") in DTO mapper for GDPR
  String location;         // optional, leave null if no geoip configured
  LocalDateTime createdAt;
  LocalDateTime lastUsedAt;
  boolean isCurrent;       // hash of current request's refresh cookie matches stored hash
}
```

Service layer: extend `RefreshTokenService` or new `SessionService`. List active (non-revoked, non-expired) refresh tokens for current user, ordered by `last_used_at DESC`. Identify "current" by hashing the cookie value.

#### 3.2 Lifetime stats

New endpoint:
```
GET /api/v1/me/stats/lifetime
Returns: {
  ordersTotal,           // count(*) from orders where user_id = ...
  ordersCompleted,       // status = COMPLETED
  totalSpent,            // sum(charge) where status IN (COMPLETED, PARTIAL)
  ticketsTotal,          // count from tickets table
  memberSince,           // user.created_at
  firstOrderAt, lastOrderAt,
  refillsRequested       // count from refill_requests table
}
```

Use existing repository methods (`OrderRepository.countByUser_Username`, `countByUser_UsernameAndStatus`, `BalanceTransactionRepository.sumAmountByUsernameAndType`). `@Cacheable("user-lifetime-stats")` TTL 60s keyed by username.

#### 3.3 Export account data

```
POST /api/v1/me/export
  → 202 Accepted: { jobId, requestedAt }
  Triggers an async job that:
  - Collects all user data (orders, transactions, refill requests, tickets, profile)
  - Builds a JSON archive (or zip with CSVs)
  - Uploads to a temporary S3-compatible store with signed URL
  - Emails the user the download link, expires in 24h
GET /api/v1/me/export/{jobId}
  → { status: 'pending' | 'ready' | 'failed', downloadUrl?: string, expiresAt?: string }
```

Realistically for now: synchronous JSON download (no S3). Endpoint returns a 200 with `Content-Disposition: attachment; filename="smmworld-account-{username}-{date}.json"`. Skip the async/email path until export volume justifies it.

#### 3.4 Pause API key

```
POST /api/v1/me/api-key/pause   → flips a `paused_at` column on user
POST /api/v1/me/api-key/resume  → clears it
```

Liquibase: add `users.api_key_paused_at TIMESTAMP NULL`. The `ApiKeyAuthFilter` checks this column — if non-null, returns 403 "API key paused by owner".

### Frontend

For each tab, replace the placeholder with real fetch + render:

1. `frontend/src/services/api.ts`:
   - Replace `notImplemented` for `sessions`, `signOutOthers`, `pauseApi`, `exportData` with real endpoints.
   - Add `lifetimeStats: () => api.get('/v1/me/stats/lifetime').then(r => r.data)`.

2. `frontend/src/pages/app/Profile.tsx`:
   - **SessionsTab**: drop the hardcoded fallback array. Render real list. Parse UA to friendly label (Chrome on macOS, etc.) — use `ua-parser-js` if you want a quick win, otherwise a small switch on substring matching. Per-row Revoke button hits `DELETE`. "Sign out all others" button hits the bulk endpoint.
   - **AccountTab → LIFETIME STATS**: fetch on mount, render real numbers. Use the shared `toNum` for `totalSpent`. Skeleton placeholders during load (not "—").
   - **DangerZoneTab → Request export**: hits the new endpoint, downloads the JSON file directly.
   - **DangerZoneTab → Pause API**: toggle, calls pause/resume. Show current state on the button.
   - **DangerZoneTab → Delete account**: see Task 04 for the full implementation.

3. Loading states: skeleton shimmer on the four lifetime-stats tiles, not literal "—".

## Verify

1. Login as `mmknshnk` → Profile → Sessions tab → see ONE session (current, "this session" badge), real UA from Playwright, real IP from prod.
2. Login from a second browser → Sessions tab now shows two rows. Click Revoke on the other → that browser's next API call 401s and redirects.
3. Account tab → real numbers (orders > 0, spent > 0, member since 2026-01-28).
4. Danger Zone → Request export → JSON file downloads, contains real orders + transactions.
5. Pause API → next API request with X-API-Key gets 403. Resume → works again.

## Production / best practices

Make sure everything is **maximum production-ready and follows best practices**. IP anonymization (last octet → `x.x`) is non-negotiable for GDPR. Don't return raw refresh-token hashes — only metadata. Audit-log every revoke, pause, export request. Export must include only the user's own data — never adjacent users' data even by accident. Cache invalidation on lifetime stats: 60s TTL is fine, don't bother with `@CacheEvict` on every order — eventual consistency is OK for this view.
