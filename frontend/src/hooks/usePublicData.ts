import { useEffect, useState } from 'react';
import { publicAPI } from '../services/api';

// =====================================================================
// Internal: shared-singleton subscriber for endpoints that several
// components on the same page consume.
//
// Without this each consumer mounts its own useEffect → its own setInterval
// → its own fetch. On the public landing usePublicStats() is used by the
// hero badge, the categories section AND the auth-page side panel; before
// this rewrite each caller produced an independent /stats/public + /stats/
// recent-orders request, so a single page load fired each endpoint twice
// AND polled them in parallel forever.
//
// We keep the existing hook signatures unchanged (the components don't
// know any of this exists) and route them through a tiny pub/sub instead.
// One fetch per endpoint per polling tick, regardless of how many
// components subscribe. Pauses fetches while the tab is hidden so
// background tabs stop hitting the API.
// =====================================================================
type Listener<T> = (value: T) => void;

interface Singleton<T> {
  value: T;
  listeners: Set<Listener<T>>;
  timer: number | null;
  inFlight: boolean;
  visibilityHandler: (() => void) | null;
}

function createSingleton<T>(
  fetcher: () => Promise<T>,
  parser: (raw: unknown) => T,
  initial: T,
  intervalMs: number,
): { subscribe: (l: Listener<T>) => () => void } {
  const state: Singleton<T> = {
    value: initial,
    listeners: new Set(),
    timer: null,
    inFlight: false,
    visibilityHandler: null,
  };

  const fetchOnce = () => {
    if (state.inFlight) return; // prevent overlapping requests if backend is slow
    if (typeof document !== 'undefined' && document.hidden) return; // skip while tab hidden
    state.inFlight = true;
    fetcher()
      .then((data) => {
        state.value = parser(data);
        state.listeners.forEach((l) => l(state.value));
      })
      .catch(() => {
        state.value = initial;
        state.listeners.forEach((l) => l(state.value));
      })
      .finally(() => {
        state.inFlight = false;
      });
  };

  const startPolling = () => {
    if (state.timer != null) return;
    state.timer = window.setInterval(fetchOnce, intervalMs);
  };

  const stopPolling = () => {
    if (state.timer != null) {
      clearInterval(state.timer);
      state.timer = null;
    }
  };

  const onVisibilityChange = () => {
    if (document.hidden) {
      stopPolling();
    } else {
      // Catch up on the data we missed while hidden, then resume the timer.
      fetchOnce();
      startPolling();
    }
  };

  return {
    subscribe(listener) {
      state.listeners.add(listener);
      // First subscriber → kick off the fetch + interval and wire the visibility
      // pause. Subsequent subscribers piggy-back on the existing timer.
      if (state.listeners.size === 1) {
        fetchOnce();
        startPolling();
        if (typeof document !== 'undefined') {
          state.visibilityHandler = onVisibilityChange;
          document.addEventListener('visibilitychange', state.visibilityHandler);
        }
      } else {
        // Hand the new subscriber whatever value we already have so it doesn't
        // have to wait for the next tick to render.
        listener(state.value);
      }
      return () => {
        state.listeners.delete(listener);
        // Last subscriber gone → tear everything down so the page stops fetching
        // when nothing on screen needs the data anymore.
        if (state.listeners.size === 0) {
          stopPolling();
          if (state.visibilityHandler && typeof document !== 'undefined') {
            document.removeEventListener('visibilitychange', state.visibilityHandler);
            state.visibilityHandler = null;
          }
        }
      };
    },
  };
}

// =====================================================================
// Shared hooks for the public, unauthenticated stats endpoints.
// All data sourced from real DB aggregates server-side and cached for
// 10–60 seconds in Redis. NO synthetic fallbacks — if the request fails
// the hook returns null/[] and the caller should hide the section.
// =====================================================================

export interface RecentOrder {
  id: number;
  quantity: number;
  service: string;
  status: string;
  ageSeconds: number;
}

export interface PublicStats {
  ordersFulfilled: number;
  ordersLast24h: number;
  /** Count of customer-visible service names (deduped by name, premium-tier only). */
  serviceCount: number;
  usersTotal: number;
  /**
   * Cheapest price per 1,000 units across the customer-visible catalog.
   * String to preserve BigDecimal precision (e.g. "4.0000"). Null when no
   * services are active.
   */
  minPricePer1k: string | null;
}

const recentOrdersStore = createSingleton<RecentOrder[] | null>(
  () => publicAPI.recentOrders(),
  (raw) => (Array.isArray(raw) ? (raw as RecentOrder[]) : []),
  null,
  30_000,
);

/**
 * Recent orders for landing tickers and the auth-page side panel.
 * Auto-refreshes every 30s. Returns null while the first request is in flight,
 * `[]` on failure / empty result. All consumers share a single fetch.
 */
export function useRecentOrders(): RecentOrder[] | null {
  const [orders, setOrders] = useState<RecentOrder[] | null>(null);
  useEffect(() => recentOrdersStore.subscribe(setOrders), []);
  return orders;
}

/**
 * Type guard: response must be an object with the four numeric fields we care
 * about. Anything else (null, array, partial, missing fields, wrong types) →
 * treat as no-data so the UI can render a placeholder instead of crashing.
 * Guards against old backend versions, accidental shape changes, and
 * proxies that return HTML error pages with status 200.
 */
function isPublicStats(x: unknown): x is PublicStats {
  if (x == null || typeof x !== 'object') return false;
  const o = x as Record<string, unknown>;
  // minPricePer1k is allowed to be null (no active services) or a string; anything else fails.
  const priceOk = o.minPricePer1k === null || typeof o.minPricePer1k === 'string';
  return (
    typeof o.ordersFulfilled === 'number' &&
    typeof o.ordersLast24h === 'number' &&
    typeof o.serviceCount === 'number' &&
    typeof o.usersTotal === 'number' &&
    priceOk
  );
}

const publicStatsStore = createSingleton<PublicStats | null>(
  () => publicAPI.stats(),
  (raw) => (isPublicStats(raw) ? raw : null),
  null,
  60_000,
);

/**
 * Aggregate public stats. Auto-refreshes every 60s (matches server cache TTL).
 * Returns null while the first request is in flight, null on failure too,
 * AND null when the backend returns an unexpected shape — caller hides
 * cards rather than rendering zeros or crashing. All consumers share a
 * single fetch (see {@code createSingleton}).
 */
export function usePublicStats(): PublicStats | null {
  const [stats, setStats] = useState<PublicStats | null>(null);
  useEffect(() => publicStatsStore.subscribe(setStats), []);
  return stats;
}

/** "47s" / "3m" / "2h" / "1d" — short relative-age formatter. */
export function fmtAge(seconds: number): string {
  if (seconds < 60) return Math.max(1, Math.round(seconds)) + 's';
  if (seconds < 3600) return Math.round(seconds / 60) + 'm';
  if (seconds < 86400) return Math.round(seconds / 3600) + 'h';
  return Math.round(seconds / 86400) + 'd';
}

/** Quantity formatter: 5 → "5", 1500 → "1,500", 12000 → "12k". */
export function fmtQty(n: number): string {
  if (n >= 10000) return Math.round(n / 1000) + 'k';
  return n.toLocaleString('en-US');
}

/** Status label for ticker rows: terminal status word, otherwise "in progress" / age. */
export function fmtTickerSecondary(status: string, ageSeconds: number): string {
  const s = status.toLowerCase();
  if (s === 'completed') return 'completed';
  if (s === 'partial') return 'partial';
  if (s === 'in_progress' || s === 'processing' || s === 'active') {
    return ageSeconds < 60 ? fmtAge(ageSeconds) : 'in progress';
  }
  return fmtAge(ageSeconds);
}

/**
 * Price formatter for the "min $X/1k" line. Accepts string (BigDecimal-from-JSON
 * preserves precision as text) or null. Renders "$1.00" / "$4.50" — always two
 * decimal places — and "$—" when the price is unknown so the line stays the
 * same width during loading.
 */
export function fmtPrice(value: string | null | undefined): string {
  if (value == null) return '$—';
  const n = Number.parseFloat(value);
  if (!Number.isFinite(n)) return '$—';
  return '$' + n.toFixed(2);
}

/** Compact integer formatter for stats cards: 5,000 → "5k", 1,200,000 → "1.2M". */
export function fmtCompact(n: number | null | undefined): string {
  if (n == null) return '—';
  if (n >= 1_000_000) return (n / 1_000_000).toFixed(1).replace(/\.0$/, '') + 'M';
  if (n >= 10_000) return Math.round(n / 1000) + 'k';
  if (n >= 1000) return (n / 1000).toFixed(1).replace(/\.0$/, '') + 'k';
  return n.toLocaleString('en-US');
}
