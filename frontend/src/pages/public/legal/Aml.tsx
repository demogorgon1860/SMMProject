import { LegalLayout, type LegalSection } from './LegalLayout';

// Bump this only when the policy text actually changes.
const LAST_UPDATED = '2026-04-27';

const sections: ReadonlyArray<LegalSection> = [
  { id: 'why', title: 'Why this exists' },
  { id: 'kyc', title: 'When we ask for ID' },
  { id: 'sars', title: 'Suspicious activity reports' },
  { id: 'sanctions', title: 'Sanctions' },
  { id: 'questions', title: 'Questions' },
];

export function AmlPage() {
  return (
    <LegalLayout eyebrow="Legal" title="AML Policy" lastUpdated={LAST_UPDATED} sections={sections}>
      <h2 id="why">Why this exists</h2>
      <p>We accept crypto payments and operate within the EU. We comply with applicable anti-money-laundering laws, including EU AMLD6 and equivalent regulations. Cryptocurrency deposits are processed through licensed payment providers that perform their own AML controls on the on-chain side.</p>

      <h2 id="kyc">When we ask for ID</h2>
      <p>We may request KYC documents in narrow cases:</p>
      <ul>
        <li>Cumulative deposits exceed €15,000 in a rolling 12-month period</li>
        <li>The activity on your account requires enhanced due diligence under applicable law</li>
      </ul>
      <p>Most accounts will never see this. When we do request it, the request is encrypted in transit and storage, and documents are deleted after the verification window.</p>

      <h2 id="sars">Suspicious activity reports</h2>
      <p>We comply with applicable laws regarding suspicious activity reporting. Where reporting is legally required, we do not warn affected users.</p>

      <h2 id="sanctions">Sanctions</h2>
      <p>We do not knowingly serve users in OFAC-sanctioned jurisdictions, North Korea, Iran, Syria, Cuba, the Crimea region, Donetsk, or Luhansk.</p>

      <h2 id="questions">Questions</h2>
      <p>Email <code>compliance@smmworld.vip</code>.</p>
    </LegalLayout>
  );
}
