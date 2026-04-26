import { useEffect, useState } from 'react';
import {
  Button,
  Card,
  Field,
  Icon,
  Input,
  Money,
  StatusBadge,
  useToast,
} from '../../components/ui';
import { balanceAPI, depositsAPI } from '../../services/api';
import { useAuthStore } from '../../store/authStore';
import type { Deposit } from '../../types';
import { fmtMoney, fmtRel } from '../../lib/utils';

// =====================================================================
// Add Funds — minimal flow:
//   1. Pick amount (preset chips or manual input)
//   2. Click Continue → POST /v1/deposits/create
//   3. Backend returns the Cryptomus checkout URL — we redirect
//   4. Cryptomus collects choice of coin + payment; webhook credits balance
//
// Below the form: recent deposits with status (auto-refreshed every 15s).
// =====================================================================

const PRESETS = [10, 25, 50, 100, 250, 500];
const MIN = 5;
const MAX = 10000;

export function AddFundsPage() {
  const toast = useToast();
  const updateBalance = useAuthStore((s) => s.updateBalance);
  const [amount, setAmount] = useState<number>(50);
  const [submitting, setSubmitting] = useState(false);
  const [recent, setRecent] = useState<Deposit[]>([]);
  const [loadingRecent, setLoadingRecent] = useState(true);

  const valid = amount >= MIN && amount <= MAX;

  // Pull recent deposits + balance, then poll every 15s while the page is open.
  useEffect(() => {
    let cancelled = false;
    const tick = async () => {
      try {
        const [deps, bal] = await Promise.allSettled([depositsAPI.recent(), balanceAPI.get()]);
        if (cancelled) return;
        if (deps.status === 'fulfilled') {
          const v = deps.value as unknown;
          const arr: Deposit[] = Array.isArray(v) ? (v as Deposit[]) : (v as { content?: Deposit[] })?.content ?? [];
          setRecent(arr);
        }
        if (bal.status === 'fulfilled') {
          const b = bal.value as { balance?: number };
          if (typeof b.balance === 'number') updateBalance(b.balance);
        }
      } finally {
        if (!cancelled) setLoadingRecent(false);
      }
    };
    tick();
    const id = window.setInterval(tick, 15000);
    return () => {
      cancelled = true;
      clearInterval(id);
    };
  }, [updateBalance]);

  const submit = async () => {
    if (!valid) return;
    setSubmitting(true);
    try {
      const res = (await depositsAPI.create(amount)) as Record<string, unknown>;
      const url = (res.paymentUrl ?? res.url ?? res.checkoutUrl ?? res.payment_url) as string | undefined;
      if (url) {
        // Hand off to Cryptomus — they pick coin + collect payment.
        window.location.href = url;
        return;
      }
      toast('Deposit created. Refresh to see status below.', 'success');
    } catch (err: unknown) {
      const e = err as { response?: { data?: { message?: string } } };
      toast(e.response?.data?.message ?? 'Could not create deposit.', 'error');
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <div className="container-narrow py-10">
      <div className="eyebrow">Top up</div>
      <h1 className="text-[28px] font-bold tracking-[-0.02em]">Add funds</h1>
      <p className="mt-1 text-[14px] text-fg-muted">
        Crypto only — Cryptomus handles the coin choice on the next page. Min ${MIN}, max ${MAX.toLocaleString()}.
      </p>

      <div className="mt-8 grid grid-cols-1 gap-6 lg:grid-cols-[1.4fr_1fr]">
        {/* Amount picker */}
        <Card className="p-7">
          <Field label="Amount in USD" hint={!valid ? `Min $${MIN} · max $${MAX.toLocaleString()}` : undefined} error={!valid && amount > 0}>
            <div className="relative">
              <span className="pointer-events-none absolute left-5 top-1/2 -translate-y-1/2 font-mono text-[34px] font-bold tabular-nums text-fg-subtle">
                $
              </span>
              <input
                type="number"
                inputMode="decimal"
                value={amount || ''}
                min={MIN}
                max={MAX}
                step="1"
                onChange={(e) => setAmount(Math.max(0, Number(e.target.value) || 0))}
                className="block h-[80px] w-full rounded-lg border border-border-strong bg-bg-elev pl-12 pr-5 font-mono text-[34px] font-bold tabular-nums outline-none focus-visible:ring-2 focus-visible:[--tw-ring-color:var(--ring)]"
                placeholder="50"
              />
            </div>
          </Field>

          <div className="mt-5">
            <div className="mb-2 text-[12px] uppercase tracking-wider text-fg-subtle">Quick amounts</div>
            <div className="flex flex-wrap gap-2">
              {PRESETS.map((p) => (
                <button
                  key={p}
                  type="button"
                  onClick={() => setAmount(p)}
                  className={`inline-flex h-[36px] items-center rounded-md border px-4 font-mono text-[13.5px] font-medium transition-colors ${
                    amount === p
                      ? 'border-accent bg-accent-soft text-accent-fg'
                      : 'border-border-strong bg-bg-elev text-fg-muted hover:bg-bg-sunken'
                  }`}
                >
                  ${p}
                </button>
              ))}
            </div>
          </div>

          <div className="mt-7 rounded-lg border border-border bg-bg-sunken p-4">
            <div className="flex items-center gap-2 text-[12.5px] font-medium">
              <Icon name="info" size={14} className="text-accent" />
              How payment works
            </div>
            <p className="mt-1 text-[12.5px] leading-relaxed text-fg-muted">
              We hand off to Cryptomus, our payment processor. There you'll pick a coin, get a
              wallet address, and a 20-minute timer. Once the network confirms, your balance
              updates here automatically.
            </p>
          </div>

          <Button
            variant="primary"
            size="xl"
            block
            className="mt-6"
            disabled={!valid}
            loading={submitting}
            onClick={submit}
            iconRight="arrow-right"
          >
            Continue · {fmtMoney(amount)}
          </Button>
          <p className="mt-2 text-center text-[11px] text-fg-subtle">
            You'll be redirected to <span className="font-mono">cryptomus.com</span> to complete payment.
          </p>
        </Card>

        {/* Why crypto + FAQ */}
        <Card className="p-6">
          <div className="text-[14px] font-semibold">Why crypto only?</div>
          <ul className="mt-3 space-y-3 text-[13px] text-fg-muted">
            {[
              ['Zero chargebacks', 'No card disputes mean lower fraud cost — passed back to you in lower rates.'],
              ['No KYC for normal volume', 'EU AML thresholds apply only above €15k/year. See the AML policy.'],
              ['Fast settlement', 'USDT TRC-20 confirms in ~1 min. BTC takes 10–30. Funds credit on confirm.'],
              ['Privacy', 'We don\'t see card details. Cryptomus only sees the wallet address.'],
            ].map(([h, b]) => (
              <li key={h} className="flex gap-3">
                <span className="mt-1 inline-flex h-[16px] w-[16px] flex-none items-center justify-center rounded-full bg-success-soft text-success">
                  <Icon name="check" size={10} />
                </span>
                <div>
                  <div className="font-medium text-fg">{h}</div>
                  <div className="mt-0.5 text-[12.5px] text-fg-muted">{b}</div>
                </div>
              </li>
            ))}
          </ul>
        </Card>
      </div>

      {/* Recent deposits */}
      <Card className="mt-8 p-0">
        <div className="flex items-center justify-between border-b border-border px-5 py-3">
          <div className="text-[14px] font-semibold">Recent deposits</div>
          <span className="font-mono text-[11px] text-fg-subtle">refreshes every 15s</span>
        </div>
        {loadingRecent ? (
          <div className="p-12 text-center text-[13px] text-fg-subtle">Loading…</div>
        ) : recent.length === 0 ? (
          <div className="p-12 text-center text-[13px] text-fg-subtle">No deposits yet — make your first one above.</div>
        ) : (
          <table className="tbl-u">
            <thead>
              <tr>
                <th>ID</th>
                <th className="text-right">Amount</th>
                <th>Status</th>
                <th>Created</th>
                <th>Tx hash</th>
              </tr>
            </thead>
            <tbody>
              {recent.map((d) => (
                <tr key={d.id}>
                  <td className="font-mono text-[12px]">#{d.id}</td>
                  <td className="text-right">
                    <Money value={d.amount} />
                  </td>
                  <td>
                    <StatusBadge status={String(d.status).toLowerCase()} />
                  </td>
                  <td className="font-mono text-[12px] text-fg-muted">{fmtRel(d.createdAt)}</td>
                  <td className="font-mono text-[12px] text-fg-muted">
                    {d.txHash ? d.txHash.slice(0, 10) + '…' : '—'}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </Card>
    </div>
  );
}
