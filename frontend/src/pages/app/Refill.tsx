import { useCallback, useEffect, useState } from 'react';
import {
  Badge,
  Button,
  Card,
  ConfirmModal,
  Donut,
  Empty,
  Icon,
  IDCell,
  Input,
  TimeCell,
  useToast,
} from '../../components/ui';
import { orderAPI, profileAPI, refillAPI } from '../../services/api';
import type { RefillCheck, RefillRequest } from '../../types';
import { cn, fmtInt, toNum } from '../../lib/utils';

// =====================================================================
// Refill — user enters an order number, the panel runs the bot's LIVE
// drop check, shows the drop rate %, and (if anything dropped) lets them
// request a refill for ONLY the dropped amount. Sits between Orders and
// Transactions. History table mirrors the classic "Refill history" view.
// =====================================================================

const POLL_MS = 3500;

function errMsg(err: unknown, fallback: string): string {
  const e = err as { response?: { data?: { message?: string }; status?: number } };
  if (e.response?.data?.message) return e.response.data.message;
  if (e.response?.status === 404) return 'Order not found.';
  return fallback;
}

export function RefillPage() {
  const toast = useToast();

  const [orderInput, setOrderInput] = useState('');
  const [activeOrderId, setActiveOrderId] = useState<number | null>(null);
  const [check, setCheck] = useState<RefillCheck | null>(null);
  const [checkError, setCheckError] = useState<string | null>(null);
  const [starting, setStarting] = useState(false);
  const [refillReq, setRefillReq] = useState<RefillRequest | null>(null);
  const [confirmOpen, setConfirmOpen] = useState(false);
  const [requesting, setRequesting] = useState(false);

  const [history, setHistory] = useState<RefillRequest[]>([]);
  const [historyLoading, setHistoryLoading] = useState(true);

  const loadHistory = useCallback(async () => {
    setHistoryLoading(true);
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

  // Poll the check while it's RUNNING (the bot enumeration takes a while).
  const running = check?.status === 'RUNNING' && activeOrderId != null;
  useEffect(() => {
    if (!running || activeOrderId == null) return;
    const t = setInterval(async () => {
      try {
        const res: RefillCheck = await refillAPI.getCheck(activeOrderId);
        setCheck(res);
      } catch {
        // transient (404 while the row settles / network blip) — keep polling
      }
    }, POLL_MS);
    return () => clearInterval(t);
  }, [running, activeOrderId]);

  const runCheck = async () => {
    const id = Number(orderInput.trim());
    if (!Number.isInteger(id) || id <= 0) {
      toast('Enter a valid order number', 'error');
      return;
    }
    setStarting(true);
    setCheckError(null);
    setCheck(null);
    setRefillReq(null);
    setActiveOrderId(id);
    try {
      const res: RefillCheck = await refillAPI.checkDrop(id);
      setCheck(res);
      // Show any existing refill-request state for this order alongside the result.
      orderAPI
        .getRefillRequest(id)
        .then((r: RefillRequest) => setRefillReq(r))
        .catch(() => setRefillReq(null));
    } catch (err) {
      setCheckError(errMsg(err, 'Could not start the drop check.'));
    } finally {
      setStarting(false);
    }
  };

  const submitRefill = async () => {
    if (activeOrderId == null) return;
    setRequesting(true);
    try {
      const created: RefillRequest = await orderAPI.requestRefill(activeOrderId);
      setRefillReq(created);
      setConfirmOpen(false);
      toast('Refill request submitted — an operator will review it.', 'success');
      void loadHistory();
    } catch (err) {
      toast(errMsg(err, 'Could not submit the refill request.'), 'error');
    } finally {
      setRequesting(false);
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
      <p className="mt-1 text-[13px] text-fg-muted">
        Enter an order number to check its drop. If part of it fell off, request a refill — it'll be
        created for exactly the dropped amount.
      </p>

      {/* ---- Check tool ---- */}
      <Card className="mt-4 p-5">
        <form
          className="flex flex-wrap items-end gap-2"
          onSubmit={(e) => {
            e.preventDefault();
            void runCheck();
          }}
        >
          <div className="min-w-[220px] flex-1">
            <label className="mb-1 block text-[11px] uppercase tracking-wider text-fg-subtle">
              Order number
            </label>
            <Input
              icon="search"
              inputMode="numeric"
              placeholder="e.g. 12345"
              value={orderInput}
              onChange={(e) => setOrderInput(e.target.value.replace(/[^0-9]/g, ''))}
              block
            />
          </div>
          <Button
            type="submit"
            variant="primary"
            size="md"
            icon="zap"
            loading={starting || running}
            disabled={!orderInput.trim()}
          >
            {running ? 'Checking…' : 'Check drop'}
          </Button>
        </form>

        {checkError && (
          <div className="mt-4 flex items-start gap-2 rounded-md border border-danger/30 bg-danger-soft px-3 py-2 text-[12.5px] text-danger">
            <Icon name="alert" size={14} className="mt-[1px] flex-none" />
            <span>{checkError}</span>
          </div>
        )}

        {check && !checkError && (
          <CheckResult
            check={check}
            refillReq={refillReq}
            onRequest={() => setConfirmOpen(true)}
          />
        )}
      </Card>

      {/* ---- History ---- */}
      <Card className="mt-4 p-0">
        <div className="border-b border-border px-5 py-3 text-[13px] font-semibold">
          Refill history
        </div>
        {historyLoading ? (
          <div className="p-12 text-center text-[13px] text-fg-subtle">Loading…</div>
        ) : history.length === 0 ? (
          <Empty
            icon="orders"
            title="No refills yet"
            subtitle="Check an order's drop and request a refill — it'll show up here."
          />
        ) : (
          <div className="overflow-x-auto">
            <table className="tbl-u min-w-[760px]">
              <thead>
                <tr>
                  <th>Refill ID</th>
                  <th>Status</th>
                  <th className="text-right">Quantity</th>
                  <th className="text-right">Drop rate</th>
                  <th>Created at</th>
                  <th>Finished at</th>
                </tr>
              </thead>
              <tbody>
                {history.map((r) => (
                  <tr key={r.id}>
                    <td>
                      <IDCell id={r.id} />
                    </td>
                    <td>
                      <RequestStatusBadge status={r.status} />
                    </td>
                    <td className="text-right font-mono">
                      {r.refillNeeded != null ? fmtInt(r.refillNeeded) : '—'}
                    </td>
                    <td className="text-right font-mono">
                      {r.dropRate != null ? `${toNum(r.dropRate)}%` : '—'}
                    </td>
                    <td>
                      <TimeCell iso={r.createdAt} />
                    </td>
                    <td>{r.decidedAt ? <TimeCell iso={r.decidedAt} /> : <span className="text-fg-dim">—</span>}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </Card>

      <ConfirmModal
        open={confirmOpen}
        onClose={() => setConfirmOpen(false)}
        onConfirm={submitRefill}
        loading={requesting}
        variant="primary"
        confirmText="Submit request"
        cancelText="Cancel"
        title={`Request refill on order #${activeOrderId ?? ''}?`}
        message={
          check?.refillNeeded != null
            ? `This requests a refill of ${fmtInt(check.refillNeeded)} (the dropped amount). An operator will review it.`
            : 'An operator will review the refill request.'
        }
      />
    </div>
  );
}

// ---------------------------------------------------------------------
// Drop-check result panel (RUNNING / DONE / FAILED)
// ---------------------------------------------------------------------

function CheckResult({
  check,
  refillReq,
  onRequest,
}: {
  check: RefillCheck;
  refillReq: RefillRequest | null;
  onRequest: () => void;
}) {
  if (check.status === 'RUNNING') {
    return (
      <div className="mt-4 flex items-center gap-3 rounded-md border border-border bg-bg-sunken px-4 py-4 text-[13px] text-fg-muted">
        <span className="relative inline-flex h-5 w-5">
          <span className="absolute inset-0 rounded-full border-2 border-border" />
          <span className="spin absolute inset-0 rounded-full border-2 border-accent border-t-transparent" />
        </span>
        Checking the drop… This can take a few minutes — you can leave the page open.
      </div>
    );
  }

  if (check.status === 'FAILED') {
    return (
      <div className="mt-4 flex items-start gap-2 rounded-md border border-danger/30 bg-danger-soft px-3 py-2 text-[12.5px] text-danger">
        <Icon name="alert" size={14} className="mt-[1px] flex-none" />
        <span>{check.error ?? 'The check failed. Try again.'}</span>
      </div>
    );
  }

  // DONE
  const dropRate = toNum(check.dropRate);
  const ordered = check.orderedCount ?? 0;
  const refillNeeded = check.refillNeeded ?? 0;
  const color = dropRate <= 0 ? 'var(--success)' : dropRate < 50 ? 'var(--warn)' : 'var(--danger)';

  const refillPending = refillReq?.status === 'PENDING';
  const refillApproved = refillReq?.status === 'APPROVED';
  const refillRejected = refillReq?.status === 'REJECTED';
  const canRequest = !!check.canRefill && !refillPending && !refillApproved;

  return (
    <div className="mt-5 grid grid-cols-1 gap-5 md:grid-cols-[180px_1fr] md:items-center">
      <Donut
        progress={Math.min(1, Math.max(0, dropRate / 100))}
        size={180}
        stroke={14}
        color={color}
        label={`${dropRate}%`}
        sublabel="drop"
      />
      <div>
        <div className="grid grid-cols-2 gap-3 sm:grid-cols-4">
          {[
            ['Ordered', fmtInt(ordered)],
            ['Still there', check.present != null ? fmtInt(check.present) : '—'],
            ['Refill needed', fmtInt(refillNeeded)],
            ['Type', (check.actionType ?? '—').toUpperCase()],
          ].map(([k, v]) => (
            <div key={k} className="rounded-md border border-border bg-bg-sunken p-3">
              <div className="text-[10.5px] uppercase tracking-wider text-fg-subtle">{k}</div>
              <div className="mt-1 font-mono text-[14px] tabular-nums">{v}</div>
            </div>
          ))}
        </div>

        {check.earlyStopped && (
          <div className="mt-3 flex items-start gap-2 rounded-md border border-warn/30 bg-warn-soft px-3 py-2 text-[12px] text-warn">
            <Icon name="warning" size={13} className="mt-[1px] flex-none" />
            <span>Scan was conservative — the real drop may be higher.</span>
          </div>
        )}

        {/* Existing request state for this order */}
        {refillReq && (
          <div
            className={cn(
              'mt-3 flex items-start gap-2 rounded-md px-3 py-2 text-[12.5px]',
              refillPending && 'bg-info-soft text-info',
              refillApproved && 'bg-success-soft text-success',
              refillRejected && 'bg-warn-soft text-warn',
            )}
          >
            <Icon
              name={refillPending ? 'info' : refillApproved ? 'check' : 'warning'}
              size={13}
              className="mt-[1px] flex-none"
            />
            <span>
              {refillPending && 'A refill request is already submitted and awaiting operator review.'}
              {refillApproved &&
                `Refill approved${refillReq.refillOrderId ? ` — order #${refillReq.refillOrderId}` : ''}.`}
              {refillRejected &&
                `Previous request rejected${refillReq.rejectionReason ? `: ${refillReq.rejectionReason}` : ''}. You can request again.`}
            </span>
          </div>
        )}

        <div className="mt-4">
          {check.canRefill ? (
            <Button
              variant="primary"
              size="md"
              icon="refresh"
              onClick={onRequest}
              disabled={!canRequest}
              title={
                refillPending
                  ? 'Request already under review'
                  : refillApproved
                    ? 'Refill already approved'
                    : undefined
              }
            >
              {refillPending
                ? 'Refill pending'
                : refillApproved
                  ? 'Refill approved'
                  : `Request refill (${fmtInt(refillNeeded)})`}
            </Button>
          ) : (
            <Badge tone="success" size="md" dot>
              No drop detected — nothing to refill
            </Badge>
          )}
        </div>
      </div>
    </div>
  );
}

function RequestStatusBadge({ status }: { status: RefillRequest['status'] }) {
  return (
    <Badge tone={status === 'PENDING' ? 'info' : status === 'APPROVED' ? 'success' : 'warn'} size="sm">
      {status === 'PENDING' ? 'Pending' : status === 'APPROVED' ? 'Approved' : 'Rejected'}
    </Badge>
  );
}
