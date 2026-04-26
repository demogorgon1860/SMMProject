import { type ReactNode } from 'react';
import { Link, NavLink, Outlet, useLocation } from 'react-router-dom';
import { Icon, type IconName } from '../ui/Icon';
import { ThemeToggle } from '../ui/ThemeToggle';
import { ToastProvider } from '../ui/Toast';
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
          <div className="flex h-[56px] items-center gap-[9px] border-b border-border px-[14px]">
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
          <div className="border-t border-border p-[10px]">
            <Link
              to="/profile?tab=account"
              className="flex items-center gap-[10px] rounded-md p-[6px] hover:bg-bg-sunken"
            >
              <span className="flex h-[28px] w-[28px] items-center justify-center rounded-full bg-gradient-to-br from-[#4f46e5] to-[#7c3aed] text-[11px] font-semibold text-white">
                YO
              </span>
              <div className="min-w-0 flex-1 leading-tight">
                <div className="truncate text-[13px] font-medium">@you</div>
                <div className="truncate text-[11px] text-fg-subtle">you@example.com</div>
              </div>
              <Icon name="chevron-right" size={12} className="text-fg-dim" />
            </Link>
          </div>
        </aside>
        <div className="flex min-w-0 flex-1 flex-col">
          <AppTopBar />
          <main className="min-w-0 flex-1">{children ?? <Outlet />}</main>
        </div>
      </div>
    </ToastProvider>
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
        <span className="font-mono tabular-nums">$0.00</span>
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
    </header>
  );
}
