# Task 06 — Account deletion flow (Privacy Policy claims it; not implemented)

## Context

(See `_CONTEXT.md`.)

## What's wrong

`frontend/src/pages/public/legal/Privacy.tsx`:

> How long we keep it: Active account data is retained while your account is open. **After deletion (Profile → Danger Zone), personal data is purged within 30 days.**

`frontend/src/pages/app/Profile.tsx` Danger Zone tab has a "Delete my account" button. It calls `profileAPI.deleteAccount(...)` which in `frontend/src/services/api.ts` is literally:

```ts
deleteAccount: (_confirmation: string) =>
  Promise.reject(new Error('deleteAccount is not implemented in this release')),
```

So clicking the button does nothing useful, but the Privacy Policy promises it does. GDPR Article 17 (right to erasure) makes this a real liability — a regulator request would expose this gap.

## What to do

### Backend

1. Add a soft-delete column on `users`: `deleted_at TIMESTAMP` (nullable). Liquibase changeset.
2. New endpoint:
   ```
   DELETE /api/v1/me/account
   Body: { "confirmation": "DELETE", "password": "..." }
   ```
3. Service flow in new `AccountDeletionService.java`:
   - Verify password (re-authenticate against current credentials)
   - Confirmation must be exact string `"DELETE"` (matches what the modal asks)
   - Reject if `balance > 0` — direct user to withdraw or contact support (or transfer to admin holding account, depending on your policy)
   - Reject if user has any orders in `IN_PROGRESS` / `PROCESSING` / `ACTIVE` status (refund flow first)
   - Mark `deleted_at = now()`, `email = "deleted-{id}@deleted.local"`, `username = "deleted-{id}"`, set `password_hash` to a random hash, revoke all refresh tokens, clear `api_key_hash`
   - Send a confirmation email (if email was verified)
   - Returns 204 No Content
4. Authentication filter must reject login attempts on soft-deleted accounts.
5. Cron job: daily, find users where `deleted_at < now() - INTERVAL '30 days'` and **hard-delete** the row + cascade orders/transactions to NULL user reference (or move to an archive table — depends on your retention policy for analytics).
6. Cascade rules:
   - `orders.user_id` — already FK, archive on hard-delete
   - `balance_transactions.user_id` — same
   - `refresh_tokens` — cascade delete
   - `email_verification_tokens` — cascade delete

### Frontend

1. `frontend/src/services/api.ts` — replace the `notImplemented` rejection with a real call.
2. `frontend/src/pages/app/Profile.tsx` Danger Zone:
   - Modal that asks for current password + types `DELETE` to confirm
   - On 200: clear localStorage, redirect to landing with a toast "Account deletion scheduled. Personal data will be removed within 30 days."
   - On error: render the backend's reason inline (positive balance, in-progress orders, wrong password)

3. Privacy Policy page — keep the "30 days" line as is (it'll now be true).

## Verify

1. Create a test user with $0 balance and no in-progress orders.
2. Profile → Danger Zone → Delete my account → enter password + DELETE → 204.
3. Try to log back in with same credentials → 401.
4. In `psql`: `SELECT id, email, username, deleted_at FROM users WHERE email LIKE 'deleted-%'` shows the soft-deleted row.
5. After cron-job runs (or manually trigger), the row is gone, orders.user_id NULL or moved to archive.
6. Test the rejection paths: positive balance, in-progress order, wrong password — each gives a distinct 4xx with a useful message.

## Production / best practices

Make sure everything is **maximum production-ready and follows best practices**. Account deletion is irreversible from the user's perspective — make the path obvious and friction-free for them, but bullet-proof against accidental clicks (password confirm, typed `DELETE`). All deletions must be logged to an admin audit table. The 30-day grace period is a feature, not a bug — let users contact support to restore within that window if they regret.
