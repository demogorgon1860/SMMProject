import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import {
  Badge,
  Button,
  Card,
  Empty,
  Icon,
  IDCell,
  Textarea,
  TimeCell,
  useToast,
} from '../../components/ui';
import type { BadgeTone, IconName } from '../../components/ui';
import { refillAPI, profileAPI } from '../../services/api';
import type { RefillBatchItem, RefillRequest, RefillRequestStatus } from '../../types';
import { cn, fmtInt, toNum } from '../../lib/utils';

// =====================================================================
// Refill — fully automatic. The customer pastes one or more order numbers
// ("29931, 29932, …") and submits. The panel auto-runs the drop check on
// each order and, if anything dropped, queues it for operator approval for
// EXACTLY the dropped amount. No manual "check drop" step. The history
// table below updates live as requests move Checking → Awaiting approval →
// Approved / No drop / Failed.
// =====================================================================

const POLL_MS = 8000;

/** Split a free-text paste ("29931, 29932 29933") into unique positive order ids. */
function parseIds(raw: string): number[] {
  const out: number[] = [];
  const seen = new Set<number>();
  for (const tok of raw.split(/[\s,]+/)) {
    if (!tok) continue;
    const n = Number(tok);
    if (Number.isInteger(n) && n > 0 && !seen.has(n)) {
      seen.add(n);
      out.push(n);
    }
  }
  return out;
}

const STATUS_META: Record<RefillRequestStatus, { tone: BadgeTone; label: string; dot?: boolean }> = {
  CHECKING: { tone: 'info', label: 'Checking drop', dot: true },
  PENDING: { tone: 'warn', label: 'Awaiting approval', dot: true },
  APPROVED: { tone: 'success', label: 'Approved' },
  NO_DROP: { tone: 'muted', label: 'No drop' },
  REJECTED: { tone: 'danger', label: 'Rejected' },
  FAILED: { tone: 'danger', label: 'Failed' },
};

export function RefillPage() {
  const toast = useToast();

  const [input, setInput] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [batch, setBatch] = useState<RefillBatchItem[] | null>(null);

  const [history, setHistory] = useState<RefillRequest[]>([]);
  const [historyLoading, setHistoryLoading] = useState(true);

  const parsed = useMemo(() => parseIds(input), [input]);

  const loadHistory = useCallback(async () => {
    try {
      const rows: RefillRequest[] = await profileAPI.myRefillRequests();
      setHistory(Array.isArray(rows) ? rows : []);
    } catch {
      setHistory([]);
    } finally {
      setHistoryLoading(false);
    }
  }, []);

  useEffect(() => {
    void loadHistory();
  }, [loadHistory]);

  // Poll while any request is still being checked, so Checking → Awaiting approval / No drop
  // appears without a manual refresh. A ref keeps the interval from re-subscribing each tick.
  const hasChecking = history.some((r) => r.status === 'CHECKING');
  const loadRef = useRef(loadHistory);
  loadRef.current = loadHistory;
  useEffect(() => {
    if (!hasChecking) return;
    const t = setInterval(() => void loadRef.current(), POLL_MS);
    return () => clearInterval(t);
  }, [hasChecking]);

  const submit = async () => {
    const ids = parseIds(input);
    if (ids.length === 0) {
      toast('Enter one or more order numbers', 'error');
      return;
    }
    setSubmitting(true);
    try {
      const res = await refillAPI.submitBatch(ids);
      setBatch(res.results);
      if (res.accepted > 0 && res.rejected === 0) {
        toast(
          res.accepted === 1
            ? 'Submitted — checking the drop now.'
            : `Submitted ${res.accepted} orders — checking the drop now.`,
          'success',
        );
        setInput('');
      } else if (res.accepted > 0) {
        toast(`${res.accepted} submitted · ${res.rejected} skipped — see details below.`, 'info');
      } else {
        toast('None of the orders could be submitted — see details below.', 'error');
      }
      void loadHistory();
    } catch {
      toast('Could not submit the refill. Please try again.', 'error');
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <div className="container-app py-8">
      <div className="flex flex-wrap items-center gap-3">
        <h1 className="text-[24px] font-bold tracking-[-0.02em]">Refill</h1>
        <span className="font-mono text-[12px] text-fg-subtle">
          {history.length} {history.length === 1 ? 'request' : 'requests'}
        </span>
      </div>
      <p className="mt-1 max-w-[680px] text-[13px] text-fg-muted">
        Paste the order numbers you want refilled — one, or several separated by commas. We
        automatically check each order's drop and, if part of it fell off, queue a refill for exactly
        the dropped amount. No need to check anything yourself.
      </p>

      {/* ---- Submit ---- */}
      <Card className="mt-4 p-5">
        <form
          onSubmit={(e) => {
            e.preventDefault();
            void submit();
          }}
        >
          <label className="mb-1 block text-[11px] uppercase tracking-wider text-fg-subtle">
            Order numbers
          </label>
          <Textarea
            block
            rows={2}
            value={input}
            onChange={(e) => setInput(e.target.value)}
            placeholder="e.g. 29931, 29932, 29933"
            // Submit on Enter, newline on Shift+Enter — a paste-list shouldn't need a mouse.
            onKeyDown={(e) => {
              if (e.key === 'Enter' && !e.shiftKey) {
                e.preventDefault();
                void submit();
              }
            }}
          />
          <div className="mt-3 flex flex-wrap items-center gap-3">
            <Button
              type="submit"
              variant="primary"
              size="md"
              icon="refresh"
              loading={submitting}
              disabled={parsed.length === 0}
            >
              Submit for refill
            </Button>
            <span className="font-mono text-[12px] text-fg-subtle">
              {parsed.length > 0
                ? `${parsed.length} order${parsed.length === 1 ? '' : 's'} ready`
                : 'Separate multiple orders with commas'}
            </span>
          </div>
        </form>

        {batch && batch.length > 0 && <BatchResults items={batch} />}
      </Card>

      {/* ---- History ---- */}
      <Card className="mt-4 p-0">
        <div className="border-b border-border px-5 py-3 text-[13px] font-semibold">Refills</div>
        {historyLoading ? (
          <div className="p-12 text-center text-[13px] text-fg-subtle">Loading…</div>
        ) : history.length === 0 ? (
          <Empty
            icon="orders"
            title="No refills yet"
            subtitle="Submit an order above — we'll check its drop and refill what's needed automatically."
          />
        ) : (
          <div className="overflow-x-auto">
            <table className="tbl-u min-w-[820px]">
              <thead>
                <tr>
                  <th>Refill ID</th>
                  <th>Order</th>
                  <th>Status</th>
                  <th className="text-right">Drop rate</th>
                  <th className="text-right">Refill qty</th>
                  <th>Submitted</th>
                  <th>Finished</th>
                </tr>
              </thead>
              <tbody>
                {history.map((r) => (
                  <tr key={r.id}>
                    <td>
                      <IDCell id={r.id} />
                    </td>
                    <td className="font-mono text-[12px] text-fg-muted">#{r.orderId}</td>
                    <td>
                      <RequestStatus r={r} />
                    </td>
                    <td className="text-right font-mono">
                      {r.dropRate != null ? `${toNum(r.dropRate)}%` : '—'}
                    </td>
                    <td className="text-right font-mono">
                      {r.refillNeeded != null ? fmtInt(r.refillNeeded) : '—'}
                    </td>
                    <td>
                      <TimeCell iso={r.createdAt} />
                    </td>
                    <td>
                      {r.decidedAt ? (
                        <TimeCell iso={r.decidedAt} />
                      ) : (
                        <span className="text-fg-dim">—</span>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </Card>
    </div>
  );
}

// ---------------------------------------------------------------------
// Per-order submit feedback
// ---------------------------------------------------------------------

function BatchResults({ items }: { items: RefillBatchItem[] }) {
  return (
    <div className="mt-4 space-y-1.5 border-t border-border pt-4">
      {items.map((it) => {
        const ok = it.accepted;
        const icon: IconName = ok ? 'check' : 'alert';
        const detail = ok
          ? it.status === 'PENDING'
            ? 'already awaiting approval'
            : 'checking the drop…'
          : it.message ?? 'not eligible';
        return (
          <div
            key={it.orderId}
            className={cn(
              'flex items-start gap-2 rounded-md border px-3 py-2 text-[12.5px]',
              ok
                ? 'border-success/30 bg-success-soft text-success'
                : 'border-warn/30 bg-warn-soft text-warn',
            )}
          >
            <Icon name={icon} size={14} className="mt-[1px] flex-none" />
            <span>
              <span className="font-mono font-semibold">#{it.orderId}</span> — {detail}
            </span>
          </div>
        );
      })}
    </div>
  );
}

function RequestStatus({ r }: { r: RefillRequest }) {
  const meta = STATUS_META[r.status] ?? { tone: 'muted' as BadgeTone, label: r.status };
  const tip =
    r.status === 'APPROVED' && r.refillOrderId
      ? `Refill order #${r.refillOrderId}`
      : r.status === 'REJECTED' && r.rejectionReason
        ? r.rejectionReason
        : r.status === 'FAILED'
          ? (r.rejectionReason ?? "Couldn't verify the drop — submit again.")
          : r.status === 'NO_DROP'
            ? 'Nothing dropped — nothing to refill.'
            : undefined;
  return (
    <span className="inline-flex items-center gap-1.5">
      <Badge tone={meta.tone} size="sm" dot={meta.dot} title={tip}>
        {meta.label}
      </Badge>
      {r.status === 'APPROVED' && r.refillOrderId && (
        <span className="font-mono text-[11px] text-fg-subtle">→ #{r.refillOrderId}</span>
      )}
    </span>
  );
}
