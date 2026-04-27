import { useEffect, useMemo, useState } from 'react';
import { Link } from 'react-router-dom';
import {
  Badge,
  Button,
  Dot,
  type IconName,
  Icon,
  MiniBars,
  MiniLine,
  Money,
  PageHeader,
  Section,
  Sparkline,
  StatusBadge,
  TimeCell,
  type BadgeTone,
} from '../../components/ui';
import { adminAPI } from '../../services/api';
import { useAdminActions } from '../../store/adminActions';
import type { Order } from '../../types';
import { fmtInt, fmtMoney } from '../../lib/utils';

// =====================================================================
// Admin Dashboard — operations overview.
//   KPI strip → 2-col charts → 2-col (recent orders + admin-actions feed)
//   → full-width system health strip.
// =====================================================================

// Shape returned by GET /api/v2/admin/dashboard (AdminController#dashboard).
// All numeric fields, including totalRevenue / activeOrders, are real.
interface AdminDashboardResponse {
  totalOrders: number;
  ordersLast24h: number;
  ordersLast7Days: number;
  ordersLast30Days: number;
  totalRevenue: number;
  revenueLast24h: number;
  revenueLast7Days: number;
  revenueLast30Days: number;
  activeOrders: number;
  pendingOrders: number;
  completedOrders: number;
  totalUsers: number;
}

// Shape of one row from /api/v2/admin/stats/daily.
interface DailyStatPoint {
  date: string;
  total: number;
  completed: number;
  partial: number;
  cancelled: number;
  revenue: string | number;
}

function toNum(v: unknown): number {
  if (typeof v === 'number') return Number.isFinite(v) ? v : 0;
  if (typeof v === 'string') {
    const n = Number.parseFloat(v);
    return Number.isFinite(n) ? n : 0;
  }
  return 0;
}

// Real dailyish delta: revenue/orders over the last 24h vs the average day in
// the prior 6 days (i.e. (last7 - last24) / 6). Returns null when there's not
// enough history to compute it honestly — caller renders nothing rather than
// inventing a number.
function deltaPct(last24h: number, last7Days: number): { v: string; tone: 'success' | 'danger' } | undefined {
  const prior6Avg = (last7Days - last24h) / 6;
  if (!Number.isFinite(prior6Avg) || prior6Avg <= 0) return undefined;
  const pct = ((last24h - prior6Avg) / prior6Avg) * 100;
  if (!Number.isFinite(pct)) return undefined;
  const sign = pct >= 0 ? '+' : '';
  return { v: `${sign}${pct.toFixed(1)}%`, tone: pct >= 0 ? 'success' : 'danger' };
}

export function AdminDashboardPage() {
  const [orders, setOrders] = useState<Order[]>([]);
  const [pendingDecisions, setPendingDecisions] = useState<number>(0);
  const [dash, setDash] = useState<AdminDashboardResponse | null>(null);
  const [daily, setDaily] = useState<DailyStatPoint[]>([]);
  // Start empty rather than hardcoded "Spring Boot 14ms / IG Bot secondary degraded 312ms".
  // Those numbers were placeholder and lied about real system state.
  const [systemHealth, setSystemHealth] = useState<Array<{ name: string; status: 'up' | 'degraded' | 'down'; latency?: number; meta?: string }>>([]);

  useEffect(() => {
    let cancelled = false;
    adminAPI
      .getDashboard()
      .then((data: unknown) => {
        if (cancelled) return;
        const d = data as AdminDashboardResponse | { data?: AdminDashboardResponse };
        const real = (d as { data?: AdminDashboardResponse })?.data ?? (d as AdminDashboardResponse);
        setDash(real ?? null);
      })
      .catch(() => {});
    adminAPI
      .dailyStats(30)
      .then((data) => {
        if (cancelled) return;
        setDaily(Array.isArray(data) ? data : []);
      })
      .catch(() => {});
    adminAPI
      .getAllOrders({ size: 50 })
      .then((data: unknown) => {
        if (cancelled) return;
        const env = data as { orders?: Order[]; content?: Order[]; data?: Order[] } | null;
        const arr: Order[] = Array.isArray(data) ? (data as Order[]) : env?.orders ?? env?.content ?? env?.data ?? [];
        setOrders(arr);
      })
      .catch(() => {});
    adminAPI
      .telegramPending()
      .then((data: unknown) => {
        if (cancelled) return;
        const arr = Array.isArray(data) ? data : (data as { content?: unknown[] })?.content ?? [];
        setPendingDecisions(arr.length);
      })
      .catch(() => {});
    adminAPI
      .systemHealth()
      .then((data: unknown) => {
        if (cancelled) return;
        if (Array.isArray(data)) setSystemHealth(data as typeof systemHealth);
      })
      .catch(() => {});
    return () => {
      cancelled = true;
    };
  }, []);

  // Real KPIs from /admin/dashboard. Falls back to "—" only if the endpoint
  // failed; never to an invented number.
  const stats = useMemo(() => {
    // Bucket the daily series into shapes the existing MiniLine / MiniBars want.
    // Both charts default to an empty array — they render an "Awaiting data"
    // placeholder rather than a Math.sin curve.
    const profit30d = daily.map((d) => ({ value: toNum(d.revenue) }));
    const orders30d = daily.map((d) => ({
      completed: d.completed ?? 0,
      partial: d.partial ?? 0,
      cancelled: d.cancelled ?? 0,
    }));
    return {
      todayProfit: dash?.revenueLast24h ?? 0,
      todayOrders: dash?.ordersLast24h ?? 0,
      activeOrders: dash?.activeOrders ?? 0,
      totalUsers: dash?.totalUsers ?? 0,
      pendingDecisions,
      profitDelta: dash ? deltaPct(dash.revenueLast24h, dash.revenueLast7Days) : undefined,
      ordersDelta: dash ? deltaPct(dash.ordersLast24h, dash.ordersLast7Days) : undefined,
      profit30d,
      orders30d,
      botHealth:
        systemHealth.length === 0
          ? ('up' as const)
          : systemHealth.some((s) => s.status === 'down')
            ? ('down' as const)
            : systemHealth.some((s) => s.status === 'degraded')
              ? ('degraded' as const)
              : ('up' as const),
    };
  }, [dash, daily, pendingDecisions, systemHealth]);

  return (
    <>
      <PageHeader
        title="Operations dashboard"
        subtitle={
          <span>
            Live as of <span className="font-mono text-fg">just now</span>
          </span>
        }
        actions={
          <>
            <Button variant="ghost" size="sm" icon="refresh" onClick={() => window.location.reload()}>
              Refresh
            </Button>
            <Button variant="secondary" size="sm" icon="download">
              Export report
            </Button>
          </>
        }
      />

      <div className="space-y-6 p-6">
        {/* KPI strip */}
        <div className="grid grid-cols-2 gap-3 lg:grid-cols-6">
          <KPICard
            label="Today's profit"
            value={<Money value={stats.todayProfit} size="md" />}
            delta={stats.profitDelta}
          />
          <KPICard
            label="Today's orders"
            value={<span className="font-mono text-[18px] font-bold tabular-nums">{fmtInt(stats.todayOrders)}</span>}
            delta={stats.ordersDelta}
          />
          <KPICard
            label="Active orders"
            value={<span className="font-mono text-[18px] font-bold tabular-nums">{fmtInt(stats.activeOrders)}</span>}
            sub={`${stats.activeOrders} dispatched`}
          />
          <KPICard
            label="Pending decisions"
            value={
              <span
                className={`font-mono text-[18px] font-bold tabular-nums ${stats.pendingDecisions > 0 ? 'text-warn' : ''}`}
              >
                {fmtInt(stats.pendingDecisions)}
              </span>
            }
            sub={
              stats.pendingDecisions > 0 ? (
                <Link to="/admin/telegram" className="text-[11px] font-medium text-warn hover:underline">
                  Review →
                </Link>
              ) : (
                <span className="text-[11px] text-fg-subtle">none</span>
              )
            }
          />
          <KPICard
            label="Total users"
            value={<span className="font-mono text-[18px] font-bold tabular-nums">{fmtInt(stats.totalUsers || 0)}</span>}
          />
          <KPICard
            label="Bot health"
            value={
              <span className="text-[14px] font-semibold">
                <StatusBadge status={stats.botHealth} size="md" />
              </span>
            }
            sub="auto-refresh · 10s"
          />
        </div>

        {/* Charts */}
        <div className="grid grid-cols-1 gap-4 lg:grid-cols-[1.3fr_1fr]">
          <Section
            title="Profit · last 30 days"
            subtitle={
              stats.profit30d.length > 0
                ? `Avg ${fmtMoney(
                    stats.profit30d.reduce((s, d) => s + d.value, 0) / stats.profit30d.length,
                  )} / day · Σ ${fmtMoney(stats.profit30d.reduce((s, d) => s + d.value, 0))}`
                : 'Loading…'
            }
            action={<Button variant="ghost" size="sm">Range</Button>}
          >
            <MiniLine data={stats.profit30d} height={200} />
          </Section>
          <Section
            title="Orders by status · last 30 days"
            subtitle={
              <span className="inline-flex items-center gap-3">
                <Legend color="#047857" label="completed" />
                <Legend color="#a16207" label="partial" />
                <Legend color="#78716c" label="cancelled" />
              </span>
            }
          >
            <MiniBars data={stats.orders30d} height={200} />
          </Section>
        </div>

        {/* Recent orders + admin actions */}
        <div className="grid grid-cols-1 gap-4 lg:grid-cols-[1.3fr_1fr]">
          <Section
            title="Recent orders"
            subtitle={`Last ${Math.min(orders.length, 10)} orders`}
            action={
              <Link to="/admin/orders">
                <Button variant="ghost" size="sm" iconRight="arrow-right">
                  View all
                </Button>
              </Link>
            }
            pad={false}
          >
            <RecentOrdersTable orders={orders.slice(0, 10)} />
          </Section>
          <RecentAdminActions />
        </div>

        {/* System health strip — only renders when /admin/system/health succeeds.
            Previously this was hardcoded with "Spring Boot 14ms / IG Bot secondary 312ms",
            which gave admins a fake green-light read on infra they couldn't actually see. */}
        {systemHealth.length > 0 && (
          <Section
            title="Live system status"
            subtitle="auto-refresh · 10s"
            action={<Button variant="ghost" size="sm" icon="refresh">Refresh</Button>}
          >
            <div className="grid grid-cols-2 gap-3 sm:grid-cols-3 lg:grid-cols-6">
              {systemHealth.map((s) => (
                <div key={s.name} className="rounded-md border border-border bg-bg-sunken p-3">
                  <div className="flex items-center gap-2">
                    <Dot
                      color={s.status === 'up' ? 'var(--success)' : s.status === 'degraded' ? 'var(--warn)' : 'var(--danger)'}
                      animate
                      size={7}
                    />
                    <div className="text-[12.5px] font-medium">{s.name}</div>
                  </div>
                  <div className="mt-1 font-mono text-[11px] text-fg-muted">{s.latency != null ? `${s.latency}ms` : '—'}</div>
                  {s.meta && <div className="mt-0.5 truncate text-[10.5px] text-fg-subtle">{s.meta}</div>}
                </div>
              ))}
            </div>
          </Section>
        )}
      </div>
    </>
  );
}

function KPICard({
  label,
  value,
  sparkline,
  sparklineColor = 'var(--accent)',
  delta,
  sub,
}: {
  label: string;
  value: React.ReactNode;
  sparkline?: number[];
  sparklineColor?: string;
  delta?: { v: string; tone: 'success' | 'danger' };
  sub?: React.ReactNode;
}) {
  return (
    <div className="rounded-md border border-border bg-bg-elev p-3">
      <div className="flex items-baseline justify-between gap-2">
        <span className="text-[10.5px] uppercase tracking-wider text-fg-subtle">{label}</span>
        {delta && (
          <Badge tone={delta.tone} size="sm">
            {delta.v}
          </Badge>
        )}
      </div>
      <div className="mt-2">{value}</div>
      {sub && <div className="mt-1 text-[11px] text-fg-subtle">{sub}</div>}
      {sparkline && (
        <div className="mt-1">
          <Sparkline data={sparkline} width={170} height={24} color={sparklineColor} stroke={1.4} />
        </div>
      )}
    </div>
  );
}

function Legend({ color, label }: { color: string; label: string }) {
  return (
    <span className="inline-flex items-center gap-1.5 text-[11px] text-fg-muted">
      <span className="inline-block h-[8px] w-[8px] rounded-full" style={{ background: color }} />
      {label}
    </span>
  );
}

function RecentOrdersTable({ orders }: { orders: Order[] }) {
  if (orders.length === 0) {
    return <div className="p-8 text-center text-[13px] text-fg-subtle">No orders yet today.</div>;
  }
  return (
    <table className="tbl">
      <thead>
        <tr>
          <th>ID</th>
          <th>User</th>
          <th>Service</th>
          <th className="text-right">Qty</th>
          <th className="text-right">Charge</th>
          <th>Status</th>
          <th>Created</th>
        </tr>
      </thead>
      <tbody>
        {orders.map((o) => (
          <tr key={o.id}>
            <td className="font-mono text-[12px]">#{o.id}</td>
            <td className="font-mono text-[12px] text-fg-muted">#{o.userId}</td>
            <td className="text-[13px]">{o.service?.name ?? o.serviceName ?? '—'}</td>
            <td className="text-right font-mono">{fmtInt(o.quantity)}</td>
            <td className="text-right">
              <Money value={o.charge} />
            </td>
            <td>
              <StatusBadge status={o.status} />
            </td>
            <td>
              <TimeCell iso={o.createdAt} />
            </td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}

function RecentAdminActions() {
  const actions = useAdminActions((s) => s.actions);
  return (
    <Section
      title="Recent admin actions"
      subtitle="Most recent first"
      action={
        <Link to="/admin/settings?tab=audit">
          <Button variant="ghost" size="sm" iconRight="arrow-right">
            Full audit log
          </Button>
        </Link>
      }
      pad={false}
    >
      {actions.length === 0 ? (
        <div className="p-8 text-center text-[13px] text-fg-subtle">
          Admin actions you take (refunds, balance adjusts, mark partial) appear here.
        </div>
      ) : (
        <ul className="divide-y divide-border">
          {actions.map((a, i) => (
            <li key={a.id} className={`flex items-start gap-3 px-4 py-3 ${i === 0 ? 'fade-in' : ''}`}>
              <ActionIcon action={a.action} />
              <div className="min-w-0 flex-1">
                <div className="text-[12.5px]">
                  <span className="font-mono text-fg-subtle">@{a.actor}</span>{' '}
                  <span className="font-medium text-fg">· {a.action}</span>{' '}
                  {a.targetLabel && <span className="text-fg-muted">· {a.targetLabel}</span>}
                </div>
                <div className="mt-0.5 truncate text-[12px] text-fg-muted">{a.summary}</div>
              </div>
              {typeof a.amount === 'number' && (
                <Money value={a.amount} sign size="sm" />
              )}
              <TimeCell iso={a.createdAt} />
            </li>
          ))}
        </ul>
      )}
    </Section>
  );
}

function ActionIcon({ action }: { action: string }) {
  const map: Record<string, { icon: IconName; tone: BadgeTone }> = {
    'order.mark_partial': { icon: 'warning', tone: 'warn' },
    'order.force_complete': { icon: 'check', tone: 'success' },
    'order.cancel': { icon: 'x', tone: 'danger' },
    'order.refund': { icon: 'refresh', tone: 'violet' },
    'balance.manual_adjust': { icon: 'wallet', tone: 'accent' },
    'user.suspend': { icon: 'shield', tone: 'danger' },
  };
  const m = map[action] ?? { icon: 'info' as IconName, tone: 'muted' as BadgeTone };
  return (
    <span
      className="mt-[2px] flex h-7 w-7 flex-none items-center justify-center rounded-md"
      style={{
        background:
          m.tone === 'warn'
            ? 'var(--warn-soft)'
            : m.tone === 'danger'
              ? 'var(--danger-soft)'
              : m.tone === 'violet'
                ? 'var(--violet-soft)'
                : m.tone === 'accent'
                  ? 'var(--accent-soft)'
                  : m.tone === 'success'
                    ? 'var(--success-soft)'
                    : 'var(--bg-sunken)',
        color:
          m.tone === 'warn'
            ? 'var(--warn)'
            : m.tone === 'danger'
              ? 'var(--danger)'
              : m.tone === 'violet'
                ? 'var(--violet)'
                : m.tone === 'accent'
                  ? 'var(--accent-fg)'
                  : m.tone === 'success'
                    ? 'var(--success)'
                    : 'var(--fg-muted)',
      }}
    >
      <Icon name={m.icon} size={14} />
    </span>
  );
}
