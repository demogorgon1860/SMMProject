import { useCallback, useEffect, useState } from 'react';
import {
  Badge,
  Button,
  Card,
  Empty,
  Field,
  Icon,
  IDCell,
  Modal,
  PageHeader,
  Tabs,
  Textarea,
  TimeCell,
  useToast,
} from '../../components/ui';
import { adminAPI } from '../../services/api';
import { useAdminActions } from '../../store/adminActions';
import { cn } from '../../lib/utils';

// =====================================================================
// Admin queue for user-initiated refill requests.
//
// Mirrors the Telegram "pending decisions" UX: PENDING items at the top,
// each with Approve / Reject buttons. APPROVED and REJECTED are read-only
// history. Reject requires a customer-facing reason.
// =====================================================================

type Status = 'PENDING' | 'APPROVED' | 'REJECTED';

interface RefillRequest {
  id: number;
  orderId: number;
  userId: number;
  status: Status;
  userNote?: string;
  rejectionReason?: string;
  adminId?: number;
  decidedAt?: string;
  refillOrderId?: number;
  createdAt: string;
}

interface ListResponse {
  items: RefillRequest[];
  totalPages: number;
  totalElements: number;
  currentPage: number;
  pageSize: number;
  pendingCount: number;
}

const TABS = [
  { value: 'PENDING' as Status, label: 'Pending' },
  { value: 'APPROVED' as Status, label: 'Approved' },
  { value: 'REJECTED' as Status, label: 'Rejected' },
];

export function AdminRefillRequestsPage() {
  const toast = useToast();
  const pushAction = useAdminActions((s) => s.push);
  const [tab, setTab] = useState<Status>('PENDING');
  const [items, setItems] = useState<RefillRequest[]>([]);
  const [loading, setLoading] = useState(true);
  const [pendingCount, setPendingCount] = useState<number>(0);
  const [busyId, setBusyId] = useState<number | null>(null);
  const [rejectTarget, setRejectTarget] = useState<RefillRequest | null>(null);

  const load = useCallback(
    async (status: Status) => {
      setLoading(true);
      try {
        const data: ListResponse = await adminAPI.refillRequestsList(status);
        setItems(Array.isArray(data.items) ? data.items : []);
        setPendingCount(data.pendingCount ?? 0);
      } catch {
        toast('Failed to load refill requests', 'error');
        setItems([]);
      } finally {
        setLoading(false);
      }
    },
    [toast],
  );

  useEffect(() => {
    void load(tab);
  }, [tab, load]);

  const approve = async (req: RefillRequest) => {
    setBusyId(req.id);
    try {
      const updated: RefillRequest = await adminAPI.refillRequestsApprove(req.id);
      pushAction({
        action: 'refill.approve',
        target: 'refill-request:' + req.id,
        targetLabel: 'Refill #' + req.id,
        summary: `Approved refill request on Order #${req.orderId}${updated.refillOrderId ? ` → refill #${updated.refillOrderId}` : ''}`,
      });
      toast(`Refill approved · order #${req.orderId}`, 'success');
      // Pop the row from PENDING tab; counter ticks down.
      setItems((rows) => rows.filter((r) => r.id !== req.id));
      setPendingCount((c) => Math.max(0, c - 1));
    } catch (err: unknown) {
      const e = err as { response?: { data?: { message?: string }; status?: number } };
      toast(e.response?.data?.message ?? 'Approval failed', 'error');
    } finally {
      setBusyId(null);
    }
  };

  const handleReject = async (req: RefillRequest, reason: string) => {
    setBusyId(req.id);
    try {
      await adminAPI.refillRequestsReject(req.id, reason);
      pushAction({
        action: 'refill.reject',
        target: 'refill-request:' + req.id,
        targetLabel: 'Refill #' + req.id,
        summary: `Rejected refill request on Order #${req.orderId} · ${reason}`,
      });
      toast(`Refill rejected · order #${req.orderId}`, 'success');
      setItems((rows) => rows.filter((r) => r.id !== req.id));
      setPendingCount((c) => Math.max(0, c - 1));
      setRejectTarget(null);
    } catch (err: unknown) {
      const e = err as { response?: { data?: { message?: string } } };
      toast(e.response?.data?.message ?? 'Rejection failed', 'error');
    } finally {
      setBusyId(null);
    }
  };

  return (
    <>
      <PageHeader
        title="Refill requests"
        subtitle={
          <span>
            <span className="font-mono text-fg">{pendingCount}</span> awaiting review
          </span>
        }
      />

      <div className="space-y-4 p-6">
        <Tabs value={tab} onChange={(v) => setTab(v as Status)} tabs={TABS} />

        <Card pad={false}>
          {loading ? (
            <div className="p-12 text-center text-[13px] text-fg-subtle">Loading…</div>
          ) : items.length === 0 ? (
            <Empty
              icon="orders"
              title={
                tab === 'PENDING'
                  ? 'No pending refill requests'
                  : tab === 'APPROVED'
                    ? 'No approved refills yet'
                    : 'No rejected requests'
              }
              subtitle={
                tab === 'PENDING'
                  ? 'When a customer requests a refill, it lands here for review.'
                  : undefined
              }
            />
          ) : (
            <table className="tbl">
              <thead>
                <tr>
                  <th>Request</th>
                  <th>Order</th>
                  <th>User</th>
                  <th>Note</th>
                  <th>Created</th>
                  <th>Status</th>
                  <th />
                </tr>
              </thead>
              <tbody>
                {items.map((r) => (
                  <RowItem
                    key={r.id}
                    req={r}
                    busy={busyId === r.id}
                    onApprove={() => approve(r)}
                    onReject={() => setRejectTarget(r)}
                  />
                ))}
              </tbody>
            </table>
          )}
        </Card>
      </div>

      <RejectModal
        target={rejectTarget}
        loading={!!rejectTarget && busyId === rejectTarget.id}
        onClose={() => setRejectTarget(null)}
        onSubmit={(reason) => rejectTarget && handleReject(rejectTarget, reason)}
      />
    </>
  );
}

function RowItem({
  req,
  busy,
  onApprove,
  onReject,
}: {
  req: RefillRequest;
  busy: boolean;
  onApprove: () => void;
  onReject: () => void;
}) {
  const isPending = req.status === 'PENDING';
  return (
    <tr>
      <td>
        <IDCell id={req.id} />
      </td>
      <td className="font-mono text-[12px]">
        <a href={`/orders/${req.orderId}`} className="text-accent hover:underline">
          #{req.orderId}
        </a>
      </td>
      <td className="font-mono text-[12px] text-fg-muted">#{req.userId}</td>
      <td className="max-w-[320px] text-[12.5px] text-fg-muted">
        <span className="block truncate" title={req.userNote ?? ''}>
          {req.userNote ?? <span className="text-fg-dim">—</span>}
        </span>
        {req.status === 'REJECTED' && req.rejectionReason && (
          <span className="mt-0.5 block text-[11.5px] text-warn">
            Rejected: {req.rejectionReason}
          </span>
        )}
        {req.status === 'APPROVED' && req.refillOrderId && (
          <span className="mt-0.5 block text-[11.5px] text-success">
            Refill order #{req.refillOrderId}
          </span>
        )}
      </td>
      <td>
        <TimeCell iso={req.createdAt} />
      </td>
      <td>
        <Badge tone={req.status === 'PENDING' ? 'info' : req.status === 'APPROVED' ? 'success' : 'warn'} size="sm">
          {req.status}
        </Badge>
      </td>
      <td className="text-right">
        {isPending ? (
          <div className="inline-flex gap-1.5">
            <Button variant="success" size="sm" icon="check" onClick={onApprove} disabled={busy} loading={busy}>
              Approve
            </Button>
            <Button variant="ghost" size="sm" icon="x" onClick={onReject} disabled={busy}>
              Reject
            </Button>
          </div>
        ) : (
          <span className="text-[11.5px] text-fg-subtle">{req.decidedAt ? <TimeCell iso={req.decidedAt} /> : '—'}</span>
        )}
      </td>
    </tr>
  );
}

function RejectModal({
  target,
  loading,
  onClose,
  onSubmit,
}: {
  target: RefillRequest | null;
  loading: boolean;
  onClose: () => void;
  onSubmit: (reason: string) => void;
}) {
  const [reason, setReason] = useState('');

  // Reset textarea every time we open the modal for a new request.
  const open = target != null;
  const targetId = target?.id;
  useEffect(() => {
    if (open) setReason('');
  }, [open, targetId]);

  const valid = reason.trim().length >= 5;

  return (
    <Modal
      open={open}
      onClose={() => {
        if (!loading) onClose();
      }}
      width={500}
      title={target ? <span>Reject refill request <span className="font-mono">#{target.id}</span></span> : null}
      subtitle="The customer will see this reason. Be specific and customer-facing."
      footer={
        <>
          <Button variant="ghost" onClick={onClose} disabled={loading}>
            Cancel
          </Button>
          <Button
            variant="warn"
            onClick={() => valid && onSubmit(reason.trim())}
            disabled={!valid || loading}
            loading={loading}
          >
            Reject request
          </Button>
        </>
      }
    >
      <Field
        label="Rejection reason (visible to customer)"
        hint={reason.length > 0 && reason.length < 5 ? `${reason.length}/5 chars min` : undefined}
        error={reason.length > 0 && !valid}
      >
        <Textarea
          block
          rows={4}
          value={reason}
          onChange={(e) => setReason(e.target.value)}
          placeholder="Original delivery already at quota · views from this geo not eligible · …"
          autoFocus
        />
      </Field>
      <p className={cn('mt-2 text-[11.5px] text-fg-subtle')}>
        <Icon name="info" size={11} className="mr-1 inline align-[-1px]" />
        Customer can submit a new request after rejection if they address the reason.
      </p>
    </Modal>
  );
}
