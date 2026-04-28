import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { unwrapPage } from '../api';
import { useDebouncedValue } from './useDebouncedValue';

/**
 * Default background-refresh cadence — matches the bot's 15-min poll cycle so
 * an open Orders page picks up `start_count` / `remains` updates without the
 * operator hitting Refresh.
 */
const DEFAULT_REFRESH_INTERVAL_MS = 15 * 60 * 1000;

/**
 * Default search debounce — long enough to skip per-keystroke refetches,
 * short enough that the operator still sees results "instantly."
 */
const DEFAULT_SEARCH_DEBOUNCE_MS = 250;

export interface UseServerPaginationOptions {
  /**
   * Issues the underlying API request. Receives `{ page, size, search?, ...baseParams }` and
   * should return whatever the endpoint returns — bare array, Spring Page, or PerfectPanelResponse.
   * `unwrapPage` normalizes the shape on the way out so the hook never depends on a specific
   * envelope.
   */
  fetcher: (params: Record<string, unknown>) => Promise<unknown>;

  /**
   * Filter parameters that vary per page (status, dateFrom, …). The hook compares by serialized
   * value, so a new identity from the caller without a real content change does NOT trigger a
   * page reset / refetch — but you should still memoize on the caller side to keep renders cheap.
   */
  baseParams?: Record<string, unknown>;

  /** `size` parameter sent to the backend. Default 100 (matches `@Max(100)` on OrderController). */
  pageSize?: number;

  /** Free-text search; debounced internally before being sent. Empty/whitespace is omitted. */
  search?: string;

  /** Override the search debounce (default 250 ms). */
  searchDebounceMs?: number;

  /**
   * Optional named key inside the response envelope (e.g. `'orders'`, `'users'`). Tried first by
   * `unwrapPage` before falling back to `content` / `data`.
   */
  envelopeKey?: string;

  /** Background refresh interval. Default 15 min; set to 0 to disable auto-refresh entirely. */
  refreshIntervalMs?: number;
}

export interface UseServerPaginationResult<T> {
  items: T[];
  totalElements: number;
  /** 1-indexed for the `<Pagination>` component. */
  page: number;
  setPage: (page: number) => void;
  loading: boolean;
  /** Triggers a foreground refetch (with spinner). Useful on operator "Refresh" buttons. */
  refresh: () => void;
}

/**
 * Stable, order-insensitive serialization of an arbitrary record. We pre-sort keys so a parent
 * spreading filter conditionals in a different order (`{a, b}` vs `{b, a}`) doesn't read as a
 * filter change. `JSON.stringify`'s natural object handling preserves nested arrays/objects fine.
 */
function serializeFilter(baseParams: Record<string, unknown> | undefined, search: string): string {
  const merged: Record<string, unknown> = { ...(baseParams ?? {}), __search: search };
  const keys = Object.keys(merged).sort();
  const ordered: Record<string, unknown> = {};
  for (const k of keys) ordered[k] = merged[k];
  return JSON.stringify(ordered);
}

/**
 * Server-paginated table data with debounced search, automatic page-1 reset on filter change, and
 * silent background refresh. Extracted from the duplicated `pages/admin/Orders.tsx` and
 * `pages/app/Orders.tsx` implementations so any future paginated page (Users, Payments, …) gets
 * the same behavior for free.
 *
 * Behavior:
 *  - Foreground fetch toggles `loading: true` so the table can show a spinner / skeleton
 *  - Background refresh keeps the existing rows on screen — no flash, no spinner
 *  - Filter changes (`baseParams` value OR debounced `search`) reset to page 1 atomically — one
 *    refetch per change, never two, even when the user was on page 5 of the old result set
 *  - Failures during a foreground fetch clear the list; failures during background refresh are
 *    swallowed (last-known-good rows stay visible)
 *  - The fetcher reference is captured in a ref so callers can pass an arrow function inline
 *    without re-firing the effect every render.
 */
export function useServerPagination<T>({
  fetcher,
  baseParams,
  pageSize = 100,
  search = '',
  searchDebounceMs = DEFAULT_SEARCH_DEBOUNCE_MS,
  envelopeKey,
  refreshIntervalMs = DEFAULT_REFRESH_INTERVAL_MS,
}: UseServerPaginationOptions): UseServerPaginationResult<T> {
  const debouncedSearch = useDebouncedValue(search.trim(), searchDebounceMs);
  const filterKey = useMemo(
    () => serializeFilter(baseParams, debouncedSearch),
    [baseParams, debouncedSearch],
  );

  const [page, setPage] = useState(1);
  const [items, setItems] = useState<T[]>([]);
  const [totalElements, setTotalElements] = useState(0);
  const [loading, setLoading] = useState(true);
  const [refreshTick, setRefreshTick] = useState(0);

  // Track which filter the current `page` value belongs to. When the parent passes a new
  // baseParams / search and `filterKey` no longer matches `committedFilterKey`, we update
  // both pieces of state synchronously *during the same render*. React discards this render's
  // output and re-runs the body with the new state — so the fetch effect downstream only ever
  // sees a consistent (page, filter) pair, and never issues a stale-page request before reset.
  // This is the documented React pattern for derived state:
  // https://react.dev/reference/react/useState#storing-information-from-previous-renders
  const [committedFilterKey, setCommittedFilterKey] = useState(filterKey);
  if (committedFilterKey !== filterKey) {
    setCommittedFilterKey(filterKey);
    if (page !== 1) setPage(1);
  }

  const refresh = useCallback(() => setRefreshTick((n) => n + 1), []);

  // Capture fetcher in a ref so an inline `(p) => api.foo(p)` doesn't re-fire the effect every
  // render — a common foot-gun with hooks that take async functions as args.
  const fetcherRef = useRef(fetcher);
  useEffect(() => {
    fetcherRef.current = fetcher;
  }, [fetcher]);

  // baseParams may legitimately change identity between renders even when the *value* is the
  // same; `filterKey` is the canonical signal for that. But we still need access to the CURRENT
  // baseParams when assembling fetch params, so stash it in a ref.
  const baseParamsRef = useRef(baseParams);
  useEffect(() => {
    baseParamsRef.current = baseParams;
  }, [baseParams]);

  useEffect(() => {
    let cancelled = false;
    const namedKeys = envelopeKey ? [envelopeKey] : [];

    const fetchPage = (showSpinner: boolean) => {
      if (showSpinner) setLoading(true);
      const params: Record<string, unknown> = {
        page: page - 1,
        size: pageSize,
        ...(baseParamsRef.current ?? {}),
        ...(debouncedSearch ? { search: debouncedSearch } : {}),
      };
      fetcherRef
        .current(params)
        .then((data) => {
          if (cancelled) return;
          const { items: nextItems, totalElements: nextTotal } = unwrapPage<T>(data, namedKeys);
          setItems(nextItems);
          setTotalElements(nextTotal);
        })
        .catch(() => {
          // Silent on background failures — keep last-known-good rows on screen so a transient
          // 502 doesn't blank the operator's table mid-task.
          if (!cancelled && showSpinner) {
            setItems([]);
            setTotalElements(0);
          }
        })
        .finally(() => {
          if (!cancelled && showSpinner) setLoading(false);
        });
    };

    fetchPage(true);

    let interval: number | undefined;
    if (refreshIntervalMs > 0) {
      interval = window.setInterval(() => fetchPage(false), refreshIntervalMs);
    }

    return () => {
      cancelled = true;
      if (interval !== undefined) window.clearInterval(interval);
    };
    // `committedFilterKey` (not `filterKey`) is the right dependency: the in-render reset above
    // makes them equal before this effect runs, but listing the committed value documents the
    // invariant that fetch only fires for committed (page, filter) pairs.
  }, [page, pageSize, committedFilterKey, debouncedSearch, envelopeKey, refreshIntervalMs, refreshTick]);

  return { items, totalElements, page, setPage, loading, refresh };
}
