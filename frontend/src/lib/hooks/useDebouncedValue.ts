import { useEffect, useState } from 'react';

/**
 * Returns `value` debounced by `delayMs`. The returned reference only updates
 * once `value` has been stable for the full delay window. Use for free-text
 * search inputs so we don't refetch on every keystroke.
 *
 * `delayMs` is reactive — if the caller wants to slow/quicken the debounce
 * mid-flight, the next change picks up the new delay.
 */
export function useDebouncedValue<T>(value: T, delayMs = 250): T {
  const [debounced, setDebounced] = useState(value);
  useEffect(() => {
    const t = window.setTimeout(() => setDebounced(value), delayMs);
    return () => window.clearTimeout(t);
  }, [value, delayMs]);
  return debounced;
}
