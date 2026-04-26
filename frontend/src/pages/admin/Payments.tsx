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

interface AdminPayment {
  id: string;
  user: { email?: string; username?: string; id?: number };
  amount: number;
  crypto: string;
  status: 'paid' | 'pending' | 'expired' | 'failed' | string;
  createdAt: string;
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
        const arr = Array.isArray(data) ? data : (data as { content?: unknown[] })?.content ?? [];
        setPayments(arr as AdminPayment[]);
      })
      .finally(() => !cancelled && setLoading(false));
    return () => {
      cancelled = true;
    };
  }, []);

  const filtered = useMemo(() => {
    return payments.filter((p) => {
      if (statusFilter !== 'all' && (p.status ?? '').toLowerCase() !== statusFilter) return false;
      if (cryptoFilter !== 'all' && p.crypto !== cryptoFilter) return false;
      if (q.trim()) {
        const needle = q.trim().toLowerCase();
        if (!String(p.id).includes(needle) && !(p.user?.email ?? '').toLowerCase().includes(needle)) return false;
      }
      return true;
    });
  }, [payments, q, statusFilter, cryptoFilter]);

  const failed = payments.filter((p) => p.status?.toLowerCase() === 'failed').length;
  const pageRows = filtered.slice((page - 1) * PAGE_SIZE, page * PAGE_SIZE);

  const cryptoColor = (sym: string) => {
    return ({ BTC: '#f7931a', ETH: '#627eea', USDT: '#26a17b', TRX: '#ef1e1e', USDC: '#2775ca' } as Record<string, string>)[sym] ?? 'var(--fg-dim)';
  };

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
            <Select
              selectSize="md"
              value={cryptoFilter}
              onChange={(e) => setCryptoFilter(e.target.value)}
              options={[
                { value: 'all', label: 'Any crypto' },
                'BTC',
                'USDT',
                'ETH',
                'TRX',
                'USDC',
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
                    <th>Payment ID</th>
                    <th>User</th>
                    <th className="text-right">Amount</th>
                    <th>Crypto</th>
                    <th>Status</th>
                    <th>Created</th>
                    <th />
                  </tr>
                </thead>
                <tbody>
                  {pageRows.map((p) => (
                    <tr key={p.id}>
                      <td className="font-mono text-[12px]">{p.id}</td>
                      <td>
                        <div className="text-[13px]">{p.user?.email ?? '—'}</div>
                        <div className="font-mono text-[11px] text-fg-subtle">@{p.user?.username ?? '—'}</div>
                      </td>
                      <td className="text-right">
                        <Money value={p.amount} />
                      </td>
                      <td>
                        <span className="inline-flex items-center gap-1.5 rounded border border-border bg-bg-sunken px-2 py-[2px] font-mono text-[11px]">
                          <span className="h-[6px] w-[6px] rounded-full" style={{ background: cryptoColor(p.crypto) }} />
                          {p.crypto}
                        </span>
                      </td>
                      <td>
                        <StatusBadge status={p.status} />
                      </td>
                      <td>
                        <TimeCell iso={p.createdAt} />
                      </td>
                      <td className="text-right">
                        {p.status === 'pending' && (
                          <Button variant="ghost" size="sm">
                            Verify
                          </Button>
                        )}
                        {p.status === 'failed' && (
                          <span className="flex justify-end gap-1.5">
                            <Button variant="secondary" size="sm">
                              Retry
                            </Button>
                            <Button variant="danger" size="sm">
                              Force credit
                            </Button>
                          </span>
                        )}
                      </td>
                    </tr>
                  ))}
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
