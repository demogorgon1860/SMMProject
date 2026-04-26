import { fmtDate, fmtRel } from '../../lib/utils';
import { cn } from '../../lib/utils';

interface TimeCellProps {
  iso: string | Date;
  /** Override the "now" reference for deterministic tests. */
  now?: number;
  className?: string;
}

export function TimeCell({ iso, now, className }: TimeCellProps) {
  return (
    <span title={fmtDate(iso)} className={cn('font-mono tabular-nums text-[12px] text-fg-muted', className)}>
      {fmtRel(iso, now)}
    </span>
  );
}
