import { LegalLayout, type LegalSection } from './LegalLayout';

// Bump this only when the policy text actually changes.
const LAST_UPDATED = '2026-04-27';

const sections: ReadonlyArray<LegalSection> = [
  { id: 'who', title: 'Who we are' },
  { id: 'service', title: 'What the service is' },
  { id: 'eligibility', title: 'Eligibility' },
  { id: 'account', title: 'Your account' },
  { id: 'orders', title: 'Orders & delivery' },
  { id: 'aup', title: 'Acceptable use' },
  { id: 'refunds', title: 'Refunds' },
  { id: 'liability', title: 'Liability' },
  { id: 'law', title: 'Governing law' },
  { id: 'changes', title: 'Changes' },
];

export function TermsPage() {
  return (
    <LegalLayout eyebrow="Legal" title="Terms of Service" lastUpdated={LAST_UPDATED} sections={sections}>
      <h2 id="who">Who we are</h2>
      <p>
        SMMWorld ("we", "our", "us") is operated by SMMWorld Ltd. We provide an Instagram growth
        network — likes, follows, and comments delivered with real quality through our own
        infrastructure.
      </p>

      <h2 id="service">What the service is</h2>
      <p>
        We are <strong>not a reseller</strong>. Every action you order is dispatched through our
        own delivery network and executed by us directly. We do not buy from external panels.
      </p>

      <h2 id="eligibility">Eligibility</h2>
      <p>You must be at least 18 years old (or the age of majority in your jurisdiction) and not located in a sanctioned country.</p>

      <h2 id="account">Your account</h2>
      <p>You're responsible for keeping your password and API key safe. Notify us immediately if you suspect unauthorized access.</p>
      <h3>API keys</h3>
      <p>API keys grant full account access. Treat them like passwords. Rotate them in Profile → Security at any time.</p>

      <h2 id="orders">Orders & delivery</h2>
      <p>Order processing begins automatically once payment is confirmed. Larger orders dripfeed based on the service profile. We do not warrant any specific start or completion time.</p>
      <p>We do not guarantee Instagram won't change its policies or remove content. We do guarantee that we will replace dropped actions during the 30-day refill window.</p>

      <h2 id="aup">Acceptable use</h2>
      <p>You may not use the service to:</p>
      <ul>
        <li>Target accounts you do not own or have permission to grow</li>
        <li>Engage in harassment, hate speech, or illegal activity</li>
        <li>Reverse-engineer, scrape, or rate-limit our APIs maliciously</li>
        <li>Resell credentials or share a single account across an organization</li>
      </ul>

      <h2 id="refunds">Refunds</h2>
      <p>See our <a href="/legal/refund" className="text-accent hover:underline">Refund Policy</a> for the full breakdown. Pending orders cancel with full refund. Partial deliveries refund the undelivered portion.</p>

      <h2 id="liability">Liability</h2>
      <p>To the maximum extent permitted by law, our total liability is capped at the amount you paid us in the 12 months preceding the claim.</p>

      <h2 id="law">Governing law</h2>
      <p>These terms are governed by the laws of the Republic of Cyprus. Disputes go to the courts of Limassol.</p>

      <h2 id="changes">Changes</h2>
      <p>We may update these terms. Material changes will be announced 30 days before they take effect. Continued use after the effective date constitutes acceptance.</p>
    </LegalLayout>
  );
}
