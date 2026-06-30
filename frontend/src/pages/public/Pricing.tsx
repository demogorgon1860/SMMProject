import { Link } from 'react-router-dom';
import { Button, Card, Icon } from '../../components/ui';

// Pricing — explainer page (no tier system, no volume discounts).
// Single transparent rate per service, listed in /services-list.
// Original Claude Design tiers (Starter/Pro/Elite/Network) were cut
// per user direction; replaced with a "How pricing works" page.
export function PricingPage() {
  const points = [
    {
      icon: 'wallet' as const,
      title: 'Pay per 1k actions',
      body: 'Every service has a flat rate per 1,000 actions. No subscriptions, no commitments. Order 50 likes or 50,000 — same per-unit price.',
    },
    {
      icon: 'shield' as const,
      title: 'Same price for everyone',
      body: 'No tiers. No volume discounts. No "talk to sales" rates. The price you see in the catalog is the price you pay.',
    },
    {
      icon: 'coin' as const,
      title: 'Crypto-only fees',
      body: 'You pay only the network fee on top-up. We don\'t mark up the exchange rate.',
    },
    {
      icon: 'refresh' as const,
      title: 'Refill is included',
      body: 'Drop replacements are free for the lifetime of the order — no time limit. Most panels charge separately, or cap it at 30 days. We don\'t.',
    },
  ];
  return (
    <div className="container-narrow py-16">
      <div className="text-center">
        <div className="eyebrow">Pricing</div>
        <h1 className="display-2 mt-3">One transparent rate. No surprises.</h1>
        <p className="lede mt-4 mx-auto max-w-[620px]">
          We don't run discount tiers or volume games. Every reseller pays the same. The goal is
          predictable cost — so you can quote your own customers without doing margin math.
        </p>
        <div className="mt-8 flex justify-center gap-3">
          {/* TEMP: services catalog hidden — restore with the Services nav link
          <Link to="/services-list">
            <Button variant="primary" size="xl" iconRight="arrow-right">
              See the catalog
            </Button>
          </Link>
          */}
          <Link to="/docs">
            <Button variant="secondary" size="xl">
              API docs
            </Button>
          </Link>
        </div>
      </div>

      <div className="mt-16 grid grid-cols-1 gap-4 sm:grid-cols-2">
        {points.map((p) => (
          <Card key={p.title} className="p-7" hover>
            <div className="flex h-10 w-10 items-center justify-center rounded-md bg-accent-soft text-accent-fg">
              <Icon name={p.icon} size={18} />
            </div>
            <div className="mt-4 text-[16px] font-semibold">{p.title}</div>
            <p className="mt-1 text-[13.5px] leading-relaxed text-fg-muted">{p.body}</p>
          </Card>
        ))}
      </div>

      <Card className="mt-10 p-8 text-center" style={{ background: 'var(--bg-sunken)' }}>
        <div className="text-[16px] font-semibold">Custom integration or enterprise volume?</div>
        <p className="mt-1 text-[14px] text-fg-muted">
          We can prioritize feature requests for accounts pushing serious volume. Reach out.
        </p>
        <div className="mt-4">
          <Link to="/help?topic=enterprise">
            <Button variant="secondary" size="md">
              Talk to engineering
            </Button>
          </Link>
        </div>
      </Card>
    </div>
  );
}
