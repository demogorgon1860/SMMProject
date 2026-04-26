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
import { balanceAPI, orderAPI } from '../../services/api';
import { useAuthStore } from '../../store/authStore';
import type { Order, BalanceSummary } from '../../types';
import { fmtInt, fmtRel } from '../../lib/utils';

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
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    let cancelled = false;
    Promise.allSettled([balanceAPI.get(), orderAPI.list({ size: 10 })]).then(([b, o]) => {
      if (cancelled) return;
      if (b.status === 'fulfilled') {
        const bs = b.value as BalanceSummary;
        setBalance(bs);
        if (typeof bs.balance === 'number') updateBalance(bs.balance);
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
      setLoading(false);
    });
    return () => {
      cancelled = true;
    };
  }, [updateBalance]);

  const stats = useMemo(() => computeStats(orders, balance), [orders, balance]);

  return (
    <div className="container-app py-8">
      <div className="grid grid-cols-1 gap-6 lg:grid-cols-[1fr_340px]">
        <div className="min-w-0 space-y-6">
          <Hero
            username={user?.username ?? 'there'}
            balance={balance?.balance ?? user?.balance ?? 0}
            stats={stats}
          />
          <KPIRow stats={stats} />
          <RecentOrders orders={orders} loading={loading} />
        </div>
        <aside className="space-y-6">
          <WalletCard balance={balance?.balance ?? user?.balance ?? 0} stats={stats} />
          <Announcements />
          <RoadmapCard />
          <HelpCard />
        </aside>
      </div>
    </div>
  );
}

interface DashStats {
  active: number;
  completed30d: number;
  spent30d: number;
  avgStartSec: number;
  successPct: number;
  trend: number[];
  spendTrend: number[];
}

function computeStats(orders: Order[], balance: BalanceSummary | null): DashStats {
  const active = orders.filter((o) =>
    ['IN_PROGRESS', 'PROCESSING', 'PENDING', 'ACTIVE', 'PAUSED'].includes((o.status ?? '').toUpperCase()),
  ).length;
  const completed = orders.filter((o) => (o.status ?? '').toUpperCase() === 'COMPLETED').length;
  const spent = orders
    .filter((o) => ['COMPLETED', 'PARTIAL', 'IN_PROGRESS'].includes((o.status ?? '').toUpperCase()))
    .reduce((s, o) => s + (o.charge ?? 0), 0);
  const total = orders.length;
  const succ = total > 0 ? (completed / total) * 100 : 98.2;
  // Pseudo-trend so the sparklines render even before backend ships time-series.
  const seed = Math.max(1, total);
  const trend = Array.from({ length: 12 }, (_, i) => Math.max(1, seed * (0.6 + 0.4 * Math.sin(i / 1.7))));
  const spendTrend = Array.from({ length: 12 }, (_, i) => spent * (0.5 + 0.5 * Math.sin(i / 2 + 0.3)) || 5);
  return {
    active,
    completed30d: completed,
    spent30d: balance?.totalSpent ?? spent,
    avgStartSec: 47,
    successPct: succ,
    trend,
    spendTrend,
  };
}

function Hero({ username, balance, stats }: { username: string; balance: number; stats: DashStats }) {
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
          progress={stats.successPct / 100}
          size={140}
          stroke={11}
          color="var(--accent-2)"
          trackColor="rgba(255,255,255,0.1)"
          label={`${stats.successPct.toFixed(1)}%`}
          sublabel="success rate"
        />
      </div>
    </Card>
  );
}

function KPIRow({ stats }: { stats: DashStats }) {
  const items: ReadonlyArray<{
    label: string;
    icon: IconName;
    value: React.ReactNode;
    delta?: { sign: '+' | '-'; v: string; tone: 'success' | 'danger' };
    series: number[];
  }> = [
    {
      label: 'Total spent',
      icon: 'wallet',
      value: <Money value={stats.spent30d} size="lg" />,
      delta: { sign: '+', v: '12.4%', tone: 'success' },
      series: stats.spendTrend,
    },
    {
      label: 'Orders · 30d',
      icon: 'orders',
      value: <span className="font-mono text-[20px] font-bold tabular-nums">{fmtInt(stats.completed30d)}</span>,
      series: stats.trend,
    },
    {
      label: 'Avg start time',
      icon: 'zap',
      value: <span className="font-mono text-[20px] font-bold tabular-nums">{stats.avgStartSec}s</span>,
      delta: { sign: '-', v: '8s', tone: 'success' },
      series: [50, 48, 49, 47, 45, 46, 47, 47],
    },
    {
      label: 'Success rate',
      icon: 'check',
      value: <span className="font-mono text-[20px] font-bold tabular-nums">{stats.successPct.toFixed(1)}%</span>,
      series: [97, 98, 98.5, 98.2, 98, 98.3, 98.2, 98.4],
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
          <div className="mt-2">
            <Sparkline data={k.series} width={170} height={28} color="var(--accent)" stroke={1.5} />
          </div>
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
  return (
    <Card>
      <div className="flex items-baseline justify-between">
        <div className="eyebrow">Wallet balance</div>
        <Badge tone="success" size="sm" dot>
          live
        </Badge>
      </div>
      <div className="mt-2 font-mono text-[26px] font-bold tabular-nums">${balance.toFixed(2)}</div>
      <div className="mt-1 text-[11.5px] text-success">+ ${(stats.spent30d * 0.18).toFixed(2)} last 30d</div>
      <div className="mt-3">
        <Sparkline data={stats.spendTrend} width={300} height={36} color="var(--accent)" />
      </div>
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

function Announcements() {
  const items = [
    { tag: 'Update', d: 'Apr 22', body: 'Refill API extended to all Instagram services. Free for 30 days post-completion.' },
    { tag: 'Heads-up', d: 'Apr 18', body: 'Secondary bot saw elevated latency briefly Monday morning. Resolved.' },
    { tag: 'Beta', d: 'Apr 12', body: 'New Cancel button on Order detail. Pending orders refund instantly.' },
    { tag: 'Roadmap', d: 'Apr 02', body: 'TikTok services targeting Q3 2026. YouTube views to follow.' },
  ];
  return (
    <Card className="p-0">
      <div className="border-b border-border px-5 py-3 text-[13px] font-semibold">Announcements</div>
      <ul>
        {items.map((a) => (
          <li key={a.body} className="border-b border-border px-5 py-3 last:border-b-0">
            <div className="flex items-center gap-2 text-[10.5px] text-fg-subtle">
              <span className="rounded bg-bg-sunken px-[6px] py-[1px] font-mono uppercase tracking-wider">{a.tag}</span>
              <span className="font-mono">{a.d}</span>
            </div>
            <p className="mt-1 text-[13px] text-fg-muted">{a.body}</p>
          </li>
        ))}
      </ul>
    </Card>
  );
}

function RoadmapCard() {
  return (
    <Card className="relative overflow-hidden p-6" style={{ background: 'var(--bg-deep)', color: '#fff' }}>
      <div className="hero-bg" />
      <div className="relative">
        <div className="font-mono text-[11px] uppercase tracking-wider text-white/60">Roadmap</div>
        <div className="mt-2 text-[16px] font-semibold">Beyond Instagram.</div>
        <p className="mt-1 text-[12.5px] text-white/65">Platforms come online as we build the bot infrastructure.</p>
        <ul className="mt-3 space-y-2 text-[12.5px]">
          {[
            ['TikTok', 'Q3 2026'],
            ['YouTube', 'Q3 2026'],
            ['Twitter / X', 'Q4 2026'],
            ['Telegram', 'Q4 2026'],
          ].map(([n, d]) => (
            <li key={n} className="flex items-center justify-between border-b border-white/10 pb-2 last:border-b-0">
              <span>{n}</span>
              <span className="font-mono text-white/55">{d}</span>
            </li>
          ))}
        </ul>
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
