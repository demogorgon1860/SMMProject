import { useState, type ReactNode } from 'react';
import { Link, NavLink, Outlet, useLocation } from 'react-router-dom';
import { Icon, type IconName } from '../ui/Icon';
import { ThemeToggle, AccentPicker } from '../ui/ThemeToggle';
import { ToastProvider } from '../ui/Toast';
import { Dot } from '../ui/Badge';
import { CommandPalette, useCommandPalette } from '../CommandPalette';
import { cn } from '../../lib/utils';

// =====================================================================
// AdminShell — sidebar + topbar layout for /admin/* routes.
// Sidebar groups: Operations, Customers, Money, Infrastructure, Settings.
// Topbar: search, env badge, theme toggle, notifications, profile menu.
// Each page renders inside <Outlet />.
// =====================================================================

interface NavItem {
  to: string;
  label: string;
  icon: IconName;
  badge?: number;
  badgeKind?: 'default' | 'warn';
}

interface NavGroup {
  section: string;
  items: NavItem[];
}

const NAV: NavGroup[] = [
  {
    section: 'Operations',
    items: [
      { to: '/admin', label: 'Dashboard', icon: 'dashboard' },
      { to: '/admin/orders', label: 'Orders', icon: 'orders' },
      { to: '/admin/refill-requests', label: 'Refill requests', icon: 'refresh' },
      { to: '/admin/services', label: 'Services', icon: 'grid' },
    ],
  },
  {
    section: 'Customers',
    items: [{ to: '/admin/users', label: 'Users', icon: 'users' }],
  },
  {
    section: 'Money',
    items: [
      { to: '/admin/payments', label: 'Payments', icon: 'card' },
      { to: '/admin/balance', label: 'Balance Txs', icon: 'wallet' },
    ],
  },
  {
    section: 'Infrastructure',
    items: [
      { to: '/admin/bot', label: 'Instagram Bot', icon: 'bot' },
      { to: '/admin/telegram', label: 'Telegram Control', icon: 'paper-plane' },
      { to: '/admin/system', label: 'System Monitoring', icon: 'activity' },
    ],
  },
  {
    section: 'Settings',
    items: [{ to: '/admin/settings', label: 'Settings', icon: 'settings' }],
  },
];

export function AdminShell({ children }: { children?: ReactNode }) {
  const [collapsed, setCollapsed] = useState(false);
  const palette = useCommandPalette();
  const w = collapsed ? 56 : 224;

  return (
    <ToastProvider>
      <CommandPalette open={palette.open} onClose={() => palette.setOpen(false)} />
      <div className="flex min-h-screen bg-bg text-fg">
        <aside
          className="sticky top-0 flex h-screen flex-none flex-col overflow-hidden border-r border-border bg-bg-elev transition-[width] duration-180"
          style={{ width: w }}
        >
          <div
            className={cn(
              'flex min-h-[56px] items-center gap-[10px] border-b border-border',
              collapsed ? 'justify-center px-0 py-[14px]' : 'justify-between px-[14px] py-[14px]',
            )}
          >
            <Link to="/admin" className="flex items-center gap-[9px]">
              <img src="/logo-v2.png" alt="SMMWorld" className="h-[32px] w-[32px] object-contain" />
              {!collapsed && (
                <div className="leading-tight">
                  <div className="text-[13px] font-semibold tracking-[-0.01em]">SMMWorld</div>
                  <div className="text-[10px] text-fg-subtle">admin · v2.4.1</div>
                </div>
              )}
            </Link>
            {!collapsed && (
              <button
                type="button"
                onClick={() => setCollapsed(true)}
                title="Collapse"
                className="rounded p-1 text-fg-subtle hover:bg-bg-sunken focus-visible:outline-none focus-visible:ring-2 focus-visible:[--tw-ring-color:var(--ring)]"
              >
                <Icon name="chevron-left" size={14} />
              </button>
            )}
          </div>

          <nav className={cn('flex-1 overflow-auto', collapsed ? 'p-[8px_4px]' : 'p-[8px_8px]')}>
            {NAV.map((group, gi) => (
              <div key={group.section} className={cn(gi === 0 ? 'mt-1' : 'mt-[14px]')}>
                {!collapsed && (
                  <div className="px-2 pb-1 pt-1 text-[10px] font-medium uppercase tracking-[0.08em] text-fg-dim">
                    {group.section}
                  </div>
                )}
                {group.items.map((it) => (
                  <SidebarLink key={it.to} item={it} collapsed={collapsed} />
                ))}
              </div>
            ))}
          </nav>

          {collapsed ? (
            <button
              type="button"
              onClick={() => setCollapsed(false)}
              className="flex justify-center border-t border-border p-[10px] text-fg-subtle hover:bg-bg-sunken focus-visible:outline-none focus-visible:ring-2 focus-visible:[--tw-ring-color:var(--ring)]"
            >
              <Icon name="chevron-right" size={14} />
            </button>
          ) : (
            <div className="border-t border-border p-[10px]">
              <div className="mb-1 text-[11px] text-fg-subtle">System health</div>
              <div className="flex items-center gap-[6px]">
                <Dot color="#a16207" size={7} animate />
                <span className="text-[12px] font-medium text-warn">Degraded</span>
                <span className="ml-auto font-mono text-[11px] text-fg-dim">bot-02</span>
              </div>
            </div>
          )}
        </aside>

        <div className="flex min-w-0 flex-1 flex-col">
          <AdminTopBar onOpenPalette={() => palette.setOpen(true)} />
          <main className="min-w-0 flex-1">{children ?? <Outlet />}</main>
        </div>
      </div>
    </ToastProvider>
  );
}

function SidebarLink({ item, collapsed }: { item: NavItem; collapsed: boolean }) {
  const location = useLocation();
  const isExact = item.to === '/admin';
  const active = isExact ? location.pathname === '/admin' : location.pathname.startsWith(item.to);
  const badgeBg = item.badgeKind === 'warn' ? 'bg-warn-soft' : 'bg-bg-sunken';
  const badgeFg = item.badgeKind === 'warn' ? 'text-warn' : 'text-fg-muted';
  return (
    <NavLink
      to={item.to}
      end={isExact}
      title={collapsed ? item.label : undefined}
      className={cn(
        'relative my-[1px] flex items-center gap-[10px] rounded-md text-[13px]',
        collapsed ? 'justify-center p-[8px]' : 'justify-start px-[10px] py-[7px]',
        active ? 'bg-bg-sunken font-semibold text-fg' : 'font-medium text-fg-muted hover:text-fg hover:bg-bg-sunken',
        'focus-visible:outline-none focus-visible:ring-2 focus-visible:[--tw-ring-color:var(--ring)]',
      )}
    >
      {active && !collapsed && (
        <span className="absolute left-0 top-[8px] bottom-[8px] w-[2px] rounded bg-accent" />
      )}
      <Icon name={item.icon} size={15} />
      {!collapsed && <span className="flex-1 text-left">{item.label}</span>}
      {!collapsed && item.badge != null && (
        <span className={cn('rounded-[10px] px-[6px] py-[1px] font-mono text-[10px] font-semibold', badgeBg, badgeFg)}>
          {item.badge}
        </span>
      )}
    </NavLink>
  );
}

function AdminTopBar({ onOpenPalette }: { onOpenPalette: () => void }) {
  return (
    <header className="sticky top-0 z-20 flex h-[52px] flex-none items-center gap-3 border-b border-border bg-bg-elev px-4">
      <button
        type="button"
        onClick={onOpenPalette}
        className="flex flex-1 max-w-[440px] cursor-pointer items-center gap-2 rounded-md border border-border bg-bg-sunken px-[11px] py-[7px] text-[13px] text-fg-subtle hover:bg-bg-elev hover:text-fg transition-colors"
      >
        <Icon name="search" size={13} />
        <span className="flex-1 text-left">Search orders, users, services…</span>
        <span className="rounded border border-border bg-bg-elev px-[5px] py-[1px] font-mono text-[10px] text-fg-muted">
          ⌘K
        </span>
      </button>
      <div className="flex-1" />
      <span className="inline-flex items-center gap-[6px] rounded-md bg-danger-soft px-[9px] py-1 text-[11px] font-semibold tracking-wider text-danger">
        <Dot color="var(--danger)" size={6} animate />
        PROD
      </span>
      <ThemeToggle />
      <button
        type="button"
        aria-label="Notifications"
        className="relative inline-flex h-[30px] w-[30px] items-center justify-center rounded-md border border-border text-fg-muted hover:bg-bg-sunken focus-visible:outline-none focus-visible:ring-2 focus-visible:[--tw-ring-color:var(--ring)]"
      >
        <Icon name="bell" size={14} />
        <span className="absolute right-[5px] top-[5px] h-[7px] w-[7px] rounded-full border-[1.5px] border-bg-elev bg-danger" />
      </button>
      <button
        type="button"
        className="inline-flex items-center gap-2 rounded-full border border-border bg-bg-elev py-1 pl-1 pr-3 text-left transition-colors hover:bg-bg-sunken focus-visible:outline-none focus-visible:ring-2 focus-visible:[--tw-ring-color:var(--ring)]"
      >
        <span className="flex h-[26px] w-[26px] items-center justify-center rounded-full bg-gradient-to-br from-[#4f46e5] to-[#7c3aed] text-[11px] font-semibold text-white">
          EP
        </span>
        <span className="leading-tight">
          <span className="block text-[12px] font-medium text-fg">elena.p</span>
          <span className="block text-[10px] text-fg-subtle">operator</span>
        </span>
        <Icon name="chevron-down" size={11} className="text-fg-dim" />
      </button>
    </header>
  );
}

// Tweaks panel exported for later use (e.g. on landing/profile)
export function AccentTweaks() {
  return (
    <div className="flex items-center gap-3">
      <ThemeToggle />
      <AccentPicker />
    </div>
  );
}
