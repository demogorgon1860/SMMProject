# Task 15 — Testing infrastructure (backend tests + frontend e2e + smoke + pre-commit)

## Context

(See `_CONTEXT.md`.)

The repo has a `backend/src/test/java/` tree but several recent fixes shipped without tests, and the frontend has no e2e suite. Multiple bugs from this audit (status filter, transaction labels, custom comments field name, admin password leak) would have been caught by integration tests against the actual API contract.

## What's wrong + what to do

### 15.1 Backend unit tests for critical services

Missing or thin tests for the services that were recently rewritten:

| Service | Why it needs tests |
|---|---|
| `RefillRequestService` | State machine: REQUESTED → APPROVED → REJECTED. Idempotency on retry. |
| `WelcomeCreditService` | At-most-once via CAS UPDATE — race condition territory. |
| `AdminService.getDailyStats(...)` | Recently shipped, has zero-fill logic + UTC date arithmetic that's easy to get wrong. |
| `EmailVerificationService.verify(email, code)` | OTP redemption with concurrent-use rejection. |
| `BalanceService.refund(...)` | Partial refund formula correctness — `charge * (1 - completed/quantity)`. |
| `OrderService.mapFromPerfectPanelStatus(...)` | We just shipped a fix where `IN_PROGRESS` was being silently mapped to `PENDING`. Test all enum values + aliases. |

**What to do:**

For each: a `@DataJpaTest` or full `@SpringBootTest` slice (depends on dependencies). Use Testcontainers for Postgres + Redis if integration. Cover:
- Happy path
- Each error branch (404 user, expired code, insufficient balance, ...)
- Concurrent-call race (where applicable — use `@RepeatedTest` + `CountDownLatch`)

Aim for ~70% line coverage on these specific services. Don't chase 100% across the whole codebase — diminishing returns.

### 15.2 Backend integration tests for admin endpoints

Every `/v2/admin/*` endpoint needs a happy-path + auth-rejection test. Use `MockMvc` + `@WithMockUser(roles="ADMIN")`.

Critical endpoints (rough list):
- `GET /v2/admin/dashboard` — returns the real KPI shape
- `GET /v2/admin/orders?page=...&status=...` — pagination + status filter (just shipped a fix for this)
- `GET /v2/admin/users` — DOES NOT include `passwordHash`, `apiKeyHash` (just shipped a fix; this is the test that prevents regression)
- `GET /v2/admin/deposits` — returns `amountUsdt` (not `amount`)
- `POST /v2/admin/orders/{id}/actions` — each action wired correctly
- `GET /v2/admin/stats/daily?days=30` — zero-filled, correct shape

The "passwordHash not exposed" test alone is worth the whole effort:
```java
@Test
void adminUsers_doesNotLeakPasswordHash() throws Exception {
    mockMvc.perform(get("/api/v2/admin/users").with(adminUser()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.users[0].passwordHash").doesNotExist())
        .andExpect(jsonPath("$.users[0].apiKeyHash").doesNotExist())
        .andExpect(jsonPath("$.users[0].password").doesNotExist());
}
```

### 15.3 Frontend e2e tests (Playwright)

Critical user-facing flows. Use the real Playwright MCP (already configured in `.mcp.json`) or set up a separate `frontend/e2e/` directory.

Suite:
1. **Register → verify email → login** flow (use mailtrap.io as `EMAIL_FROM` target — capture incoming mail, parse code, submit). Catches the recent "code never arrived" regression.
2. **Place order — Custom Comments**: paste 5 lines, verify quantity auto-derives, check passes, order placed, no redirect (Task 08).
3. **Place order — Likes/Followers**: standard quantity input flow.
4. **Status filter — user Orders**: each tab (Pending / In progress / Processing / Completed / Partial / Cancelled) shows correct rows. Catches the regression we just fixed (`IN_PROGRESS` → `PENDING` mapping).
5. **Status filter — admin Orders**: same.
6. **Admin refund**: cancel an in-progress order → balance reverts.
7. **Mobile (375px width)**: hamburger opens drawer, drawer auto-closes on nav, content uses full width.
8. **Copy buttons**: order id copy → clipboard contains id, drawer doesn't open.

Run on every PR via GitHub Actions (or whatever CI is set up). Record video on failure.

### 15.4 Smoke tests post-deploy

Bash script in `scripts/smoke.sh`:

```bash
#!/bin/bash
set -euo pipefail
HOST=${1:-https://smmworld.vip}

# Public endpoints
curl -sf "$HOST/api/v1/stats/public" | jq -e '.serviceCount > 0'
curl -sf "$HOST/api/v1/service/services" | jq -e '.data | length > 0'

# Public pages render
curl -sf "$HOST/" | grep -q SMMWorld
curl -sf "$HOST/login" | grep -q "Sign in"

# Admin (requires token argument)
if [ -n "${ADMIN_TOKEN:-}" ]; then
  curl -sf -H "Authorization: Bearer $ADMIN_TOKEN" "$HOST/api/v2/admin/dashboard" | jq -e '.totalUsers > 0'
  # critical regression check: admin users response must NOT contain passwordHash
  curl -sf -H "Authorization: Bearer $ADMIN_TOKEN" "$HOST/api/v2/admin/users" \
    | jq -e '.users[0] | has("passwordHash") | not'
fi

echo "OK"
```

Wire into the deploy script: after `docker-compose up -d --build`, run smoke and rollback if it fails.

### 15.5 Pre-commit hooks

Stop bad code from getting committed in the first place.

`frontend/.husky/pre-commit`:
```
npx lint-staged
```

`frontend/package.json`:
```json
"lint-staged": {
  "*.{ts,tsx}": ["prettier --write", "eslint --fix"],
  "*.{md,json,yaml,yml}": ["prettier --write"]
}
```

Backend has `spotlessApply` already — add a Git hook that runs `./gradlew spotlessCheck compileJava` pre-commit (or `compileTestJava` if quick enough).

Optional but high-value: a `pre-push` hook that runs `tsc --noEmit` and `gradle test` — catches "I forgot to update the consumer" before it hits CI.

## Verify

1. `cd backend && ./gradlew test` — passes, includes the new tests, covers the listed services.
2. The "passwordHash not exposed" integration test fails if you delete the `UserAdminDto` mapping — run that experiment to confirm the test actually guards.
3. `cd frontend && npx playwright test` — all listed flows green.
4. `./scripts/smoke.sh` after deploy — all assertions pass.
5. Commit with a deliberately mis-formatted file → pre-commit reformats it.
6. Try to push code that doesn't tsc-compile → pre-push blocks (if hook installed).

## Production / best practices

Make sure everything is **maximum production-ready and follows best practices**. Tests must be deterministic — no time-of-day dependencies, no shared mutable state, fixed seeds for randomness. Use Testcontainers over H2 for backend integration tests; H2 lies about real Postgres behavior (date functions, partitioning). Smoke tests should fail loud and fast — exit code matters. Pre-commit hooks must be fast (< 5s) or developers will bypass them.
