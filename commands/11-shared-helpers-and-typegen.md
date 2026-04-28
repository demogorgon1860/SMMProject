# Task 11 — Shared frontend helpers + OpenAPI type generation

## Context

(See `_CONTEXT.md`.)

The frontend has duplicated helper functions inlined across multiple files, repeated server-pagination + debounce patterns, and hand-maintained types that drift from backend DTOs. Each drift becomes a runtime bug — we already shipped fixes for ~7 of them. Generated types catch them at compile time.

This is one task because all three pieces (extract helpers, extract hooks, generate types) are interlocking — types feed hooks, hooks feed pages, helpers feed both.

## What's wrong

### 11.1 Duplicated `toNum` helper

Same identical function in 6 files:
- `frontend/src/pages/app/Dashboard.tsx`
- `frontend/src/pages/app/NewOrder.tsx` (inlined inside balance coercion)
- `frontend/src/pages/admin/Users.tsx`
- `frontend/src/pages/admin/Payments.tsx`
- `frontend/src/components/shells/AppShell.tsx` (inlined inside topbar wallet chip)
- `frontend/src/pages/admin/Dashboard.tsx`

```ts
function toNum(v: unknown): number {
  if (typeof v === 'number') return Number.isFinite(v) ? v : 0;
  if (typeof v === 'string') {
    const n = Number.parseFloat(v);
    return Number.isFinite(n) ? n : 0;
  }
  return 0;
}
```

### 11.2 Repeated envelope unwrap

Every paginated page hand-rolls a different variant of:
```ts
const arr = Array.isArray(data)
  ? data
  : data?.users ?? data?.content ?? data?.data ?? [];
```

Several bugs we shipped were envelope mismatches (`b361cf8e` ServicesList; admin Payments; NewOrder). Same shape pattern, different keys per endpoint.

### 11.3 Debounce + server-pagination + 15-min refresh

`pages/admin/Orders.tsx` and `pages/app/Orders.tsx` both implement nearly identical:
- `[page, totalElements, loading]` state
- `useDebouncedValue` for search (250ms)
- Reset page to 1 on filter change
- Fetch on `[page, status, debouncedSearch]`
- 15-minute background refresh (silent, no spinner)
- Cleanup on unmount

The pattern will repeat for any paginated table (Users, Payments, future).

### 11.4 Hand-maintained types drift from backend (catches all of the above + more)

`frontend/src/types/index.ts` is hand-written. Real bugs we already shipped fixes for, all caused by drift:

| Bug | Frontend expected | Backend returned |
|---|---|---|
| Admin Users password leak | trimmed `User` | full JPA entity with `passwordHash`, `apiKeyHash` |
| `isActive` vs `active` | `u.isActive` | `active` (Lombok-generated getter, Jackson strips `is`) |
| `userId` on admin order | `order.userId` | `username` |
| `service` on admin order | `order.service.name` | `serviceName` (flat) |
| `amount` on deposit | `p.amount` | `amountUsdt` |
| `crypto` on deposit | `p.crypto` | (nothing — Cryptomus picks at checkout) |
| `reason` on transaction | `t.reason` | `description` |
| `comments` on order create | `comments` | `customComments` |

Generated types catch all of these at compile time.

## What to do

### 11.A Extract `toNum` to `frontend/src/lib/utils.ts`

Add one export:
```ts
/**
 * Coerce maybe-string-maybe-number-maybe-undefined money value into a real Number.
 * The backend serializes BigDecimal as a JSON string ("252.18") to preserve precision;
 * frontend math (toFixed, comparisons, arithmetic) needs a real Number. Returns 0 for
 * null / undefined / NaN / non-finite input — never propagates NaN.
 */
export function toNum(v: unknown): number {
  if (typeof v === 'number') return Number.isFinite(v) ? v : 0;
  if (typeof v === 'string') {
    const n = Number.parseFloat(v);
    return Number.isFinite(n) ? n : 0;
  }
  return 0;
}
```

Replace all 6 inline definitions with `import { toNum } from '../../lib/utils'`.

### 11.B Add `unwrapList<T>` + `unwrapPage<T>` to `frontend/src/lib/api.ts` (new file)

```ts
export function unwrapList<T>(data: unknown, namedKeys: readonly string[] = []): T[] {
  if (Array.isArray(data)) return data as T[];
  if (data == null || typeof data !== 'object') return [];
  const obj = data as Record<string, unknown>;
  for (const key of namedKeys) {
    if (Array.isArray(obj[key])) return obj[key] as T[];
  }
  if (Array.isArray(obj.data)) return obj.data as T[];
  if (Array.isArray(obj.content)) return obj.content as T[];
  // Spring Page wrapped in PerfectPanelResponse: { data: { content: T[] } }
  if (obj.data && typeof obj.data === 'object' && Array.isArray((obj.data as Record<string, unknown>).content)) {
    return (obj.data as Record<string, unknown>).content as T[];
  }
  return [];
}

export function unwrapPage<T>(data: unknown, namedKeys: readonly string[] = []): { items: T[]; totalElements: number } {
  const items = unwrapList<T>(data, namedKeys);
  const obj = (data ?? {}) as Record<string, unknown>;
  const total =
    typeof obj.totalElements === 'number' ? obj.totalElements :
    obj.data && typeof (obj.data as Record<string, unknown>).totalElements === 'number'
      ? (obj.data as Record<string, unknown>).totalElements as number
      : items.length;
  return { items, totalElements: total };
}
```

Replace inline unwraps in: `pages/app/Dashboard.tsx`, `pages/app/Orders.tsx`, `pages/app/NewOrder.tsx`, `pages/admin/Orders.tsx`, `pages/admin/Users.tsx`, `pages/admin/Payments.tsx`, `pages/admin/Services.tsx`, `pages/public/ServicesList.tsx`.

### 11.C Extract hooks to `frontend/src/lib/hooks/`

Three files:

**`useDebouncedValue.ts`** — generic debounce:
```ts
export function useDebouncedValue<T>(value: T, delayMs = 250): T {
  const [d, setD] = useState(value);
  useEffect(() => {
    const t = setTimeout(() => setD(value), delayMs);
    return () => clearTimeout(t);
  }, [value, delayMs]);
  return d;
}
```

**`useDailyStats.ts`** — fetches `DailyStatPoint[]` from any `(days) => Promise<unknown>` fetcher. Used by both user and admin Dashboards.

**`useServerPagination.ts`** — full pattern: page state, totalElements, debounced search, reset on filter change, 15-min refresh, no-spinner background fetches, envelope unwrap. Signature roughly:
```ts
useServerPagination<T>({
  fetcher: (params) => Promise<unknown>,
  baseParams,
  pageSize = 100,
  search = '',
  envelopeKey?: string,
  refreshIntervalMs = 15 * 60 * 1000,
}) => { items, totalElements, page, setPage, loading, refresh }
```

Migrate `pages/admin/Orders.tsx` + `pages/app/Orders.tsx` to use it.

### 11.D OpenAPI type generation

1. Verify SpringDoc is exposing `/v3/api-docs`. If Task 01 moved it (path conflict with SPA route), use the new path. Default config:
   ```yaml
   springdoc:
     api-docs:
       enabled: true
       path: /openapi/v3
   ```

2. Add devDependency:
   ```
   cd frontend && npm install -D openapi-typescript
   ```

3. `frontend/package.json` script:
   ```json
   "scripts": {
     "gen-types": "openapi-typescript http://localhost:8080/openapi/v3 -o src/types/api.gen.ts"
   }
   ```

4. Generate: `npm run gen-types` (with backend running locally). Output is ~2-5k lines of `paths` and `components.schemas`.

5. Replace hand-maintained types with thin re-exports in `frontend/src/types/index.ts`:
   ```ts
   import type { components } from './api.gen';
   export type Order = components['schemas']['OrderResponse'];
   export type User = components['schemas']['UserDto'];
   export type Service = components['schemas']['ServiceResponse'];
   export type BalanceTransaction = components['schemas']['TransactionHistoryResponse'];
   ```

6. Walk through every consumer in `frontend/src/pages/` and `components/` — TypeScript compile errors will surface field-name mismatches. Fix them (this is the whole point — drift caught at compile time).

7. CI: add `npm run gen-types` step that fails build if `api.gen.ts` would change. Or commit the regenerated file from a backend-build job.

## Verify

1. `grep -rn 'function toNum' frontend/src` → only one match in `lib/utils.ts`.
2. `grep -c 'data?.content ??' frontend/src/pages/**/*.tsx` → 0 (all unwraps go through helper).
3. `npm run build` and `npx tsc --noEmit` clean.
4. All affected pages still load: Dashboard, Orders (user + admin), Users, Payments, Services, ServicesList.
5. Server-pagination still works: prev/next, search debounce 250ms, status filter resets to page 1, 15-min auto-refresh fires (instrument with console.log temporarily).
6. `npm run gen-types` produces non-empty `api.gen.ts`.
7. Touch a backend DTO field name → regen → consumer file fails to compile until fixed. (This proves the loop works.)

## Production / best practices

Make sure everything is **maximum production-ready and follows best practices**. Don't widen function signatures speculatively. Generated types are the single source of truth — never hand-edit `api.gen.ts`. Pre-commit hook should regenerate. Hooks should be unit-testable in isolation (Vitest); write at least one happy-path + one error-path test before migrating consumers. Use `useMemo` on caller side for `baseParams` rather than `JSON.stringify` in the hook deps.
