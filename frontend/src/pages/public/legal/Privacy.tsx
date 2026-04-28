import { LegalLayout, type LegalSection } from './LegalLayout';

// Bump this only when the policy text actually changes.
const LAST_UPDATED = '2026-04-27';

const sections: ReadonlyArray<LegalSection> = [
  { id: 'summary', title: 'Plain-English summary' },
  { id: 'collect', title: 'What we collect' },
  { id: 'use', title: 'How we use it' },
  { id: 'share', title: 'Who we share with' },
  { id: 'retain', title: 'How long we keep it' },
  { id: 'rights', title: 'Your rights' },
  { id: 'cookies', title: 'Cookies' },
  { id: 'children', title: 'Children' },
  { id: 'contact', title: 'Contact DPO' },
];

export function PrivacyPage() {
  return (
    <LegalLayout eyebrow="Legal" title="Privacy Policy" lastUpdated={LAST_UPDATED} sections={sections}>
      <h2 id="summary">Plain-English summary</h2>
      <p>We collect the minimum needed to run the service. We don't sell your data. We don't run ads. Crypto-only payments mean we don't even store card details.</p>

      <h2 id="collect">What we collect</h2>
      <ul>
        <li>Account: email, username, hashed password</li>
        <li>Usage: orders you place, links you submit, IP address, user-agent</li>
        <li>Billing: deposit transactions and crypto wallet addresses</li>
        <li>Support: ticket content and attachments you send</li>
      </ul>

      <h2 id="use">How we use it</h2>
      <p>To deliver orders, prevent fraud, comply with law, and improve the service. We do not use your data to train AI or sell to third parties.</p>

      <h2 id="share">Who we share with</h2>
      <p>We share data only with sub-processors necessary to operate (e.g. Cryptomus for payments) under appropriate data-processing agreements.</p>

      <h2 id="retain">How long we keep it</h2>
      <p>Active account data is retained while your account is open. Order history and audit logs are kept 7 years for accounting and AML compliance. After deletion (Profile → Danger Zone), personal data is purged within 30 days.</p>

      <h2 id="rights">Your rights</h2>
      <p>Under GDPR / CCPA you can request access, correction, deletion, or export of your data. Email <code>compliance@smmworld.vip</code> from the address on your account.</p>

      <h2 id="cookies">Cookies</h2>
      <p>We use a single session cookie for authentication and a refresh-token cookie. We do not use tracking or advertising cookies.</p>

      <h2 id="children">Children</h2>
      <p>Service is not directed to children under 18. We delete any account we discover does not meet this requirement.</p>

      <h2 id="contact">Contact DPO</h2>
      <p>Email <code>dpo@smmworld.vip</code>. We respond within 30 days.</p>
    </LegalLayout>
  );
}
