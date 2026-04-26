import { type ReactNode } from 'react';
import { Icon, type IconName } from './Icon';
import { cn } from '../../lib/utils';

interface EmptyProps {
  title: ReactNode;
  subtitle?: ReactNode;
  action?: ReactNode;
  icon?: IconName;
  className?: string;
}

export function Empty({ title, subtitle, action, icon = 'info', className }: EmptyProps) {
  return (
    <div className={cn('flex flex-col items-center justify-center gap-2 p-12 text-center', className)}>
      <div className="flex h-10 w-10 items-center justify-center rounded-[10px] bg-bg-sunken text-fg-dim">
        <Icon name={icon} size={18} />
      </div>
      <div className="text-[14px] font-semibold">{title}</div>
      {subtitle && <div className="max-w-[320px] text-[12px] text-fg-subtle">{subtitle}</div>}
      {action && <div className="mt-2">{action}</div>}
    </div>
  );
}
