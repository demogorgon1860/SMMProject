import { useEffect, useMemo, useState } from 'react';
import { Link } from 'react-router-dom';
import {
  Badge,
  Button,
  Card,
  Donut,
  Empty,
  Icon,
  type IconName,
  Money,
  Sparkline,
  StatusBadge,
} from '../../components/ui';
import { balanceAPI, orderAPI, profileAPI } from '../../services/api';
import { useAuthStore } from '../../store/authStore';
import type { Order, BalanceSummary } from '../../types';
import { fmtInt, fmtRel } from '../../lib/utils';

interface DailyStatPoint {
  date: string;
  total: number;
  completed: number;
  partial: number;
  cancelled: number;
  revenue: string | number;
}

// =====================================================================
// Dashboard — main authed landing.
// Layout: 1fr | 340px
//   left  : hero + KPI row + recent orders
//   right : wallet card, announcements, roadmap, help
// =====================================================================

export function DashboardPage() {
  const user = useAuthStore((s) => s.user);
  const updateBalance = useAuthStore((s) => s.updateBalance);

  const [balance, setBalance] = useState<BalanceSummary | null>(null);
  const [orders, setOrders] = useState<Order[]>([]);
  const [daily, setDaily] = useState<DailyStatPoint[]>([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    let cancelled = false;
    Promise.allSettled([
      balanceAPI.get(),
      orderAPI.list({ size: 10 }),
      profileAPI.dailyStats(30),
    ]).then(([b, o, d]) => {
      if (cancelled) return;
      if (b.status === 'fulfilled') {
        const bs = b.value as BalanceSummary;
        setBalance(bs);
        // Backend serializes BigDecimal as string; coerce so the auth store always holds a Number.
        const n = toNum(bs.balance);
        if (n > 0 || bs.balance != null) updateBalance(n);
      }
      if (o.status === 'fulfilled') {
        const v = o.value as { content?: Order[] } | Order[] | { data?: Order[] };
        setOrders(
          Array.isArray(v)
            ? v
            : Array.isArray((v as { content?: Order[] }).content)
              ? (v as { content: Order[] }).content
              : Array.isArray((v as { data?: Order[] }).data)
                ? (v as { data: Order[] }).data
                : [],
        );
      }
      if (d.status === 'fulfilled' && Array.isArray(d.value)) {
        setDaily(d.value as DailyStatPoint[]);
      }
      setLoading(false);
    });
    return () => {
      cancelled = true;
    };
  }, [updateBalance]);

  const stats = useMemo(() => computeStats(orders, balance, daily), [orders, balance, daily]);

  // The backend serializes BigDecimal balances as JSON strings (e.g. "252.18") to preserve
  // precision. Coerce to a real number once, here, so all downstream <Hero> / <WalletCard>
  // calls to .toFixed() / arithmetic see a Number and not a String. toNum returns 0 for any
  // non-finite input — never NaN propagating through the dashboard.
  const balanceNum = toNum(balance?.balance ?? user?.balance);

  return (
    <div className="container-app space-y-6 py-8">
      {/* Hero + Wallet sit side-by-side at the top so the right column doesn't end
          mid-page leaving a hanging gap (which is what happened after the
          Announcements + Roadmap cards came out). On narrow viewports they stack. */}
      <div className="grid grid-cols-1 gap-6 lg:grid-cols-[1fr_340px]">
        <Hero username={user?.username ?? 'there'} balance={balanceNum} stats={stats} />
        <WalletCard balance={balanceNum} stats={stats} />
      </div>

      {/* KPI strip + Recent orders + Help all run full width below — every
          section closes cleanly at the right edge of the layout, no orphans. */}
      <KPIRow stats={stats} />
      <RecentOrders orders={orders} loading={loading} />
      <HelpCard />
    </div>
  );
}

interface DashStats {
  active: number;
  total30d: number;
  completed30d: number;
  partial30d: number;
  cancelled30d: number;
  spent30d: number;
  successPct: number;
  /** Daily order count (last N days) for the order-volume sparkline. */
  trend: number[];
  /** Daily spend (last N days) for the spend sparkline. */
  spendTrend: number[];
  /** Real growth: spend in last 7 days vs prior 7 days, as a percentage. Null if not computable. */
  spendDelta: { sign: '+' | '-'; v: string; tone: 'success' | 'danger' } | null;
  /** Same shape, for order count over the last 7d vs prior 7d. */
  ordersDelta: { sign: '+' | '-'; v: string; tone: 'success' | 'danger' } | null;
}

/**
 * Coerce a maybe-string-maybe-number-maybe-undefined money value into a real Number.
 * The backend serializes BigDecimal as a JSON string ("252.18") to preserve precision; the
 * frontend math (toFixed, comparisons, arithmetic) needs a real Number. Returns 0 for null /
 * undefined / NaN / non-finite input — never propagates NaN downstream.
 */
function toNum(v: unknown): number {
  if (typeof v === 'number') return Number.isFinite(v) ? v : 0;
  if (typeof v === 'string') {
    const n = Number.parseFloat(v);
    return Number.isFinite(n) ? n : 0;
  }
  return 0;
}

function computeStats(
  orders: Order[],
  balance: BalanceSummary | null,
  daily: DailyStatPoint[],
): DashStats {
  // `orders` here is just the recent-orders preview (size: 10). The 30-day rollups come from
  // `daily` which the backend computed via SQL GROUP BY DATE — that's the source of truth.
  const active = orders.filter((o) =>
    ['IN_PROGRESS', 'PROCESSING', 'PENDING', 'ACTIVE', 'PAUSED'].includes((o.status ?? '').toUpperCase()),
  ).length;

  const total30d = daily.reduce((s, d) => s + (d.total ?? 0), 0);
  const completed30d = daily.reduce((s, d) => s + (d.completed ?? 0), 0);
  const partial30d = daily.reduce((s, d) => s + (d.partial ?? 0), 0);
  const cancelled30d = daily.reduce((s, d) => s + (d.cancelled ?? 0), 0);
  const spent30d = daily.reduce((s, d) => s + toNum(d.revenue), 0);

  // Success rate against actually-finished orders only. If nothing has finished yet show "—"
  // upstream rather than inventing "98.2%".
  const finished = completed30d + partial30d + cancelled30d;
  const successPct = finished > 0 ? (completed30d / finished) * 100 : 0;

  // Sparklines straight from the daily series.
  const trend = daily.map((d) => d.total ?? 0);
  const spendTrend = daily.map((d) => toNum(d.revenue));

  // Real WoW deltas: last 7 days vs prior 7 days.
  const last7 = daily.slice(-7);
  const prior7 = daily.slice(-14, -7);
  const sumOrders = (arr: DailyStatPoint[]) => arr.reduce((s, d) => s + (d.total ?? 0), 0);
  const sumSpend = (arr: DailyStatPoint[]) => arr.reduce((s, d) => s + toNum(d.revenue), 0);
  const ordersDelta = pctDelta(sumOrders(last7), sumOrders(prior7));
  const spendDelta = pctDelta(sumSpend(last7), sumSpend(prior7));

  // `balance.totalSpent` is the lifetime number; we prefer the 30d sum from `daily` for the
  // KPI card so the value stays in sync with the sparkline next to it. Fall back to lifetime
  // total only when the daily endpoint hasn't responded yet.
  const spentForKpi = daily.length > 0 ? spent30d : toNum(balance?.totalSpent);

  return {
    active,
    total30d,
    completed30d,
    partial30d,
    cancelled30d,
    spent30d: spentForKpi,
    successPct,
    trend,
    spendTrend,
    ordersDelta,
    spendDelta,
  };
}

function pctDelta(curr: number, prev: number): DashStats['spendDelta'] {
  if (!Number.isFinite(curr) || !Number.isFinite(prev) || prev <= 0) return null;
  const pct = ((curr - prev) / prev) * 100;
  if (!Number.isFinite(pct)) return null;
  const sign = pct >= 0 ? '+' : '-';
  return { sign, v: `${Math.abs(pct).toFixed(1)}%`, tone: pct >= 0 ? 'success' : 'danger' };
}

function Hero({ username, balance, stats }: { username: string; balance: number; stats: DashStats }) {
  // No success rate yet → render "—" rather than a misleading 0% donut.
  const finished = stats.completed30d + stats.partial30d + stats.cancelled30d;
  const hasSuccess = finished > 0;
  return (
    <Card className="relative overflow-hidden p-0" style={{ background: 'var(--bg-deep)', color: '#fff' }}>
      <div className="hero-bg" />
      <div className="grid-lines absolute inset-0 opacity-50" />
      <div className="relative z-10 grid grid-cols-1 gap-6 p-7 md:grid-cols-[1fr_auto] md:items-center">
        <div>
          <div className="font-mono text-[12px] uppercase tracking-wider text-white/60">Welcome back</div>
          <h1 className="mt-1 text-[28px] font-bold tracking-[-0.02em]">@{username}</h1>
          <div className="mt-1 text-[14px] text-white/70">
            {stats.active} active · {fmtInt(stats.completed30d)} completed last 30 days · balance{' '}
            <span className="font-mono text-white">${balance.toFixed(2)}</span>
          </div>
          <div className="mt-6 flex flex-wrap gap-3">
            <Link to="/new-order">
              <Button variant="primary" size="lg" iconRight="arrow-right">
                Place new order
              </Button>
            </Link>
            <Link to="/add-funds">
              <Button variant="outline-dark" size="lg" icon="wallet">
                Add funds
              </Button>
            </Link>
          </div>
        </div>
        <Donut
          progress={hasSuccess ? stats.successPct / 100 : 0}
          size={140}
          stroke={11}
          color="var(--accent-2)"
          trackColor="rgba(255,255,255,0.1)"
          label={hasSuccess ? `${stats.successPct.toFixed(1)}%` : '—'}
          sublabel="success rate"
        />
      </div>
    </Card>
  );
}

function KPIRow({ stats }: { stats: DashStats }) {
  const finished = stats.completed30d + stats.partial30d + stats.cancelled30d;
  const items: ReadonlyArray<{
    label: string;
    icon: IconName;
    value: React.ReactNode;
    delta?: { sign: '+' | '-'; v: string; tone: 'success' | 'danger' };
    series: number[];
  }> = [
    {
      label: 'Total spent · 30d',
      icon: 'wallet',
      value: <Money value={stats.spent30d} size="lg" />,
      delta: stats.spendDelta ?? undefined,
      series: stats.spendTrend,
    },
    {
      label: 'Orders · 30d',
      icon: 'orders',
      value: <span className="font-mono text-[20px] font-bold tabular-nums">{fmtInt(stats.total30d)}</span>,
      delta: stats.ordersDelta ?? undefined,
      series: stats.trend,
    },
    {
      label: 'Completed · 30d',
      icon: 'check',
      value: (
        <span className="font-mono text-[20px] font-bold tabular-nums">
          {fmtInt(stats.completed30d)}
        </span>
      ),
      series: stats.trend,
    },
    {
      label: 'Success rate · 30d',
      icon: 'zap',
      value: (
        <span className="font-mono text-[20px] font-bold tabular-nums">
          {finished > 0 ? `${stats.successPct.toFixed(1)}%` : '—'}
        </span>
      ),
      series: stats.spendTrend.map((_, i) => {
        const day = stats.completed30d > 0
          ? (stats.trend[i] ?? 0) > 0
            ? 1
            : 0
          : 0;
        return day;
      }),
    },
  ];
  return (
    <div className="grid grid-cols-2 gap-3 lg:grid-cols-4">
      {items.map((k) => (
        <Card key={k.label} className="p-4" hover>
          <div className="flex items-center justify-between">
            <div className="flex h-7 w-7 items-center justify-center rounded-md bg-bg-sunken text-fg-muted">
              <Icon name={k.icon} size={14} />
            </div>
            {k.delta && (
              <Badge tone={k.delta.tone} size="sm">
                {k.delta.sign}
                {k.delta.v}
              </Badge>
            )}
          </div>
          <div className="mt-3">{k.value}</div>
          <div className="mt-1 text-[11px] text-fg-subtle">{k.label}</div>
          {k.series.some((v) => v > 0) && (
            <div className="mt-2">
              <Sparkline data={k.series} width={170} height={28} color="var(--accent)" stroke={1.5} />
            </div>
          )}
        </Card>
      ))}
    </div>
  );
}

function RecentOrders({ orders, loading }: { orders: Order[]; loading: boolean }) {
  return (
    <Card className="p-0">
      <div className="flex items-center justify-between border-b border-border px-5 py-3">
        <div>
          <div className="text-[14px] font-semibold">Recent orders</div>
          <div className="mt-0.5 text-[11.5px] text-fg-subtle">Last {Math.min(orders.length, 6)} orders</div>
        </div>
        <Link to="/orders">
          <Button variant="ghost" size="sm" iconRight="arrow-right">
            View all
          </Button>
        </Link>
      </div>
      {loading ? (
        <div className="p-12 text-center text-[13px] text-fg-subtle">Loading…</div>
      ) : orders.length === 0 ? (
        <Empty
          icon="orders"
          title="No orders yet"
          subtitle="Place your first order to see it here."
          action={
            <Link to="/new-order">
              <Button variant="primary" size="md">
                Place an order
              </Button>
            </Link>
          }
        />
      ) : (
        <table className="tbl-u">
          <thead>
            <tr>
              <th>ID</th>
              <th>Service</th>
              <th>Link</th>
              <th className="text-right">Qty</th>
              <th>Progress</th>
              <th>Status</th>
              <th className="text-right">Charge</th>
              <th />
            </tr>
          </thead>
          <tbody>
            {orders.slice(0, 6).map((o) => {
              const pct = o.quantity > 0 ? Math.min(1, (o.completed ?? 0) / o.quantity) : 0;
              return (
                <tr key={o.id}>
                  <td className="font-mono text-[12px] text-fg-muted">#{o.id}</td>
                  <td>{o.service?.name ?? o.serviceName ?? '—'}</td>
                  <td>
                    <a
                      href={o.link}
                      target="_blank"
                      rel="noopener noreferrer"
                      className="font-mono text-[12px] text-accent hover:underline"
                    >
                      {short(o.link)}
                    </a>
                  </td>
                  <td className="text-right font-mono">{fmtInt(o.quantity)}</td>
                  <td>
                    <div className="flex items-center gap-2">
                      <div className="h-[5px] w-[100px] overflow-hidden rounded-full bg-bg-sunken">
                        <span
                          className="block h-full bg-accent transition-[width] duration-400"
                          style={{ width: `${(pct * 100).toFixed(0)}%` }}
                        />
                      </div>
                      <span className="font-mono text-[11px] text-fg-muted">{(pct * 100).toFixed(0)}%</span>
                    </div>
                  </td>
                  <td>
                    <StatusBadge status={o.status} />
                  </td>
                  <td className="text-right">
                    <Money value={o.charge} />
                  </td>
                  <td>
                    <Link to={`/orders/${o.id}`} className="row-action inline-flex p-1 text-fg-subtle hover:text-accent">
                      <Icon name="chevron-right" size={14} />
                    </Link>
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
      )}
    </Card>
  );
}

function WalletCard({ balance, stats }: { balance: number; stats: DashStats }) {
  const hasSpend = stats.spendTrend.some((v) => v > 0);
  return (
    <Card>
      <div className="flex items-baseline justify-between">
        <div className="eyebrow">Wallet balance</div>
        <Badge tone="success" size="sm" dot>
          live
        </Badge>
      </div>
      <div className="mt-2 font-mono text-[26px] font-bold tabular-nums">${balance.toFixed(2)}</div>
      {/* Honest 30-day spend label, not a fake "+ $X earned" line. */}
      <div className="mt-1 text-[11.5px] text-fg-subtle">
        Spent ${stats.spent30d.toFixed(2)} last 30d
      </div>
      {hasSpend && (
        <div className="mt-3">
          <Sparkline data={stats.spendTrend} width={300} height={36} color="var(--accent)" />
        </div>
      )}
      <div className="mt-4">
        <Link to="/add-funds">
          <Button variant="primary" size="md" block icon="plus">
            Add funds
          </Button>
        </Link>
      </div>
    </Card>
  );
}

function HelpCard() {
  return (
    <Card className="p-5">
      <div className="flex items-start gap-3">
        <div className="flex h-9 w-9 items-center justify-center rounded-md bg-accent-soft text-accent-fg">
          <Icon name="help" size={16} />
        </div>
        <div>
          <div className="text-[14px] font-semibold">Need a hand?</div>
          <p className="mt-1 text-[12.5px] text-fg-muted">Average response under 12 minutes. Email beats Telegram.</p>
        </div>
      </div>
      <div className="mt-3 flex gap-2">
        <Link to="/help" className="flex-1">
          <Button variant="secondary" size="sm" block>
            Open ticket
          </Button>
        </Link>
        <a href="mailto:hello@smmworld.vip" className="flex-1">
          <Button variant="ghost" size="sm" block>
            Email
          </Button>
        </a>
      </div>
    </Card>
  );
}

function short(url: string): string {
  try {
    const u = new URL(url);
    return u.host.replace(/^www\./, '') + u.pathname;
  } catch {
    return url;
  }
}

// We don't currently use fmtRel here, but Recent Orders may switch to it later.
void fmtRel;
