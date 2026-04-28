# Task 10 — Dead code cleanup (YouTube + Binom + Flyway + admin no-ops + /mobile)

## Context

(See `_CONTEXT.md`.)

The repo has accumulated a lot of code from earlier product iterations and Phase-2 placeholders that never got real wiring. This is one sweep to take it all out. Big PR by line count, but each piece is mechanical.

## What's wrong

### 10.1 YouTube infrastructure (panel pivoted to Instagram-only)

`grep -rin 'youtube' backend/src/main/java`:
- Entity `YouTubeAccount` + `YouTubeAccountStatus` enum
- Repository `YouTubeAccountRepository`
- Field `activeYouTubeAccounts` on `DashboardStats` DTO
- Field `youtubeVideoId` on `OrderResponse`
- Service row id=1 in DB: `name="YouTube Views (Standard)", category="YouTube"`
- `AdminService.updateAccountStatus` references `YouTubeAccountStatus.valueOf`
- Possibly Liquibase changesets creating `youtube_accounts` table

Frontend:
- `pages/admin/Services.tsx` shows the YouTube row
- `pages/public/Landing.tsx` Coverage section "YouTube · Soon"
- `pages/app/NewOrder.tsx` + `pages/public/ServicesList.tsx` `CATS` arrays both have `'yt'` entries

### 10.2 Binom integration (no longer used)

`backend/src/main/java/com/smmpanel/service/integration/BinomService.java` — ~2000 lines, generates 4 unchecked-cast warnings on every `compileJava`. Plus:
- Methods `OrderService.isOrderCompletedBasedOnCampaigns`, `OrderService.getCampaignStatsForOrder`
- Field `binomOfferId` on `OrderResponse` (visible in admin orders API: `"binomOfferId": "No offer"`)
- Likely `BinomConfig`, `BinomController`, env vars `BINOM_*`, Liquibase tables

### 10.3 Flyway directory + disabled health indicator + PageStub

- `backend/src/main/resources/db/migration/` — legacy Flyway. Project uses Liquibase only (per `CLAUDE.md`). Spring may scan it and warn.
- `backend/src/main/java/com/smmpanel/health/RedisHealthIndicator.java.disabled` — `.disabled` suffix means intentionally not compiled. Either re-enable properly as a `HealthIndicator` bean, or delete.
- `frontend/src/pages/_PageStub.tsx` — placeholder from Phase 0. Verify nothing imports it (`grep -rn '_PageStub' frontend/src/`). If clean, delete.

### 10.4 Stale tests

After 10.1 + 10.2 ship, `cd backend && ./gradlew compileTestJava` will fail on tests referencing deleted services. Delete those.

### 10.5 No-op admin action buttons

`frontend/src/pages/admin/Users.tsx` `UserActionsTab`:
```ts
{ icon: 'lock', title: 'Reset password', onClick: () => toast('Reset email sent.', 'success') },
{ icon: 'shield', title: 'Suspend' / 'Unsuspend', onClick: () => toast('Action saved.', 'success') },
{ icon: 'user', title: 'Impersonate', onClick: () => toast('Impersonation: Phase 3.', 'info') },
```

None of these do anything. The toasts are lies. Either wire them or pull the buttons.

### 10.6 `/mobile` page (fake mockups)

`frontend/src/pages/public/Mobile.tsx` — marketing page for a non-existent iOS app:
- Phone mockups with fake order ids `#1029488`, `#1029487`
- "Sign in with passkey" claim (passkey not implemented anywhere)
- "Notify me when iOS launches" form with no backend
- Page even claims "the web app collapses into clean responsive layouts on mobile" — was actively false until commit `07fbf1a7` (mobile-responsive shells fix)

Page isn't linked from the nav. Just delete.

## What to do

### YouTube removal

```
grep -rln -i 'youtube\|yt[A-Z]' backend/src/main backend/src/test frontend/src
```

#### Phase 1 (this task) — stop using the YouTube code paths

**Code-only changes — no physical schema migration on `orders`.**

- Delete YouTube entity, repository, status enum, controller, service files (these are standalone tables, safe to drop).
- `activeYouTubeAccounts` field from `DashboardStats` (also frontend type).
- `youtubeVideoId` field from `OrderResponse` DTO.
- **Remove `youtube_video_id` (and any `target_views`, `target_country`, etc. that came from the YouTube path) from the `Order` JPA entity** — once gone from `@Entity`, Hibernate stops SELECT/INSERT/UPDATE-ing those columns. The columns physically remain in the DB but are orphaned (data preserved, code blind to them).
- Compilation will surface every place that touched the removed getters — clean those branches up.
- `'yt'` entries from frontend `CATS` arrays.
- Landing Coverage tile.
- Standalone `youtube_accounts` table — drop via Liquibase (separate from `orders`, no partition risk).

#### Phase 2 (separate maintenance-window task, 30+ days later)

**`orders` is partitioned and populated** — `<dropColumn>` takes `ACCESS EXCLUSIVE` per partition. On a hot prod DB this can stall inserts and risk deadlock. Don't run it inline with deploy.

When enough time has passed to confirm Phase 1 didn't break anything:

1. Snapshot prod DB → staging. Run the `dropColumn` changeset there. Time it. With many partitions you can be looking at 10+ minutes total.
2. Schedule maintenance window (1-2h, off-peak).
3. Enable maintenance mode (Task 07 adds the toggle) so user traffic returns 503.
4. Run the changeset:
   ```xml
   <changeSet id="phase2-drop-youtube-columns-from-orders" author="cleanup" runInTransaction="false">
     <comment>Phase 1 (commit ...) stopped reading these. Safe to drop physically now.</comment>
     <dropColumn tableName="orders" columnName="youtube_video_id"/>
     <dropColumn tableName="orders" columnName="target_views"/>
     <dropColumn tableName="orders" columnName="target_country"/>
     <rollback>
       <addColumn tableName="orders">
         <column name="youtube_video_id" type="VARCHAR(64)"/>
         <column name="target_views" type="INTEGER"/>
         <column name="target_country" type="VARCHAR(8)"/>
       </addColumn>
       <!-- Rollback recreates schema only — column data is gone forever. -->
     </rollback>
   </changeSet>
   ```
   `runInTransaction="false"` because a single transaction across all partitions can lock too long.
5. `VACUUM (ANALYZE) orders;` on each partition after — reclaim space, refresh stats.
6. Disable maintenance mode.

**For this task (Phase 1) only the code change ships. Phase 2 is a separate task on the calendar.**

DB: don't drop the `services.id=1` row — historical orders FK to it. Set `active=false, name="(retired)", category="DEPRECATED"`.

### Binom removal

```
grep -rln -i 'binom\|BinomService\|isOrderCompletedBasedOnCampaigns\|getCampaignStatsForOrder' \
  backend/src/main backend/src/test
```

#### Phase 1 (this task)

- Delete `BinomService`, `BinomController`, `BinomConfig`, `BinomClient`, dto/binom/, dto/campaign/.
- In `OrderService.java`: delete `isOrderCompletedBasedOnCampaigns(Long)` and `getCampaignStatsForOrder(Long)`. Audit callers via call graph, not just imports.
- In `OrderResponse.java`: drop `binomOfferId` field. Sweep frontend for any reader.
- **Remove `binom_offer_id`, `binom_campaign_id`, etc. from the `Order` JPA entity** so Hibernate stops touching those physical columns. Same rationale as YouTube above — code blind, columns remain in DB.
- Drop standalone `binom_*` tables (`binom_campaigns`, `binom_offers`, etc.) via Liquibase with rollback. These are separate tables, no partition risk. Find them: `psql ... -c "\dt *binom*"` on prod.
- Env: drop `BINOM_*` from `application.yml` and `.env.docker.example`.

#### Phase 2 (paired with the YouTube Phase 2, same maintenance window)

Drop the `binom_offer_id` / `binom_campaign_id` columns from `orders` via the same `runInTransaction="false"` Liquibase changeset described above. Roll into one DDL operation per `orders` to minimize total lock time.

### Flyway directory + disabled indicator + PageStub

```
git rm -r backend/src/main/resources/db/migration/
grep -rn 'classpath:db/migration' backend/    # confirm nothing references it

# RedisHealthIndicator: pick one
git rm backend/src/main/java/com/smmpanel/health/RedisHealthIndicator.java.disabled
# OR
mv ...RedisHealthIndicator.java.disabled ...RedisHealthIndicator.java
# (then register as @Component, expose via /actuator/health)

# PageStub
grep -rn '_PageStub' frontend/src/        # verify zero non-self matches
git rm frontend/src/pages/_PageStub.tsx
```

### Stale tests

```
cd backend && ./gradlew compileTestJava 2>&1 | grep error
# delete files with "cannot find symbol BinomService" / YouTubeAccount / etc.
```

### No-op admin buttons

For `UserActionsTab` in `frontend/src/pages/admin/Users.tsx`:
- **Pull all three buttons** until backend exists. A button whose `onClick` is a fake toast is worse than no button.
- If one of them is in scope to wire NOW: `Suspend / Unsuspend` is the easiest — backend `PUT /api/v2/admin/users/{userId}/status` with `{status: 'active' | 'suspended'}`, flips `User.active`, revokes refresh tokens on suspend, audit-logs.

### `/mobile` page

```
git rm frontend/src/pages/public/Mobile.tsx
```
- `frontend/src/App.tsx` — remove `<Route path="mobile">` and `import { MobilePage }`
- `grep -rn '/mobile\|MobilePage' frontend/src/` — should be 0 after delete

## Verify

1. `cd backend && ./gradlew clean build test` — all green, fewer warnings than before (the 4 Binom unchecked-cast ones gone).
2. `cd backend && ./gradlew liquibaseUpdate` applies the drop changesets cleanly.
3. `cd frontend && npm run build` — green.
4. `grep -rin 'youtube\|binom' backend/src/main/java | wc -l` ≈ 0.
5. `grep -rin 'youtube\|binom' frontend/src/ | wc -l` — 0 outside legal/* (which mentions YouTube as an external platform — that's fine).
6. `/admin/services` page no longer shows the YouTube row at the top.
7. `https://smmworld.vip/mobile` 404s.
8. Admin → Users drawer → Admin actions tab: only buttons that actually work.
9. `curl /api/v2/admin/orders ... | jq '.orders[0] | keys'` — no `binomOfferId`, no `youtubeVideoId`.

## Production / best practices

Make sure everything is **maximum production-ready and follows best practices**.

**The partitioned-table rule** — `orders` (and `operator_logs`) are partitioned and populated. `<dropColumn>` against them takes `ACCESS EXCLUSIVE` per partition and can deadlock with concurrent inserts. **Phase 1 of this task removes the columns from the JPA entity only — code stops reading/writing them, columns stay in the DB.** That's safe to deploy any time. **Phase 2 is the physical drop** — separate task, separate PR, scheduled maintenance window, `runInTransaction="false"`, off-peak. Do not bundle Phase 2 with this PR.

Each Liquibase drop on standalone tables (`youtube_accounts`, `binom_campaigns`, `binom_offers`) must include a working `<rollback>` block. Verify `getCampaignStatsForOrder` and `isOrderCompletedBasedOnCampaigns` truly have zero live callers — search the call graph, not just imports. Tag the commit clearly so a future "what happened to Binom?" grep lands here.
