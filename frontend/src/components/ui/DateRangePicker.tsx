import { useEffect, useRef, useState } from 'react';
import { cn } from '../../lib/utils';
import { Icon } from './Icon';

// =====================================================================
// DateRangePicker — popover with preset chips + custom from/to inputs.
//
// API:
//   from / to — ISO calendar dates `YYYY-MM-DD` (no time component) or undefined.
//   onChange  — fires with (from, to) both undefined for "All time" / cleared.
//
// Behavior:
//   - "All" preset clears both dates.
//   - 24h / 7d / 30d presets compute (today − N) … today, inclusive.
//   - "Custom" reveals two native <input type="date"> for from / to. Apply
//     only commits when both dates are valid and from ≤ to.
//   - Clicking outside closes the popover. Esc closes too.
// =====================================================================

interface DateRangePickerProps {
  from?: string;
  to?: string;
  onChange: (from?: string, to?: string) => void;
  className?: string;
}

type Preset = 'all' | 'last24h' | 'last7d' | 'last30d' | 'custom';

function todayIso(): string {
  return ymd(new Date());
}

function ymd(d: Date): string {
  const pad = (n: number) => n.toString().padStart(2, '0');
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
}

function presetRange(p: Preset): { from?: string; to?: string } {
  if (p === 'all' || p === 'custom') return {};
  const now = new Date();
  const to = ymd(now);
  const days = p === 'last24h' ? 1 : p === 'last7d' ? 7 : 30;
  const fromDate = new Date(now);
  fromDate.setDate(fromDate.getDate() - (days - 1));
  return { from: ymd(fromDate), to };
}

function detectPreset(from?: string, to?: string): Preset {
  if (!from && !to) return 'all';
  const today = todayIso();
  if (to !== today) return 'custom';
  for (const p of ['last24h', 'last7d', 'last30d'] as const) {
    const r = presetRange(p);
    if (r.from === from && r.to === to) return p;
  }
  return 'custom';
}

function formatLabel(from?: string, to?: string): string {
  if (!from && !to) return 'All time';
  if (from && to && from === to) return from;
  if (from && to) return `${from} → ${to}`;
  if (from) return `from ${from}`;
  return `until ${to}`;
}

const PRESETS: { value: Preset; label: string }[] = [
  { value: 'all', label: 'All time' },
  { value: 'last24h', label: 'Last 24h' },
  { value: 'last7d', label: 'Last 7 days' },
  { value: 'last30d', label: 'Last 30 days' },
  { value: 'custom', label: 'Custom' },
];

export function DateRangePicker({ from, to, onChange, className }: DateRangePickerProps) {
  const [open, setOpen] = useState(false);
  const [active, setActive] = useState<Preset>(() => detectPreset(from, to));
  // Custom-tab local state — only commits to onChange on Apply.
  const [customFrom, setCustomFrom] = useState(from ?? '');
  const [customTo, setCustomTo] = useState(to ?? '');
  const rootRef = useRef<HTMLDivElement>(null);

  // Re-sync internal state if parent changes from/to externally.
  useEffect(() => {
    setActive(detectPreset(from, to));
    setCustomFrom(from ?? '');
    setCustomTo(to ?? '');
  }, [from, to]);

  // Close on outside click + Esc.
  useEffect(() => {
    if (!open) return;
    const onDoc = (e: MouseEvent) => {
      if (rootRef.current && !rootRef.current.contains(e.target as Node)) setOpen(false);
    };
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') setOpen(false);
    };
    document.addEventListener('mousedown', onDoc);
    document.addEventListener('keydown', onKey);
    return () => {
      document.removeEventListener('mousedown', onDoc);
      document.removeEventListener('keydown', onKey);
    };
  }, [open]);

  const pickPreset = (p: Preset) => {
    setActive(p);
    if (p === 'custom') return; // wait for Apply
    const r = presetRange(p);
    onChange(r.from, r.to);
    setOpen(false);
  };

  const applyCustom = () => {
    if (!customFrom || !customTo) return;
    if (customFrom > customTo) return;
    onChange(customFrom, customTo);
    setOpen(false);
  };

  const customValid = customFrom && customTo && customFrom <= customTo;

  return (
    <div ref={rootRef} className={cn('relative inline-block', className)}>
      <button
        type="button"
        onClick={() => setOpen((v) => !v)}
        className={cn(
          'inline-flex h-[34px] items-center gap-2 rounded-md border border-bd px-3',
          'text-[13px] font-medium text-fg hover:bg-bg-sunken',
          open && 'bg-bg-sunken',
        )}
        aria-haspopup="dialog"
        aria-expanded={open}
      >
        <Icon name="calendar" size={14} />
        <span className="font-mono tabular-nums">{formatLabel(from, to)}</span>
        <Icon name="chevron-down" size={12} className="text-fg-subtle" />
      </button>

      {open && (
        <div
          role="dialog"
          aria-label="Pick a date range"
          className={cn(
            'absolute right-0 z-30 mt-1 w-[300px] rounded-lg border border-bd bg-bg shadow-lg',
            'p-3',
          )}
        >
          <div className="flex flex-col gap-1">
            {PRESETS.map((p) => (
              <button
                key={p.value}
                type="button"
                onClick={() => pickPreset(p.value)}
                className={cn(
                  'flex h-[32px] items-center justify-between rounded-md px-2 text-[13px]',
                  'hover:bg-bg-sunken',
                  active === p.value && 'bg-accent-soft text-accent-fg',
                )}
              >
                <span>{p.label}</span>
                {active === p.value && <Icon name="check" size={12} />}
              </button>
            ))}
          </div>

          {active === 'custom' && (
            <div className="mt-3 border-t border-bd pt-3">
              <div className="flex flex-col gap-2">
                <label className="flex items-center gap-2 text-[12px] text-fg-muted">
                  <span className="w-12 shrink-0">From</span>
                  <input
                    type="date"
                    value={customFrom}
                    max={customTo || undefined}
                    onChange={(e) => setCustomFrom(e.target.value)}
                    className="h-[32px] flex-1 rounded-md border border-bd bg-bg px-2 font-mono text-[12px]"
                  />
                </label>
                <label className="flex items-center gap-2 text-[12px] text-fg-muted">
                  <span className="w-12 shrink-0">To</span>
                  <input
                    type="date"
                    value={customTo}
                    min={customFrom || undefined}
                    onChange={(e) => setCustomTo(e.target.value)}
                    className="h-[32px] flex-1 rounded-md border border-bd bg-bg px-2 font-mono text-[12px]"
                  />
                </label>
                <div className="mt-1 flex justify-end gap-2">
                  <button
                    type="button"
                    onClick={() => {
                      setCustomFrom('');
                      setCustomTo('');
                      onChange(undefined, undefined);
                      setOpen(false);
                    }}
                    className="h-[28px] rounded-md px-2 text-[12px] text-fg-muted hover:bg-bg-sunken"
                  >
                    Clear
                  </button>
                  <button
                    type="button"
                    onClick={applyCustom}
                    disabled={!customValid}
                    className={cn(
                      'h-[28px] rounded-md px-3 text-[12px] font-semibold text-white',
                      customValid ? 'bg-accent hover:brightness-110' : 'cursor-not-allowed bg-bg-sunken text-fg-subtle',
                    )}
                  >
                    Apply
                  </button>
                </div>
              </div>
            </div>
          )}
        </div>
      )}
    </div>
  );
}
