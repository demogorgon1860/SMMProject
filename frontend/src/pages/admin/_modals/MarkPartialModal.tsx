import { useState } from 'react';
import { Button, Field, Input, Modal, Money, Textarea, useToast } from '../../../components/ui';
import { adminAPI } from '../../../services/api';
import { useAdminActions } from '../../../store/adminActions';
import type { Order } from '../../../types';
import { fmtInt, fmtMoney } from '../../../lib/utils';
import { HighValueGuard, isGuardCleared } from './HighValueGuard';

interface MarkPartialModalProps {
  open: boolean;
  order: Order | null;
  onClose: () => void;
  onSuccess: (updated: Partial<Order> & { id: number }) => void;
}

// Mark an order as PARTIAL — capture how many were actually delivered,
// refund the rest, log a reason. Refund > $100 triggers HighValueGuard
// (operator must retype the order ID).
export function MarkPartialModal({ open, order, onClose, onSuccess }: MarkPartialModalProps) {
  const toast = useToast();
  const pushAction = useAdminActions((s) => s.push);

  const [delivered, setDelivered] = useState(0);
  const [reason, setReason] = useState('');
  const [guard, setGuard] = useState('');
  const [submitting, setSubmitting] = useState(false);

  if (!order) return null;

  const rate = order.charge / Math.max(1, order.quantity);
  const refundQty = Math.max(0, order.quantity - delivered);
  const refund = +(rate * refundQty).toFixed(4);
  const newCharge = +(order.charge - refund).toFixed(4);
  const deliveredValid = delivered >= 1 && delivered < order.quantity;
  const reasonValid = reason.trim().length >= 10;
  const highValue = refund > 100;
  const guardOK = isGuardCleared(highValue, String(order.id), guard);
  const canSubmit = deliveredValid && reasonValid && guardOK && !submitting;

  const submit = async () => {
    if (!canSubmit) return;
    setSubmitting(true);
    try {
      await adminAPI.performOrderAction(order.id, {
        action: 'partial',
        reason,
        remains: refundQty,
      });
      pushAction({
        action: 'order.mark_partial',
        target: 'order:' + order.id,
        targetLabel: 'Order #' + order.id,
        amount: -refund,
        summary: `Marked Order #${order.id} as partial · delivered ${fmtInt(delivered)}/${fmtInt(order.quantity)} · refund ${fmtMoney(refund)}`,
      });
      toast(`Order #${order.id} marked partial · ${fmtMoney(refund)} refunded`, 'success');
      onSuccess({ id: order.id, status: 'PARTIAL', completed: delivered });
      reset();
      onClose();
    } catch (err: unknown) {
      const e = err as { response?: { data?: { message?: string } } };
      toast(e.response?.data?.message ?? 'Mark-partial failed.', 'error');
    } finally {
      setSubmitting(false);
    }
  };

  const reset = () => {
    setDelivered(0);
    setReason('');
    setGuard('');
  };

  return (
    <Modal
      open={open}
      onClose={() => {
        if (submitting) return;
        reset();
        onClose();
      }}
      width={560}
      title={
        <span>
          Mark order <span className="font-mono">#{order.id}</span> as Partial
        </span>
      }
      subtitle="Refund credits to user wallet and is logged in balance audit."
      footer={
        <>
          <Button variant="ghost" onClick={onClose} disabled={submitting}>
            Cancel
          </Button>
          <Button variant="warn" onClick={submit} disabled={!canSubmit} loading={submitting}>
            Confirm Partial
          </Button>
        </>
      }
    >
      {/* Snapshot strip */}
      <div className="grid grid-cols-2 gap-3 rounded-md border border-border bg-bg-sunken p-3 sm:grid-cols-4">
        <Snapshot k="User" v={'#' + order.userId} />
        <Snapshot k="Service" v={order.service?.name ?? order.serviceName ?? '—'} />
        <Snapshot k="Quantity" v={fmtInt(order.quantity)} mono />
        <Snapshot k="Charge" v={fmtMoney(order.charge)} mono />
      </div>

      <Field
        label="Delivered quantity"
        hint={`Valid range: 1 — ${fmtInt(order.quantity - 1)}`}
        error={delivered > 0 && !deliveredValid}
        className="mt-4"
      >
        <Input
          block
          inputSize="lg"
          type="number"
          autoFocus
          min={1}
          max={order.quantity - 1}
          value={delivered || ''}
          onChange={(e) => setDelivered(Math.max(0, Math.floor(Number(e.target.value) || 0)))}
        />
      </Field>

      {deliveredValid && (
        <div className="mb-4 rounded-md border border-accent-soft bg-accent-soft p-3 font-mono text-[12.5px]">
          <div className="flex justify-between">
            <span className="text-fg-muted">Delivered</span>
            <span className="font-semibold">
              {fmtInt(delivered)} / {fmtInt(order.quantity)} ({((delivered / order.quantity) * 100).toFixed(1)}%)
            </span>
          </div>
          <div className="mt-1 flex justify-between">
            <span className="text-fg-muted">Refund</span>
            <span className="font-semibold text-danger">
              {fmtMoney(rate)} × {fmtInt(refundQty)} = −{fmtMoney(refund)}
            </span>
          </div>
          <div className="mt-1 flex justify-between">
            <span className="text-fg-muted">New order charge</span>
            <span className="font-semibold text-success">{fmtMoney(newCharge)}</span>
          </div>
        </div>
      )}

      <Field
        label="Reason (visible in balance audit)"
        hint={reason.length > 0 && reason.length < 10 ? `${reason.length}/10 chars min` : undefined}
        error={reason.length > 0 && !reasonValid}
      >
        <Textarea
          block
          rows={3}
          value={reason}
          onChange={(e) => setReason(e.target.value)}
          placeholder="Target account went private mid-run · profile pool exhausted · …"
        />
      </Field>

      <HighValueGuard active={highValue} expected={String(order.id)} value={guard} onChange={setGuard} />

      <p className="mt-3 text-[11.5px] text-fg-subtle">
        This refunds <Money value={refund} /> to the user and posts a MANUAL_ADJUST balance transaction.
      </p>
    </Modal>
  );
}

function Snapshot({ k, v, mono }: { k: string; v: React.ReactNode; mono?: boolean }) {
  return (
    <div>
      <div className="text-[10.5px] uppercase tracking-wider text-fg-subtle">{k}</div>
      <div className={`mt-0.5 text-[13px] font-medium ${mono ? 'font-mono tabular-nums' : ''}`}>{v}</div>
    </div>
  );
}
