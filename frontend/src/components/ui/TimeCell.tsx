import { fmtDate, fmtDateTime, fmtRel } from '../../lib/utils';
import { cn } from '../../lib/utils';

interface TimeCellProps {
  iso: string | Date;
  /** Override the "now" reference for deterministic tests. */
  now?: number;
  className?: string;
}

/**
 * Renders an explicit local YYYY-MM-DD HH:MM by default. Hovering reveals both the
 * relative time ("3m ago") and the absolute UTC for audit/cross-zone work.
 */
export function TimeCell({ iso, now, className }: TimeCellProps) {
  return (
    <span
      title={`${fmtRel(iso, now)} · ${fmtDate(iso)}`}
      className={cn('font-mono tabular-nums text-[12px] text-fg-muted', className)}
    >
      {fmtDateTime(iso)}
    </span>
  );
}
