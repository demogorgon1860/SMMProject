import { useEffect, useMemo, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Icon, type IconName } from './ui/Icon';
import { cn } from '../lib/utils';

// =====================================================================
// Command palette — opens with ⌘K / Ctrl+K from anywhere within the
// admin shell. Quick navigation to admin pages + jump-to-order-by-id +
// jump-to-user shortcuts.
// =====================================================================

interface Command {
  id: string;
  label: string;
  hint?: string;
  icon: IconName;
  to: string;
  group: 'Navigate' | 'Quick actions';
}

const COMMANDS: Command[] = [
  { id: 'nav-dashboard', label: 'Dashboard', icon: 'dashboard', to: '/admin', group: 'Navigate' },
  { id: 'nav-orders', label: 'Orders', icon: 'orders', to: '/admin/orders', group: 'Navigate' },
  { id: 'nav-users', label: 'Users', icon: 'users', to: '/admin/users', group: 'Navigate' },
  { id: 'nav-services', label: 'Services', icon: 'grid', to: '/admin/services', group: 'Navigate' },
  { id: 'nav-payments', label: 'Payments', icon: 'card', to: '/admin/payments', group: 'Navigate' },
  { id: 'nav-balance', label: 'Balance Txs', icon: 'wallet', to: '/admin/balance', group: 'Navigate' },
  { id: 'nav-bot', label: 'Instagram Bot', icon: 'bot', to: '/admin/bot', group: 'Navigate' },
  { id: 'nav-telegram', label: 'Telegram Control', icon: 'paper-plane', to: '/admin/telegram', group: 'Navigate' },
  { id: 'nav-system', label: 'System monitoring', icon: 'activity', to: '/admin/system', group: 'Navigate' },
  { id: 'nav-settings', label: 'Settings', icon: 'settings', to: '/admin/settings', group: 'Navigate' },
  { id: 'qa-pending', label: 'View pending decisions', hint: 'circuit-breaker queue', icon: 'paper-plane', to: '/admin/telegram?tab=decisions', group: 'Quick actions' },
  { id: 'qa-bot-health', label: 'Bot health', icon: 'bot', to: '/admin/bot', group: 'Quick actions' },
  { id: 'qa-logs', label: 'Open logs', icon: 'terminal', to: '/admin/system?tab=logs', group: 'Quick actions' },
];

export function CommandPalette({ open, onClose }: { open: boolean; onClose: () => void }) {
  const navigate = useNavigate();
  const [q, setQ] = useState('');
  const [active, setActive] = useState(0);
  const inputRef = useRef<HTMLInputElement>(null);

  useEffect(() => {
    if (open) {
      setQ('');
      setActive(0);
      window.setTimeout(() => inputRef.current?.focus(), 10);
    }
  }, [open]);

  const filtered = useMemo(() => {
    const needle = q.trim().toLowerCase();
    if (!needle) return COMMANDS;
    return COMMANDS.filter((c) => c.label.toLowerCase().includes(needle) || c.hint?.toLowerCase().includes(needle));
  }, [q]);

  // Detect "order #1234" pattern → suggest jump-to-order
  const orderJump = useMemo(() => {
    const m = q.match(/^(?:#|order\s*#?)\s*(\d+)/i);
    return m ? Number(m[1]) : null;
  }, [q]);

  useEffect(() => {
    if (!open) return;
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        onClose();
        return;
      }
      if (e.key === 'ArrowDown') {
        e.preventDefault();
        setActive((a) => Math.min((orderJump ? 1 : 0) + filtered.length - 1, a + 1));
        return;
      }
      if (e.key === 'ArrowUp') {
        e.preventDefault();
        setActive((a) => Math.max(0, a - 1));
        return;
      }
      if (e.key === 'Enter') {
        e.preventDefault();
        if (orderJump && active === 0) {
          navigate(`/admin/orders?id=${orderJump}`);
          onClose();
          return;
        }
        const list = orderJump ? [{ to: '__order__' }, ...filtered] : filtered;
        const target = list[active];
        if (target && 'to' in target && target.to !== '__order__') {
          navigate(target.to);
          onClose();
        }
      }
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [open, filtered, active, orderJump, navigate, onClose]);

  if (!open) return null;

  let i = 0;
  return (
    <div className="fixed inset-0 z-[100] flex items-start justify-center pt-[80px]" role="dialog" aria-modal="true">
      <div className="absolute inset-0 bg-black/50" onClick={onClose} />
      <div className="fade-in relative w-[560px] max-w-[92vw] overflow-hidden rounded-[10px] border border-border bg-bg-elev shadow-modal">
        <div className="flex items-center gap-[10px] border-b border-border px-4 py-3">
          <Icon name="search" size={15} className="text-fg-dim" />
          <input
            ref={inputRef}
            value={q}
            onChange={(e) => {
              setQ(e.target.value);
              setActive(0);
            }}
            placeholder="Type a command, page name, or order #1234"
            className="flex-1 border-0 bg-transparent text-[14px] outline-none"
          />
          <span className="font-mono text-[10px] text-fg-dim">ESC</span>
        </div>
        <div className="max-h-[400px] overflow-auto">
          {orderJump && (
            <PaletteRow
              isActive={active === i++}
              icon="orders"
              label={`Jump to order #${orderJump}`}
              hint="open in admin"
              onClick={() => {
                navigate(`/admin/orders?id=${orderJump}`);
                onClose();
              }}
            />
          )}
          {(['Navigate', 'Quick actions'] as const).map((group) => {
            const items = filtered.filter((c) => c.group === group);
            if (items.length === 0) return null;
            return (
              <div key={group}>
                <div className="px-4 py-2 text-[10px] font-medium uppercase tracking-wider text-fg-dim">{group}</div>
                {items.map((c) => {
                  const idx = i++;
                  return (
                    <PaletteRow
                      key={c.id}
                      isActive={active === idx}
                      icon={c.icon}
                      label={c.label}
                      hint={c.hint}
                      onClick={() => {
                        navigate(c.to);
                        onClose();
                      }}
                    />
                  );
                })}
              </div>
            );
          })}
          {filtered.length === 0 && !orderJump && (
            <div className="p-6 text-center text-[13px] text-fg-subtle">No matches for "{q}"</div>
          )}
        </div>
      </div>
    </div>
  );
}

function PaletteRow({
  isActive,
  icon,
  label,
  hint,
  onClick,
}: {
  isActive: boolean;
  icon: IconName;
  label: string;
  hint?: string;
  onClick: () => void;
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      className={cn(
        'flex w-full items-center gap-3 px-4 py-2.5 text-left',
        isActive ? 'bg-bg-sunken' : 'hover:bg-bg-sunken',
      )}
    >
      <Icon name={icon} size={14} className="text-fg-muted" />
      <span className="flex-1 text-[13.5px]">{label}</span>
      {hint && <span className="font-mono text-[11px] text-fg-subtle">{hint}</span>}
    </button>
  );
}

// Hook: register the global ⌘K / Ctrl+K shortcut.
export function useCommandPalette(): { open: boolean; setOpen: (v: boolean) => void } {
  const [open, setOpen] = useState(false);
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === 'k') {
        e.preventDefault();
        setOpen(true);
      }
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, []);
  return { open, setOpen };
}
