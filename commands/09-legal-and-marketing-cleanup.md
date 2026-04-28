# Task 09 — Legal + marketing pages: drop unverifiable claims

## Context

(See `_CONTEXT.md`.)

## What's wrong

Audit of the public legal/marketing pages found multiple claims the panel can't back up. These belong in a single sweep — they're all text edits in the same handful of files plus one repeated hardcode.

### 9.1 Refund Policy — Wallet Withdrawal claim (P0 legal)

`frontend/src/pages/public/legal/Refund.tsx` says:
> Wallet withdrawal: You can withdraw your wallet balance at any time to a crypto address you control. Minimum $5. We pay the network fee from your balance.

`grep -rn 'withdraw' backend/src/main/java` confirms **zero withdrawal endpoints, services, or entities.** No `WithdrawalController`, no `WithdrawalService`, no `withdrawals` table. The policy promises something the panel can't do — a written legal liability.

### 9.2 AML Policy — false compliance claims (P0 legal)

`frontend/src/pages/public/legal/Aml.tsx`:
> Wallet screening: Every incoming deposit is screened against sanction lists and known mixer/darknet flows via our payment processor.
> Suspicious activity reports: We file SARs with the appropriate Financial Intelligence Unit when legally required.

`grep -rn -i 'screening\|sanction\|mixer\|darknet\|chainalysis\|elliptic\|SAR\b' backend/` finds nothing implementing this. No screening service, no Chainalysis or Elliptic integration, no SAR tooling. Cryptomus does its own AML on its end, but the panel's separate claim doesn't hold up.

### 9.3 Privacy Policy — broken account-deletion claim (P0 GDPR)

`frontend/src/pages/public/legal/Privacy.tsx`:
> After deletion (Profile → Danger Zone), personal data is purged within 30 days.

But Danger Zone's Delete button currently rejects with "not implemented". GDPR Article 17 + this written promise = real risk. Either implement deletion (Task 04) AND keep the claim, or drop the claim until deletion ships.

### 9.4 "47s avg start" hardcoded everywhere (P1)

`grep -rn '47s\|47 seconds' frontend/src` returns 6 places, all the same fake stat:
- `pages/public/Landing.tsx` Step 03 — "Avg start 47s"
- `pages/public/Help.tsx` FAQ — "Median start time is 47 seconds"
- `pages/public/legal/Terms.tsx` Orders & delivery — "Median start time is 47 seconds"
- `pages/auth/Register.tsx` Numbers panel — "47s avg start"
- `pages/app/NewOrder.tsx` AVG START tile + Receipt row (already removed in `00fc4a55`, double-check)
- `pages/app/Orders.tsx` ProgressTab — "Median start time was 47s"

Compile-time string literal. No measurement, no calculation. Particularly bad in Terms (legal doc) where a customer can hold the panel to a 47-second median.

### 9.5 `Last updated 2026-04-15` on all 4 legal pages (P2)

`grep -rn 'Last updated' frontend/src/pages/public/legal/` — same date hardcoded on Terms / Privacy / Refund / AML. None of these pages have actually been updated on that date — it's just the date someone typed.

### 9.6 Privacy Policy — secondary claim (P2)

The Privacy page also says "Email compliance@smmworld.vip" and "Email dpo@smmworld.vip". Verify those mailboxes exist and are monitored. If not, drop the email or set up forwarders.

## What to do

This is mostly text edits across ~7 files. One commit, one PR.

### Refund Policy (9.1)

`frontend/src/pages/public/legal/Refund.tsx`:
- Delete the entire **Wallet withdrawal** section.
- Update the `SECTIONS` table-of-contents array to drop the `wallet-withdrawal` anchor.
- Cross-check: AML page mentions "withdrawal larger than €10,000" — drop if withdrawals don't exist.

If the user later wants withdrawals: separate task (Cryptomus payout API + KYC + admin approval flow + escrow). Don't half-ship.

### AML Policy (9.2)

`frontend/src/pages/public/legal/Aml.tsx`:
- Remove the **Wallet screening** section completely.
- Rewrite **Suspicious activity reports** to: "We comply with applicable laws regarding suspicious activity reporting." Vague but true.
- Keep **Sanctions** section (refusal-to-serve list — that's a policy, not active screening).
- Drop or rewrite **When we ask for ID** if withdrawals are out (the €10k withdrawal trigger never fires).

### Privacy Policy (9.3)

`frontend/src/pages/public/legal/Privacy.tsx`:
- If account deletion ships in Task 04: keep the "30 days" line.
- If not: rewrite to "Account deletion is available on request — email dpo@smmworld.vip" until the feature exists.

### "47s avg start" hardcodes (9.4)

For each occurrence: drop the literal and the surrounding fragment. Reflow layout.
- Landing Step 03: drop "Avg start 47s" inline.
- Help FAQ: rewrite to "Most Instagram services start within minutes once dispatched."
- Terms: rewrite "Order processing begins automatically once payment is confirmed."
- Register Numbers panel: drop the tile or replace with a real metric (e.g. "30d refill" — that's policy, not measured).
- NewOrder + Orders ProgressTab: drop the lines.

If you want a real number instead of dropping: extend `OrderRepository` with a `findMedianStartLagSeconds()` query (median of `bot_picked_up_at - created_at` for last 7 days), expose at `GET /api/v1/stats/median-start-time`, replace each hardcode with the dynamic value. **But only if the bot tracks `bot_picked_up_at`** — verify in `Order` entity. If not, this is a Go-bot change too. Skip and just drop.

### Last-updated dates (9.5)

Convert each "Last updated 2026-04-15" string to a `LAST_UPDATED` constant at the top of each legal file. Update only on actual policy edits — make it a PR review checklist item.

### Compliance emails (9.6)

Verify `compliance@smmworld.vip` and `dpo@smmworld.vip` are real, monitored mailboxes. SSH to mail server config or check Resend dashboard. If they're not set up, fix or drop the references.

## Verify

1. `grep -rin 'withdraw' frontend/src/pages/public/legal/` returns nothing (or is in a section that survived rewriting).
2. `grep -rin 'screening\|mixer\|darknet\|SAR\b' frontend/src/pages/public/legal/` returns nothing.
3. `grep -rin '47s\|47 seconds' frontend/src/` returns nothing outside this commit's diff.
4. All four `/legal/*` pages render cleanly. TOC anchors all resolve to existing sections.
5. Privacy claim about account deletion is consistent with what Danger Zone actually does.
6. Test `mailto:compliance@smmworld.vip` actually delivers (send a test email).

## Production / best practices

Make sure everything is **maximum production-ready and follows best practices**. Legal pages are adversarial documents — every claim is something a customer or regulator can hold the panel to. Write the policy after the implementation, not before. If a feature is "coming soon", the policy doesn't say so until it's live. The 47s number was particularly bad: it's a **measurable claim in a legal document**.
