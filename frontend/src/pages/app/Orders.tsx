import { useEffect, useMemo, useState } from 'react';
import { useServerPagination } from '../../lib/hooks/useServerPagination';
import { Link, useNavigate, useParams, useSearchParams } from 'react-router-dom';
import {
  Badge,
  Button,
  Card,
  CopyBtn,
  CopyIcon,
  Donut,
  Drawer,
  Empty,
  Icon,
  IDCell,
  Input,
  Money,
  Pagination,
  SocialTile,
  Sparkline,
  StatusBadge,
  Tabs,
  TimeCell,
  useToast,
} from '../../components/ui';
import { Confirm } from './_OrderActions';
import { orderAPI } from '../../services/api';
import type { Order } from '../../types';
import { cn, fmtDate, fmtDateTime, fmtInt, fmtMoney } from '../../lib/utils';

interface RefillRequest {
  id: number;
  orderId: number;
  status: 'CHECKING' | 'PENDING' | 'APPROVED' | 'REJECTED' | 'NO_DROP' | 'FAILED';
  rejectionReason?: string;
  refillOrderId?: number;
  decidedAt?: string;
  createdAt: string;
}

// =====================================================================
// Orders + Order detail drawer.
// /orders          → list (filter by status, search)
// /orders/:id      → list + drawer auto-open
// =====================================================================

// PROCESSING is intentionally NOT exposed as a tab — it's an internal sub-state of
// IN_PROGRESS. The badge component renders both as "In progress" and the backend
// coalesces the IN_PROGRESS filter to match BOTH PROCESSING and IN_PROGRESS rows, so
// the user sees one unified bucket.
const STATUS_TABS = [
  { value: 'all', label: 'All' },
  { value: 'in_progress', label: 'In progress' },
  { value: 'pending', label: 'Pending' },
  { value: 'completed', label: 'Completed' },
  { value: 'partial', label: 'Partial' },
  { value: 'cancelled', label: 'Cancelled' },
  // Not a real OrderStatus — backed by the is_refill flag instead. Treated as a separate
  // tab so customers can see make-up deliveries (PENDING/IN_PROGRESS/COMPLETED refills) in
  // one bucket. baseParams below routes this value through `refill=true` instead of `status`.
  { value: 'refill', label: 'Refill' },
] as const;

const PAGE_SIZE = 100;

export function OrdersPage() {
  const navigate = useNavigate();
  const params = useParams<{ id?: string }>();
  const [search] = useSearchParams();

  const [tab, setTab] = useState<string>(search.get('status') ?? 'all');
  const [q, setQ] = useState('');

  // Stable identity for the hook's effect dependency: only changes when `tab` actually changes,
  // so a re-render that doesn't touch the filter doesn't trigger a refetch. The "refill" tab
  // isn't backed by a status — it's the is_refill flag — so it routes through a separate
  // query param instead of the status one.
  const baseParams = useMemo(() => {
    if (tab === 'all') return {};
    if (tab === 'refill') return { refill: true };
    return { status: tab.toUpperCase() };
  }, [tab]);

  // Backend caps `size` at 100 (@Max(100) on OrderController#getUserOrders).
  // Server-pagination so the user can reach every order they own.
  const {
    items: orders,
    totalElements,
    page,
    setPage,
    loading,
  } = useServerPagination<Order>({
    fetcher: orderAPI.list,
    baseParams,
    pageSize: PAGE_SIZE,
    search: q,
  });

  // Local optimistic-update bridge: when the drawer cancels/refills an order, mirror it onto
  // the items array so the table reflects the new status without waiting for the next refetch.
  const [overrides, setOverrides] = useState<Record<number, Partial<Order>>>({});
  const filtered = useMemo(() => {
    if (Object.keys(overrides).length === 0) return orders;
    return orders.map((o) => (overrides[o.id] ? { ...o, ...overrides[o.id] } : o));
  }, [orders, overrides]);

  const detailOrder = useMemo(() => {
    if (!params.id) return null;
    // Read from `filtered` so the drawer reflects optimistic post-action overrides.
    return filtered.find((o) => String(o.id) === params.id) ?? null;
  }, [filtered, params.id]);

  return (
    // Use the wider container — Orders is the only data-dense table on the user side, and a
    // 1240px cap (container-app) leaves obvious whitespace on FullHD+. container-wide caps at
    // 1800px so the table breathes on wide monitors without going edge-to-edge on 4K.
    <div className="container-wide py-8">
      <div className="flex flex-wrap items-center gap-3">
        <h1 className="text-[24px] font-bold tracking-[-0.02em]">Orders</h1>
        <span className="font-mono text-[12px] text-fg-subtle">
          Page {page} of {Math.max(1, Math.ceil(totalElements / PAGE_SIZE))} · {totalElements} total
        </span>
        <div className="ml-auto flex flex-wrap items-center gap-2">
          <Input
            icon="search"
            placeholder="Search id(s) / link — e.g. 29931, 29932"
            value={q}
            onChange={(e) => setQ(e.target.value)}
            containerClassName="min-w-[260px]"
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
          {/* Tab counts removed: with server pagination we only have the current
              page in memory, so per-status counts couldn't be computed honestly.
              Adding them would need a separate count endpoint per status. */}
          <Tabs value={tab} onChange={setTab} tabs={STATUS_TABS.map((t) => ({ value: t.value, label: t.label }))} />
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
          <>
          <div className="overflow-x-auto">
            <table className="tbl-u min-w-[1000px]">
            <thead>
              <tr>
                <th>ID</th>
                <th>Service</th>
                <th>Link</th>
                <th className="text-right">Qty</th>
                {/* Start count + Remains restored from the pre-redesign Orders table.
                    The bot updates these on a 15-min poll cycle (start_count is captured
                    when it picks up the order, current_count + remains move as actions
                    land). Both come straight off the order entity — no client math. */}
                <th className="text-right">Start count</th>
                <th className="text-right">Remains</th>
                <th>Status</th>
                <th className="text-right">Charge</th>
                <th>Started</th>
                <th />
              </tr>
            </thead>
            <tbody>
              {filtered.map((o) => {
                const remains = computeRemains(o);
                return (
                  <tr
                    key={o.id}
                    className="cursor-pointer"
                    onClick={() => navigate(`/orders/${o.id}`)}
                  >
                    <td onClick={(e) => e.stopPropagation()}>
                      <span className="inline-flex items-center gap-1.5">
                        <IDCell id={o.id} />
                        {o.isRefill && (
                          <Badge tone="info" size="sm" title="Refill of an earlier order">
                            Refill
                          </Badge>
                        )}
                      </span>
                    </td>
                    <td>
                      <div className="flex items-center gap-2">
                        <SocialTile cat="ig" size={26} />
                        <div>
                          <div className="text-[13px]">{o.service?.name ?? o.serviceName ?? '—'}</div>
                          <div className="font-mono text-[10.5px] text-fg-subtle">SVC #{o.serviceId ?? o.service?.id ?? '?'}</div>
                        </div>
                      </div>
                    </td>
                    <td onClick={(e) => e.stopPropagation()}>
                      <span className="inline-flex items-center gap-1.5">
                        <a
                          href={o.link}
                          target="_blank"
                          rel="noopener noreferrer"
                          className="font-mono text-[12px] text-accent hover:underline"
                        >
                          {short(o.link)}
                        </a>
                        <CopyIcon value={o.link ?? ''} title="Copy link" />
                      </span>
                    </td>
                    <td className="text-right font-mono">{fmtInt(o.quantity)}</td>
                    <td className="text-right font-mono text-fg-muted">
                      {o.startCount != null ? fmtInt(o.startCount) : '—'}
                    </td>
                    <td className="text-right font-mono">
                      {remains != null ? fmtInt(remains) : '—'}
                    </td>
                    <td>
                      <StatusBadge status={o.status} />
                    </td>
                    <td className="text-right">
                      <Money value={o.charge} />
                    </td>
                    <td><TimeCell iso={o.createdAt} /></td>
                    <td>
                      <Icon name="chevron-right" size={14} className="text-fg-dim" />
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
          </div>
          <Pagination
            page={page}
            total={totalElements}
            pageSize={PAGE_SIZE}
            onPage={(p) => {
              setPage(p);
              window.scrollTo({ top: 0, behavior: 'smooth' });
            }}
          />
          </>
        )}
      </Card>

      <OrderDetailDrawer
        order={detailOrder}
        onClose={() => navigate('/orders')}
        onAfterAction={(updated) => {
          if (!updated) return;
          setOverrides((prev) => ({ ...prev, [updated.id]: { ...prev[updated.id], ...updated } }));
        }}
      />
    </div>
  );
}

/**
 * Backend exposes `remains` directly when known, otherwise we derive it from
 * (quantity − completed). For finished orders (COMPLETED / PARTIAL / CANCELLED)
 * the entity may not carry a `remains` value, so this helper is the single
 * source of truth.
 */
function computeRemains(o: Order): number | null {
  if (typeof o.remains === 'number') return Math.max(0, o.remains);
  if (typeof o.quantity === 'number') {
    return Math.max(0, o.quantity - (typeof o.completed === 'number' ? o.completed : 0));
  }
  return null;
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
  const [refillReq, setRefillReq] = useState<RefillRequest | null>(null);
  const [refillReqLoading, setRefillReqLoading] = useState(false);

  // Whenever the drawer opens for a refillable order, fetch any existing refill request so
  // the UI can show pending / approved / rejected state instead of a naive "Refill" button.
  useEffect(() => {
    if (!order) return;
    const isRefillable = ['COMPLETED', 'PARTIAL'].includes(order.status?.toUpperCase() ?? '');
    if (!isRefillable) {
      setRefillReq(null);
      return;
    }
    let cancelled = false;
    setRefillReqLoading(true);
    orderAPI
      .getRefillRequest(order.id)
      .then((r: RefillRequest) => {
        if (!cancelled) setRefillReq(r);
      })
      .catch(() => {
        // 404 = no request yet; everything else swallowed (UI just shows "Request" button).
        if (!cancelled) setRefillReq(null);
      })
      .finally(() => {
        if (!cancelled) setRefillReqLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [order?.id, order?.status]);

  if (!order) return null;

  const pct = order.quantity > 0 ? Math.min(1, (order.completed ?? 0) / order.quantity) : 0;
  // Normalize spaces to underscores: backend serializes as Perfect Panel labels
  // ("In progress", "Pending"), so a naive .toUpperCase() against enum-style
  // constants ("IN_PROGRESS") never matches and the action buttons stay hidden.
  const normalizedStatus = (order.status ?? '').toUpperCase().replace(/\s+/g, '_');
  const cancelable = ['PENDING', 'IN_PROGRESS', 'PROCESSING', 'ACTIVE'].includes(normalizedStatus);
  const refillable = ['COMPLETED', 'PARTIAL'].includes(normalizedStatus);
  const refillChecking = refillReq?.status === 'CHECKING';
  const refillPending = refillReq?.status === 'PENDING';
  const refillApproved = refillReq?.status === 'APPROVED';
  const refillRejected = refillReq?.status === 'REJECTED';
  const refillNoDrop = refillReq?.status === 'NO_DROP';
  const refillFailed = refillReq?.status === 'FAILED';
  // Approved is terminal; an in-flight request (CHECKING/PENDING) blocks a new one. Rejected /
  // no-drop / failed are all "done, nothing in flight" → the customer may submit again.
  const refillInFlight = refillChecking || refillPending;
  const canRequestRefill = refillable && !refillInFlight && !refillApproved && !refillReqLoading;

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
      const created: RefillRequest = await orderAPI.requestRefill(order.id);
      setRefillReq(created);
      // The panel now checks the drop automatically; status updates on the next drawer open
      // or from the Refill page.
      toast("Submitted — we're checking this order's drop now.", 'success');
      setConfirm(null);
    } catch (err: unknown) {
      const e = err as { response?: { data?: { message?: string }; status?: number } };
      toast(
        e.response?.data?.message ??
          (e.response?.status === 409
            ? 'This order isn’t eligible for refill.'
            : 'Could not submit refill request.'),
        'error',
      );
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
          {order.isRefill && (
            <Badge tone="info" size="sm" title="Refill of an earlier order">
              Refill
            </Badge>
          )}
        </span>
      }
      subtitle={
        <span className="flex items-center gap-2 text-[12px] text-fg-subtle">
          {order.service?.name ?? order.serviceName ?? '—'} · {fmtDateTime(order.createdAt)}
        </span>
      }
      actions={
        <>
          {refillable && (
            <Button
              variant={refillInFlight ? 'ghost' : 'secondary'}
              size="sm"
              icon={refillChecking ? 'refresh' : refillPending ? 'info' : refillApproved ? 'check' : 'refresh'}
              onClick={() => setConfirm('refill')}
              disabled={!canRequestRefill}
              title={
                refillChecking
                  ? "We're checking this order's drop"
                  : refillPending
                    ? 'Awaiting operator approval'
                    : refillApproved
                      ? 'Refill already approved'
                      : refillRejected
                        ? 'Previous request rejected — submit again'
                        : refillNoDrop
                          ? 'No drop was detected last time — you can submit again'
                          : 'Submit for an automatic refill check'
              }
            >
              {refillChecking
                ? 'Checking drop…'
                : refillPending
                  ? 'Awaiting approval'
                  : refillApproved
                    ? 'Refill approved'
                    : refillRejected || refillFailed || refillNoDrop
                      ? 'Submit again'
                      : 'Request refill'}
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
      {/* Refill request status banner */}
      {refillReq && (
        <div
          className={cn(
            'flex items-start gap-3 border-b border-border px-6 py-3 text-[12.5px]',
            refillChecking && 'bg-info-soft text-info',
            refillPending && 'bg-warn-soft text-warn',
            refillApproved && 'bg-success-soft text-success',
            (refillRejected || refillFailed) && 'bg-danger-soft text-danger',
            refillNoDrop && 'bg-bg-sunken text-fg-muted',
          )}
        >
          <Icon
            name={
              refillChecking
                ? 'refresh'
                : refillApproved
                  ? 'check'
                  : refillPending || refillNoDrop
                    ? 'info'
                    : 'warning'
            }
            size={14}
            className="mt-[1px] flex-none"
          />
          <div className="min-w-0 flex-1">
            {refillChecking && (
              <span>
                <strong>Checking the drop.</strong> We're measuring how much of this order fell
                off — if anything dropped, a refill for that amount is queued for approval
                automatically.
              </span>
            )}
            {refillPending && (
              <span>
                <strong>Drop detected — awaiting approval.</strong> An operator will approve the
                refill shortly. Refresh to see the decision.
              </span>
            )}
            {refillApproved && (
              <span>
                <strong>Refill approved</strong> — new refill order{' '}
                {refillReq.refillOrderId ? (
                  <span className="font-mono">#{refillReq.refillOrderId}</span>
                ) : (
                  'created'
                )}
                . Drop counts are tracked automatically.
              </span>
            )}
            {refillNoDrop && (
              <span>
                <strong>No drop detected.</strong> This order is still fully delivered — nothing to
                refill. You can submit again later if that changes.
              </span>
            )}
            {refillRejected && (
              <span>
                <strong>Previous refill request rejected.</strong>
                {refillReq.rejectionReason ? ` Reason: ${refillReq.rejectionReason}` : ''} You
                can submit a new request.
              </span>
            )}
            {refillFailed && (
              <span>
                <strong>Couldn't verify the drop.</strong>
                {refillReq.rejectionReason ? ` ${refillReq.rejectionReason}` : ''} Please submit
                again.
              </span>
            )}
          </div>
        </div>
      )}

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
        title={`Submit order #${order.id} for refill?`}
        body="We'll automatically check how much of this order dropped. If part of it fell off, a refill for exactly the dropped amount is queued for a quick operator approval. Refills are free during the 30-day window."
        confirmText="Submit for refill"
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
        Progress: <span className="font-mono">{(pct * 100).toFixed(1)}%</span>. Remaining actions are queued across the
        delivery network and dripfeed based on the service profile.
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
            {fmtDateTime(ev.t)}
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
