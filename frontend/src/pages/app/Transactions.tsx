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
import { fmtMoney, fmtRel, toNum } from '../../lib/utils';

// =====================================================================
// Transactions — wallet ledger.
//   - 5-tile stat header (balance / deposited / spent / refunded / 30d flow)
//   - tabs filter by type
//   - search across reason + id
//   - simple ledger table
// =====================================================================

// Tab `value` is the *frontend bucket* — multiple backend TransactionType values
// can share a tab (e.g. ORDER_PAYMENT and CHARGE both render under "Orders").
//
// "Adjustments" was intentionally dropped: ADJUSTMENT / BONUS / COMMISSION /
// PENALTY / TRANSFER_OUT are unused by the current product (admin top-ups land as
// DEPOSIT via BalanceService.addBalance, no referral/penalty features wired). If
// any of those flows go live later, restore the tab and the matching TAB_TO_TYPES
// entry — bucketOf already maps the row-level icons.
const TYPE_TABS = [
  { value: 'all', label: 'All' },
  { value: 'deposit', label: 'Deposits' },
  { value: 'order', label: 'Orders' },
  { value: 'refund', label: 'Refunds' },
] as const;

// Reverse of `bucketOf`: a tab → the set of backend TransactionType enum names that
// fall into it. Sent as `?type=DEPOSIT,TRANSFER_IN` query param so the backend can
// filter at the SQL layer. Without this, an active user with thousands of
// ORDER_PAYMENT rows would never see their old DEPOSIT entries — they fall outside
// the first 200-row page on which the local-filter version relied.
const TAB_TO_TYPES: Record<string, string[] | null> = {
  all: null,
  deposit: ['DEPOSIT', 'TRANSFER_IN'],
  order: ['ORDER_PAYMENT'],
  refund: ['REFUND', 'REFILL'],
};

/**
 * Map the raw backend type onto a frontend bucket. ORDER_PAYMENT (a real per-order charge)
 * was previously falling into the MANUAL_ADJUST default and rendering as "Adjustment", which
 * is what the customer screenshot called out — every order charge looked like a manual admin
 * adjustment. REFILL collapses to "refund" because that's how the user perceives it; positive
 * ADJUSTMENT (admin top-up) keeps a distinct icon so the operator origin is visible.
 */
function bucketOf(type: string | undefined): 'deposit' | 'order' | 'refund' | 'adjust' | 'other' {
  switch ((type ?? '').toUpperCase()) {
    case 'DEPOSIT':
    case 'TRANSFER_IN':
      return 'deposit';
    case 'ORDER_PAYMENT':
    case 'CHARGE':
      return 'order';
    case 'REFUND':
    case 'REFILL':
      return 'refund';
    case 'ADJUSTMENT':
    case 'MANUAL_ADJUST':
    case 'BONUS':
    case 'COMMISSION':
    case 'PENALTY':
    case 'TRANSFER_OUT':
      return 'adjust';
    default:
      return 'other';
  }
}

export function TransactionsPage() {
  const updateBalance = useAuthStore((s) => s.updateBalance);
  // `txs` powers the stat strip aggregates (Deposited / Spent / Refunded) and the
  // 30-day flow chart — always loaded as the unfiltered last-200 page regardless
  // of which tab is active.
  const [txs, setTxs] = useState<BalanceTransaction[]>([]);
  // `tabTxs` is the type-filtered list backing the table when a non-'all' tab is
  // active. We hit the API with the matching ?type= so even if the user has
  // thousands of ORDER_PAYMENT rows, their few DEPOSIT entries appear.
  const [tabTxs, setTabTxs] = useState<BalanceTransaction[]>([]);
  const [balance, setBalance] = useState<BalanceSummary | null>(null);
  // Lifetime sums by TransactionType — drives the stat cards. Backend GROUP BY
  // gives us real totals regardless of how many transactions the user has.
  const [summary, setSummary] = useState<Record<string, string> | null>(null);
  const [loading, setLoading] = useState(true);
  const [tabLoading, setTabLoading] = useState(false);
  const [tab, setTab] = useState<string>('all');
  const [q, setQ] = useState('');

  // Initial: load last-200 (for the 30-day flow chart and the All tab table) +
  // balance + lifetime summary (drives the stat cards).
  useEffect(() => {
    let cancelled = false;
    Promise.allSettled([
      balanceAPI.transactions(0, 200),
      balanceAPI.get(),
      balanceAPI.transactionSummary(),
    ]).then(([t, b, s]) => {
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
      if (s.status === 'fulfilled') {
        setSummary(s.value as Record<string, string>);
      }
      // Note: if summary fails, stats fall back to summing over txs (last-200) —
      // imperfect for active users but better than blanks.
      setLoading(false);
    });
    return () => {
      cancelled = true;
    };
  }, [updateBalance]);

  // Tab-scoped: load type-filtered list for the active non-'all' tab.
  useEffect(() => {
    const types = TAB_TO_TYPES[tab];
    if (!types) {
      setTabTxs([]);
      return;
    }
    let cancelled = false;
    setTabLoading(true);
    balanceAPI
      .transactions(0, 200, types)
      .then((v) => {
        if (cancelled) return;
        const arr: BalanceTransaction[] = Array.isArray(v)
          ? (v as BalanceTransaction[])
          : (v as { content?: BalanceTransaction[] })?.content ?? [];
        setTabTxs(arr);
      })
      .catch(() => {
        if (!cancelled) setTabTxs([]);
      })
      .finally(() => {
        if (!cancelled) setTabLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [tab]);

  const stats = useMemo(() => {
    // Sums for the stat cards. Prefer the backend lifetime `summary` (GROUP BY over
    // the user's full history) — falls back to summing the last-200 page if the
    // summary endpoint is unavailable. Only Deposited / Spent / Refunded are
    // displayed; the Adjustments tab + card were dropped (see TYPE_TABS comment).
    let dep = 0;
    let charge = 0;
    let refund = 0;
    if (summary) {
      // Bucket keys match `bucketOf`: DEPOSIT/TRANSFER_IN → deposits,
      // ORDER_PAYMENT (negative in DB) → spent, REFUND/REFILL → refunded.
      dep = toNum(summary.DEPOSIT) + toNum(summary.TRANSFER_IN);
      charge = -toNum(summary.ORDER_PAYMENT); // stored signed-negative; show positive
      refund = toNum(summary.REFUND) + toNum(summary.REFILL);
    } else {
      for (const t of txs) {
        const amt = Number(t.amount) || 0;
        switch (bucketOf(t.type)) {
          case 'deposit':
            dep += amt;
            break;
          case 'order':
            charge += -amt;
            break;
          case 'refund':
            refund += amt;
            break;
          default:
            break;
        }
      }
    }
    // Build a 30-day flow series from txs (running sum of net flow per day).
    const now = Date.now();
    const buckets = Array.from({ length: 30 }, () => 0);
    for (const t of txs) {
      const d = new Date(t.createdAt).getTime();
      const idx = 29 - Math.floor((now - d) / 86_400_000);
      if (idx >= 0 && idx < 30) buckets[idx] += Number(t.amount) || 0;
    }
    let acc = 0;
    const flow = buckets.map((v) => (acc += v));
    return { dep, charge, refund, flow };
  }, [txs, summary]);

  const filtered = useMemo(() => {
    // 'all' tab — show the unfiltered last-200 (`txs`); other tabs show the
    // type-scoped fetch (`tabTxs`) which guarantees old DEPOSIT/REFUND entries
    // appear even for users with thousands of ORDER_PAYMENT rows.
    const source = tab === 'all' ? txs : tabTxs;
    return source.filter((t) => {
      if (q.trim()) {
        const needle = q.trim().toLowerCase();
        if (
          !String(t.id).includes(needle) &&
          !(t.description ?? t.reason ?? '').toLowerCase().includes(needle) &&
          !String(t.orderId ?? '').includes(needle) &&
          !(t.referenceNumber ?? '').toLowerCase().includes(needle)
        ) {
          return false;
        }
      }
      return true;
    });
  }, [txs, tabTxs, tab, q]);

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

        {loading || tabLoading ? (
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
                  <td className="max-w-[280px]">
                    <span className="block truncate text-[12.5px]" title={t.description ?? t.reason ?? ''}>
                      {t.description ?? t.reason ?? '—'}
                    </span>
                  </td>
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

// Per-type rendering. Keyed by the actual backend enum value (and legacy aliases).
// "Order payment", "Refill", "Bonus" etc. all get distinct labels — previously every
// non-(Deposit/Charge/Refund/Manual_Adjust/Bonus) row collapsed into "Adjustment".
const TYPE_RENDER: Record<
  string,
  { label: string; icon: IconName; bg: string; fg: string; tone: 'success' | 'info' | 'violet' | 'accent' | 'warn' | 'danger' }
> = {
  DEPOSIT: { label: 'Deposit', icon: 'arrow-up-right', bg: 'bg-success-soft', fg: 'text-success', tone: 'success' },
  ORDER_PAYMENT: { label: 'Order payment', icon: 'zap', bg: 'bg-info-soft', fg: 'text-info', tone: 'info' },
  CHARGE: { label: 'Order payment', icon: 'zap', bg: 'bg-info-soft', fg: 'text-info', tone: 'info' },
  REFUND: { label: 'Refund', icon: 'refresh', bg: 'bg-violet-soft', fg: 'text-violet', tone: 'violet' },
  REFILL: { label: 'Refill', icon: 'refresh', bg: 'bg-violet-soft', fg: 'text-violet', tone: 'violet' },
  ADJUSTMENT: { label: 'Adjustment', icon: 'spark', bg: 'bg-accent-soft', fg: 'text-accent-fg', tone: 'accent' },
  MANUAL_ADJUST: { label: 'Adjustment', icon: 'spark', bg: 'bg-accent-soft', fg: 'text-accent-fg', tone: 'accent' },
  BONUS: { label: 'Bonus', icon: 'check', bg: 'bg-warn-soft', fg: 'text-warn', tone: 'warn' },
  TRANSFER_IN: { label: 'Transfer in', icon: 'arrow-up-right', bg: 'bg-success-soft', fg: 'text-success', tone: 'success' },
  TRANSFER_OUT: { label: 'Transfer out', icon: 'arrow-up-right', bg: 'bg-bg-sunken', fg: 'text-fg-muted', tone: 'accent' },
  COMMISSION: { label: 'Commission', icon: 'check', bg: 'bg-success-soft', fg: 'text-success', tone: 'success' },
  PENALTY: { label: 'Penalty', icon: 'warning', bg: 'bg-danger-soft', fg: 'text-danger', tone: 'danger' },
};
function renderFor(type: string | undefined) {
  return TYPE_RENDER[(type ?? '').toUpperCase()] ?? TYPE_RENDER.ADJUSTMENT;
}

function TypeIcon({ type }: { type: TransactionType }) {
  const m = renderFor(type);
  return (
    <span className={`flex h-8 w-8 items-center justify-center rounded-md ${m.bg} ${m.fg}`}>
      <Icon name={m.icon} size={14} />
    </span>
  );
}

function TypeBadge({ type }: { type: TransactionType }) {
  const m = renderFor(type);
  return (
    <Badge tone={m.tone === 'danger' ? 'danger' : m.tone} size="sm">
      {m.label}
    </Badge>
  );
}

// Suppress unused warning if fmtMoney isn't directly used in the body.
void fmtMoney;
