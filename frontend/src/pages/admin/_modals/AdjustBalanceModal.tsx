import { useState } from 'react';
import { Button, Checkbox, Field, Input, Modal, Money, Textarea, useToast } from '../../../components/ui';
import { adminAPI } from '../../../services/api';
import { useAdminActions } from '../../../store/adminActions';
import { cn, fmtMoney } from '../../../lib/utils';
import { HighValueGuard, isGuardCleared } from './HighValueGuard';

interface User {
  id: number;
  email: string;
  username?: string;
  balance?: number;
}

interface AdjustBalanceModalProps {
  open: boolean;
  user: User | null;
  onClose: () => void;
  onSuccess: (delta: number) => void;
}

// Manually credit or debit a user's wallet. Above $500 the high-value
// guard demands the user ID be retyped. Cannot debit below zero.
export function AdjustBalanceModal({ open, user, onClose, onSuccess }: AdjustBalanceModalProps) {
  const toast = useToast();
  const pushAction = useAdminActions((s) => s.push);

  const [direction, setDirection] = useState<'credit' | 'debit'>('credit');
  const [amount, setAmount] = useState<number>(0);
  const [reason, setReason] = useState('');
  const [notify, setNotify] = useState(true);
  const [guard, setGuard] = useState('');
  const [submitting, setSubmitting] = useState(false);

  if (!user) return null;

  const balance = user.balance ?? 0;
  const signed = direction === 'credit' ? amount : -amount;
  const newBalance = balance + signed;
  const amountValid = amount > 0;
  const reasonValid = reason.trim().length >= 10;
  const overdraft = direction === 'debit' && newBalance < 0;
  const highValue = amount > 500;
  const guardOK = isGuardCleared(highValue, String(user.id), guard);
  const canSubmit = amountValid && reasonValid && !overdraft && guardOK && !submitting;

  const submit = async () => {
    if (!canSubmit) return;
    setSubmitting(true);
    try {
      await adminAPI.adjustUserBalance(user.id, signed, reason);
      pushAction({
        action: 'balance.manual_adjust',
        target: 'user:' + user.id,
        targetLabel: '@' + (user.username ?? user.email),
        amount: signed,
        summary: `${direction === 'credit' ? 'Credited' : 'Debited'} ${fmtMoney(amount)} ${direction === 'credit' ? 'to' : 'from'} @${user.username ?? user.email} · ${reason}`,
      });
      toast(`Balance ${direction === 'credit' ? 'credited' : 'debited'} · ${fmtMoney(amount)}`, 'success');
      onSuccess(signed);
      reset();
      onClose();
    } catch (err: unknown) {
      const e = err as { response?: { data?: { message?: string } } };
      toast(e.response?.data?.message ?? 'Balance adjust failed.', 'error');
    } finally {
      setSubmitting(false);
    }
  };

  const reset = () => {
    setDirection('credit');
    setAmount(0);
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
      width={540}
      title={
        <span>
          Adjust balance for <span className="text-accent">{user.email}</span>
        </span>
      }
      subtitle="Logged as a MANUAL_ADJUST balance transaction. Cannot be undone."
      footer={
        <>
          <Button variant="ghost" onClick={onClose} disabled={submitting}>
            Cancel
          </Button>
          <Button variant="primary" onClick={submit} disabled={!canSubmit} loading={submitting}>
            Confirm adjustment
          </Button>
        </>
      }
    >
      {/* Balance preview */}
      <div className="rounded-md border border-border bg-bg-sunken p-4">
        <div className="grid grid-cols-2 gap-4">
          <div>
            <div className="text-[10.5px] uppercase tracking-wider text-fg-subtle">Current</div>
            <div className="mt-1">
              <Money value={balance} size="lg" />
            </div>
          </div>
          {amountValid && (
            <div className="fade-in border-l border-border pl-4">
              <div className="text-[10.5px] uppercase tracking-wider text-fg-subtle">After</div>
              <div className="mt-1">
                <Money value={newBalance} size="lg" color={overdraft ? 'var(--danger)' : undefined} />
              </div>
              <div className={cn('mt-1 font-mono text-[11.5px]', overdraft ? 'text-danger' : 'text-fg-subtle')}>
                {signed >= 0 ? '+' : ''}
                {fmtMoney(signed)}
              </div>
            </div>
          )}
        </div>
      </div>

      {/* Direction toggle */}
      <div className="mt-4 inline-flex rounded-md bg-bg-sunken p-[3px]">
        <button
          type="button"
          onClick={() => setDirection('credit')}
          className={cn(
            'rounded px-3 py-1 text-[12.5px] font-medium',
            direction === 'credit' ? 'bg-success-soft text-success shadow-sm' : 'text-fg-muted hover:text-fg',
          )}
        >
          Credit (+)
        </button>
        <button
          type="button"
          onClick={() => setDirection('debit')}
          className={cn(
            'rounded px-3 py-1 text-[12.5px] font-medium',
            direction === 'debit' ? 'bg-danger-soft text-danger shadow-sm' : 'text-fg-muted hover:text-fg',
          )}
        >
          Debit (−)
        </button>
      </div>

      <Field label="Amount (USD)" className="mt-4" error={overdraft}>
        <div className="relative">
          <span className="pointer-events-none absolute left-3 top-1/2 -translate-y-1/2 font-mono text-fg-subtle">$</span>
          <Input
            block
            inputSize="lg"
            type="number"
            min={0}
            step="0.01"
            value={amount || ''}
            onChange={(e) => setAmount(Math.max(0, Number(e.target.value) || 0))}
            className="pl-7 font-mono"
          />
        </div>
      </Field>
      {overdraft && (
        <div className="mb-3 -mt-2 text-[12px] text-danger">
          This would put balance at {fmtMoney(newBalance)} (negative). Reduce the amount.
        </div>
      )}

      <Field label="Reason" hint={reason.length > 0 && reason.length < 10 ? `${reason.length}/10 chars min` : undefined} error={reason.length > 0 && !reasonValid}>
        <Textarea
          block
          rows={3}
          value={reason}
          onChange={(e) => setReason(e.target.value)}
          placeholder="Goodwill credit · ticket #8821 · webhook miss · duplicate charge · …"
        />
      </Field>

      <label className="mt-1 flex items-center gap-2 text-[12.5px] text-fg-muted">
        <Checkbox checked={notify} onChange={(e) => setNotify(e.target.checked)} />
        Notify user by email
      </label>

      <HighValueGuard active={highValue} expected={String(user.id)} value={guard} onChange={setGuard} />
    </Modal>
  );
}
