import { type ReactNode } from 'react';
import { Link, Outlet } from 'react-router-dom';
import { Icon } from '../ui/Icon';
import { ToastProvider } from '../ui/Toast';
import {
  fmtCompact,
  fmtQty,
  fmtTickerSecondary,
  useRecentOrders,
  usePublicStats,
} from '../../hooks/usePublicData';

// =====================================================================
// AuthShell — split-screen layout for /login, /register, /verify-email,
// /forgot, /reset. Form on the left, dark marketing visual on the right.
// Visual variant defaults to 'router' (live order feed); auth pages can
// pass `visual="stats"` or `visual="check"` for the other two.
// =====================================================================

type Visual = 'router' | 'stats' | 'check';

interface AuthShellProps {
  visual?: Visual;
  children?: ReactNode;
}

export function AuthShell({ visual = 'router', children }: AuthShellProps) {
  return (
    <ToastProvider>
      <div className="grid min-h-screen grid-cols-1 lg:grid-cols-2">
        <div className="flex flex-col bg-bg">
          <div className="px-8 pt-8">
            <Link to="/" className="inline-flex items-center gap-[9px]">
              <img src="/logo-v2.png" alt="SMMWorld" className="h-[36px] w-[36px] object-contain" />
              <span className="text-[15px] font-semibold tracking-[-0.01em] text-fg">SMMWorld</span>
            </Link>
          </div>
          <div className="flex flex-1 items-center justify-center p-8">
            <div className="w-full max-w-[420px]">{children ?? <Outlet />}</div>
          </div>
        </div>
        <AuthVisual variant={visual} />
      </div>
    </ToastProvider>
  );
}

function AuthVisual({ variant }: { variant: Visual }) {
  return (
    <div className="relative hidden overflow-hidden bg-bg-deep text-white lg:flex">
      <div className="hero-bg" />
      <div className="grid-lines absolute inset-0 opacity-60" />
      <div className="relative z-10 flex flex-1 items-center justify-center p-12">
        <div className="w-full max-w-[420px]">
          {variant === 'router' && <AuthVisualRouter />}
          {variant === 'stats' && <AuthVisualStats />}
          {variant === 'check' && <AuthVisualCheck />}
        </div>
      </div>
    </div>
  );
}

// Live order feed — REAL recent orders sourced from /api/v1/stats/recent-orders.
// Sanitized server-side (no usernames, no URLs). The widget hides itself if
// the backend has no qualifying orders yet — no fake fallback.
function AuthVisualRouter() {
  const orders = useRecentOrders();
  return (
    <div>
      <div className="eyebrow text-white/60">Live network</div>
      <h2 className="mt-2 text-[32px] font-bold tracking-[-0.025em]">Real delivery, every order.</h2>
      <p className="mt-3 text-[14px] leading-relaxed text-white/70">
        SMMWorld is the network. We don't resell — every action is delivered by us directly with
        real quality.
      </p>
      {orders === null ? (
        // Initial fetch — keep layout stable with a same-size placeholder.
        <div className="mt-8 rounded-xl border border-white/10 bg-white/5 p-4" style={{ minHeight: 184 }} />
      ) : orders.length === 0 ? null : (
        <div className="mt-8 rounded-xl border border-white/10 bg-white/5 p-4 backdrop-blur">
          <div className="mb-3 flex items-center gap-2 font-mono text-[11px] uppercase tracking-wider text-white/60">
            <span className="pulse-dot inline-block h-[6px] w-[6px] rounded-full bg-emerald-400" />
            Last orders dispatched
          </div>
          <ul className="space-y-2 text-[13px]">
            {orders.slice(0, 4).map((o) => (
              <li key={o.id} className="flex items-center gap-3 font-mono">
                <span className="text-white">#{o.id}</span>
                <span className="flex-1 truncate text-white/70">
                  {fmtQty(o.quantity)} {o.service.toLowerCase()}
                </span>
                <span className="rounded border border-white/10 bg-white/5 px-[6px] py-[1px] text-[10.5px] text-white/80">
                  {o.status === 'in_progress' ? 'in progress' : o.status}
                </span>
                <span className="text-[11px] text-white/40">{fmtTickerSecondary(o.status, o.ageSeconds)}</span>
              </li>
            ))}
          </ul>
        </div>
      )}
    </div>
  );
}

// Real metrics from /api/v1/stats/public + a single hardcoded "47s avg start"
// kept by user request (the only metric that survives the audit). Other cards
// render once the API responds; show "—" while loading rather than fake data.
function AuthVisualStats() {
  const stats = usePublicStats();
  const cards: ReadonlyArray<readonly [string, string]> = [
    ['47s', 'avg start'],
    [fmtCompact(stats?.ordersFulfilled), 'orders delivered'],
    [fmtCompact(stats?.usersTotal), 'resellers'],
    [fmtCompact(stats?.serviceCount), 'services live'],
  ];
  return (
    <div>
      <div className="eyebrow text-white/60">Numbers</div>
      <h2 className="mt-2 text-[32px] font-bold tracking-[-0.025em]">Built for resellers running real money.</h2>
      <div className="mt-8 grid grid-cols-2 gap-3">
        {cards.map(([v, l]) => (
          <div key={l} className="rounded-xl border border-white/10 bg-white/5 p-4">
            <div className="font-mono text-[26px] font-bold tracking-[-0.02em]">{v}</div>
            <div className="mt-1 text-[12px] text-white/60">{l}</div>
          </div>
        ))}
      </div>
    </div>
  );
}

// Only items that are actually implemented in the codebase today.
// 2FA, IP allow-list, per-session signing, active-session view — all explicitly
// notImplemented in services/api.ts; advertising them on the verify-email page
// would be dishonest.
function AuthVisualCheck() {
  const items = [
    'Email verification before activation',
    'Refresh token in HttpOnly cookie',
    'Per-account API key, hash-only storage',
    'Password reset revokes all sessions',
    'Crypto-only payments — no card data stored',
  ];
  return (
    <div>
      <div className="eyebrow text-white/60">Security</div>
      <h2 className="mt-2 text-[32px] font-bold tracking-[-0.025em]">Designed like a vault.</h2>
      <ul className="mt-8 space-y-3">
        {items.map((s) => (
          <li key={s} className="flex items-center gap-3 rounded-lg border border-white/10 bg-white/5 px-4 py-3 text-[13.5px]">
            <span className="flex h-6 w-6 items-center justify-center rounded-full bg-emerald-500/20 text-emerald-300">
              <Icon name="check" size={13} />
            </span>
            {s}
          </li>
        ))}
      </ul>
    </div>
  );
}
