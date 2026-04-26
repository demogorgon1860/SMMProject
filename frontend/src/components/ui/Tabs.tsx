import { type ReactNode } from 'react';
import { Icon, type IconName } from './Icon';
import { cn } from '../../lib/utils';

export interface TabItem<V extends string = string> {
  value: V;
  label: ReactNode;
  icon?: IconName;
  count?: number;
}

interface TabsProps<V extends string = string> {
  value: V;
  onChange: (next: V) => void;
  tabs: ReadonlyArray<TabItem<V>>;
  className?: string;
}

export function Tabs<V extends string = string>({ value, onChange, tabs, className }: TabsProps<V>) {
  return (
    <div className={cn('flex gap-[2px] border-b border-border', className)}>
      {tabs.map((t) => {
        const active = value === t.value;
        return (
          <button
            key={t.value}
            type="button"
            onClick={() => onChange(t.value)}
            className={cn(
              'inline-flex items-center gap-[7px] border-b-2 px-[14px] py-[9px] text-[13px]',
              'transition-colors duration-120 -mb-px',
              'focus-visible:outline-none focus-visible:ring-2 focus-visible:[--tw-ring-color:var(--ring)]',
              active
                ? 'border-accent font-semibold text-fg'
                : 'border-transparent font-medium text-fg-muted hover:text-fg',
            )}
          >
            {t.icon && <Icon name={t.icon} size={13} />}
            {t.label}
            {t.count != null && (
              <span className="rounded-[10px] bg-bg-sunken px-[6px] py-[1px] font-mono text-[10px] font-medium text-fg-muted">
                {t.count}
              </span>
            )}
          </button>
        );
      })}
    </div>
  );
}
