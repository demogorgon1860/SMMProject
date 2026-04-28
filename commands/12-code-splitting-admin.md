# Task 18 — Code-split admin routes (lazy-load)

## Context

(See `_CONTEXT.md`.)

## What's wrong

Frontend builds to a single ~370kB JS chunk (95kB gzip), checked at last build:

```
dist/assets/index-XXX.js   369.06 kB │ gzip: 95.71 kB
```

That chunk includes all 11 admin pages (Dashboard, Orders, Users, Services, Payments, Balance, Bot, Telegram, System, Settings, RefillRequests) plus their drawers and modals. Visitors who never touch admin pay for ~150kB of admin code on every page load including Landing.

`frontend/src/App.tsx` imports admin pages directly:
```ts
import { AdminDashboardPage } from './pages/admin/Dashboard';
import { AdminOrdersPage } from './pages/admin/Orders';
// ... 9 more
```

These all bundle into the initial chunk. React Router supports `React.lazy` + `Suspense` boundaries to split routes naturally.

## What to do

1. Convert admin imports in `frontend/src/App.tsx` to `React.lazy`:
   ```ts
   const AdminDashboardPage = React.lazy(() => import('./pages/admin/Dashboard').then(m => ({ default: m.AdminDashboardPage })));
   const AdminOrdersPage = React.lazy(() => import('./pages/admin/Orders').then(m => ({ default: m.AdminOrdersPage })));
   // ... etc
   ```
2. The existing `<Suspense fallback={<PageLoader />}>` wrapping `<Routes>` already covers it — no new boundaries needed.
3. Same trick for the public routes that aren't on the critical path (Help, ApiDocs, legal/*) — these aren't tiny but they aren't on Landing → Login → Dashboard.
4. Keep eager imports for: `LandingPage`, `LoginPage`, `RegisterPage`, `DashboardPage`. These are critical-path; lazy-loading them costs more than it saves.
5. Verify Vite's chunking actually splits — `npm run build` should now produce multiple `assets/admin-*.js` chunks. If everything still ends up in `index-*.js`, check `vite.config.ts` for any `build.rollupOptions.output.manualChunks` that's flattening it back.
6. Optional: a small `manualChunks` config to group:
   - `vendor` (React, React-router, Zustand)
   - `charts` (Recharts? Chart libs)
   - `forms` (form-related shared)
   - `admin` (everything under `pages/admin/`)
   - `app` (everything under `pages/app/`)
   - `public` (everything under `pages/public/`)

## Verify

1. `cd frontend && npm run build` produces multiple chunks.
2. Initial bundle (`index-*.js`) drops to ~50-70kB gzip.
3. Open Landing — DevTools Network shows only the initial chunk loaded.
4. Click "Sign in" → Login chunk fetched (or eager — check).
5. Sign in as admin → Dashboard route lazily loads `admin-dashboard-*.js`.
6. Navigate to `/admin/orders` → admin-orders chunk loads.
7. PageLoader spinner shows during the lazy fetch on slow connections (throttle in DevTools to verify).

## Production / best practices

Make sure everything is **maximum production-ready and follows best practices**. Lazy chunks need a robust loading state — the existing `<PageLoader />` is fine. Add an error boundary too: if a chunk fetch fails (intermittent network on mobile), show "Couldn't load this section. Refresh." instead of a blank page. Cache headers on chunks should be `immutable, max-age=31536000` since Vite content-hashes them — verify nginx config.
