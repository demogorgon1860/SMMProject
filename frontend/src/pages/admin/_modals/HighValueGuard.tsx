import { Icon, Input } from '../../../components/ui';

interface HighValueGuardProps {
  /** Visible only when this is true (e.g. refund > $100). */
  active: boolean;
  /** Mono string the operator must retype to confirm. */
  expected: string;
  /** Current value of the confirm input. */
  value: string;
  onChange: (next: string) => void;
}

// Warn-soft block + free-text input requiring the operator to type a
// specific string (the order ID, user ID, or `CONFIRM`) to enable the
// destructive button. Used inside Mark-Partial / Adjust-Balance / Manual
// transaction modals.
export function HighValueGuard({ active, expected, value, onChange }: HighValueGuardProps) {
  if (!active) return null;
  return (
    <div className="mt-3 rounded-md border border-warn/30 bg-warn-soft p-3">
      <div className="flex items-center gap-2 text-[12.5px] font-medium text-warn">
        <Icon name="warning" size={14} /> High-value action — confirm to enable
      </div>
      <div className="mt-1 text-[11.5px] text-fg-muted">
        Type <span className="font-mono text-fg">{expected}</span> exactly to unlock the confirm button.
      </div>
      <div className="mt-2">
        <Input
          block
          inputSize="sm"
          value={value}
          onChange={(e) => onChange(e.target.value)}
          placeholder={expected}
        />
      </div>
    </div>
  );
}

/** True when the typed value matches expected exactly. */
export function isGuardCleared(active: boolean, expected: string, value: string): boolean {
  return !active || value === expected;
}
