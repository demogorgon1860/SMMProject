import { LegalLayout, type LegalSection } from './LegalLayout';

// Bump this only when the policy text actually changes.
const LAST_UPDATED = '2026-04-27';

const sections: ReadonlyArray<LegalSection> = [
  { id: 'short', title: 'Short version' },
  { id: 'auto', title: 'Automatic refunds' },
  { id: 'manual', title: 'Manual refunds' },
  { id: 'noncovered', title: 'Not covered' },
  { id: 'chargebacks', title: 'Chargebacks' },
];

export function RefundPage() {
  return (
    <LegalLayout eyebrow="Legal" title="Refund Policy" lastUpdated={LAST_UPDATED} sections={sections}>
      <h2 id="short">Short version</h2>
      <ul>
        <li>Pending orders → full refund, automatic, instant</li>
        <li>Partial delivery → refund for the undelivered portion, automatic</li>
        <li>Delivery pause / circuit breaker → either resume or full refund based on admin decision (≤4h SLA)</li>
        <li>Refill: free for the lifetime of the order, no time limit</li>
      </ul>
      <p>All refunds go back to your SMMWorld wallet balance and can be used toward future orders.</p>

      <h2 id="auto">Automatic refunds</h2>
      <p>The system issues these without you opening a ticket:</p>
      <ul>
        <li>Order cancelled while pending</li>
        <li>Order ends as <code>partial</code> — undelivered portion refunded</li>
        <li>Order fails with zero delivery — full refund</li>
        <li>Profile drop detected after delivery — replacement at no charge, any time</li>
      </ul>

      <h2 id="manual">Manual refunds</h2>
      <p>If something feels wrong (wrong link, target account privated mid-run), open a ticket from Help. We review and credit within 24 business hours.</p>

      <h2 id="noncovered">Not covered</h2>
      <ul>
        <li>Account changes after delivery (rebrand, post deleted, account banned)</li>
        <li>Targeting accounts that turn private after dispatch — partial delivery applies</li>
      </ul>

      <h2 id="chargebacks">Chargebacks</h2>
      <p>We are crypto-only, so chargebacks do not apply. Any disputes are resolved through this policy and our ticket system.</p>
    </LegalLayout>
  );
}
