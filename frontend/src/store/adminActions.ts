import { create } from 'zustand';
import { adminAPI } from '../services/api';

// =====================================================================
// Recent admin actions feed.
//
// Persistence model:
//   • Server is the source of truth — `admin_audit_log` table backs it.
//     Dashboard fetches on mount via `loadFromServer`, so refresh no longer wipes history.
//   • `push` still exists for instant optimistic feedback when the operator clicks an
//     action; the server-recorded row replaces it on the next `loadFromServer` tick.
//   • Cap the in-memory cache at ~50 rows — sidebar shows top N, deeper history lives
//     server-side via /v2/admin/audit-log?limit=N.
// =====================================================================

export interface AdminAction {
  id: string;
  /** Admin who performed the action. "you" for local optimistic, real username from server. */
  actor: string;
  /** Machine action key, e.g. `order.mark_partial`, `balance.manual_adjust`. */
  action: string;
  /** Target reference, e.g. `order:1029488`, `user:10011`. */
  target: string;
  /** Short human label of the target ("Order #1029488", "@elena_v"). */
  targetLabel?: string;
  /** Optional signed money delta (negative for refund, positive for credit). */
  amount?: number;
  /** Plain-language summary shown in the feed row. */
  summary: string;
  /** When the action fired. ISO string. */
  createdAt: string;
}

interface ServerRow {
  id: number;
  actor?: string;
  action: string;
  target?: string;
  targetLabel?: string;
  summary: string;
  amount?: number | string;
  createdAt: string;
}

interface State {
  actions: AdminAction[];
  loading: boolean;
  push: (entry: Omit<AdminAction, 'id' | 'createdAt' | 'actor'> & { actor?: string }) => void;
  loadFromServer: () => Promise<void>;
}

const MAX_ROWS = 50;

export const useAdminActions = create<State>((set, get) => ({
  actions: [],
  loading: false,

  push: (entry) =>
    set((s) => ({
      actions: [
        {
          // `local-` prefix marks this as an optimistic row not yet from the server. The next
          // `loadFromServer` tick will dedupe via prefix check + server id.
          id: 'local-' + Math.random().toString(36).slice(2, 10),
          actor: entry.actor ?? 'you',
          createdAt: new Date().toISOString(),
          ...entry,
        },
        ...s.actions,
      ].slice(0, MAX_ROWS),
    })),

  loadFromServer: async () => {
    if (get().loading) return;
    set({ loading: true });
    try {
      const rows: ServerRow[] = await adminAPI.getAuditLog(MAX_ROWS);
      const mapped: AdminAction[] = rows.map((r) => ({
        id: 'srv-' + r.id,
        actor: r.actor ?? 'system',
        action: r.action,
        target: r.target ?? '',
        targetLabel: r.targetLabel,
        summary: r.summary,
        amount: r.amount === undefined || r.amount === null ? undefined : Number(r.amount),
        createdAt: r.createdAt,
      }));
      // Keep local optimistic rows newer than the most recent server row at the top —
      // they'll get replaced on the next tick once the server-side write surfaces. Any
      // local rows older than the newest server row are stale and dropped.
      const newestServerTime = mapped[0]?.createdAt ? Date.parse(mapped[0].createdAt) : 0;
      const localRecent = get()
        .actions.filter((a) => a.id.startsWith('local-'))
        .filter((a) => Date.parse(a.createdAt) > newestServerTime);
      set({ actions: [...localRecent, ...mapped].slice(0, MAX_ROWS), loading: false });
    } catch {
      set({ loading: false });
    }
  },
}));
