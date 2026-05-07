import { useEffect, useRef } from 'react';

/**
 * Visibility-aware polling: runs {@code load} once on mount, then on a fixed
 * interval, but only while the tab is in the foreground. When the user
 * switches away (Cmd+Tab, minimise, navigate to another tab) we stop firing
 * timers; when they come back we run {@code load} once and resume.
 *
 * Why not just {@code setInterval}? Open admin tabs were polling endpoints
 * every 5–15 seconds in the background — a single forgotten browser tab
 * generated ~1 RPS of bot/status / queue / system-stats traffic at the
 * panel's API for hours. Multiplied across operators it pushed the backend
 * into Tomcat-thread saturation territory and surfaced as the "panel feels
 * laggy" complaint, even though no single endpoint was slow on its own.
 *
 * The {@code load} callback should be stable — wrap it in {@code useCallback}
 * with the deps it actually reads, otherwise this hook will tear down the
 * interval on every render and you'll spam fetches.
 *
 * Returns nothing; the caller manages its own state. The hook only owns the
 * timer and the visibility wiring.
 */
export function useVisibilityAwarePoll(load: () => void, intervalMs: number): void {
  // Stash the latest callback in a ref so the interval doesn't have to be
  // re-created when it changes (and we don't have to demand stability from
  // the caller for correctness, only for perf).
  const loadRef = useRef(load);
  useEffect(() => {
    loadRef.current = load;
  }, [load]);

  useEffect(() => {
    let timer: number | null = null;
    let cancelled = false;

    const tick = () => {
      if (cancelled) return;
      if (typeof document !== 'undefined' && document.hidden) return;
      loadRef.current();
    };

    const start = () => {
      if (timer != null) return;
      timer = window.setInterval(tick, intervalMs);
    };

    const stop = () => {
      if (timer != null) {
        clearInterval(timer);
        timer = null;
      }
    };

    const onVisibility = () => {
      if (document.hidden) {
        stop();
      } else {
        // Catch up on data we missed while hidden, then resume the timer.
        loadRef.current();
        start();
      }
    };

    // Fire once on mount (initial load is the caller's responsibility ONLY
    // when they need different first-tick behaviour; otherwise this gives a
    // single immediate fetch + interval).
    loadRef.current();
    start();
    if (typeof document !== 'undefined') {
      document.addEventListener('visibilitychange', onVisibility);
    }

    return () => {
      cancelled = true;
      stop();
      if (typeof document !== 'undefined') {
        document.removeEventListener('visibilitychange', onVisibility);
      }
    };
  }, [intervalMs]);
}
