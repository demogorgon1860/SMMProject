// =====================================================================
// Misc utilities — class-name composer + formatters used across UI.
// =====================================================================

/**
 * Compose class names. Filters out falsy values (undefined, null, false, '').
 * Lighter than `clsx` for our needs, no extra dependency.
 */
export function cn(...parts: Array<string | false | null | undefined>): string {
  return parts.filter(Boolean).join(' ');
}

/** Format USD money. Negative values show a leading minus. */
export function fmtMoney(value: number, currency = '$'): string {
  const neg = value < 0;
  const abs = Math.abs(value);
  return (neg ? '-' : '') + currency + abs.toFixed(2).replace(/\B(?=(\d{3})+(?!\d))/g, ',');
}

/** Pretty-print integer (`12,345`). */
export function fmtInt(n: number): string {
  return n.toLocaleString('en-US');
}

/** Absolute UTC date `YYYY-MM-DD HH:MM UTC` (used in tooltips). */
export function fmtDate(iso: string | Date): string {
  const d = iso instanceof Date ? iso : new Date(iso);
  return d.toISOString().replace('T', ' ').slice(0, 16) + ' UTC';
}

/** Relative time vs. now, `Xs/m/h/d ago`. Pass `nowMs` for deterministic tests. */
export function fmtRel(iso: string | Date, nowMs: number = Date.now()): string {
  const d = (iso instanceof Date ? iso : new Date(iso)).getTime();
  const s = Math.floor((nowMs - d) / 1000);
  if (s < 60) return s + 's ago';
  if (s < 3600) return Math.floor(s / 60) + 'm ago';
  if (s < 86400) return Math.floor(s / 3600) + 'h ago';
  return Math.floor(s / 86400) + 'd ago';
}

/** Duration `Xh Xm` from milliseconds. */
export function fmtDur(ms: number): string {
  const s = Math.floor(ms / 1000);
  const h = Math.floor(s / 3600);
  const m = Math.floor((s % 3600) / 60);
  return h + 'h ' + m + 'm';
}

/** Countdown `MM:SS` from seconds. Used on Add Funds payment screen. */
export function fmtMMSS(secondsLeft: number): string {
  const s = Math.max(0, Math.floor(secondsLeft));
  const m = Math.floor(s / 60);
  const r = s % 60;
  return `${m.toString().padStart(2, '0')}:${r.toString().padStart(2, '0')}`;
}
