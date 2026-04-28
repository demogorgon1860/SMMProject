// =====================================================================
// API envelope helpers — single source of truth for "give me an array
// out of whatever shape this endpoint returns this week."
//
// The backend uses three shapes interchangeably across endpoints:
//
//   1) bare array                          [ {...}, {...} ]
//   2) Spring Page                          { content: [...], totalElements, ... }
//   3) PerfectPanelResponse wrapping #1/#2  { success, data: <#1 or #2> }
//
// On top of that, controllers add their own named keys (`orders`, `users`,
// `services`, `deposits`, …). We've shipped at least four envelope-mismatch
// bugs that rendered "0 total" with real data on prod. Every page that
// consumes a list endpoint should go through these helpers — no per-page
// inline `data?.x ?? data?.y ?? data?.z ?? []` ladders.
// =====================================================================

/**
 * Walk a maybe-array / maybe-Spring-Page / maybe-PerfectPanelResponse and
 * return the array inside, or `[]` if nothing fits. Try named keys first
 * (caller-supplied, e.g. `'orders'`) so a controller-specific envelope wins
 * before the generic `content` / `data` fallbacks.
 */
export function unwrapList<T = unknown>(
  data: unknown,
  namedKeys: readonly string[] = [],
): T[] {
  if (Array.isArray(data)) return data as T[];
  if (data == null || typeof data !== 'object') return [];
  const obj = data as Record<string, unknown>;

  for (const key of namedKeys) {
    const v = obj[key];
    if (Array.isArray(v)) return v as T[];
  }

  if (Array.isArray(obj.content)) return obj.content as T[];

  // PerfectPanelResponse: { success, data: T[] | Page<T> }
  const inner = obj.data;
  if (Array.isArray(inner)) return inner as T[];
  if (inner && typeof inner === 'object') {
    const innerObj = inner as Record<string, unknown>;
    for (const key of namedKeys) {
      const v = innerObj[key];
      if (Array.isArray(v)) return v as T[];
    }
    if (Array.isArray(innerObj.content)) return innerObj.content as T[];
  }

  return [];
}

/**
 * Like `unwrapList` but also pulls `totalElements` out of the same envelope
 * so server-paginated tables can render their pager without a second
 * round-trip. Falls back to `items.length` when the endpoint returns a bare
 * array (no pagination metadata available).
 */
export function unwrapPage<T = unknown>(
  data: unknown,
  namedKeys: readonly string[] = [],
): { items: T[]; totalElements: number } {
  const items = unwrapList<T>(data, namedKeys);

  let total: number | undefined;
  if (data && typeof data === 'object' && !Array.isArray(data)) {
    const obj = data as Record<string, unknown>;
    if (typeof obj.totalElements === 'number') total = obj.totalElements;
    else {
      const inner = obj.data;
      if (inner && typeof inner === 'object' && !Array.isArray(inner)) {
        const innerObj = inner as Record<string, unknown>;
        if (typeof innerObj.totalElements === 'number') total = innerObj.totalElements;
      }
    }
  }

  return { items, totalElements: total ?? items.length };
}
