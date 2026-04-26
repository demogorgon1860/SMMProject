import { type ReactNode } from 'react';
import { Icon, type IconName } from './Icon';
import { cn } from '../../lib/utils';

// =====================================================================
// Badge — generic colored chip with 8 tones and optional dot/icon.
// StatusBadge — status-aware badge driven by the design's STATUS_COLORS
// map; covers all order/payment/system statuses used in the prototypes.
// Dot — small colored circle, optionally pulsing.
// =====================================================================

export type BadgeTone = 'muted' | 'success' | 'warn' | 'danger' | 'info' | 'accent' | 'violet' | 'black';
export type BadgeSize = 'sm' | 'md' | 'lg';

interface BadgeProps {
  children: ReactNode;
  tone?: BadgeTone;
  size?: BadgeSize;
  icon?: IconName;
  dot?: boolean;
  /** Tooltip on hover (e.g. for status meaning). */
  title?: string;
  className?: string;
}

// Each tone maps to: bg, text, border (uses color-mix on the live token).
const toneStyles: Record<BadgeTone, { bg: string; fg: string; border: string; dot: string }> = {
  muted:   { bg: 'bg-bg-sunken',     fg: 'text-fg-muted',   border: 'border-border',                                              dot: 'bg-fg-muted' },
  success: { bg: 'bg-success-soft',  fg: 'text-success',    border: 'border-[color-mix(in_oklab,var(--success)_30%,transparent)]', dot: 'bg-success' },
  warn:    { bg: 'bg-warn-soft',     fg: 'text-warn',       border: 'border-[color-mix(in_oklab,var(--warn)_30%,transparent)]',    dot: 'bg-warn' },
  danger:  { bg: 'bg-danger-soft',   fg: 'text-danger',     border: 'border-[color-mix(in_oklab,var(--danger)_30%,transparent)]',  dot: 'bg-danger' },
  info:    { bg: 'bg-info-soft',     fg: 'text-info',       border: 'border-[color-mix(in_oklab,var(--info)_30%,transparent)]',    dot: 'bg-info' },
  accent:  { bg: 'bg-accent-soft',   fg: 'text-accent-fg',  border: 'border-[color-mix(in_oklab,var(--accent)_30%,transparent)]',  dot: 'bg-accent' },
  violet:  { bg: 'bg-violet-soft',   fg: 'text-violet',     border: 'border-[color-mix(in_oklab,var(--violet)_30%,transparent)]',  dot: 'bg-violet' },
  black:   { bg: 'bg-fg',            fg: 'text-bg',         border: 'border-fg',                                                   dot: 'bg-bg' },
};

const sizeMap: Record<BadgeSize, { pad: string; fs: string; dot: number }> = {
  sm: { pad: 'px-[7px] py-[1px]', fs: 'text-[10.5px]', dot: 5 },
  md: { pad: 'px-[8px] py-[2px]', fs: 'text-[11.5px]', dot: 6 },
  lg: { pad: 'px-[10px] py-[4px]', fs: 'text-[12.5px]', dot: 7 },
};

export function Badge({ children, tone = 'muted', size = 'md', icon, dot, title, className }: BadgeProps) {
  const t = toneStyles[tone];
  const sz = sizeMap[size];
  return (
    <span
      title={title}
      className={cn(
        'inline-flex items-center gap-[5px] whitespace-nowrap rounded border font-medium leading-[1.35]',
        t.bg,
        t.fg,
        t.border,
        sz.pad,
        sz.fs,
        className,
      )}
    >
      {dot && (
        <span
          className={cn('inline-block flex-none rounded-full', t.dot)}
          style={{ width: sz.dot, height: sz.dot }}
        />
      )}
      {icon && <Icon name={icon} size={parseInt(sz.fs.match(/\d+/)?.[0] || '12') + 1} />}
      {children}
    </span>
  );
}

// ---------------------------------------------------------------------
// StatusBadge — drives tone + label from a status string. Covers admin
// (orders/payments/system) and user statuses.
// ---------------------------------------------------------------------

const STATUS_TONE: Record<string, { tone: BadgeTone; label?: string }> = {
  pending:     { tone: 'muted',   label: 'Pending' },
  in_progress: { tone: 'info',    label: 'In progress' },
  processing:  { tone: 'info',    label: 'Processing' },
  active:      { tone: 'info',    label: 'Active' },
  completed:   { tone: 'success', label: 'Completed' },
  partial:     { tone: 'warn',    label: 'Partial' },
  cancelled:   { tone: 'muted',   label: 'Cancelled' },
  canceled:    { tone: 'muted',   label: 'Cancelled' },
  paused:      { tone: 'violet',  label: 'Paused' },
  holding:     { tone: 'violet',  label: 'Holding' },
  refill:      { tone: 'accent',  label: 'Refilling' },
  error:       { tone: 'danger',  label: 'Error' },
  failed:      { tone: 'danger',  label: 'Failed' },
  suspended:   { tone: 'danger',  label: 'Suspended' },
  paid:        { tone: 'success', label: 'Paid' },
  expired:     { tone: 'muted',   label: 'Expired' },
  up:          { tone: 'success', label: 'Up' },
  down:        { tone: 'danger',  label: 'Down' },
  degraded:    { tone: 'warn',    label: 'Degraded' },
  refunded:    { tone: 'muted',   label: 'Refunded' },
};

const STATUS_MEANING: Record<string, string> = {
  pending: 'Queued, not yet dispatched to bot',
  in_progress: 'Bot is actively executing actions',
  processing: 'Bot is counting start values / preparing',
  active: 'Bot running (alias of in_progress)',
  partial: 'Stopped early, partial refund issued',
  completed: 'Fully delivered',
  cancelled: 'Cancelled by admin or system',
  paused: 'Circuit breaker — awaiting admin decision',
  holding: 'On-hold, awaiting manual review',
  refill: 'Being refilled after drop detection',
  error: 'Unrecoverable error, see message',
  suspended: 'User suspended — order frozen',
};

interface StatusBadgeProps {
  status: string;
  /** Override the auto-mapped label. */
  label?: string;
  size?: BadgeSize;
  className?: string;
}

export function StatusBadge({ status, label, size = 'sm', className }: StatusBadgeProps) {
  const key = status.toLowerCase().replace(/-/g, '_');
  const config = STATUS_TONE[key] ?? { tone: 'muted' as BadgeTone, label: status };
  const tip = STATUS_MEANING[key];
  return (
    <Badge tone={config.tone} size={size} dot title={tip} className={className}>
      {label ?? config.label ?? status}
    </Badge>
  );
}

// ---------------------------------------------------------------------
// Dot — tiny status LED, with optional pulse.
// ---------------------------------------------------------------------

interface DotProps {
  /** Pass a CSS color or a `var(--token)`. Defaults to `var(--fg-dim)`. */
  color?: string;
  size?: number;
  animate?: boolean;
  className?: string;
}

export function Dot({ color = 'var(--fg-dim)', size = 6, animate = false, className }: DotProps) {
  return (
    <span
      className={cn('inline-block flex-none rounded-full', animate && 'pulse-dot', className)}
      style={{ width: size, height: size, background: color }}
    />
  );
}
