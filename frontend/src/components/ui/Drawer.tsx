import { useEffect, type ReactNode } from 'react';
import { Icon } from './Icon';

// =====================================================================
// Drawer — right-side slide-over panel. Used heavily for order/user/
// service detail. Default 720px; admin uses 820 for orders/users.
// =====================================================================

interface DrawerProps {
  open: boolean;
  onClose: () => void;
  title?: ReactNode;
  subtitle?: ReactNode;
  width?: number | string;
  actions?: ReactNode;
  closeOnOverlayClick?: boolean;
  closeOnEscape?: boolean;
  children?: ReactNode;
}

export function Drawer({
  open,
  onClose,
  title,
  subtitle,
  width = 720,
  actions,
  closeOnOverlayClick = true,
  closeOnEscape = true,
  children,
}: DrawerProps) {
  useEffect(() => {
    if (!open || !closeOnEscape) return;
    const handler = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose();
    };
    document.addEventListener('keydown', handler);
    return () => document.removeEventListener('keydown', handler);
  }, [open, closeOnEscape, onClose]);

  useEffect(() => {
    if (!open) return;
    const prev = document.body.style.overflow;
    document.body.style.overflow = 'hidden';
    return () => {
      document.body.style.overflow = prev;
    };
  }, [open]);

  if (!open) return null;
  const w = typeof width === 'number' ? `${width}px` : width;

  return (
    <div className="fixed inset-0 z-40" role="dialog" aria-modal="true">
      <div
        className="absolute inset-0 bg-black/35 overlay-in"
        onClick={closeOnOverlayClick ? onClose : undefined}
      />
      <div
        className="absolute right-0 top-0 bottom-0 flex max-w-[95vw] flex-col border-l border-border bg-bg shadow-drawer slide-in-right"
        style={{ width: w }}
        onClick={(e) => e.stopPropagation()}
      >
        <div className="flex items-center justify-between gap-3 border-b border-border bg-bg-elev px-[20px] py-[14px]">
          <div className="flex min-w-0 items-center gap-[10px]">
            <button
              type="button"
              onClick={onClose}
              aria-label="Close"
              className="rounded-md p-1 text-fg-subtle transition-colors hover:bg-bg-sunken hover:text-fg focus-visible:outline-none focus-visible:ring-2 focus-visible:[--tw-ring-color:var(--ring)]"
            >
              <Icon name="x" size={16} />
            </button>
            <div className="min-w-0">
              {title && <div className="flex items-center gap-2 text-[14px] font-semibold text-fg">{title}</div>}
              {subtitle && <div className="mt-[2px] text-[12px] text-fg-subtle">{subtitle}</div>}
            </div>
          </div>
          {actions && <div className="flex gap-[6px]">{actions}</div>}
        </div>
        <div className="flex-1 overflow-auto">{children}</div>
      </div>
    </div>
  );
}
