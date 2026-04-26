import { LegalLayout, type LegalSection } from './LegalLayout';

const sections: ReadonlyArray<LegalSection> = [
  { id: 'short', title: 'Short version' },
  { id: 'auto', title: 'Automatic refunds' },
  { id: 'manual', title: 'Manual refunds' },
  { id: 'withdrawal', title: 'Wallet withdrawal' },
  { id: 'noncovered', title: 'Not covered' },
  { id: 'chargebacks', title: 'Chargebacks' },
];

export function RefundPage() {
  return (
    <LegalLayout eyebrow="Legal" title="Refund Policy" lastUpdated="2026-04-15" sections={sections}>
      <h2 id="short">Short version</h2>
      <ul>
        <li>Pending orders → full refund, automatic, instant</li>
        <li>Partial delivery → refund for the undelivered portion, automatic</li>
        <li>Delivery pause / circuit breaker → either resume or full refund based on admin decision (≤4h SLA)</li>
        <li>Refill window: 30 days after completion, free</li>
      </ul>
      <p>All refunds go back to your SMMWorld wallet balance. Crypto withdrawals incur the network fee.</p>

      <h2 id="auto">Automatic refunds</h2>
      <p>The system issues these without you opening a ticket:</p>
      <ul>
        <li>Order cancelled while pending</li>
        <li>Order ends as <code>partial</code> — undelivered portion refunded</li>
        <li>Order fails with zero delivery — full refund</li>
        <li>Profile drop detected during refill window — replacement at no charge</li>
      </ul>

      <h2 id="manual">Manual refunds</h2>
      <p>If something feels wrong (wrong link, target account privated mid-run), open a ticket from Help. We review and credit within 24 business hours.</p>

      <h2 id="withdrawal">Wallet withdrawal</h2>
      <p>You can withdraw your wallet balance at any time to a crypto address you control. Minimum $5. We pay the network fee from your balance.</p>

      <h2 id="noncovered">Not covered</h2>
      <ul>
        <li>Drops detected outside the 30-day refill window</li>
        <li>Account changes after delivery (rebrand, post deleted, account banned)</li>
        <li>Targeting accounts that turn private after dispatch — partial delivery applies</li>
      </ul>

      <h2 id="chargebacks">Chargebacks</h2>
      <p>We are crypto-only, so chargebacks do not apply. Any disputes are resolved through this policy and our ticket system.</p>
    </LegalLayout>
  );
}
