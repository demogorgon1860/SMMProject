# Task 16 — Forgotten checks (audit + fix in one sweep)

## Context

(See `_CONTEXT.md`.)

A list of "easy to forget" items the panel needs but nobody has explicitly tested or implemented. Each is small individually; together they're an audit pass on the operational surface.

For each item: **verify first**, fix only if broken. Some may already be correct.

## Items

### 16.1 Welcome credit grant flow

`WelcomeCreditService.grantIfEligible(user)` is called from `EmailVerificationService.verify(...)`. Customer reported (in earlier session) that they registered and never got the email — meaning verify never ran — meaning credit not granted.

**Verify:**
- Register a fresh test user, complete email verification.
- `psql ... -c "SELECT id, balance, welcome_credit_granted_at FROM users WHERE email='<test>';"` — `balance` should be $5.00, `welcome_credit_granted_at` should be set.
- Verify a second `verify()` call (replay) doesn't double-credit (CAS-style update with `welcome_credit_granted_at IS NULL` check).

Fix if broken; otherwise mark verified.

### 16.2 Refill request flow end-to-end

The `RefillRequestService` shipped in commit `1ce23412`. Verify the full path:

1. As `mmknshnk`, place an order, mark it complete (or use an already-completed real order).
2. Open the order drawer, click "Request refill". Confirm modal → submit.
3. As admin, `/admin/refill-requests` → see the pending row.
4. Approve it. New refill order created, linked to the original (`refilled_from_order_id`).
5. As user, original order drawer now shows "Refill approved — new refill order #X created".

Fix any broken step.

### 16.3 Telegram bot config check (mirror of EMAIL_ENABLED)

Same class of bug as the email config — if `TELEGRAM_BOT_TOKEN` or `TELEGRAM_CHAT_ID` is missing on prod, `TelegramNotificationService.notifyOrderCancelledPending(...)` silently no-ops. The bot's circuit breaker fires, the panel marks the order paused, **no Telegram message gets sent** — admin never sees it, decision times out, default action applies.

**Verify:**
- SSH to prod, `docker-compose logs spring-boot-app | grep -i telegram` — look for any "Telegram disabled" warning or skipped notifications.
- Send a test cancel-pending event manually if possible.
- Add a startup banner similar to the EMAIL banner from commit `874acb29`:
  ```
  ============================================================
  TELEGRAM NOTIFICATIONS DISABLED — set TELEGRAM_BOT_TOKEN and
  TELEGRAM_CHAT_ID to enable circuit-breaker / completion alerts.
  ============================================================
  ```
  Place in `TelegramBotService` `@PostConstruct`. Same pattern as `EmailService.announceConfiguration()`.

### 16.4 Cryptomus deposit webhook idempotency

A delivery retry on Cryptomus's side can hit the panel webhook twice with the same payment_id. Without idempotency, the user gets credited twice.

**Verify:**
- Find `CryptomusWebhookController.handleWebhook(...)`.
- It MUST check `deposits.cryptomus_payment_id` before crediting:
  ```java
  if (depositRepository.existsByCryptomusPaymentIdAndStatus(paymentId, COMPLETED)) {
      return ResponseEntity.ok().build();  // already processed, no-op
  }
  ```
- If the check is missing or insufficient, add it. Plus a unique index on `(cryptomus_payment_id, status)` to make double-credit impossible at DB level.

### 16.5 Order placement idempotency

A flaky network where the user double-clicks "Place order" can create two orders. Add idempotency:

- Frontend: disable button while submission is in-flight (already done if `submitting` state is wired — verify in `NewOrder.tsx`).
- Backend: optional `Idempotency-Key: <uuid>` header support. If header present, dedupe inside a 5-min window keyed by `(user_id, idempotency_key)`. Stripe-style. Implementation: `idempotency_keys` table + `BalanceService.charge` checks before debiting.

Verify that double-submit doesn't double-charge today (it might already be safe via DB constraint). If unsafe, add the key.

### 16.6 Partial refund formula tests

`OrderService.cancelOrder(orderId)` and `BalanceService.refund(...)` use the formula:
```
refundAmount = charge * (1 - completed / quantity)
```

Edge cases that might break:
- `completed = 0` → full refund (correct)
- `completed = quantity` → zero refund (correct, no over-delivery refund either)
- `completed > quantity` (overdelivery — bot delivered more than asked) → negative refund? Should clamp to zero.
- `quantity = 0` → divide by zero → NaN
- BigDecimal precision — `0.1 + 0.2 != 0.3`. Use `setScale(2, HALF_UP)`.

Add unit tests covering all five edges. Fix any that misbehave.

### 16.7 Registration rate limit

Verified earlier — no rate limit on `POST /v1/auth/register`. Bot-spam-protection issue.

**Fix:** add `@RateLimit(perIp = 3, perHour = 1)` (or whatever the existing rate-limit annotation looks like) to `AuthController.register`. After 3 attempts in 1h from one IP, return 429.

Same for `POST /v1/auth/forgot-password` — already configured in `application.yml` (`app.auth.forgot-password.rate-limit-per-hour: 5`) but verify it actually fires.

### 16.8 Honeypot field on registration form

A trivial bot deterrent. Add a hidden field `website` to the register form — real users never fill it, bots usually do. Reject the registration server-side if the field is non-empty.

```tsx
<input type="text" name="website" autoComplete="off" tabIndex={-1}
       style={{position: 'absolute', left: '-9999px', opacity: 0}} />
```

```java
// in AuthController.register:
if (request.getWebsite() != null && !request.getWebsite().isBlank()) {
    log.warn("Honeypot triggered for IP {}", clientIp);
    return ResponseEntity.status(204).build();  // pretend it worked, bot moves on
}
```

### 16.9 Admin Orders search by URL on backend

Today: backend's `search` param on `/v2/admin/orders` covers id / service / username — NOT the order's `link` field. Frontend's `urlQ` filter is client-side-only on the current page (Task `14d0b976`). On paginated results across 6266 orders, you can't actually find an order by URL substring.

**Fix:** extend `AdminService.getAllOrders` to also match against `link` if the search term contains `instagram.com` or starts with `https://`. Or add a separate `urlSearch` query param. Frontend: drop the client-side `urlQ` filter and pass straight to backend.

### 16.10 Admin audit log persistence

`useAdminActions` (Zustand store, in-memory) — every admin action writes to it for the "Recent admin actions" sidebar on `/admin/dashboard`. **It's lost on refresh.** The dashboard always shows empty until the admin does something during this session.

**Fix:** create `admin_audit_log` table. On every admin action (cancel order, force complete, mark partial, balance adjust, suspend user, etc.), insert a row: `(id, admin_id, action, target_type, target_id, summary, created_at)`. Endpoint `GET /v2/admin/audit-log?limit=50`. Replace `useAdminActions` with a fetch on Dashboard mount.

### 16.11 Service price versioning

Today: when admin edits a service rate, ALL future orders for that service use the new rate. Existing in-flight orders keep their original `charge` (frozen at create time, stored on `orders.charge`). That's correct.

But: if admin lowers the rate, the user's quoted "Receipt $X" on the New Order page is the new rate — until they actually click Place Order, at which point backend recomputes. **Quote vs charge can differ.** Verify that frontend uses backend's authoritative charge, not the displayed estimate.

If a user disputes ("you charged me $10 but the page said $8"): `orders.charge` is what they actually paid. UI must always display the backend-confirmed value, not the pre-submit estimate, on the Order detail / Receipt.

### 16.12 Email notification settings actually fire

`Profile → Notifications` toggles for: Order completed / partial / cancelled / Deposit confirmed / Weekly summary / Promotions. Saved via `PATCH /v1/me/notifications`. But are the emails actually sent when events happen?

**Verify:**
- Toggle "Order completed" off for a test user, place an order, mark complete.
- Confirm no email arrives (search Resend dashboard or mailtrap).
- Toggle on, mark another order complete → email arrives.
- Same check for the rest.

Wire up if missing — `OrderService.complete()` should check `userNotificationPreferences` before calling `EmailService.sendOrderCompleted()`.

### 16.13 Postgres partition future creation

`orders` and `operator_logs` are partitioned by date. Without a cron-job creating future partitions, inserts beyond the last existing partition will fail.

**Verify:**
- `\d+ orders` in psql to see partitioning scheme.
- Find any cron-job / Spring scheduled task that creates partitions ahead of time.
- If none: write one. Spring `@Scheduled(cron = "0 0 1 * * ?")` monthly that creates partitions for `now() + 90 days`. Or use `pg_partman` extension on Postgres.

Without this, you have a time bomb at the date of the last existing partition.

## Verify

For each item: write down the test you ran (welcome credit balance check, partial-refund unit test, double-webhook curl), and the outcome (pass / fixed / N/A). Commit message should reference each by 16.X number.

## Production / best practices

Make sure everything is **maximum production-ready and follows best practices**. These are the items that bite at month-6 — none feels urgent on day 1, all of them get expensive when ignored. Verify before you fix; some are already correct. Fix the genuinely broken ones with tests so they don't silently regress. Group commits by item so revert is surgical.
