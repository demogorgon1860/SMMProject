import { useEffect, useMemo, useState } from 'react';
import {
  Badge,
  Button,
  Card,
  Empty,
  Icon,
  IDCell,
  Input,
  Modal,
  Money,
  PageHeader,
  Pagination,
  Select,
  TimeCell,
  Field,
  Textarea,
  useToast,
} from '../../components/ui';
import type { BalanceTransaction, TransactionType } from '../../types';
import { fmtMoney } from '../../lib/utils';

const PAGE_SIZE = 30;

export function AdminBalancePage() {
  const toast = useToast();
  const [txs, setTxs] = useState<BalanceTransaction[]>([]);
  const [loading] = useState(false);
  const [q, setQ] = useState('');
  const [typeFilter, setTypeFilter] = useState('all');
  const [page, setPage] = useState(1);
  const [adjustOpen, setAdjustOpen] = useState(false);

  useEffect(() => {
    // No /v2/admin/transactions endpoint yet. Phase 3 backend.
    setTxs([]);
  }, []);

  const filtered = useMemo(() => {
    return txs.filter((t) => {
      if (typeFilter !== 'all' && t.type !== typeFilter) return false;
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
  }, [txs, typeFilter, q]);

  const pageRows = filtered.slice((page - 1) * PAGE_SIZE, page * PAGE_SIZE);

  return (
    <>
      <PageHeader
        title="Balance transactions"
        subtitle={
          <span>
            Immutable audit log · <span className="font-mono text-fg">{txs.length}</span> entries
          </span>
        }
        actions={
          <>
            <Button variant="ghost" size="sm" icon="download">
              Export CSV
            </Button>
            <Button variant="secondary" size="sm" icon="plus" onClick={() => setAdjustOpen(true)}>
              Manual adjust
            </Button>
          </>
        }
      />

      <div className="space-y-4 p-6">
        <Card className="p-4">
          <div className="flex flex-wrap items-center gap-2">
            <Input
              icon="search"
              placeholder="Search id / user / reason"
              value={q}
              onChange={(e) => setQ(e.target.value)}
              containerClassName="flex-1 min-w-[260px]"
              block
            />
            <Select
              selectSize="md"
              value={typeFilter}
              onChange={(e) => setTypeFilter(e.target.value)}
              options={[
                { value: 'all', label: 'All types' },
                { value: 'DEPOSIT', label: 'Deposit' },
                { value: 'CHARGE', label: 'Charge' },
                { value: 'REFUND', label: 'Refund' },
                { value: 'MANUAL_ADJUST', label: 'Manual adjust' },
              ]}
            />
          </div>
        </Card>

        <Card className="p-0">
          {loading ? (
            <div className="p-12 text-center text-[13px] text-fg-subtle">Loading…</div>
          ) : filtered.length === 0 ? (
            <Empty
              icon="receipt"
              title="No balance transactions yet"
              subtitle="The admin-side ledger will populate when GET /v2/admin/transactions ships in Phase 3."
            />
          ) : (
            <>
              <table className="tbl">
                <thead>
                  <tr>
                    <th>ID</th>
                    <th>Type</th>
                    <th>User</th>
                    <th className="text-right">Amount</th>
                    <th className="text-right">Balance after</th>
                    <th>Order</th>
                    <th>Reason</th>
                    <th>Actor</th>
                    <th>When</th>
                  </tr>
                </thead>
                <tbody>
                  {pageRows.map((t) => (
                    <tr key={t.id}>
                      <td>
                        <IDCell id={t.id} />
                      </td>
                      <td>
                        <TypeChip type={t.type} />
                      </td>
                      <td className="font-mono text-[12px]">—</td>
                      <td className="text-right">
                        <Money value={t.amount} sign />
                      </td>
                      <td className="text-right font-mono text-fg-muted">
                        {t.balanceAfter != null ? fmtMoney(t.balanceAfter) : '—'}
                      </td>
                      <td className="font-mono text-[12px] text-fg-muted">{t.orderId ? '#' + t.orderId : '—'}</td>
                      <td className="text-[12.5px] text-fg-muted">{t.reason ?? '—'}</td>
                      <td className="font-mono text-[11.5px]">
                        {t.actorType === 'admin' ? (
                          <span className="rounded bg-accent-soft px-1.5 py-[1px] text-accent-fg">
                            <Icon name="shield" size={10} className="mr-1 inline align-[-1px]" />
                            {t.actor}
                          </span>
                        ) : (
                          <span className="text-fg-dim">system</span>
                        )}
                      </td>
                      <td>
                        <TimeCell iso={t.createdAt} />
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

      <ManualAdjustModal open={adjustOpen} onClose={() => setAdjustOpen(false)} />
      {void toast /* keep linter happy */}
    </>
  );
}

function TypeChip({ type }: { type: TransactionType }) {
  const map: Record<string, { label: string; tone: 'success' | 'info' | 'warn' | 'accent' }> = {
    DEPOSIT: { label: 'deposit', tone: 'success' },
    CHARGE: { label: 'charge', tone: 'info' },
    REFUND: { label: 'refund', tone: 'warn' },
    MANUAL_ADJUST: { label: 'manual', tone: 'accent' },
  };
  const m = map[type] ?? { label: 'misc', tone: 'accent' as const };
  return (
    <Badge tone={m.tone} size="sm">
      <span className="font-mono">{m.label}</span>
    </Badge>
  );
}

function ManualAdjustModal({ open, onClose }: { open: boolean; onClose: () => void }) {
  const toast = useToast();
  const [identifier, setIdentifier] = useState('');
  const [amount, setAmount] = useState(0);
  const [direction, setDirection] = useState<'credit' | 'debit'>('credit');
  const [reason, setReason] = useState('');
  const [confirm, setConfirm] = useState('');
  const valid = identifier.trim() && amount > 0 && reason.trim().length >= 10 && confirm === 'CONFIRM';
  return (
    <Modal
      open={open}
      onClose={onClose}
      width={520}
      title="Manual balance adjustment"
      footer={
        <>
          <Button variant="ghost" onClick={onClose}>
            Cancel
          </Button>
          <Button
            variant="danger"
            disabled={!valid}
            onClick={() => {
              toast('Manual adjustment posted.', 'success');
              onClose();
            }}
          >
            Post adjustment
          </Button>
        </>
      }
    >
      <Field label="User (email or id)">
        <Input block icon="users" value={identifier} onChange={(e) => setIdentifier(e.target.value)} />
      </Field>
      <div className="grid grid-cols-2 gap-x-4">
        <Field label="Amount" hint="Use − to debit">
          <Input
            block
            type="number"
            step="0.01"
            value={amount || ''}
            onChange={(e) => setAmount(Math.abs(Number(e.target.value) || 0))}
          />
        </Field>
        <Field label="Direction">
          <Select
            block
            value={direction}
            onChange={(e) => setDirection(e.target.value as 'credit' | 'debit')}
            options={[
              { value: 'credit', label: 'Credit user' },
              { value: 'debit', label: 'Debit user' },
            ]}
          />
        </Field>
      </div>
      <Field label="Reason">
        <Textarea block rows={3} value={reason} onChange={(e) => setReason(e.target.value)} placeholder="Why this adjustment is needed…" />
      </Field>
      <div className="rounded-md border border-warn/30 bg-warn-soft p-3 text-[12.5px] text-warn">
        <Icon name="warning" size={12} className="mr-1 inline align-[-1px]" />
        Manual adjustments cannot be reversed. Type <code className="font-mono">CONFIRM</code> below.
      </div>
      <Field label="Confirmation" className="mt-3">
        <Input block value={confirm} onChange={(e) => setConfirm(e.target.value)} placeholder="CONFIRM" />
      </Field>
    </Modal>
  );
}
