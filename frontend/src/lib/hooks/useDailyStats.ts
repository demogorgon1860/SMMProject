import { useEffect, useRef, useState } from 'react';

/**
 * One row from `/v1/me/stats/daily` and `/v2/admin/stats/daily`.
 * `revenue` is BigDecimal — comes back as a JSON string to preserve precision;
 * the UI must coerce with `toNum` before arithmetic.
 */
export interface DailyStatPoint {
  date: string;
  total: number;
  completed: number;
  partial: number;
  cancelled: number;
  revenue: string | number;
}

/**
 * Fetch daily order/revenue rollups (last `days` days) from any backend that
 * returns the canonical `DailyStatPoint[]`. Used by both the user dashboard
 * (profileAPI.dailyStats) and the admin dashboard (adminAPI.dailyStats); the
 * fetcher is passed in so this hook stays decoupled from the API client.
 *
 * Returns an empty array on failure (caller renders an "Awaiting data"
 * placeholder rather than a misleading zeroed chart). `loading` is true until
 * the first fetch settles, regardless of success/failure.
 *
 * The fetcher is captured in a ref so callers can pass an inline arrow function
 * without re-firing the effect every render — the effect only depends on `days`.
 */
export function useDailyStats(
  fetcher: (days: number) => Promise<unknown>,
  days = 30,
): { daily: DailyStatPoint[]; loading: boolean } {
  const [daily, setDaily] = useState<DailyStatPoint[]>([]);
  const [loading, setLoading] = useState(true);

  const fetcherRef = useRef(fetcher);
  useEffect(() => {
    fetcherRef.current = fetcher;
  }, [fetcher]);

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    fetcherRef
      .current(days)
      .then((data) => {
        if (cancelled) return;
        setDaily(Array.isArray(data) ? (data as DailyStatPoint[]) : []);
      })
      .catch(() => {
        if (!cancelled) setDaily([]);
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [days]);

  return { daily, loading };
}
