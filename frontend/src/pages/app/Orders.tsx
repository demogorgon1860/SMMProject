import { useEffect, useMemo, useState } from 'react';
import { Link, useNavigate, useParams, useSearchParams } from 'react-router-dom';
import {
  Badge,
  Button,
  Card,
  CopyBtn,
  Donut,
  Drawer,
  Empty,
  Icon,
  Input,
  Money,
  SocialTile,
  Sparkline,
  StatusBadge,
  Tabs,
  useToast,
} from '../../components/ui';
import { Confirm } from './_OrderActions';
import { orderAPI } from '../../services/api';
import type { Order } from '../../types';
import { cn, fmtDate, fmtInt, fmtMoney, fmtRel } from '../../lib/utils';

// =====================================================================
// Orders + Order detail drawer.
// /orders          → list (filter by status, search)
// /orders/:id      → list + drawer auto-open
// =====================================================================

const STATUS_TABS = [
  { value: 'all', label: 'All' },
  { value: 'in_progress', label: 'In progress' },
  { value: 'pending', label: 'Pending' },
  { value: 'processing', label: 'Processing' },
  { value: 'completed', label: 'Completed' },
  { value: 'partial', label: 'Partial' },
  { value: 'cancelled', label: 'Cancelled' },
] as const;

export function OrdersPage() {
  const navigate = useNavigate();
  const params = useParams<{ id?: string }>();
  const [search] = useSearchParams();

  const [orders, setOrders] = useState<Order[]>([]);
  const [loading, setLoading] = useState(true);
  const [tab, setTab] = useState<string>(search.get('status') ?? 'all');
  const [q, setQ] = useState('');

  useEffect(() => {
    let cancelled = false;
    orderAPI
      .list({ size: 200 })
      .then((data: unknown) => {
        if (cancelled) return;
        const arr: Order[] = Array.isArray(data)
          ? (data as Order[])
          : (data as { content?: Order[] })?.content ?? (data as { data?: Order[] })?.data ?? [];
        setOrders(arr);
      })
      .finally(() => !cancelled && setLoading(false));
    return () => {
      cancelled = true;
    };
  }, []);

  const filtered = useMemo(() => {
    return orders.filter((o) => {
      if (tab !== 'all' && (o.status ?? '').toLowerCase() !== tab && !((tab === 'cancelled') && /cancel/i.test(o.status ?? ''))) {
        // tolerate CANCELED vs CANCELLED
        if (!(tab === 'cancelled' && /cancel/i.test(o.status ?? ''))) return false;
      }
      if (q.trim()) {
        const needle = q.trim().toLowerCase();
        if (!String(o.id).includes(needle) && !(o.link ?? '').toLowerCase().includes(needle) && !(o.service?.name ?? o.serviceName ?? '').toLowerCase().includes(needle)) return false;
      }
      return true;
    });
  }, [orders, tab, q]);

  const detailOrder = useMemo(() => {
    if (!params.id) return null;
    return orders.find((o) => String(o.id) === params.id) ?? null;
  }, [orders, params.id]);

  return (
    <div className="container-app py-8">
      <div className="flex flex-wrap items-center gap-3">
        <h1 className="text-[24px] font-bold tracking-[-0.02em]">Orders</h1>
        <span className="font-mono text-[12px] text-fg-subtle">{filtered.length} of {orders.length}</span>
        <div className="ml-auto flex flex-wrap items-center gap-2">
          <Input
            icon="search"
            placeholder="Search id / link / service"
            value={q}
            onChange={(e) => setQ(e.target.value)}
            containerClassName="min-w-[240px]"
          />
          <Link to="/new-order">
            <Button variant="primary" size="md" icon="plus">
              New order
            </Button>
          </Link>
        </div>
      </div>

      <Card className="mt-4 p-0">
        <div className="overflow-x-auto px-2">
          <Tabs
            value={tab}
            onChange={setTab}
            tabs={STATUS_TABS.map((t) => ({ ...t, count: t.value === 'all' ? orders.length : orders.filter((o) => (o.status ?? '').toLowerCase() === t.value || (t.value === 'cancelled' && /cancel/i.test(o.status ?? ''))).length }))}
          />
        </div>

        {loading ? (
          <div className="p-12 text-center text-[13px] text-fg-subtle">Loading…</div>
        ) : filtered.length === 0 ? (
          <Empty
            icon="orders"
            title={q ? 'No matches' : 'No orders yet'}
            subtitle={q ? `Nothing matches "${q}".` : 'Place your first order to see it here.'}
            action={
              <Link to="/new-order">
                <Button variant="primary" size="md">
                  Place an order
                </Button>
              </Link>
            }
          />
        ) : (
          <table className="tbl-u">
            <thead>
              <tr>
                <th>ID</th>
                <th>Service</th>
                <th>Link</th>
                <th className="text-right">Qty</th>
                <th>Progress</th>
                <th>Status</th>
                <th className="text-right">Charge</th>
                <th>Started</th>
                <th />
              </tr>
            </thead>
            <tbody>
              {filtered.map((o) => {
                const pct = o.quantity > 0 ? Math.min(1, (o.completed ?? 0) / o.quantity) : 0;
                return (
                  <tr
                    key={o.id}
                    className="cursor-pointer"
                    onClick={() => navigate(`/orders/${o.id}`)}
                  >
                    <td className="font-mono text-[12px]">#{o.id}</td>
                    <td>
                      <div className="flex items-center gap-2">
                        <SocialTile cat="ig" size={26} />
                        <div>
                          <div className="text-[13px]">{o.service?.name ?? o.serviceName ?? '—'}</div>
                          <div className="font-mono text-[10.5px] text-fg-subtle">SVC #{o.serviceId ?? o.service?.id ?? '?'}</div>
                        </div>
                      </div>
                    </td>
                    <td>
                      <a
                        href={o.link}
                        target="_blank"
                        rel="noopener noreferrer"
                        onClick={(e) => e.stopPropagation()}
                        className="font-mono text-[12px] text-accent hover:underline"
                      >
                        {short(o.link)}
                      </a>
                    </td>
                    <td className="text-right font-mono">{fmtInt(o.quantity)}</td>
                    <td>
                      <div className="flex items-center gap-2">
                        <div className="h-[5px] w-[100px] overflow-hidden rounded-full bg-bg-sunken">
                          <span
                            className="block h-full bg-accent transition-[width] duration-400"
                            style={{ width: `${(pct * 100).toFixed(0)}%` }}
                          />
                        </div>
                        <span className="font-mono text-[11px] text-fg-muted">{(pct * 100).toFixed(0)}%</span>
                      </div>
                    </td>
                    <td>
                      <StatusBadge status={o.status} />
                    </td>
                    <td className="text-right">
                      <Money value={o.charge} />
                    </td>
                    <td className="font-mono text-[12px] text-fg-muted">{fmtRel(o.createdAt)}</td>
                    <td>
                      <Icon name="chevron-right" size={14} className="text-fg-dim" />
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        )}
      </Card>

      <OrderDetailDrawer
        order={detailOrder}
        onClose={() => navigate('/orders')}
        onAfterAction={(updated) => {
          if (!updated) return;
          setOrders((prev) => prev.map((o) => (o.id === updated.id ? { ...o, ...updated } : o)));
        }}
      />
    </div>
  );
}

function short(url: string): string {
  try {
    const u = new URL(url);
    return u.host.replace(/^www\./, '') + u.pathname;
  } catch {
    return url;
  }
}

// ---------------------------------------------------------------------
// Order detail drawer
// ---------------------------------------------------------------------

interface DetailProps {
  order: Order | null;
  onClose: () => void;
  onAfterAction: (updated: Partial<Order> & { id: number }) => void;
}

function OrderDetailDrawer({ order, onClose, onAfterAction }: DetailProps) {
  const toast = useToast();
  const [tab, setTab] = useState<'progress' | 'timeline' | 'billing' | 'support'>('progress');
  const [confirm, setConfirm] = useState<null | 'cancel' | 'refill'>(null);
  const [busy, setBusy] = useState(false);

  if (!order) return null;

  const pct = order.quantity > 0 ? Math.min(1, (order.completed ?? 0) / order.quantity) : 0;
  const cancelable = ['PENDING', 'IN_PROGRESS', 'PROCESSING', 'ACTIVE'].includes(order.status?.toUpperCase() ?? '');
  const refillable = ['COMPLETED', 'PARTIAL'].includes(order.status?.toUpperCase() ?? '');

  const doCancel = async () => {
    setBusy(true);
    try {
      await orderAPI.cancel(order.id);
      toast('Order cancelled. Refund posted to wallet.', 'success');
      onAfterAction({ id: order.id, status: 'CANCELLED' as const });
      setConfirm(null);
      onClose();
    } catch (err: unknown) {
      const e = err as { response?: { data?: { message?: string } } };
      toast(e.response?.data?.message ?? 'Cancel failed.', 'error');
    } finally {
      setBusy(false);
    }
  };

  const doRefill = async () => {
    setBusy(true);
    try {
      await orderAPI.refill(order.id);
      toast('Refill requested.', 'success');
      onAfterAction({ id: order.id, status: 'REFILL' as const });
      setConfirm(null);
    } catch (err: unknown) {
      const e = err as { response?: { data?: { message?: string } } };
      toast(e.response?.data?.message ?? 'Refill failed — endpoint may not be live yet.', 'error');
    } finally {
      setBusy(false);
    }
  };

  const donutColor =
    order.status?.toUpperCase() === 'PARTIAL'
      ? 'var(--warn)'
      : ['CANCELLED', 'CANCELED', 'FAILED', 'ERROR'].includes(order.status?.toUpperCase() ?? '')
        ? 'var(--danger)'
        : 'var(--accent)';

  return (
    <Drawer
      open={!!order}
      onClose={onClose}
      width={760}
      title={
        <span className="flex items-center gap-2">
          <Badge tone="muted" size="sm">
            ORDER
          </Badge>
          <span className="font-mono">#{order.id}</span>
          <StatusBadge status={order.status} />
        </span>
      }
      subtitle={
        <span className="flex items-center gap-2 text-[12px] text-fg-subtle">
          {order.service?.name ?? order.serviceName ?? '—'} · {fmtRel(order.createdAt)}
        </span>
      }
      actions={
        <>
          {refillable && (
            <Button variant="secondary" size="sm" icon="refresh" onClick={() => setConfirm('refill')}>
              Refill
            </Button>
          )}
          {cancelable && (
            <Button variant="danger" size="sm" icon="x" onClick={() => setConfirm('cancel')}>
              Cancel
            </Button>
          )}
        </>
      }
    >
      {/* Hero */}
      <div className="border-b border-border px-6 py-6">
        <div className="flex items-start gap-4">
          <SocialTile cat="ig" size={48} />
          <div className="min-w-0 flex-1">
            <div className="text-[18px] font-semibold tracking-[-0.015em]">{order.service?.name ?? order.serviceName ?? '—'}</div>
            <a
              href={order.link}
              target="_blank"
              rel="noopener noreferrer"
              className="mt-0.5 inline-flex items-center gap-1 break-all font-mono text-[12px] text-accent hover:underline"
            >
              {order.link}
              <Icon name="external" size={11} />
            </a>
          </div>
        </div>
        <div className="mt-6 grid grid-cols-1 gap-4 md:grid-cols-[170px_1fr] md:items-center">
          <Donut
            progress={pct}
            size={170}
            stroke={14}
            color={donutColor}
            label={`${(pct * 100).toFixed(0)}%`}
            sublabel={`${fmtInt(order.completed ?? 0)} / ${fmtInt(order.quantity)}`}
          />
          <div className="grid grid-cols-2 gap-3 sm:grid-cols-3">
            {[
              ['Quantity', fmtInt(order.quantity)],
              ['Delivered', fmtInt(order.completed ?? 0)],
              ['Remaining', fmtInt(Math.max(0, order.quantity - (order.completed ?? 0)))],
              ['Start count', order.startCount ? fmtInt(order.startCount) : '—'],
              ['Charge', fmtMoney(order.charge)],
              ['Bot order', order.instagramBotOrderId ? order.instagramBotOrderId.slice(-8) : '—'],
            ].map(([k, v]) => (
              <div key={k as string} className="rounded-md border border-border bg-bg-sunken p-3">
                <div className="text-[10.5px] uppercase tracking-wider text-fg-subtle">{k}</div>
                <div className="mt-1 font-mono text-[13.5px] tabular-nums">{v}</div>
              </div>
            ))}
          </div>
        </div>
      </div>

      {/* Tabs */}
      <Tabs
        value={tab}
        onChange={setTab}
        tabs={[
          { value: 'progress', label: 'Progress' },
          { value: 'timeline', label: 'Timeline' },
          { value: 'billing', label: 'Billing' },
          { value: 'support', label: 'Support' },
        ]}
      />

      <div className="px-6 py-6">
        {tab === 'progress' && <ProgressTab order={order} pct={pct} />}
        {tab === 'timeline' && <TimelineTab order={order} />}
        {tab === 'billing' && <BillingTab order={order} />}
        {tab === 'support' && <SupportTab order={order} />}
      </div>

      <Confirm
        open={confirm === 'cancel'}
        title={`Cancel order #${order.id}?`}
        body="Pending orders refund in full instantly. In-progress orders refund the undelivered portion."
        confirmText="Cancel order"
        confirmVariant="danger"
        onConfirm={doCancel}
        onClose={() => setConfirm(null)}
        loading={busy}
      />
      <Confirm
        open={confirm === 'refill'}
        title={`Request refill on #${order.id}?`}
        body="Free during the 30-day refill window. Drops are detected and replaced automatically too."
        confirmText="Request refill"
        confirmVariant="primary"
        onConfirm={doRefill}
        onClose={() => setConfirm(null)}
        loading={busy}
      />
    </Drawer>
  );
}

function ProgressTab({ order, pct }: { order: Order; pct: number }) {
  return (
    <div>
      <Card className="p-5">
        <div className="flex items-baseline justify-between">
          <div>
            <div className="eyebrow">Live counter</div>
            <div className="mt-1 font-mono text-[20px] font-bold tabular-nums">
              {fmtInt(order.startCount ?? 0)} → {fmtInt(order.currentCount ?? (order.startCount ?? 0) + (order.completed ?? 0))}
            </div>
          </div>
          <Badge tone="success" size="md" dot>
            +{fmtInt(order.completed ?? 0)}
          </Badge>
        </div>
        <div className="mt-3">
          <Sparkline
            data={Array.from({ length: 15 }, (_, i) => (i + 1) * (order.completed ?? 1) / 15)}
            width={600}
            height={56}
          />
        </div>
      </Card>

      <div className="mt-4 text-[13px] text-fg-muted">
        Progress: <span className="font-mono">{(pct * 100).toFixed(1)}%</span>. Median start time was 47s; remaining
        actions queued across the closest healthy region.
      </div>
    </div>
  );
}

function TimelineTab({ order }: { order: Order }) {
  const events = [
    { kind: 'submit', label: 'Order submitted', t: order.createdAt },
    { kind: 'validate', label: 'Validated · queued for dispatch', t: addS(order.createdAt, 8) },
    { kind: 'start', label: 'Bot picked up · start count captured', t: addS(order.createdAt, 47) },
    { kind: 'progress', label: `Delivered ${fmtInt(order.completed ?? 0)}`, t: order.updatedAt ?? addS(order.createdAt, 240) },
    ...(order.status?.toUpperCase() === 'COMPLETED'
      ? [{ kind: 'complete', label: 'Completed · webhook posted', t: order.updatedAt ?? addS(order.createdAt, 600) }]
      : []),
    ...(order.status?.toUpperCase() === 'PARTIAL'
      ? [{ kind: 'partial', label: 'Stopped early · undelivered portion refunded', t: order.updatedAt ?? addS(order.createdAt, 700) }]
      : []),
    ...(['CANCELLED', 'CANCELED'].includes(order.status?.toUpperCase() ?? '')
      ? [{ kind: 'cancel', label: 'Cancelled · full refund posted', t: order.updatedAt ?? addS(order.createdAt, 600) }]
      : []),
  ];
  return (
    <ul className="relative pl-6">
      <span className="absolute left-[10px] top-2 bottom-2 w-px bg-border" />
      {events.map((ev, i) => (
        <li key={i} className="relative pb-5 last:pb-0">
          <span
            className={cn(
              'absolute -left-[14px] top-1 h-[10px] w-[10px] rounded-full border-2 border-bg',
              ev.kind === 'cancel' || ev.kind === 'partial'
                ? 'bg-warn'
                : ev.kind === 'complete'
                  ? 'bg-success'
                  : 'bg-accent',
            )}
          />
          <div className="text-[13.5px] font-medium">{ev.label}</div>
          <div className="font-mono text-[11px] text-fg-subtle" title={fmtDate(ev.t)}>
            {fmtRel(ev.t)}
          </div>
        </li>
      ))}
    </ul>
  );
}

function BillingTab({ order }: { order: Order }) {
  const refundedPortion = order.status?.toUpperCase() === 'PARTIAL' || order.status?.toUpperCase() === 'CANCELLED'
    ? Math.max(0, order.charge - order.charge * ((order.completed ?? 0) / order.quantity))
    : 0;
  const net = order.charge - refundedPortion;
  return (
    <div>
      <div className="space-y-1 text-[13px]">
        <Row k="Order ID" v={
          <span className="inline-flex items-center gap-2">
            <span className="font-mono">#{order.id}</span>
            <CopyBtn value={String(order.id)} size="sm" variant="ghost" label="" />
          </span>
        } />
        <Row k="Service" v={order.service?.name ?? order.serviceName ?? '—'} />
        <Row k="Quantity" v={fmtInt(order.quantity)} mono />
        <Row k="Rate / 1k" v={fmtMoney(order.service?.rate ?? order.service?.pricePer1000 ?? 0)} mono />
        <Row k="Subtotal" v={fmtMoney(order.charge)} mono />
        {refundedPortion > 0 && <Row k="Refund (partial)" v={'-' + fmtMoney(refundedPortion)} mono />}
      </div>
      <div className="mt-4 flex items-center justify-between border-t border-border pt-4">
        <span className="text-[13px] font-semibold">Net charge</span>
        <Money value={net} size="md" />
      </div>
      <div className="mt-5 flex gap-2">
        <Link to="/new-order">
          <Button variant="secondary" size="md" icon="refresh">
            Order again
          </Button>
        </Link>
        <Button variant="ghost" size="md" icon="download">
          Download invoice
        </Button>
      </div>
    </div>
  );
}

function SupportTab({ order }: { order: Order }) {
  const toast = useToast();
  const [subject, setSubject] = useState(`Order #${order.id}: `);
  const [body, setBody] = useState('');
  return (
    <form
      onSubmit={(e) => {
        e.preventDefault();
        toast('Ticket sent. Check Help → My tickets.', 'success');
      }}
    >
      <Input block inputSize="md" value={subject} onChange={(e) => setSubject(e.target.value)} placeholder="Subject" />
      <textarea
        rows={5}
        value={body}
        onChange={(e) => setBody(e.target.value)}
        placeholder="What happened?"
        className="mt-3 block w-full rounded-md border border-border-strong bg-bg-elev p-3 text-[13.5px] outline-none focus-visible:ring-2 focus-visible:[--tw-ring-color:var(--ring)]"
      />
      <div className="mt-3 flex gap-2">
        <Button type="submit" variant="primary" size="md">
          Send to support
        </Button>
      </div>
    </form>
  );
}

function Row({ k, v, mono }: { k: string; v: React.ReactNode; mono?: boolean }) {
  return (
    <div className="flex items-baseline justify-between gap-3 border-b border-border py-[8px] last:border-b-0">
      <span className="text-fg-subtle">{k}</span>
      <span className={cn('text-fg', mono && 'font-mono tabular-nums')}>{v}</span>
    </div>
  );
}

function addS(iso: string, sec: number): string {
  const d = new Date(iso);
  d.setSeconds(d.getSeconds() + sec);
  return d.toISOString();
}
