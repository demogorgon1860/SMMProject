import { useEffect, useMemo, useState } from 'react';
import {
  Badge,
  Button,
  Card,
  Empty,
  Icon,
  Input,
  Money,
  PageHeader,
  Pagination,
  Select,
  StatusBadge,
  TimeCell,
  useToast,
} from '../../components/ui';
import { adminAPI } from '../../services/api';

/**
 * Mirrors the actual /v2/admin/deposits payload (DepositResponse on the backend).
 * Earlier iterations of this page assumed a `{ user: { email }, amount, crypto }` shape
 * that the API never returned, which rendered "$NaN", "@—", and an empty crypto column
 * for every row. Field names below match the JSON keys exactly.
 */
interface AdminPayment {
  id: number;
  /** Per-deposit identifier we hand to Cryptomus; useful as a copy/paste reference. */
  orderId?: string;
  username?: string;
  userId?: number;
  /** USD amount the user is paying. Backend serializes BigDecimal as number-or-string. */
  amountUsdt?: number | string;
  cryptoAmount?: number | string;
  cryptomusPaymentId?: string;
  paymentUrl?: string;
  status: string;
  createdAt: string;
  confirmedAt?: string | null;
  expiresAt?: string | null;
}

function toNum(v: unknown): number {
  if (typeof v === 'number') return Number.isFinite(v) ? v : 0;
  if (typeof v === 'string') {
    const n = Number.parseFloat(v);
    return Number.isFinite(n) ? n : 0;
  }
  return 0;
}

const PAGE_SIZE = 25;

export function AdminPaymentsPage() {
  const toast = useToast();
  const [payments, setPayments] = useState<AdminPayment[]>([]);
  const [loading, setLoading] = useState(true);
  const [q, setQ] = useState('');
  const [statusFilter, setStatusFilter] = useState('all');
  const [cryptoFilter, setCryptoFilter] = useState('all');
  const [page, setPage] = useState(1);

  useEffect(() => {
    let cancelled = false;
    adminAPI
      .getAllDeposits(undefined, 0, 200)
      .then((data: unknown) => {
        if (cancelled) return;
        // /v2/admin/deposits returns { totalPages, pageSize, currentPage, deposits: [...] }.
        // Accept deposits/content/data envelopes so a future refactor doesn't silently blank
        // the page (which is what was happening before — admins saw "0 total" with real data).
        const env = data as { deposits?: unknown[]; content?: unknown[]; data?: unknown[] } | null;
        const arr = Array.isArray(data)
          ? (data as unknown[])
          : env?.deposits ?? env?.content ?? env?.data ?? [];
        setPayments(arr as AdminPayment[]);
      })
      .finally(() => !cancelled && setLoading(false));
    return () => {
      cancelled = true;
    };
  }, []);

  const filtered = useMemo(() => {
    return payments.filter((p) => {
      // Backend statuses are upper-case (PENDING / COMPLETED / FAILED / EXPIRED);
      // the filter <Select> still uses lower-case. Normalize on read.
      if (statusFilter !== 'all' && (p.status ?? '').toLowerCase() !== statusFilter) return false;
      if (q.trim()) {
        const needle = q.trim().toLowerCase();
        if (
          !String(p.id).includes(needle) &&
          !(p.username ?? '').toLowerCase().includes(needle) &&
          !(p.orderId ?? '').toLowerCase().includes(needle)
        )
          return false;
      }
      return true;
    });
  }, [payments, q, statusFilter]);

  const failed = payments.filter((p) => (p.status ?? '').toLowerCase() === 'failed').length;
  const pageRows = filtered.slice((page - 1) * PAGE_SIZE, page * PAGE_SIZE);
  // Cryptomus chooses the actual coin at checkout; we don't store which one was used,
  // so the per-row crypto chip is gone. Use the cryptomusPaymentId column instead.
  const _ = cryptoFilter;
  void _;

  return (
    <>
      <PageHeader
        title="Payments"
        subtitle={
          <span>
            Cryptomus deposit reconciliation ·{' '}
            <span className="font-mono text-fg">{payments.length}</span> total
          </span>
        }
        actions={
          <Button variant="secondary" size="sm" icon="refresh" onClick={() => toast('Sync queued.', 'success')}>
            Sync with Cryptomus
          </Button>
        }
      />

      <div className="space-y-4 p-6">
        {failed > 0 && (
          <div className="flex items-center gap-3 rounded-md border border-danger/30 bg-danger-soft p-3">
            <Icon name="warning" size={16} className="text-danger" />
            <div className="flex-1 text-[13px]">
              <strong>{failed} failed payments</strong> · webhook may have been missed. Manual review recommended.
            </div>
            <Button variant="danger" size="sm" onClick={() => setStatusFilter('failed')}>
              Review failed
            </Button>
          </div>
        )}

        <Card className="p-4">
          <div className="flex flex-wrap items-center gap-2">
            <Input
              icon="search"
              placeholder="Search payment id / user email"
              value={q}
              onChange={(e) => setQ(e.target.value)}
              containerClassName="flex-1 min-w-[260px]"
              block
            />
            <Select
              selectSize="md"
              value={statusFilter}
              onChange={(e) => setStatusFilter(e.target.value)}
              options={[
                { value: 'all', label: 'Any status' },
                { value: 'paid', label: 'Paid' },
                { value: 'pending', label: 'Pending' },
                { value: 'expired', label: 'Expired' },
                { value: 'failed', label: 'Failed' },
              ]}
            />
          </div>
        </Card>

        <Card className="p-0">
          {loading ? (
            <div className="p-12 text-center text-[13px] text-fg-subtle">Loading…</div>
          ) : filtered.length === 0 ? (
            <Empty icon="card" title="No payments match" />
          ) : (
            <>
              <table className="tbl">
                <thead>
                  <tr>
                    <th>ID</th>
                    <th>User</th>
                    <th className="text-right">Amount</th>
                    <th>Cryptomus ID</th>
                    <th>Status</th>
                    <th>Created</th>
                    <th />
                  </tr>
                </thead>
                <tbody>
                  {pageRows.map((p) => {
                    const status = (p.status ?? '').toLowerCase();
                    return (
                      <tr key={p.id}>
                        <td className="font-mono text-[12px]">#{p.id}</td>
                        <td>
                          {p.username ? (
                            <span className="font-mono text-[12.5px]">@{p.username}</span>
                          ) : (
                            <span className="text-fg-subtle">—</span>
                          )}
                        </td>
                        <td className="text-right">
                          <Money value={toNum(p.amountUsdt)} />
                        </td>
                        <td>
                          {p.cryptomusPaymentId ? (
                            <span className="font-mono text-[11px] text-fg-muted">
                              {p.cryptomusPaymentId.slice(0, 8)}…
                            </span>
                          ) : (
                            <span className="text-fg-subtle">—</span>
                          )}
                        </td>
                        <td>
                          <StatusBadge status={p.status} />
                        </td>
                        <td>
                          <TimeCell iso={p.createdAt} />
                        </td>
                        <td className="text-right">
                          {status === 'pending' && p.paymentUrl && (
                            <a href={p.paymentUrl} target="_blank" rel="noopener noreferrer">
                              <Button variant="ghost" size="sm">
                                Open invoice
                              </Button>
                            </a>
                          )}
                        </td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
              <Pagination page={page} total={filtered.length} pageSize={PAGE_SIZE} onPage={setPage} />
            </>
          )}
        </Card>
      </div>
    </>
  );
}

void Badge;
