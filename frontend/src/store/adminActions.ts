import { create } from 'zustand';

// =====================================================================
// Recent admin actions feed.
// Operator pages push entries here when they fire a write (mark-partial,
// adjust-balance, force-refund, etc.). Dashboard subscribes and shows
// the live list. Replace with real GET /v2/admin/audit feed in Phase 3.
// =====================================================================

export interface AdminAction {
  id: string;
  /** Admin who performed the action (defaults to "you" for the local UI). */
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

interface State {
  actions: AdminAction[];
  push: (entry: Omit<AdminAction, 'id' | 'createdAt' | 'actor'> & { actor?: string }) => void;
}

export const useAdminActions = create<State>((set) => ({
  actions: [],
  push: (entry) =>
    set((s) => ({
      actions: [
        {
          id: Math.random().toString(36).slice(2, 10),
          actor: entry.actor ?? 'you',
          createdAt: new Date().toISOString(),
          ...entry,
        },
        ...s.actions,
      ].slice(0, 30),
    })),
}));
