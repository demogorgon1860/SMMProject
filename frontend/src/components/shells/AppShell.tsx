import { useEffect, useRef, useState, type ReactNode } from 'react';
import { Link, NavLink, Outlet, useLocation, useNavigate } from 'react-router-dom';
import { Icon, type IconName } from '../ui/Icon';
import { ThemeToggle } from '../ui/ThemeToggle';
import { ToastProvider } from '../ui/Toast';
import { authAPI } from '../../services/api';
import { useAuthStore } from '../../store/authStore';
import { cn } from '../../lib/utils';

// =====================================================================
// AppShell — for authenticated user pages (/dashboard, /orders, etc.)
// Sidebar (fixed 220px) + slim topbar with balance chip and profile.
// Phase 0 stub: nav links route to placeholders. Real per-page wiring
// lands in Phase 1.
// =====================================================================

interface NavItem {
  to: string;
  label: string;
  icon: IconName;
}

const NAV: NavItem[] = [
  { to: '/dashboard', label: 'Dashboard', icon: 'dashboard' },
  { to: '/new-order', label: 'New order', icon: 'plus' },
  { to: '/orders', label: 'Orders', icon: 'list' },
  { to: '/transactions', label: 'Transactions', icon: 'receipt' },
  { to: '/add-funds', label: 'Add funds', icon: 'wallet' },
  { to: '/api-docs', label: 'API', icon: 'code' },
  { to: '/help', label: 'Help', icon: 'help' },
  { to: '/profile', label: 'Profile', icon: 'user' },
];

export function AppShell({ children }: { children?: ReactNode }) {
  return (
    <ToastProvider>
      <div className="flex min-h-screen bg-bg text-fg">
        <aside className="sticky top-0 flex h-screen w-[220px] flex-none flex-col border-r border-border bg-bg-elev">
          {/* Sidebar header height matches the topbar (52px) so the two horizontal
              dividers line up across the layout. The previous 56px gap was off by
              4px and rendered as a visible "step" along the top border. */}
          <div className="flex h-[52px] items-center gap-[9px] border-b border-border px-[14px]">
            <Link to="/dashboard" className="flex items-center gap-[9px]">
              <img src="/logo-v2.png" alt="SMMWorld" className="h-[32px] w-[32px] object-contain" />
              <span className="text-[14px] font-semibold tracking-[-0.01em]">SMMWorld</span>
            </Link>
          </div>
          <nav className="flex-1 overflow-auto p-2">
            {NAV.map((it) => (
              <AppNavLink key={it.to} item={it} />
            ))}
          </nav>
          <SidebarUserPill />
        </aside>
        <div className="flex min-w-0 flex-1 flex-col">
          <AppTopBar />
          <main className="min-w-0 flex-1">{children ?? <Outlet />}</main>
        </div>
      </div>
    </ToastProvider>
  );
}

/**
 * Bottom-of-sidebar user pill. Reads from the auth store rather than rendering
 * static "YO / @you / you@example.com" placeholders — those were visible on
 * production and gave away that the widget wasn't actually wired up.
 */
function SidebarUserPill() {
  const user = useAuthStore((s) => s.user);
  const username = user?.username ?? '';
  const email = user?.email ?? '';
  const initials =
    username
      .split(/[._-]/)
      .map((p) => p[0])
      .filter(Boolean)
      .slice(0, 2)
      .join('')
      .toUpperCase() || 'U';
  return (
    <div className="border-t border-border p-[10px]">
      <Link
        to="/profile?tab=account"
        className="flex items-center gap-[10px] rounded-md p-[6px] hover:bg-bg-sunken"
      >
        <span className="flex h-[28px] w-[28px] items-center justify-center rounded-full bg-gradient-to-br from-[#4f46e5] to-[#7c3aed] text-[11px] font-semibold text-white">
          {initials}
        </span>
        <div className="min-w-0 flex-1 leading-tight">
          <div className="truncate text-[13px] font-medium">
            {username ? '@' + username : '—'}
          </div>
          <div className="truncate text-[11px] text-fg-subtle">{email || ''}</div>
        </div>
        <Icon name="chevron-right" size={12} className="text-fg-dim" />
      </Link>
    </div>
  );
}

function AppNavLink({ item }: { item: NavItem }) {
  const location = useLocation();
  const active = location.pathname === item.to || location.pathname.startsWith(item.to + '/');
  return (
    <NavLink
      to={item.to}
      className={cn(
        'relative my-[1px] flex items-center gap-[10px] rounded-md px-[10px] py-[8px] text-[13.5px]',
        active ? 'bg-bg-sunken font-semibold text-fg' : 'font-medium text-fg-muted hover:bg-bg-sunken hover:text-fg',
        'focus-visible:outline-none focus-visible:ring-2 focus-visible:[--tw-ring-color:var(--ring)]',
      )}
    >
      {active && <span className="absolute left-0 top-[8px] bottom-[8px] w-[2px] rounded bg-accent" />}
      <Icon name={item.icon} size={15} />
      <span className="flex-1 text-left">{item.label}</span>
    </NavLink>
  );
}

function AppTopBar() {
  // Backend serializes BigDecimal as a string for precision, so user.balance
  // can arrive as either a number (legacy) or a string. Coerce here so the
  // topbar chip shows the same value as the Dashboard wallet card.
  const rawBalance = useAuthStore((s) => s.user?.balance);
  const balance =
    typeof rawBalance === 'number'
      ? Number.isFinite(rawBalance)
        ? rawBalance
        : 0
      : typeof rawBalance === 'string'
        ? Number.isFinite(Number.parseFloat(rawBalance))
          ? Number.parseFloat(rawBalance)
          : 0
        : 0;
  return (
    <header className="sticky top-0 z-20 flex h-[52px] flex-none items-center gap-3 border-b border-border bg-bg-elev px-5">
      <button
        type="button"
        className="flex max-w-[380px] flex-1 cursor-pointer items-center gap-2 rounded-md border border-border bg-bg-sunken px-[11px] py-[7px] text-[13px] text-fg-subtle hover:bg-bg-elev hover:text-fg transition-colors"
      >
        <Icon name="search" size={13} />
        <span className="flex-1 text-left">Search orders, services…</span>
      </button>
      <div className="flex-1" />
      <Link
        to="/add-funds"
        className="inline-flex items-center gap-[6px] rounded-md border border-border bg-bg-elev px-[10px] py-[6px] text-[12.5px] font-medium hover:bg-bg-sunken"
      >
        <Icon name="wallet" size={13} className="text-fg-subtle" />
        <span className="font-mono tabular-nums">${balance.toFixed(2)}</span>
        <Icon name="plus" size={12} className="text-accent" />
      </Link>
      <ThemeToggle />
      <button
        type="button"
        aria-label="Notifications"
        className="relative inline-flex h-[30px] w-[30px] items-center justify-center rounded-md border border-border text-fg-muted hover:bg-bg-sunken focus-visible:outline-none focus-visible:ring-2 focus-visible:[--tw-ring-color:var(--ring)]"
      >
        <Icon name="bell" size={14} />
      </button>
      <UserMenu />
    </header>
  );
}

/**
 * Avatar dropdown with sign-out for the user-side shell. Mirrors the AdminShell version —
 * kept inline rather than extracted because the two shells are otherwise self-contained and
 * a shared helper would create cross-shell coupling.
 */
function UserMenu() {
  const user = useAuthStore((s) => s.user);
  const logout = useAuthStore((s) => s.logout);
  const navigate = useNavigate();
  const [open, setOpen] = useState(false);
  const ref = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!open) return;
    const onClickOutside = (e: MouseEvent) => {
      if (ref.current && !ref.current.contains(e.target as Node)) setOpen(false);
    };
    const onEscape = (e: KeyboardEvent) => {
      if (e.key === 'Escape') setOpen(false);
    };
    document.addEventListener('mousedown', onClickOutside);
    document.addEventListener('keydown', onEscape);
    return () => {
      document.removeEventListener('mousedown', onClickOutside);
      document.removeEventListener('keydown', onEscape);
    };
  }, [open]);

  const handleLogout = async () => {
    setOpen(false);
    try {
      await authAPI.logout();
    } catch {
      /* still clear local state on network failure */
    }
    logout();
    navigate('/login', { replace: true });
  };

  const username = user?.username ?? '';
  const initials = username
    .split(/[._-]/)
    .map((p) => p[0])
    .filter(Boolean)
    .slice(0, 2)
    .join('')
    .toUpperCase();

  return (
    <div className="relative" ref={ref}>
      <button
        type="button"
        onClick={() => setOpen((v) => !v)}
        className="inline-flex items-center gap-2 rounded-full border border-border bg-bg-elev py-1 pl-1 pr-3 text-left transition-colors hover:bg-bg-sunken focus-visible:outline-none focus-visible:ring-2 focus-visible:[--tw-ring-color:var(--ring)]"
      >
        <span className="flex h-[26px] w-[26px] items-center justify-center rounded-full bg-gradient-to-br from-[#4f46e5] to-[#7c3aed] text-[11px] font-semibold text-white">
          {initials || 'U'}
        </span>
        <Icon name="chevron-down" size={11} className="text-fg-dim" />
      </button>
      {open && (
        <div className="fade-in absolute right-0 top-[calc(100%+6px)] z-30 w-[210px] overflow-hidden rounded-md border border-border bg-bg-elev shadow-pop">
          <div className="border-b border-border px-3 py-2">
            <div className="flex items-center gap-2">
              <span className="text-[12px] font-medium text-fg">{username || '—'}</span>
              {user?.role === 'ADMIN' && (
                <span className="rounded border border-accent/30 bg-accent-soft px-[5px] py-[1px] font-mono text-[9.5px] font-semibold uppercase tracking-wider text-accent-fg">
                  admin
                </span>
              )}
              {user?.role === 'OPERATOR' && (
                <span className="rounded border border-warn/30 bg-warn-soft px-[5px] py-[1px] font-mono text-[9.5px] font-semibold uppercase tracking-wider text-warn">
                  operator
                </span>
              )}
            </div>
            <div className="text-[11px] text-fg-subtle">{user?.email ?? ''}</div>
          </div>
          {/* Quick switch to the admin panel — only rendered for ADMIN role so a regular
              user never sees a link to a route they'd just be 403'd out of. */}
          {user?.role === 'ADMIN' && (
            <Link
              to="/admin"
              onClick={() => setOpen(false)}
              className="flex items-center gap-2 border-b border-border px-3 py-2 text-[13px] text-accent hover:bg-bg-sunken"
            >
              <Icon name="shield" size={13} />
              Admin panel
            </Link>
          )}
          <Link
            to="/profile"
            onClick={() => setOpen(false)}
            className="flex items-center gap-2 px-3 py-2 text-[13px] text-fg-muted hover:bg-bg-sunken hover:text-fg"
          >
            <Icon name="user" size={13} />
            Profile
          </Link>
          <button
            type="button"
            onClick={handleLogout}
            className="flex w-full items-center gap-2 border-t border-border px-3 py-2 text-left text-[13px] text-danger hover:bg-danger-soft"
          >
            <Icon name="logout" size={13} />
            Sign out
          </button>
        </div>
      )}
    </div>
  );
}
