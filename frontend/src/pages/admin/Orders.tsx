import { useEffect, useMemo, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { useServerPagination } from '../../lib/hooks/useServerPagination';
import {
  Badge,
  Button,
  Card,
  Checkbox,
  ConfirmModal,
  CopyIcon,
  Drawer,
  Empty,
  Icon,
  IDCell,
  Input,
  DateRangePicker,
  Money,
  PageHeader,
  Pagination,
  StatusBadge,
  Tabs,
  TimeCell,
  useToast,
} from '../../components/ui';
import { adminAPI } from '../../services/api';
import { useAdminActions } from '../../store/adminActions';
import type { Order } from '../../types';
import { cn, fmtInt } from '../../lib/utils';
import { ForceCompleteModal, MarkPartialModal } from './_modals';

// Status filter chips, mirrored from STATUS_TONE colors via StatusBadge.
// PROCESSING is intentionally NOT here — it's an internal sub-state of IN_PROGRESS that's
// hidden from the UI. Backend coalesces IN_PROGRESS filter to match BOTH IN_PROGRESS and
// PROCESSING orders, so picking "in_progress" here returns everything the operator expects.
const STATUS_CHIPS: ReadonlyArray<string> = [
  'pending',
  'in_progress',
  'completed',
  'partial',
  'cancelled',
  'paused',
  'error',
  // Not an OrderStatus — backed by the is_refill flag. baseParams below routes this chip
  // through `refill=true` instead of `status=...` so the operator gets every make-up
  // delivery in one bucket regardless of its current lifecycle state.
  'refill',
];

const PAGE_SIZE = 100;

export function AdminOrdersPage() {
  const [openId, setOpenId] = useState<number | null>(null);
  // Deep-link support: /admin/orders?id=123 opens that order's drawer when the row is on the
  // loaded page (e.g. arrived from the Refill queue or the command palette). Harmless otherwise.
  const [searchParams] = useSearchParams();
  useEffect(() => {
    const id = Number(searchParams.get('id'));
    if (Number.isInteger(id) && id > 0) setOpenId(id);
  }, [searchParams]);
  // Single-select status — backend's GET /v2/admin/orders takes one `status` param,
  // so multi-select would either be client-side-only (and break with server pagination)
  // or fan out into N requests. One value keeps the URL→backend mapping honest.
  const [statusFilter, setStatusFilter] = useState<string>('');
  const [q, setQ] = useState('');
  const [urlQ, setUrlQ] = useState('');
  const [selected, setSelected] = useState<Set<number>>(new Set());
  // Date range — `undefined` on both means "All time". Sent to the backend as
  // dateFrom/dateTo (YYYY-MM-DD), interpreted as inclusive calendar days.
  const [dateFrom, setDateFrom] = useState<string | undefined>(undefined);
  const [dateTo, setDateTo] = useState<string | undefined>(undefined);

  // Stable identity for the hook's effect dependency: only changes when the actual filter
  // string changes, so a re-render that doesn't touch the filter doesn't trigger a refetch.
  // `urlSearch` is now a real backend param — it pages and indexes against orders.link instead
  // of being filtered client-side on the current page (which was useless across 6k+ orders).
  const baseParams = useMemo(() => {
    const p: Record<string, string> = {};
    if (statusFilter === 'refill') {
      // Refill isn't a status — route through the is_refill flag instead. Backend ignores
      // status/search/date when refill=true so the operator gets every make-up delivery.
      p.refill = 'true';
    } else if (statusFilter) {
      p.status = statusFilter;
    }
    if (urlQ.trim()) p.urlSearch = urlQ.trim();
    if (dateFrom && dateTo) {
      p.dateFrom = dateFrom;
      p.dateTo = dateTo;
    }
    return p;
  }, [statusFilter, urlQ, dateFrom, dateTo]);

  // Bot pushes start_count and remains updates on a 15-min cycle; useServerPagination's default
  // refresh interval already matches that cadence (silent — no spinner on background ticks).
  const {
    items: orders,
    totalElements,
    page,
    setPage,
    loading,
  } = useServerPagination<Order>({
    fetcher: adminAPI.getAllOrders,
    baseParams,
    pageSize: PAGE_SIZE,
    search: q,
    envelopeKey: 'orders',
  });

  // Local optimistic overrides for drawer-driven status changes (retry/pause/cancel/refund).
  // The hook owns the items list; we layer per-id partial patches on top of it so the UI
  // reflects an action immediately without waiting for the next refresh tick.
  const [overrides, setOverrides] = useState<Record<number, Partial<Order>>>({});
  const ordersWithOverrides = useMemo(() => {
    if (Object.keys(overrides).length === 0) return orders;
    return orders.map((o) => (overrides[o.id] ? { ...o, ...overrides[o.id] } : o));
  }, [orders, overrides]);

  // urlQ is now pushed to the backend via baseParams.urlSearch (see above), so we just sort
  // the server-returned rows here. The previous client-side filter was applied per-page only,
  // so a substring match on a page that didn't contain the URL silently returned empty.
  const pageRows = useMemo(() => {
    return [...ordersWithOverrides].sort((a, b) => {
      const ta = a.createdAt ? Date.parse(a.createdAt) : 0;
      const tb = b.createdAt ? Date.parse(b.createdAt) : 0;
      return tb - ta;
    });
  }, [ordersWithOverrides]);

  const allOnPageSelected = pageRows.length > 0 && pageRows.every((o) => selected.has(o.id));
  const someOnPageSelected = pageRows.some((o) => selected.has(o.id));

  // Aggregates for the bulk strip — totals across the current selection.
  // `charge` arrives from the backend as a JSON string (BigDecimal precision),
  // so coerce defensively before summing or .toFixed would blow up at runtime.
  const selectedAggregates = useMemo(() => {
    if (selected.size === 0) return null;
    const sel = ordersWithOverrides.filter((o) => selected.has(o.id));
    let qty = 0;
    let done = 0;
    let remains = 0;
    let charge = 0;
    for (const o of sel) {
      const q = Number(o.quantity) || 0;
      const d = Number(o.completed) || 0;
      qty += q;
      done += d;
      remains += Math.max(0, q - d);
      const c = typeof o.charge === 'number' ? o.charge : Number.parseFloat(String(o.charge ?? 0));
      if (Number.isFinite(c)) charge += c;
    }
    return { qty, done, remains, charge };
  }, [ordersWithOverrides, selected]);

  // Group filtered rows on the current page by calendar day for the
  // day-banner rendering inside <tbody>. Stable iteration order preserved
  // because `pageRows` is already sorted newest-first.
  const groupedPageRows = useMemo(() => {
    const groups: Array<{ key: string; label: string; rows: Order[] }> = [];
    const today = startOfDay(new Date());
    const yesterday = new Date(today.getTime() - 86_400_000);
    for (const o of pageRows) {
      const d = o.createdAt ? new Date(o.createdAt) : null;
      const key = d ? d.toISOString().slice(0, 10) : 'unknown';
      let label: string;
      if (!d) {
        label = 'Unknown date';
      } else if (sameDay(d, today)) {
        label = 'Today';
      } else if (sameDay(d, yesterday)) {
        label = 'Yesterday';
      } else {
        label = d.toLocaleDateString(undefined, { weekday: 'short', month: 'short', day: 'numeric' });
      }
      const last = groups[groups.length - 1];
      if (last && last.key === key) last.rows.push(o);
      else groups.push({ key, label, rows: [o] });
    }
    return groups;
  }, [pageRows]);

  const togglePageSelect = () => {
    setSelected((s) => {
      const next = new Set(s);
      if (allOnPageSelected) pageRows.forEach((o) => next.delete(o.id));
      else pageRows.forEach((o) => next.add(o.id));
      return next;
    });
  };

  const toggleStatus = (st: string) => {
    // Toggle: clicking the active chip clears the filter.
    setStatusFilter((curr) => (curr === st ? '' : st));
  };

  const onAfterAction = (updated: Partial<Order> & { id: number }) => {
    setOverrides((prev) => ({ ...prev, [updated.id]: { ...prev[updated.id], ...updated } }));
  };

  const openOrder = ordersWithOverrides.find((o) => o.id === openId) ?? null;

  return (
    <>
      <PageHeader
        title="Orders"
        subtitle={
          <span>
            Page <span className="font-mono text-fg">{page}</span> of{' '}
            <span className="font-mono text-fg">{Math.max(1, Math.ceil(totalElements / PAGE_SIZE))}</span>{' '}
            · <span className="font-mono text-fg">{totalElements}</span> total
          </span>
        }
      />

      <div className="space-y-4 p-6">
        {/* Filters */}
        <Card className="p-4">
          <div className="flex flex-wrap items-center gap-2">
            <Input
              icon="search"
              placeholder="Search id / service / user id"
              value={q}
              onChange={(e) => setQ(e.target.value)}
              containerClassName="min-w-[260px] flex-1"
              block
            />
            <Input
              icon="link"
              placeholder="URL contains…"
              value={urlQ}
              onChange={(e) => setUrlQ(e.target.value)}
              containerClassName="min-w-[200px]"
            />
            <DateRangePicker
              from={dateFrom}
              to={dateTo}
              onChange={(f, t) => {
                setDateFrom(f);
                setDateTo(t);
              }}
            />
            {(statusFilter || q || urlQ || dateFrom || dateTo) && (
              <Button
                variant="ghost"
                size="sm"
                onClick={() => {
                  setStatusFilter('');
                  setQ('');
                  setUrlQ('');
                  setDateFrom(undefined);
                  setDateTo(undefined);
                }}
              >
                Clear
              </Button>
            )}
          </div>
          <div className="mt-3 flex flex-wrap gap-1.5">
            {STATUS_CHIPS.map((st) => {
              const active = statusFilter === st;
              return (
                <button
                  key={st}
                  type="button"
                  onClick={() => toggleStatus(st)}
                  className={cn(
                    'rounded-full border px-3 py-[3px] text-[11.5px]',
                    active
                      ? 'border-accent bg-accent-soft text-accent-fg'
                      : 'border-border bg-bg-elev text-fg-muted hover:bg-bg-sunken',
                  )}
                >
                  {st.replace('_', ' ')}
                </button>
              );
            })}
          </div>
        </Card>

        {/* Bulk strip — shows aggregates over the current selection so an operator
            can sanity-check "what am I about to act on" before opening individual rows.
            Bulk write actions are still per-order (drawer); this is a read summary. */}
        {selected.size > 0 && selectedAggregates && (
          <div className="fade-in flex flex-wrap items-center gap-x-5 gap-y-2 rounded-md border border-accent bg-accent-soft px-4 py-2.5">
            <span className="font-mono text-[12.5px] font-semibold text-accent-fg">
              {selected.size} selected
            </span>
            <BulkAggregate label="Qty" value={fmtInt(selectedAggregates.qty)} />
            <BulkAggregate label="Done" value={fmtInt(selectedAggregates.done)} />
            <BulkAggregate label="Remains" value={fmtInt(selectedAggregates.remains)} />
            <BulkAggregate label="Charge" value={'$' + selectedAggregates.charge.toFixed(2)} />
            <div className="ml-auto">
              <Button variant="ghost" size="sm" onClick={() => setSelected(new Set())}>
                Clear
              </Button>
            </div>
          </div>
        )}

        {/* Table — overflow-x-auto so the wide layout (11 columns + URL) scrolls
            horizontally inside the card on narrow viewports instead of being clipped
            by the parent layout. Without this the right-most columns (status / created
            / chevron) sat behind the page chrome on screens under ~1400px. */}
        <Card className="p-0">
          {loading ? (
            <div className="p-12 text-center text-[13px] text-fg-subtle">Loading…</div>
          ) : pageRows.length === 0 ? (
            <Empty icon="orders" title="No orders match" subtitle="Adjust filters above." />
          ) : (
            <>
              <div className="overflow-x-auto">
                <table className="tbl min-w-[1100px]">
                  <thead>
                    <tr>
                      <th style={{ width: 32 }}>
                        <Checkbox
                          checked={allOnPageSelected}
                          indeterminate={!allOnPageSelected && someOnPageSelected}
                          onChange={togglePageSelect}
                        />
                      </th>
                      <th>ID</th>
                      <th>User</th>
                      <th>Service</th>
                      <th>Target URL</th>
                      <th className="text-right">Qty</th>
                      {/* Start count + Remains restored from the pre-redesign Orders table.
                          Bot updates start_count when it picks up the order and remains
                          on its 15-min poll cycle. Both come straight off the order entity. */}
                      <th className="text-right">Start count</th>
                      <th className="text-right">Remains</th>
                      <th className="text-right">Charge</th>
                      <th>Status</th>
                      <th>Created</th>
                      <th />
                    </tr>
                  </thead>
                  <tbody>
                    {groupedPageRows.map((g) => (
                      <DayGroup
                        key={g.key}
                        label={g.label}
                        rows={g.rows}
                        selected={selected}
                        setSelected={setSelected}
                        onOpen={setOpenId}
                      />
                    ))}
                  </tbody>
                </table>
              </div>
              <Pagination
                page={page}
                total={totalElements}
                pageSize={PAGE_SIZE}
                onPage={(p) => {
                  setPage(p);
                  // Scroll to top of the table when paginating so the user lands at the
                  // header of the next page rather than the bottom of the last one.
                  window.scrollTo({ top: 0, behavior: 'smooth' });
                }}
              />
            </>
          )}
        </Card>
      </div>

      <OrderAdminDrawer order={openOrder} onClose={() => setOpenId(null)} onAfterAction={onAfterAction} />
    </>
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

function startOfDay(d: Date): Date {
  const x = new Date(d);
  x.setHours(0, 0, 0, 0);
  return x;
}

function sameDay(a: Date, b: Date): boolean {
  return a.getFullYear() === b.getFullYear() && a.getMonth() === b.getMonth() && a.getDate() === b.getDate();
}

function BulkAggregate({ label, value }: { label: string; value: string }) {
  return (
    <span className="inline-flex items-baseline gap-1.5 text-[12px]">
      <span className="text-fg-muted">{label}</span>
      <span className="font-mono font-semibold tabular-nums text-fg">{value}</span>
    </span>
  );
}

function DayGroup({
  label,
  rows,
  selected,
  setSelected,
  onOpen,
}: {
  label: string;
  rows: Order[];
  selected: Set<number>;
  setSelected: React.Dispatch<React.SetStateAction<Set<number>>>;
  onOpen: (id: number | null) => void;
}) {
  // 12 columns total: select / id / user / service / url / qty / start / remains / charge / status / created / chevron.
  // The day banner row spans the full width and sits in the same <tbody> so it scrolls with rows.
  return (
    <>
      <tr className="bg-bg-sunken">
        <td colSpan={12} className="px-4 py-1.5 text-[10.5px] font-semibold uppercase tracking-wider text-fg-subtle">
          <span className="inline-flex items-center gap-2">
            {label}
            <span className="font-mono text-[10px] text-fg-dim">({rows.length})</span>
          </span>
        </td>
      </tr>
      {rows.map((o) => {
        const remains =
          typeof o.remains === 'number'
            ? Math.max(0, o.remains)
            : Math.max(0, o.quantity - (o.completed ?? 0));
        return (
          <tr key={o.id} className="cursor-pointer" onClick={() => onOpen(o.id)}>
            <td onClick={(e) => e.stopPropagation()}>
              <Checkbox
                checked={selected.has(o.id)}
                onChange={(e) => {
                  setSelected((s) => {
                    const next = new Set(s);
                    if (e.target.checked) next.add(o.id);
                    else next.delete(o.id);
                    return next;
                  });
                }}
              />
            </td>
            <td>
              <span className="inline-flex items-center gap-1.5">
                <IDCell id={o.id} />
                {o.isRefill && (
                  <Badge
                    tone="info"
                    size="sm"
                    title={
                      o.refillParentId != null
                        ? `Refill of order #${o.refillParentId}`
                        : 'Refill of an earlier order'
                    }
                  >
                    Refill
                  </Badge>
                )}
              </span>
            </td>
            <td className="font-mono text-[12px] text-fg-muted">#{o.userId}</td>
            <td className="text-[13px]">{o.service?.name ?? o.serviceName ?? '—'}</td>
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
            <td className="text-right font-mono">{fmtInt(remains)}</td>
            <td className="text-right">
              <Money value={o.charge} />
            </td>
            <td>
              <StatusBadge status={o.status} />
            </td>
            <td>
              <TimeCell iso={o.createdAt} />
            </td>
            <td>
              <Icon name="chevron-right" size={14} className="text-fg-dim" />
            </td>
          </tr>
        );
      })}
    </>
  );
}

// ---------------------------------------------------------------------
// Order admin drawer — operator-grade detail with all the write actions.
// ---------------------------------------------------------------------

interface DrawerProps {
  order: Order | null;
  onClose: () => void;
  onAfterAction: (updated: Partial<Order> & { id: number }) => void;
}

function OrderAdminDrawer({ order, onClose, onAfterAction }: DrawerProps) {
  const toast = useToast();
  const pushAction = useAdminActions((s) => s.push);
  const [tab, setTab] = useState<'overview' | 'actions'>('overview');
  const [confirm, setConfirm] = useState<null | 'retry' | 'pause' | 'cancel' | 'refund'>(null);
  const [busy, setBusy] = useState(false);
  const [partialOpen, setPartialOpen] = useState(false);
  const [forceCompleteOpen, setForceCompleteOpen] = useState(false);

  if (!order) return null;

  const fire = async (action: string, label: string, status?: Order['status']) => {
    setBusy(true);
    try {
      await adminAPI.performOrderAction(order.id, { action });
      pushAction({
        action: `order.${action}`,
        target: 'order:' + order.id,
        targetLabel: 'Order #' + order.id,
        summary: `${label} on Order #${order.id}`,
      });
      toast(`${label} · order #${order.id}`, 'success');
      onAfterAction({ id: order.id, ...(status ? { status } : {}) });
      setConfirm(null);
    } catch (err: unknown) {
      const e = err as { response?: { data?: { message?: string } } };
      toast(e.response?.data?.message ?? `${label} failed.`, 'error');
    } finally {
      setBusy(false);
    }
  };

  return (
    <>
      <Drawer
        open={!!order}
        onClose={onClose}
        width={820}
        title={
          <span className="flex items-center gap-2">
            <Badge tone="muted" size="sm">ORDER</Badge>
            <span className="font-mono">#{order.id}</span>
            <StatusBadge status={order.status} />
            {order.isRefill && (
              <Badge
                tone="info"
                size="sm"
                title={
                  order.refillParentId != null
                    ? `Refill of order #${order.refillParentId}`
                    : 'Refill of an earlier order'
                }
              >
                Refill
              </Badge>
            )}
          </span>
        }
        subtitle={
          <span className="text-[12px] text-fg-subtle">
            {order.service?.name ?? order.serviceName ?? '—'} ·{' '}
            {(order as Order & { username?: string }).username
              ? '@' + (order as Order & { username?: string }).username
              : order.userId
                ? 'user #' + order.userId
                : '—'}{' '}
            · created <TimeCell iso={order.createdAt} />
          </span>
        }
        actions={
          <>
            <Button variant="secondary" size="sm" icon="refresh" onClick={() => setConfirm('retry')}>
              Retry
            </Button>
            <Button variant="secondary" size="sm" icon="pause" onClick={() => setConfirm('pause')}>
              Pause
            </Button>
            <Button variant="success" size="sm" icon="check" onClick={() => setForceCompleteOpen(true)}>
              Force complete
            </Button>
            <Button variant="warn" size="sm" icon="warning" onClick={() => setPartialOpen(true)}>
              Mark Partial
            </Button>
            <Button variant="danger" size="sm" icon="x" onClick={() => setConfirm('refund')}>
              Force refund
            </Button>
          </>
        }
      >
        <div className="border-b border-border">
          <Tabs
            value={tab}
            onChange={setTab}
            tabs={[
              { value: 'overview', label: 'Overview' },
              { value: 'actions', label: 'Admin actions' },
            ]}
          />
        </div>

        <div className="p-6">
          {tab === 'overview' && <OverviewTab order={order} />}
          {tab === 'actions' && (
            <ActionsTab
              order={order}
              onRetry={() => setConfirm('retry')}
              onPause={() => setConfirm('pause')}
              onCancel={() => setConfirm('cancel')}
              onRefund={() => setConfirm('refund')}
              onMarkPartial={() => setPartialOpen(true)}
              onForceComplete={() => setForceCompleteOpen(true)}
            />
          )}
        </div>
      </Drawer>

      <MarkPartialModal
        open={partialOpen}
        order={order}
        onClose={() => setPartialOpen(false)}
        onSuccess={onAfterAction}
      />
      <ForceCompleteModal
        open={forceCompleteOpen}
        order={order}
        onClose={() => setForceCompleteOpen(false)}
        onSuccess={onAfterAction}
      />

      <ConfirmModal
        open={confirm === 'retry'}
        onClose={() => setConfirm(null)}
        onConfirm={() => fire('start', 'Retry')}
        title={`Retry order #${order.id}?`}
        message="Re-dispatches the order to the bot fleet. Existing progress is preserved."
        confirmText="Retry"
        variant="primary"
        loading={busy}
      />
      <ConfirmModal
        open={confirm === 'pause'}
        onClose={() => setConfirm(null)}
        onConfirm={() => fire('stop', 'Pause', 'PAUSED')}
        title={`Pause order #${order.id}?`}
        message="Bot stops dispatch immediately. Resume with Retry. No refund issued."
        confirmText="Pause"
        variant="warn"
        loading={busy}
      />
      <ConfirmModal
        open={confirm === 'cancel'}
        onClose={() => setConfirm(null)}
        onConfirm={() => fire('cancel', 'Cancel', 'CANCELLED')}
        title={`Cancel order #${order.id}?`}
        message="Refunds the undelivered portion to user wallet. Logged in balance audit."
        confirmText="Cancel order"
        variant="danger"
        loading={busy}
      />
      <ConfirmModal
        open={confirm === 'refund'}
        onClose={() => setConfirm(null)}
        onConfirm={() => fire('cancel', 'Force refund', 'CANCELLED')}
        title={`Force refund #${order.id}?`}
        message="Issues full refund and marks order as CANCELLED regardless of delivery state. Use sparingly."
        confirmText="Force refund"
        variant="danger"
        loading={busy}
      />
    </>
  );
}

function OverviewTab({ order }: { order: Order }) {
  // Admin /v2/admin/orders returns { id, username, serviceName, quantity, charge,
  // startCount, remains, status, createdAt, updatedAt, ... }. It does NOT return
  // userId, a nested service object, completed, currentCount, instagramBotOrderId,
  // trafficStatus, or errorMessage — so we derive what we can and skip the rest
  // rather than render "#undefined" placeholders for fields the API never sent.
  const ext = order as Order & { username?: string };
  const remains =
    typeof order.remains === 'number'
      ? Math.max(0, order.remains)
      : Math.max(0, order.quantity - (order.completed ?? 0));
  const completed =
    typeof order.completed === 'number' ? order.completed : Math.max(0, order.quantity - remains);
  const pct = order.quantity > 0 ? Math.min(1, completed / order.quantity) : 0;
  const charge =
    typeof order.charge === 'number' ? order.charge : Number.parseFloat(String(order.charge ?? 0));
  const startCount = typeof order.startCount === 'number' ? order.startCount : null;
  const currentCount =
    typeof order.currentCount === 'number'
      ? order.currentCount
      : startCount != null
        ? startCount + completed
        : null;
  const fields: Array<[string, React.ReactNode]> = [
    ['User', ext.username ? '@' + ext.username : order.userId ? '#' + order.userId : '—'],
    ['Service', order.service?.name ?? order.serviceName ?? '—'],
    ['Quantity', fmtInt(order.quantity)],
    ['Completed', `${fmtInt(completed)} (${(pct * 100).toFixed(1)}%)`],
    ['Remains', fmtInt(remains)],
    ['Charge', '$' + (Number.isFinite(charge) ? charge : 0).toFixed(2)],
    ['Created', order.createdAt.replace('T', ' ').slice(0, 19) + ' UTC'],
    ['Updated', order.updatedAt ? order.updatedAt.replace('T', ' ').slice(0, 19) + ' UTC' : '—'],
  ];
  if (order.instagramBotOrderId) fields.splice(6, 0, ['Bot order id', order.instagramBotOrderId]);
  if (order.trafficStatus) fields.splice(7, 0, ['Traffic status', order.trafficStatus]);

  return (
    <div className="grid grid-cols-1 gap-4 lg:grid-cols-[1fr_280px]">
      <Card>
        <div className="grid grid-cols-2 gap-x-4 gap-y-3 text-[13px]">
          {fields.map(([k, v]) => (
            <div key={k} className="border-b border-border pb-2">
              <div className="text-[11px] uppercase tracking-wider text-fg-subtle">{k}</div>
              <div className="mt-0.5 font-mono text-[13px]">{v}</div>
            </div>
          ))}
        </div>
        {order.errorMessage && (
          <div className="mt-3 rounded-md border border-danger/30 bg-danger-soft p-3 text-[12.5px] text-danger">
            <strong>Error:</strong> {order.errorMessage}
          </div>
        )}
        <div className="mt-3 break-all rounded-md border border-border bg-bg-sunken p-3 font-mono text-[12.5px]">
          <a href={order.link} target="_blank" rel="noopener noreferrer" className="text-accent hover:underline">
            {order.link} <Icon name="external" size={11} className="inline align-[-1px]" />
          </a>
        </div>
      </Card>
      <div className="space-y-3">
        <Card>
          <div className="text-[12.5px] font-medium">Progress</div>
          <div className="mt-2 h-[8px] overflow-hidden rounded-full bg-bg-sunken">
            <span
              className="block h-full bg-accent transition-[width] duration-400"
              style={{ width: `${(pct * 100).toFixed(0)}%` }}
            />
          </div>
          <div className="mt-1 flex justify-between font-mono text-[11.5px]">
            <span className="text-fg-muted">
              {fmtInt(completed)} / {fmtInt(order.quantity)}
            </span>
            <span>{(pct * 100).toFixed(1)}%</span>
          </div>
        </Card>
        {startCount != null && (
          <Card>
            <div className="text-[12.5px] font-medium">Instagram count</div>
            <div className="mt-2 grid grid-cols-3 gap-2 text-center font-mono text-[11px]">
              <div>
                <div className="text-fg-subtle">Start</div>
                <div className="mt-1 text-[14px]">{fmtInt(startCount)}</div>
              </div>
              <div>
                <div className="text-fg-subtle">Current</div>
                <div className="mt-1 text-[14px]">
                  {currentCount != null ? fmtInt(currentCount) : '—'}
                </div>
              </div>
              <div>
                <div className="text-fg-subtle">Δ</div>
                <div className="mt-1 text-[14px] text-success">+{fmtInt(completed)}</div>
              </div>
            </div>
          </Card>
        )}
      </div>
    </div>
  );
}

function ActionsTab({
  order,
  onRetry,
  onPause,
  onCancel,
  onRefund,
  onMarkPartial,
  onForceComplete,
}: {
  order: Order;
  onRetry: () => void;
  onPause: () => void;
  onCancel: () => void;
  onRefund: () => void;
  onMarkPartial: () => void;
  onForceComplete: () => void;
}) {
  const cards: Array<{ icon: 'refresh' | 'pause' | 'warning' | 'x' | 'wallet' | 'check'; title: string; body: string; variant: 'primary' | 'warn' | 'danger' | 'secondary' | 'success'; onClick: () => void }> = [
    { icon: 'refresh', title: 'Retry', body: 'Re-dispatch to the bot fleet. Preserves progress.', variant: 'secondary', onClick: onRetry },
    { icon: 'pause', title: 'Pause', body: 'Stop dispatching new actions. No refund.', variant: 'secondary', onClick: onPause },
    { icon: 'check', title: 'Force complete', body: 'Mark fully delivered regardless of state, stop the bot, record profit.', variant: 'success', onClick: onForceComplete },
    { icon: 'warning', title: 'Mark partial', body: 'Lock delivery at current state, refund the rest.', variant: 'warn', onClick: onMarkPartial },
    { icon: 'x', title: 'Cancel', body: 'Refund undelivered portion. Logs admin action.', variant: 'danger', onClick: onCancel },
    { icon: 'wallet', title: 'Force refund', body: 'Full refund, regardless of delivery. Use sparingly.', variant: 'danger', onClick: onRefund },
  ];
  return (
    <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
      {cards.map((c) => (
        <Card key={c.title} className="flex items-start gap-3 p-5">
          <span
            className="flex h-9 w-9 flex-none items-center justify-center rounded-md"
            style={{
              background:
                c.variant === 'warn'
                  ? 'var(--warn-soft)'
                  : c.variant === 'danger'
                    ? 'var(--danger-soft)'
                    : c.variant === 'success'
                      ? 'var(--success-soft)'
                      : 'var(--bg-sunken)',
              color:
                c.variant === 'warn'
                  ? 'var(--warn)'
                  : c.variant === 'danger'
                    ? 'var(--danger)'
                    : c.variant === 'success'
                      ? 'var(--success)'
                      : 'var(--fg-muted)',
            }}
          >
            <Icon name={c.icon} size={16} />
          </span>
          <div className="min-w-0 flex-1">
            <div className="text-[13.5px] font-semibold">{c.title}</div>
            <p className="mt-1 text-[12.5px] text-fg-muted">{c.body}</p>
            <div className="mt-3">
              <Button
                variant={
                  c.variant === 'warn'
                    ? 'warn'
                    : c.variant === 'danger'
                      ? 'danger'
                      : c.variant === 'success'
                        ? 'success'
                        : 'secondary'
                }
                size="sm"
                onClick={c.onClick}
              >
                {c.title}
              </Button>
            </div>
          </div>
        </Card>
      ))}
    </div>
  );
}
