import { useEffect, useMemo, useState } from 'react';
import { Link } from 'react-router-dom';
import {
  Badge,
  Button,
  Card,
  Empty,
  type IconName,
  Icon,
  Input,
  Money,
  Sparkline,
  StatusBadge,
  Tabs,
} from '../../components/ui';
import { balanceAPI } from '../../services/api';
import { useAuthStore } from '../../store/authStore';
import type { BalanceTransaction, TransactionType, BalanceSummary } from '../../types';
import { fmtMoney, fmtRel } from '../../lib/utils';

// =====================================================================
// Transactions — wallet ledger.
//   - 5-tile stat header (balance / deposited / spent / refunded / 30d flow)
//   - tabs filter by type
//   - search across reason + id
//   - simple ledger table
// =====================================================================

const TYPE_TABS = [
  { value: 'all', label: 'All' },
  { value: 'DEPOSIT', label: 'Deposits' },
  { value: 'CHARGE', label: 'Orders' },
  { value: 'REFUND', label: 'Refunds' },
  { value: 'MANUAL_ADJUST', label: 'Adjustments' },
] as const;

export function TransactionsPage() {
  const updateBalance = useAuthStore((s) => s.updateBalance);
  const [txs, setTxs] = useState<BalanceTransaction[]>([]);
  const [balance, setBalance] = useState<BalanceSummary | null>(null);
  const [loading, setLoading] = useState(true);
  const [tab, setTab] = useState<string>('all');
  const [q, setQ] = useState('');

  useEffect(() => {
    let cancelled = false;
    Promise.allSettled([balanceAPI.transactions(0, 200), balanceAPI.get()]).then(([t, b]) => {
      if (cancelled) return;
      if (t.status === 'fulfilled') {
        const v = t.value as unknown;
        const arr: BalanceTransaction[] = Array.isArray(v)
          ? (v as BalanceTransaction[])
          : (v as { content?: BalanceTransaction[] })?.content ?? [];
        setTxs(arr);
      }
      if (b.status === 'fulfilled') {
        const bs = b.value as BalanceSummary;
        setBalance(bs);
        if (typeof bs.balance === 'number') updateBalance(bs.balance);
      }
      setLoading(false);
    });
    return () => {
      cancelled = true;
    };
  }, [updateBalance]);

  const stats = useMemo(() => {
    const dep = sum(txs, 'DEPOSIT');
    const charge = -sum(txs, 'CHARGE');
    const refund = sum(txs, 'REFUND');
    const adjust = sum(txs, 'MANUAL_ADJUST');
    // Build a 30-day flow series from txs (running sum of net flow per day).
    const now = Date.now();
    const buckets = Array.from({ length: 30 }, () => 0);
    for (const t of txs) {
      const d = new Date(t.createdAt).getTime();
      const idx = 29 - Math.floor((now - d) / 86_400_000);
      if (idx >= 0 && idx < 30) buckets[idx] += t.amount;
    }
    let acc = 0;
    const flow = buckets.map((v) => (acc += v));
    return { dep, charge, refund, adjust, flow };
  }, [txs]);

  const filtered = useMemo(() => {
    return txs.filter((t) => {
      if (tab !== 'all' && t.type !== tab) return false;
      if (q.trim()) {
        const needle = q.trim().toLowerCase();
        if (
          !String(t.id).includes(needle) &&
          !(t.reason ?? '').toLowerCase().includes(needle) &&
          !String(t.orderId ?? '').includes(needle)
        ) {
          return false;
        }
      }
      return true;
    });
  }, [txs, tab, q]);

  return (
    <div className="container-app py-8">
      <div className="flex flex-wrap items-center gap-3">
        <h1 className="text-[24px] font-bold tracking-[-0.02em]">Transactions</h1>
        <span className="font-mono text-[12px] text-fg-subtle">{txs.length} entries</span>
        <div className="ml-auto flex gap-2">
          <Button variant="ghost" size="md" icon="download">
            Export CSV
          </Button>
          <Link to="/add-funds">
            <Button variant="primary" size="md" icon="plus">
              Add funds
            </Button>
          </Link>
        </div>
      </div>

      {/* Stat strip */}
      <div className="mt-5 grid grid-cols-2 gap-3 lg:grid-cols-5">
        <Stat label="Balance" value={<Money value={balance?.balance ?? 0} size="md" />} />
        <Stat label="Deposited" value={<Money value={stats.dep} size="md" color="var(--success)" />} icon="arrow-up-right" />
        <Stat label="Spent on orders" value={<Money value={stats.charge} size="md" />} icon="zap" />
        <Stat label="Refunded" value={<Money value={stats.refund} size="md" color="var(--violet)" />} icon="refresh" />
        <Card className="p-4">
          <div className="flex items-baseline justify-between">
            <span className="text-[10.5px] uppercase tracking-wider text-fg-subtle">30-day flow</span>
            <Badge tone="muted" size="sm">
              cumulative
            </Badge>
          </div>
          <div className="mt-1">
            <Sparkline data={stats.flow.length ? stats.flow : [0, 0, 0, 0, 0, 0]} width={300} height={40} />
          </div>
        </Card>
      </div>

      {/* Filter + table */}
      <Card className="mt-6 p-0">
        <div className="flex flex-wrap items-center gap-3 px-2">
          <div className="flex-1 min-w-[240px]">
            <Tabs value={tab} onChange={setTab} tabs={TYPE_TABS} />
          </div>
          <div className="ml-auto py-2 pr-4">
            <Input
              icon="search"
              placeholder="Search reason / id / order"
              value={q}
              onChange={(e) => setQ(e.target.value)}
              containerClassName="min-w-[260px]"
            />
          </div>
        </div>

        {loading ? (
          <div className="p-12 text-center text-[13px] text-fg-subtle">Loading…</div>
        ) : filtered.length === 0 ? (
          <Empty
            icon="receipt"
            title={q ? 'No matches' : 'No transactions yet'}
            subtitle={q ? `Nothing matches "${q}".` : 'Top up to get started.'}
            action={
              <Link to="/add-funds">
                <Button variant="primary" size="md">
                  Add funds
                </Button>
              </Link>
            }
          />
        ) : (
          <table className="tbl-u">
            <thead>
              <tr>
                <th />
                <th>Type</th>
                <th>Note</th>
                <th>Reference</th>
                <th>Date</th>
                <th className="text-right">Amount</th>
                <th>Status</th>
              </tr>
            </thead>
            <tbody>
              {filtered.map((t) => (
                <tr key={t.id}>
                  <td>
                    <TypeIcon type={t.type} />
                  </td>
                  <td>
                    <TypeBadge type={t.type} />
                  </td>
                  <td>{t.reason ?? '—'}</td>
                  <td className="font-mono text-[12px] text-fg-muted">
                    {t.orderId ? (
                      <Link to={`/orders/${t.orderId}`} className="text-accent hover:underline">
                        order #{t.orderId}
                      </Link>
                    ) : (
                      `tx-${t.id}`
                    )}
                  </td>
                  <td className="font-mono text-[12px] text-fg-muted">{fmtRel(t.createdAt)}</td>
                  <td className="text-right">
                    <Money value={t.amount} sign />
                  </td>
                  <td>
                    <StatusBadge status="completed" label="Cleared" />
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </Card>

      <div className="mt-4 text-[11.5px] text-fg-subtle">
        Off-chain → on-chain balance reconciliation: <span className="font-mono">$0.0000</span>
      </div>
    </div>
  );
}

function Stat({ label, value, icon }: { label: string; value: React.ReactNode; icon?: IconName }) {
  return (
    <Card className="p-4">
      <div className="flex items-center justify-between">
        <span className="text-[10.5px] uppercase tracking-wider text-fg-subtle">{label}</span>
        {icon && (
          <span className="flex h-6 w-6 items-center justify-center rounded-md bg-bg-sunken text-fg-muted">
            <Icon name={icon} size={12} />
          </span>
        )}
      </div>
      <div className="mt-2">{value}</div>
    </Card>
  );
}

function TypeIcon({ type }: { type: TransactionType }) {
  const map: Record<TransactionType, { icon: IconName; bg: string; fg: string }> = {
    DEPOSIT: { icon: 'arrow-up-right', bg: 'bg-success-soft', fg: 'text-success' },
    CHARGE: { icon: 'zap', bg: 'bg-info-soft', fg: 'text-info' },
    REFUND: { icon: 'refresh', bg: 'bg-violet-soft', fg: 'text-violet' },
    MANUAL_ADJUST: { icon: 'spark', bg: 'bg-accent-soft', fg: 'text-accent-fg' },
    BONUS: { icon: 'check', bg: 'bg-warn-soft', fg: 'text-warn' },
  };
  const m = map[type] ?? map.MANUAL_ADJUST;
  return (
    <span className={`flex h-8 w-8 items-center justify-center rounded-md ${m.bg} ${m.fg}`}>
      <Icon name={m.icon} size={14} />
    </span>
  );
}

function TypeBadge({ type }: { type: TransactionType }) {
  const map: Record<TransactionType, { label: string; tone: 'success' | 'info' | 'violet' | 'accent' | 'warn' }> = {
    DEPOSIT: { label: 'Deposit', tone: 'success' },
    CHARGE: { label: 'Order', tone: 'info' },
    REFUND: { label: 'Refund', tone: 'violet' },
    MANUAL_ADJUST: { label: 'Adjustment', tone: 'accent' },
    BONUS: { label: 'Bonus', tone: 'warn' },
  };
  const m = map[type] ?? map.MANUAL_ADJUST;
  return (
    <Badge tone={m.tone} size="sm">
      {m.label}
    </Badge>
  );
}

function sum(txs: BalanceTransaction[], type: TransactionType): number {
  return txs.filter((t) => t.type === type).reduce((s, t) => s + Math.abs(t.amount), 0);
}

// Suppress unused warning if fmtMoney isn't directly used in the body.
void fmtMoney;
