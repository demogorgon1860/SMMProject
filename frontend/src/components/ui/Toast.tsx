import { createContext, useCallback, useContext, useState, type ReactNode } from 'react';
import { Icon } from './Icon';

// =====================================================================
// Toast — bottom-right transient messages. ToastProvider mounts a single
// stack at the root; useToast() returns a `push(msg, kind)` function.
// =====================================================================

export type ToastKind = 'info' | 'success' | 'error' | 'warn';

interface ToastItem {
  id: string;
  msg: ReactNode;
  kind: ToastKind;
}

type PushFn = (msg: ReactNode, kind?: ToastKind) => void;

const ToastCtx = createContext<PushFn | null>(null);

export function ToastProvider({ children, timeoutMs = 3500 }: { children: ReactNode; timeoutMs?: number }) {
  const [toasts, setToasts] = useState<ToastItem[]>([]);

  const push = useCallback<PushFn>(
    (msg, kind = 'info') => {
      // crypto.randomUUID is universally available in evergreen browsers + Node 19+;
      // fall back to a Math.random pair only on legacy targets.
      const id =
        typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function'
          ? crypto.randomUUID()
          : Math.random().toString(36).slice(2) + Math.random().toString(36).slice(2);
      setToasts((t) => [...t, { id, msg, kind }]);
      window.setTimeout(() => {
        setToasts((t) => t.filter((x) => x.id !== id));
      }, timeoutMs);
    },
    [timeoutMs],
  );

  return (
    <ToastCtx.Provider value={push}>
      {children}
      <div className="pointer-events-none fixed bottom-4 right-4 z-[60] flex flex-col items-end gap-2">
        {toasts.map((t) => (
          <ToastBubble key={t.id} item={t} />
        ))}
      </div>
    </ToastCtx.Provider>
  );
}

function ToastBubble({ item }: { item: ToastItem }) {
  const { kind, msg } = item;
  const iconName = kind === 'success' ? 'check' : kind === 'error' ? 'warning' : kind === 'warn' ? 'warning' : 'info';
  const colorClass =
    kind === 'success' ? 'text-success' : kind === 'error' ? 'text-danger' : kind === 'warn' ? 'text-warn' : 'text-accent';

  return (
    <div className="fade-in pointer-events-auto flex min-w-[260px] max-w-[400px] items-center gap-[10px] rounded-lg border border-border-strong bg-bg-elev px-[14px] py-[10px] text-[13px] shadow-pop">
      <Icon name={iconName} size={15} className={colorClass} />
      <span>{msg}</span>
    </div>
  );
}

export function useToast(): PushFn {
  const ctx = useContext(ToastCtx);
  if (!ctx) throw new Error('useToast must be used within a ToastProvider');
  return ctx;
}
