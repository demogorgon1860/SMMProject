import { useCallback, useEffect, useState } from 'react';
import {
  Badge,
  Button,
  Card,
  ConfirmModal,
  Donut,
  Icon,
  Input,
  PageHeader,
  Tabs,
  useToast,
} from '../../components/ui';
import { adminAPI } from '../../services/api';
import { useAdminActions } from '../../store/adminActions';
import type { RefillCheck } from '../../types';
import { fmtInt, toNum } from '../../lib/utils';
import { AdminRefillQueue } from './RefillRequests';

// =====================================================================
// Admin Refill — one page, two tabs:
//   • "Drop check" — check ANY order by id, then create a refill for
//     exactly the dropped amount (admin is the approver).
//   • "Request queue" — the user-initiated refill request queue
//     (embedded; approve/reject with drop rate + refill-needed columns).
// =====================================================================

const POLL_MS = 3500;
type Tab = 'check' | 'queue';

function errMsg(err: unknown, fallback: string): string {
  const e = err as { response?: { data?: { message?: string }; status?: number } };
  if (e.response?.data?.message) return e.response.data.message;
  if (e.response?.status === 404) return 'Order not found.';
  return fallback;
}

export function AdminRefillPage() {
  const [tab, setTab] = useState<Tab>('check');

  return (
    <>
      <PageHeader title="Refill" subtitle="Drop check and the refill request queue" />
      <div className="space-y-4 p-6">
        <Tabs
          value={tab}
          onChange={(v) => setTab(v as Tab)}
          tabs={[
            { value: 'check', label: 'Drop check' },
            { value: 'queue', label: 'Request queue' },
          ]}
        />
        {tab === 'check' ? <CheckTool /> : <AdminRefillQueue />}
      </div>
    </>
  );
}

// ---------------------------------------------------------------------
// Drop-check tool (admin: check any order, refill the dropped amount)
// ---------------------------------------------------------------------

function CheckTool() {
  const toast = useToast();
  const pushAction = useAdminActions((s) => s.push);

  const [orderInput, setOrderInput] = useState('');
  const [activeOrderId, setActiveOrderId] = useState<number | null>(null);
  const [check, setCheck] = useState<RefillCheck | null>(null);
  const [checkError, setCheckError] = useState<string | null>(null);
  const [starting, setStarting] = useState(false);
  const [confirmOpen, setConfirmOpen] = useState(false);
  const [creating, setCreating] = useState(false);
  const [doneMsg, setDoneMsg] = useState<string | null>(null);

  const running = check?.status === 'RUNNING' && activeOrderId != null;
  useEffect(() => {
    if (!running || activeOrderId == null) return;
    const t = setInterval(async () => {
      try {
        const res: RefillCheck = await adminAPI.refillCheckGet(activeOrderId);
        setCheck(res);
      } catch {
        // transient — keep polling
      }
    }, POLL_MS);
    return () => clearInterval(t);
  }, [running, activeOrderId]);

  const runCheck = useCallback(async () => {
    const id = Number(orderInput.trim());
    if (!Number.isInteger(id) || id <= 0) {
      toast('Enter a valid order number', 'error');
      return;
    }
    setStarting(true);
    setCheckError(null);
    setCheck(null);
    setDoneMsg(null);
    setActiveOrderId(id);
    try {
      const res: RefillCheck = await adminAPI.refillCheckStart(id);
      setCheck(res);
    } catch (err) {
      setCheckError(errMsg(err, 'Could not start the drop check.'));
    } finally {
      setStarting(false);
    }
  }, [orderInput, toast]);

  const createRefill = async () => {
    if (activeOrderId == null || check?.refillNeeded == null) return;
    setCreating(true);
    try {
      const qty = check.refillNeeded;
      await adminAPI.refillOrderDirect(activeOrderId, qty);
      pushAction({
        action: 'order.refill',
        target: 'order:' + activeOrderId,
        targetLabel: 'Order #' + activeOrderId,
        summary: `Created drop-based refill of ${qty} on order #${activeOrderId}`,
      });
      toast(`Refill of ${fmtInt(qty)} created · order #${activeOrderId}`, 'success');
      setConfirmOpen(false);
      setDoneMsg(`Refill created for ${fmtInt(qty)}.`);
      setCheck(null);
    } catch (err) {
      toast(errMsg(err, 'Could not create the refill.'), 'error');
    } finally {
      setCreating(false);
    }
  };

  return (
    <Card className="p-5">
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

      {doneMsg && (
        <div className="mt-4 flex items-start gap-2 rounded-md border border-success/30 bg-success-soft px-3 py-2 text-[12.5px] text-success">
          <Icon name="check" size={14} className="mt-[1px] flex-none" />
          <span>{doneMsg}</span>
        </div>
      )}

      {checkError && (
        <div className="mt-4 flex items-start gap-2 rounded-md border border-danger/30 bg-danger-soft px-3 py-2 text-[12.5px] text-danger">
          <Icon name="alert" size={14} className="mt-[1px] flex-none" />
          <span>{checkError}</span>
        </div>
      )}

      {check?.status === 'RUNNING' && (
        <div className="mt-4 flex items-center gap-3 rounded-md border border-border bg-bg-sunken px-4 py-4 text-[13px] text-fg-muted">
          <span className="relative inline-flex h-5 w-5">
            <span className="absolute inset-0 rounded-full border-2 border-border" />
            <span className="spin absolute inset-0 rounded-full border-2 border-accent border-t-transparent" />
          </span>
          Checking the drop… This can take a few minutes.
        </div>
      )}

      {check?.status === 'FAILED' && (
        <div className="mt-4 flex items-start gap-2 rounded-md border border-danger/30 bg-danger-soft px-3 py-2 text-[12.5px] text-danger">
          <Icon name="alert" size={14} className="mt-[1px] flex-none" />
          <span>{check.error ?? 'The check failed.'}</span>
        </div>
      )}

      {check?.status === 'DONE' && <DoneResult check={check} onCreate={() => setConfirmOpen(true)} />}

      <ConfirmModal
        open={confirmOpen}
        onClose={() => setConfirmOpen(false)}
        onConfirm={createRefill}
        loading={creating}
        variant="primary"
        confirmText="Create refill"
        cancelText="Cancel"
        title={`Create refill on order #${activeOrderId ?? ''}?`}
        message={
          check?.refillNeeded != null
            ? `This creates a free refill of ${fmtInt(check.refillNeeded)} (the dropped amount).`
            : undefined
        }
      />
    </Card>
  );
}

function DoneResult({ check, onCreate }: { check: RefillCheck; onCreate: () => void }) {
  const dropRate = toNum(check.dropRate);
  const ordered = check.orderedCount ?? 0;
  const refillNeeded = check.refillNeeded ?? 0;
  const color = dropRate <= 0 ? 'var(--success)' : dropRate < 50 ? 'var(--warn)' : 'var(--danger)';

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

        <div className="mt-4">
          {check.canRefill ? (
            <Button variant="primary" size="md" icon="refresh" onClick={onCreate}>
              {`Create refill for ${fmtInt(refillNeeded)}`}
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
