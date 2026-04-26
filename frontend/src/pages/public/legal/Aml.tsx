import { LegalLayout, type LegalSection } from './LegalLayout';

const sections: ReadonlyArray<LegalSection> = [
  { id: 'why', title: 'Why this exists' },
  { id: 'screen', title: 'Wallet screening' },
  { id: 'kyc', title: 'When we ask for ID' },
  { id: 'sars', title: 'Suspicious activity reports' },
  { id: 'sanctions', title: 'Sanctions' },
  { id: 'questions', title: 'Questions' },
];

export function AmlPage() {
  return (
    <LegalLayout eyebrow="Legal" title="AML Policy" lastUpdated="2026-04-15" sections={sections}>
      <h2 id="why">Why this exists</h2>
      <p>We're a crypto-accepting business operating in the EU. We screen for compliance with EU AMLD6 and equivalent regulations.</p>

      <h2 id="screen">Wallet screening</h2>
      <p>Every incoming deposit is screened against sanction lists and known mixer/darknet flows via our payment processor. Flagged deposits are held until cleared.</p>

      <h2 id="kyc">When we ask for ID</h2>
      <p>We may request KYC documents in narrow cases:</p>
      <ul>
        <li>Cumulative deposits exceed €15,000 in a rolling 12-month period</li>
        <li>The wallet screening flags require enhanced due diligence</li>
        <li>You request a withdrawal larger than €10,000 in a single transaction</li>
      </ul>
      <p>Most accounts will never see this. When we do request it, the request is encrypted in transit and storage, and documents are deleted after the verification window.</p>

      <h2 id="sars">Suspicious activity reports</h2>
      <p>We file SARs with the appropriate Financial Intelligence Unit when legally required. We do not warn affected users in such cases.</p>

      <h2 id="sanctions">Sanctions</h2>
      <p>We do not knowingly serve users in OFAC-sanctioned jurisdictions, North Korea, Iran, Syria, Cuba, the Crimea region, Donetsk, or Luhansk.</p>

      <h2 id="questions">Questions</h2>
      <p>Email <code>compliance@smmworld.vip</code>.</p>
    </LegalLayout>
  );
}
