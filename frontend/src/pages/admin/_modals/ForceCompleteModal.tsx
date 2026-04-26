import { useState } from 'react';
import { Button, Field, Modal, Money, Textarea, useToast } from '../../../components/ui';
import { adminAPI } from '../../../services/api';
import { useAdminActions } from '../../../store/adminActions';
import type { Order } from '../../../types';
import { fmtInt } from '../../../lib/utils';
import { HighValueGuard, isGuardCleared } from './HighValueGuard';

interface ForceCompleteModalProps {
  open: boolean;
  order: Order | null;
  onClose: () => void;
  onSuccess: (updated: Partial<Order> & { id: number }) => void;
}

// Force-complete is the most destructive panel action: it flips status to
// COMPLETED, signals the bot to stop, and books the order's full charge as
// profit — irreversible. We require a reason (audit) and, for orders over
// $50, the operator must retype the order ID via HighValueGuard before the
// confirm button unlocks.
export function ForceCompleteModal({ open, order, onClose, onSuccess }: ForceCompleteModalProps) {
  const toast = useToast();
  const pushAction = useAdminActions((s) => s.push);

  const [reason, setReason] = useState('');
  const [guard, setGuard] = useState('');
  const [submitting, setSubmitting] = useState(false);

  if (!order) return null;

  const reasonValid = reason.trim().length >= 10;
  const highValue = order.charge > 50;
  const guardOK = isGuardCleared(highValue, String(order.id), guard);
  const canSubmit = reasonValid && guardOK && !submitting;

  const submit = async () => {
    if (!canSubmit) return;
    setSubmitting(true);
    try {
      await adminAPI.performOrderAction(order.id, { action: 'force_complete', reason });
      pushAction({
        action: 'order.force_complete',
        target: 'order:' + order.id,
        targetLabel: 'Order #' + order.id,
        amount: order.charge,
        summary: `Force-completed Order #${order.id} · ${reason}`,
      });
      toast(`Order #${order.id} force-completed`, 'success');
      onSuccess({ id: order.id, status: 'COMPLETED', completed: order.quantity });
      reset();
      onClose();
    } catch (err: unknown) {
      const e = err as { response?: { data?: { message?: string } } };
      toast(e.response?.data?.message ?? 'Force complete failed.', 'error');
    } finally {
      setSubmitting(false);
    }
  };

  const reset = () => {
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
      width={520}
      title={
        <span>
          Force-complete Order <span className="font-mono">#{order.id}</span>
        </span>
      }
      subtitle="Marks the order as fully delivered and books the full charge as profit. Cannot be undone."
      footer={
        <>
          <Button variant="ghost" onClick={onClose} disabled={submitting}>
            Cancel
          </Button>
          <Button variant="success" onClick={submit} disabled={!canSubmit} loading={submitting}>
            Force complete
          </Button>
        </>
      }
    >
      <div className="grid grid-cols-2 gap-3 rounded-md border border-border bg-bg-sunken p-3 sm:grid-cols-4">
        <Snapshot k="User" v={'#' + order.userId} />
        <Snapshot k="Service" v={order.service?.name ?? order.serviceName ?? '—'} />
        <Snapshot k="Quantity" v={fmtInt(order.quantity)} mono />
        <Snapshot k="Charge" v={<Money value={order.charge} />} />
      </div>

      <Field
        label="Reason (visible in operator audit)"
        hint={reason.length > 0 && reason.length < 10 ? `${reason.length}/10 chars min` : undefined}
        error={reason.length > 0 && !reasonValid}
        className="mt-4"
      >
        <Textarea
          block
          rows={3}
          value={reason}
          onChange={(e) => setReason(e.target.value)}
          placeholder="Confirmed delivery via Instagram screenshot · webhook lost in transit · …"
        />
      </Field>

      <HighValueGuard active={highValue} expected={String(order.id)} value={guard} onChange={setGuard} />
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
