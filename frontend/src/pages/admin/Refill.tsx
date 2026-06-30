import { AdminRefillRequestsPage } from './RefillRequests';

// =====================================================================
// Admin Refill (/admin/refill) — the approval queue, nothing else.
//
// Refill checking is fully automatic now: the customer submits orders, the
// panel runs the bot drop-check itself, and only real-drop requests surface
// here as PENDING with the exact dropped amount already computed. The operator
// just approves (or rejects) — no manual "check drop" tool anymore.
// =====================================================================

export function AdminRefillPage() {
  return <AdminRefillRequestsPage />;
}
