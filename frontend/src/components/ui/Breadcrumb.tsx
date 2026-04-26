import { Fragment, type ReactNode } from 'react';
import { Icon } from './Icon';
import { cn } from '../../lib/utils';

interface BreadcrumbProps {
  items: ReadonlyArray<ReactNode>;
  className?: string;
}

export function Breadcrumb({ items, className }: BreadcrumbProps) {
  return (
    <div className={cn('flex items-center gap-[6px] text-[12px] text-fg-subtle', className)}>
      {items.map((item, i) => {
        const last = i === items.length - 1;
        return (
          <Fragment key={i}>
            {i > 0 && <Icon name="chevron-right" size={12} className="text-fg-dim" />}
            <span className={last ? 'font-medium text-fg' : undefined}>{item}</span>
          </Fragment>
        );
      })}
    </div>
  );
}
