import { type HTMLAttributes, type ReactNode, forwardRef } from 'react';
import { cn } from '../../lib/utils';

// =====================================================================
// Card — bordered surface. `pad={false}` for tables that need to butt
// against the borders. `hover` enables the lift on hover (`.lift` class).
// Section — Card with a titled header that has its own border-bottom.
// KV — definition row used inside cards/drawers.
// =====================================================================

interface CardProps extends HTMLAttributes<HTMLDivElement> {
  pad?: boolean | number;
  hover?: boolean;
  children?: ReactNode;
}

export const Card = forwardRef<HTMLDivElement, CardProps>(function Card(
  { pad = true, hover, children, className, style, ...rest },
  ref,
) {
  const padPx = pad === false ? 0 : pad === true ? 16 : pad;
  return (
    <div
      ref={ref}
      className={cn('rounded-lg border border-border bg-bg-elev', hover && 'lift', className)}
      style={{ padding: padPx, ...style }}
      {...rest}
    >
      {children}
    </div>
  );
});

interface SectionProps extends Omit<HTMLAttributes<HTMLDivElement>, 'title'> {
  title?: ReactNode;
  subtitle?: ReactNode;
  action?: ReactNode;
  pad?: boolean | number;
  children?: ReactNode;
}

export function Section({ title, subtitle, action, pad = true, children, className, ...rest }: SectionProps) {
  const padPx = pad === false ? 0 : pad === true ? 16 : pad;
  return (
    <div className={cn('overflow-hidden rounded-lg border border-border bg-bg-elev', className)} {...rest}>
      {(title || action) && (
        <div className="flex items-center justify-between gap-3 border-b border-border bg-bg-elev px-4 py-[12px]">
          <div className="min-w-0">
            {title && <div className="text-[13px] font-semibold text-fg">{title}</div>}
            {subtitle && <div className="mt-[2px] text-[11px] text-fg-subtle">{subtitle}</div>}
          </div>
          {action}
        </div>
      )}
      <div style={{ padding: padPx }}>{children}</div>
    </div>
  );
}

interface KVProps {
  k: ReactNode;
  v: ReactNode;
  mono?: boolean;
  className?: string;
}

export function KV({ k, v, mono, className }: KVProps) {
  return (
    <div className={cn('flex items-center justify-between gap-3 border-b border-border py-[9px] last:border-b-0', className)}>
      <div className="text-[12.5px] text-fg-subtle">{k}</div>
      <div className={cn('text-right text-[13px] text-fg', mono && 'font-mono tabular-nums')}>{v}</div>
    </div>
  );
}
