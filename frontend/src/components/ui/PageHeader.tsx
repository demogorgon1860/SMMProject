import { type ReactNode } from 'react';
import { Breadcrumb } from './Breadcrumb';

interface PageHeaderProps {
  breadcrumb?: ReadonlyArray<ReactNode>;
  title: ReactNode;
  subtitle?: ReactNode;
  actions?: ReactNode;
}

// Used at the top of admin pages — sticky header with optional crumbs
// + title/subtitle on the left and actions on the right.
export function PageHeader({ breadcrumb, title, subtitle, actions }: PageHeaderProps) {
  return (
    <div className="border-b border-border bg-bg-elev px-6 pb-[14px] pt-[18px]">
      {breadcrumb && (
        <div className="mb-2">
          <Breadcrumb items={breadcrumb} />
        </div>
      )}
      <div className="flex items-center justify-between gap-4">
        <div className="min-w-0">
          <div className="text-[20px] font-semibold leading-tight tracking-[-0.01em]">{title}</div>
          {subtitle && <div className="mt-[3px] text-[13px] text-fg-subtle">{subtitle}</div>}
        </div>
        {actions && <div className="flex items-center gap-2">{actions}</div>}
      </div>
    </div>
  );
}
