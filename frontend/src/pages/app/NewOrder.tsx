import { useEffect, useMemo, useState } from 'react';
import { Link } from 'react-router-dom';
import {
  Badge,
  Button,
  Card,
  Field,
  Icon,
  Input,
  Money,
  SocialTile,
  Textarea,
  useToast,
} from '../../components/ui';
import { balanceAPI, orderAPI, serviceAPI } from '../../services/api';
import { useAuthStore } from '../../store/authStore';
import type { BalanceSummary, Service } from '../../types';
import { cn, fmtInt, fmtMoney, toNum } from '../../lib/utils';
import { unwrapList } from '../../lib/api';

// =====================================================================
// New Order — 3-column flow:
//   Col 1 — service picker (sticky)
//   Col 2 — selected service hero + quantity & link & options
//   Col 3 — receipt (sticky)
// All Instagram-only; other categories show as "soon".
// =====================================================================

const CATS = [
  { id: 'ig' as const, label: 'Instagram', live: true },
  { id: 'tt' as const, label: 'TikTok', live: false, eta: 'Q3 2026' },
  { id: 'x' as const, label: 'Twitter', live: false, eta: 'Q4 2026' },
  { id: 'tg' as const, label: 'Telegram', live: false, eta: 'Q4 2026' },
];

const PRESETS = [100, 250, 500, 1000, 2500, 5000];

export function NewOrderPage() {
  const toast = useToast();
  const updateBalance = useAuthStore((s) => s.updateBalance);
  // Backend serializes BigDecimal as string; coerce so balance arithmetic
  // and `Sufficient balance` check don't silently fail when the API returns
  // "1000.00" instead of 1000.
  const rawBalance = useAuthStore((s) => s.user?.balance);
  const balance = toNum(rawBalance);

  const [services, setServices] = useState<Service[]>([]);
  const [loading, setLoading] = useState(true);
  const [cat, setCat] = useState<'ig' | 'tt' | 'x' | 'tg'>('ig');
  const [search, setSearch] = useState('');
  const [selectedId, setSelectedId] = useState<number | null>(null);

  const [link, setLink] = useState('');
  const [qty, setQty] = useState<number>(1000);
  const [comments, setComments] = useState('');
  const [submitting, setSubmitting] = useState(false);

  useEffect(() => {
    serviceAPI
      .list()
      .then((data: unknown) => {
        // /v1/service/services wraps in PerfectPanelResponse `{ success, data: [...] }`; legacy
        // paths return `{ services: [...] }` or a Spring Page. unwrapList handles all.
        const arr = unwrapList<Service>(data, ['services']);
        const live = arr.filter((s) => s.active !== false && s.isActive !== false);
        setServices(live);
        if (live.length > 0 && selectedId == null) setSelectedId(live[0].id);
      })
      .catch(() => toast('Could not load services.', 'error'))
      .finally(() => setLoading(false));
  }, []); // eslint-disable-line react-hooks/exhaustive-deps

  const filtered = useMemo(
    () => services.filter((s) => (s.name?.toLowerCase() ?? '').includes(search.trim().toLowerCase())),
    [services, search],
  );
  const selected = services.find((s) => s.id === selectedId);

  const isCustom = useMemo(() => /custom/i.test(selected?.name ?? ''), [selected]);

  // Instagram caps a single comment at 2200 characters; longer comments would be
  // rejected at dispatch. Surface this constraint in the UI rather than letting the
  // bot fail the whole order downstream.
  const MAX_COMMENT_LENGTH = 2200;

  // Parse Custom-Comments textarea into validated lines.
  const commentLines = useMemo(() => {
    if (!isCustom) return [] as string[];
    return comments
      .split('\n')
      .map((l) => l.trim())
      .filter((l) => l.length > 0);
  }, [isCustom, comments]);
  const overLengthLines = useMemo(
    () => commentLines.filter((l) => l.length > MAX_COMMENT_LENGTH).length,
    [commentLines],
  );

  const rate = selected?.rate ?? selected?.pricePer1000 ?? selected?.pricePerThousand ?? 0;
  const min = selected?.min ?? selected?.minOrder ?? 50;
  const max = selected?.max ?? selected?.maxOrder ?? 100000;

  // For Custom-Comments services the quantity is the line count, not the number
  // typed into the (hidden) Quantity input. The bot dispatches one comment per
  // line in order, so quantity == comments.length is the only correct mapping.
  const effectiveQty = isCustom ? commentLines.length : qty;
  const charge = effectiveQty > 0 && rate > 0 ? (effectiveQty / 1000) * rate : 0;

  const checks = useMemo(() => {
    const list: Array<{ label: string; ok: boolean }> = [
      { label: 'Service selected', ok: !!selected },
      { label: 'Link provided', ok: link.trim().length > 6 },
    ];
    if (isCustom) {
      list.push({
        label: `Comments count in range (${fmtInt(min)}–${fmtInt(max)})`,
        ok: commentLines.length >= min && commentLines.length <= max,
      });
      list.push({
        label: `All comments under ${MAX_COMMENT_LENGTH.toLocaleString()} chars`,
        ok: overLengthLines === 0,
      });
    } else {
      list.push({
        label: `Quantity in range (${fmtInt(min)}–${fmtInt(max)})`,
        ok: qty >= min && qty <= max,
      });
    }
    list.push({ label: 'Sufficient balance', ok: charge <= balance });
    return list;
  }, [selected, link, qty, min, max, isCustom, commentLines, overLengthLines, charge, balance]);

  const allValid = checks.every((c) => c.ok);

  const placeOrder = async () => {
    // Hard guard against double-submit: a user double-clicking the button before React
    // re-renders with `submitting=true` would otherwise race past the `loading` check.
    if (!selected || !allValid || submitting) return;
    setSubmitting(true);
    // Stable per-attempt key. Reused on a retry of the SAME submission so the backend
    // returns the existing order (no double-charge) instead of creating a sibling.
    const idempotencyKey =
      (typeof crypto !== 'undefined' && 'randomUUID' in crypto
        ? crypto.randomUUID()
        : `${Date.now()}-${Math.random().toString(36).slice(2)}`);
    try {
      const created = await orderAPI.create(
        {
          service: selected.id,
          link: link.trim(),
          // Custom-Comments services derive quantity from the line count so it
          // can never desync from the comment list.
          quantity: effectiveQty,
          comments: isCustom ? comments : undefined,
        },
        idempotencyKey,
      );

      // Stay on /new-order and let the user place another. Toast carries
      // the new order id + a deep-link to its drawer so the action is still
      // discoverable. Bumped to 6s so users have time to click "View it".
      toast(
        <span>
          Order #{created.id} placed.{' '}
          <Link
            to={`/orders/${created.id}`}
            className="font-medium text-accent underline underline-offset-2 hover:text-accent-fg"
          >
            View it
          </Link>
        </span>,
        'success',
        6000,
      );

      // Reset just the inputs that are order-specific. Keep `selectedId` —
      // resellers typically place several orders for the same service.
      setLink('');
      setComments('');
      setQty(selected.min ?? selected.minOrder ?? 1000);

      // Re-fetch authoritative balance from the server (charge has just been
      // deducted). Optimistic update would drift over time when the backend
      // applies bonuses / refunds we don't model here. Failure is non-fatal:
      // the wallet chip still updates on the next dashboard load.
      balanceAPI
        .get()
        .then((b: BalanceSummary) => updateBalance(toNum(b.balance)))
        .catch(() => {
          /* keep stale balance — user can still operate */
        });
    } catch (err: unknown) {
      // Leave the form filled so the user can retry without re-typing.
      const e = err as { response?: { data?: { message?: string } } };
      toast(e.response?.data?.message ?? 'Could not place order.', 'error');
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <div className="container-app py-8">
      <h1 className="text-[24px] font-bold tracking-[-0.02em]">New order</h1>
      <p className="mt-1 text-[13px] text-fg-muted">Pick a service, paste your link, set the quantity. We dispatch on confirm.</p>

      <div className="mt-6 grid grid-cols-1 gap-6 lg:grid-cols-[300px_1fr_360px]">
        {/* COL 1 — service picker */}
        <Card className="p-0 lg:sticky lg:top-[64px] lg:max-h-[calc(100vh-92px)] lg:overflow-auto">
          <div className="border-b border-border p-3">
            <Input block icon="search" placeholder="Search services" value={search} onChange={(e) => setSearch(e.target.value)} />
          </div>
          <div className="flex flex-wrap gap-1 border-b border-border p-3">
            {CATS.map((c) => {
              const active = cat === c.id;
              return (
                <button
                  key={c.id}
                  type="button"
                  onClick={() => setCat(c.id)}
                  className={cn(
                    'inline-flex items-center gap-1 rounded-full border px-[10px] py-[3px] text-[11.5px]',
                    active ? 'border-accent bg-accent-soft text-accent-fg' : 'border-border text-fg-muted hover:bg-bg-sunken',
                  )}
                >
                  {c.label}
                  {!c.live && <span className="font-mono text-[10px] text-fg-dim">soon</span>}
                </button>
              );
            })}
          </div>

          <div>
            {cat !== 'ig' ? (
              <div className="p-6 text-center text-[13px] text-fg-subtle">
                {CATS.find((c) => c.id === cat)?.label} services launch{' '}
                {CATS.find((c) => c.id === cat)?.eta ?? 'soon'}.
              </div>
            ) : loading ? (
              <div className="p-6 text-center text-[13px] text-fg-subtle">Loading services…</div>
            ) : filtered.length === 0 ? (
              <div className="p-6 text-center text-[13px] text-fg-subtle">No services match.</div>
            ) : (
              <ul>
                {filtered.map((s) => {
                  const active = s.id === selectedId;
                  return (
                    <li key={s.id}>
                      <button
                        type="button"
                        onClick={() => setSelectedId(s.id)}
                        className={cn(
                          'group flex w-full items-start gap-3 border-b border-border px-4 py-3 text-left transition-colors last:border-b-0 hover:bg-bg-sunken',
                          active && 'bg-accent-soft hover:bg-accent-soft',
                        )}
                      >
                        <SocialTile cat="ig" size={32} />
                        <div className="min-w-0 flex-1">
                          {/* Name + price are stacked so the name gets the full row width and
                              wraps to two lines instead of getting clipped to "Instagram Custom Co…".
                              On wide viewports we still show the price on the right via items-start
                              + auto-margin so the visual hierarchy isn't lost. */}
                          <div className="flex items-start justify-between gap-2">
                            <div
                              className={cn(
                                'min-w-0 break-words text-[13px] font-medium leading-snug',
                                active && 'text-accent-fg',
                              )}
                            >
                              {s.name}
                            </div>
                            <span className="flex-none whitespace-nowrap font-mono text-[11.5px] text-fg-muted">
                              {fmtMoney(s.rate ?? s.pricePer1000 ?? s.pricePerThousand ?? 0)}/1k
                            </span>
                          </div>
                          <div className="mt-0.5 flex items-center gap-2 text-[10.5px] text-fg-subtle">
                            <span className="font-mono">SVC #{s.id}</span>
                            <span>·</span>
                            <span>
                              min {fmtInt(s.min ?? s.minOrder ?? 0)} / max {fmtInt(s.max ?? s.maxOrder ?? 0)}
                            </span>
                          </div>
                        </div>
                      </button>
                    </li>
                  );
                })}
              </ul>
            )}
          </div>
        </Card>

        {/* COL 2 — config */}
        <div className="space-y-4">
          {selected && (
            <Card className="p-6">
              <div className="flex items-start gap-4">
                <SocialTile cat="ig" size={56} />
                <div className="min-w-0">
                  <Badge tone="muted" size="sm">
                    SVC #{selected.id}
                  </Badge>
                  <div className="mt-2 text-[20px] font-semibold tracking-[-0.015em]">{selected.name}</div>
                  {selected.description && <p className="mt-1 text-[13px] text-fg-muted">{selected.description}</p>}
                </div>
                <div className="ml-auto whitespace-nowrap text-right">
                  <div className="font-mono text-[20px] font-bold tabular-nums">
                    {fmtMoney(selected.rate ?? selected.pricePer1000 ?? selected.pricePerThousand ?? 0)}
                  </div>
                  <div className="text-[11px] text-fg-subtle">per 1,000</div>
                </div>
              </div>
              <div className="mt-5 grid grid-cols-3 gap-3">
                {[
                  ['Min', fmtInt(min)],
                  ['Max', fmtInt(max)],
                  ['Refill', 'Lifetime'],
                ].map(([k, v]) => (
                  <div key={k as string} className="rounded-md border border-border bg-bg-sunken p-3 text-center">
                    <div className="text-[10.5px] uppercase tracking-wider text-fg-subtle">{k}</div>
                    <div className="mt-1 font-mono text-[14px] font-semibold tabular-nums">{v}</div>
                  </div>
                ))}
              </div>
            </Card>
          )}

          <Card className="p-6">
            <div className="text-[14px] font-semibold">Configure your order</div>
            <div className="mt-4 grid grid-cols-1 gap-x-6 md:grid-cols-2">
              <div className="md:col-span-2">
                <Field label="Target link" hint={link ? '' : 'Paste an Instagram URL'}>
                  <Input
                    block
                    inputSize="lg"
                    icon="link"
                    placeholder="https://instagram.com/p/CY9k3a/"
                    value={link}
                    onChange={(e) => setLink(e.target.value)}
                  />
                </Field>
              </div>
              {/* For Custom-Comments services Quantity is derived from the comment list
                  and the input is hidden — the user could only get the two out of sync
                  otherwise. Quick presets disappear for the same reason. */}
              {!isCustom && (
                <>
                  <Field label="Quantity" hint={`${fmtInt(min)}–${fmtInt(max)}`}>
                    <Input
                      block
                      inputSize="lg"
                      type="number"
                      min={min}
                      max={max}
                      value={qty}
                      onChange={(e) => setQty(Math.max(0, Math.floor(Number(e.target.value) || 0)))}
                    />
                  </Field>
                  <div>
                    <div className="mb-[6px] text-[12.5px] font-medium text-fg-muted">Quick presets</div>
                    <div className="flex flex-wrap gap-1">
                      {PRESETS.map((p) => (
                        <button
                          key={p}
                          type="button"
                          onClick={() => setQty(Math.max(min, Math.min(max, p)))}
                          className="rounded border border-border bg-bg-elev px-[8px] py-[4px] font-mono text-[12px] text-fg-muted hover:bg-bg-sunken"
                        >
                          {fmtInt(p)}
                        </button>
                      ))}
                    </div>
                  </div>
                </>
              )}
              {isCustom && (
                <div className="md:col-span-2">
                  <Field
                    label="Custom comments"
                    hint={
                      <span>
                        <span
                          className={cn(
                            'font-mono',
                            commentLines.length >= min && commentLines.length <= max
                              ? 'text-success'
                              : 'text-fg-muted',
                          )}
                        >
                          {fmtInt(commentLines.length)}
                        </span>{' '}
                        / {fmtInt(min)}–{fmtInt(max)} lines
                        {overLengthLines > 0 && (
                          <span className="ml-2 text-danger">
                            · {overLengthLines} over {MAX_COMMENT_LENGTH.toLocaleString()} chars
                          </span>
                        )}
                      </span>
                    }
                  >
                    <Textarea
                      block
                      rows={8}
                      value={comments}
                      onChange={(e) => setComments(e.target.value)}
                      placeholder="One comment per line. We'll dispatch them in order."
                    />
                  </Field>
                </div>
              )}
            </div>
          </Card>
        </div>

        {/* COL 3 — receipt */}
        <Card className="p-6 lg:sticky lg:top-[64px]">
          <div className="eyebrow">Receipt</div>
          <div className="mt-2">
            <Money value={charge} size="lg" />
            <span className="ml-1 text-[12px] text-fg-subtle">USD</span>
          </div>
          <div className="mt-4 space-y-1 text-[13px]">
            {selected ? (
              <KVRow k="Service" v={`SVC #${selected.id}`} mono />
            ) : (
              <KVRow k="Service" v="—" />
            )}
            <KVRow k="Quantity" v={fmtInt(effectiveQty)} mono />
            <KVRow
              k="Rate / 1k"
              v={fmtMoney(selected?.rate ?? selected?.pricePer1000 ?? selected?.pricePerThousand ?? 0)}
              mono
            />
          </div>

          <div className="mt-5 space-y-1.5 border-t border-border pt-4">
            {checks.map((c) => (
              <div key={c.label} className="flex items-center gap-2 text-[12.5px]">
                <span
                  className={cn(
                    'flex h-[14px] w-[14px] flex-none items-center justify-center rounded-full',
                    c.ok ? 'bg-success-soft text-success' : 'bg-bg-sunken text-fg-dim',
                  )}
                >
                  <Icon name={c.ok ? 'check' : 'x'} size={9} />
                </span>
                <span className={c.ok ? 'text-fg' : 'text-fg-muted'}>{c.label}</span>
              </div>
            ))}
          </div>

          <Button
            variant="primary"
            size="lg"
            block
            className="mt-5"
            disabled={!allValid || submitting}
            loading={submitting}
            onClick={placeOrder}
          >
            Place order · {fmtMoney(charge)}
          </Button>
          <p className="mt-2 text-center text-[11px] text-fg-subtle">
            Balance after: <span className="font-mono">${(balance - charge).toFixed(2)}</span>
          </p>
        </Card>
      </div>
    </div>
  );
}

function KVRow({ k, v, mono }: { k: string; v: React.ReactNode; mono?: boolean }) {
  return (
    <div className="flex items-baseline justify-between gap-3">
      <span className="text-fg-subtle">{k}</span>
      <span className={cn('text-fg', mono && 'font-mono tabular-nums')}>{v}</span>
    </div>
  );
}
