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

interface DashStats {
  todayProfit: number;
  todayOrders: number;
  todayOrdersDelta: number;
  activeOrders: number;
  pendingDecisions: number;
  totalUsers: number;
  totalUsersDelta: number;
  botHealth: 'up' | 'degraded' | 'down';
  profitTrend: number[];
  ordersTrend: number[];
  profit30d: Array<{ value: number }>;
  orders30d: Array<{ completed: number; partial: number; cancelled: number }>;
}

function fakeStats(orders: Order[]): DashStats {
  const todayProfit = orders.reduce((s, o) => s + (o.charge ?? 0) * 0.7, 0);
  const todayOrders = orders.length;
  const active = orders.filter((o) =>
    ['IN_PROGRESS', 'PROCESSING', 'PENDING', 'ACTIVE'].includes(o.status?.toUpperCase() ?? ''),
  ).length;
  const profitTrend = Array.from({ length: 12 }, (_, i) => Math.max(0, 200 + Math.sin(i / 1.5) * 120 + i * 15));
  const ordersTrend = Array.from({ length: 12 }, (_, i) => Math.max(0, 80 + Math.cos(i / 1.7) * 30 + i * 4));
  const profit30d = Array.from({ length: 30 }, (_, i) => ({ value: Math.max(0, 420 + Math.sin(i * 0.4) * 180 + (i - 15) * 4) }));
  const orders30d = Array.from({ length: 30 }, (_, i) => ({
    completed: Math.floor(60 + Math.sin(i * 0.3) * 30 + i * 0.8),
    partial: Math.floor(8 + Math.sin(i * 0.7) * 6),
    cancelled: Math.floor(3 + Math.cos(i * 0.5) * 2),
  }));
  return {
    todayProfit,
    todayOrders,
    todayOrdersDelta: +12.4,
    activeOrders: active,
    pendingDecisions: 0,
    totalUsers: 0,
    totalUsersDelta: 0,
    botHealth: 'degraded',
    profitTrend,
    ordersTrend,
    profit30d,
    orders30d,
  };
}

export function AdminDashboardPage() {
  const [orders, setOrders] = useState<Order[]>([]);
  const [pendingDecisions, setPendingDecisions] = useState<number>(0);
  const [systemHealth, setSystemHealth] = useState<Array<{ name: string; status: 'up' | 'degraded' | 'down'; latency?: number; meta?: string }>>([
    { name: 'Spring Boot', status: 'up', latency: 14, meta: 'v2.4.1 · 3h uptime' },
    { name: 'PostgreSQL', status: 'up', latency: 3, meta: '42 conn · 18.2 GB' },
    { name: 'Redis', status: 'up', latency: 1, meta: 'lettuce · 412 MB' },
    { name: 'RabbitMQ', status: 'up', latency: 6, meta: '12 queues · 0 DLQ' },
    { name: 'IG Bot · primary', status: 'up', latency: 41, meta: '6 workers' },
    { name: 'IG Bot · secondary', status: 'degraded', latency: 312, meta: 'circuit half-open' },
  ]);

  useEffect(() => {
    let cancelled = false;
    adminAPI
      .getAllOrders({ size: 50 })
      .then((data: unknown) => {
        if (cancelled) return;
        const arr: Order[] = Array.isArray(data) ? (data as Order[]) : (data as { content?: Order[] })?.content ?? [];
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

  const stats = useMemo(() => ({ ...fakeStats(orders), pendingDecisions }), [orders, pendingDecisions]);

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
            sparkline={stats.profitTrend}
            delta={{ v: '+12.4%', tone: 'success' }}
          />
          <KPICard
            label="Today's orders"
            value={<span className="font-mono text-[18px] font-bold tabular-nums">{fmtInt(stats.todayOrders)}</span>}
            sparkline={stats.ordersTrend}
            sparklineColor="#0369a1"
            delta={{ v: '+12.4%', tone: 'success' }}
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
            delta={stats.totalUsersDelta > 0 ? { v: '+' + stats.totalUsersDelta, tone: 'success' } : undefined}
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
            subtitle="Avg $782 / day · Σ $23.4k"
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

        {/* System health strip */}
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
