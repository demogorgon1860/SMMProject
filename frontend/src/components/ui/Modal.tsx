import { useEffect, type ReactNode } from 'react';
import { Icon } from './Icon';
import { Button } from './Button';
import { cn } from '../../lib/utils';

// =====================================================================
// Modal — center modal with overlay. Uses CSS animations from index.css
// (no framer-motion dep needed). Pass `width={number}` for design-spec
// pixel widths (480/520/540/560), or string for `'90vw'` etc.
// =====================================================================

interface ModalProps {
  open: boolean;
  onClose: () => void;
  title?: ReactNode;
  subtitle?: ReactNode;
  width?: number | string;
  footer?: ReactNode;
  closeOnOverlayClick?: boolean;
  closeOnEscape?: boolean;
  showClose?: boolean;
  children?: ReactNode;
}

export function Modal({
  open,
  onClose,
  title,
  subtitle,
  width = 480,
  footer,
  closeOnOverlayClick = true,
  closeOnEscape = true,
  showClose = true,
  children,
}: ModalProps) {
  // Esc to close
  useEffect(() => {
    if (!open || !closeOnEscape) return;
    const handler = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose();
    };
    document.addEventListener('keydown', handler);
    return () => document.removeEventListener('keydown', handler);
  }, [open, closeOnEscape, onClose]);

  // Lock body scroll while open
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
    <div className="fixed inset-0 z-50 flex items-center justify-center" role="dialog" aria-modal="true">
      <div
        className="absolute inset-0 bg-black/40 overlay-in"
        onClick={closeOnOverlayClick ? onClose : undefined}
      />
      <div
        className="relative flex max-h-[90vh] w-[95vw] flex-col rounded-[10px] border border-border bg-bg-elev shadow-modal fade-in"
        style={{ width: w, maxWidth: '95vw' }}
        onClick={(e) => e.stopPropagation()}
      >
        {(title || showClose) && (
          <div className="flex items-start justify-between gap-3 border-b border-border px-[18px] py-[14px]">
            <div className="min-w-0">
              {title && <div className="text-[14px] font-semibold text-fg">{title}</div>}
              {subtitle && <div className="mt-[3px] text-[12px] text-fg-subtle">{subtitle}</div>}
            </div>
            {showClose && (
              <button
                type="button"
                onClick={onClose}
                aria-label="Close"
                className="rounded-md p-1 text-fg-subtle transition-colors hover:bg-bg-sunken hover:text-fg focus-visible:outline-none focus-visible:ring-2 focus-visible:[--tw-ring-color:var(--ring)]"
              >
                <Icon name="x" size={16} />
              </button>
            )}
          </div>
        )}
        <div className="flex-1 overflow-auto px-[18px] py-[16px]">{children}</div>
        {footer && (
          <div className="flex items-center justify-end gap-2 border-t border-border px-[18px] py-[12px]">{footer}</div>
        )}
      </div>
    </div>
  );
}

// Convenience wrapper for confirm-style modals.
interface ConfirmModalProps {
  open: boolean;
  onClose: () => void;
  onConfirm: () => void;
  title: ReactNode;
  message?: ReactNode;
  confirmText?: string;
  cancelText?: string;
  variant?: 'danger' | 'warn' | 'primary';
  loading?: boolean;
  children?: ReactNode;
}

export function ConfirmModal({
  open,
  onClose,
  onConfirm,
  title,
  message,
  confirmText = 'Confirm',
  cancelText = 'Cancel',
  variant = 'danger',
  loading = false,
  children,
}: ConfirmModalProps) {
  return (
    <Modal
      open={open}
      onClose={onClose}
      title={title}
      width={480}
      footer={
        <>
          <Button variant="ghost" onClick={onClose} disabled={loading}>
            {cancelText}
          </Button>
          <Button variant={variant === 'primary' ? 'primary' : variant} onClick={onConfirm} loading={loading}>
            {confirmText}
          </Button>
        </>
      }
    >
      {message && <p className={cn('text-[13px] text-fg-muted')}>{message}</p>}
      {children}
    </Modal>
  );
}
