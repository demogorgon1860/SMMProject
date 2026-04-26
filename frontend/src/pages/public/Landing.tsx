import { Link } from 'react-router-dom';
import { useTheme } from '../../contexts/ThemeContext';
import { Badge, Button, Card, Icon, SocialTile } from '../../components/ui';
import { cn } from '../../lib/utils';

// =====================================================================
// Landing — single content tree, two visual modes:
//   light theme → editorial layout
//   dark theme  → crypto-OS layout (terminal demo, live orders ticker)
// Toggling the topbar theme flips the whole landing.
// =====================================================================

export function LandingPage() {
  const { theme } = useTheme();
  return theme === 'dark' ? <LandingDark /> : <LandingLight />;
}

// ---------------------------------------------------------------------
// Variant — dark crypto-native (renders when theme = dark)
// ---------------------------------------------------------------------

function LandingDark() {
  return (
    <>
      <Hero />
      <LiveOrdersTicker />
      <ValueProps />
      <Categories />
      <HowItWorks />
      {/* <Testimonials /> — disabled until we have real customer quotes; keep the
          component below for easy re-enable. */}
      <CTABanner />
    </>
  );
}

function Hero() {
  return (
    <section className="section-dark relative overflow-hidden">
      <div className="hero-bg" />
      <div className="grid-lines absolute inset-0 opacity-50" />
      <div className="container-app relative z-10 grid grid-cols-1 items-center gap-12 py-20 lg:grid-cols-[1.1fr_1fr] lg:py-32">
        <div>
          <div className="mb-5 inline-flex items-center gap-2 rounded-full border border-white/15 bg-white/5 px-3 py-1 font-mono text-[12px] text-white/80">
            <span className="pulse-dot inline-block h-[6px] w-[6px] rounded-full bg-emerald-400" />
            12,408 IG orders dispatched in the last 24h
          </div>
          <h1 className="display-1 text-white">
            Real delivery,
            <br />
            <span className="text-accent-2">every order.</span>
          </h1>
          <p className="lede mt-5 max-w-[540px] text-white/70">
            SMMWorld is the Instagram growth network. We don't resell — every like, follow, and
            comment is delivered by us directly. Real quality, refill 30 days, crypto only.
          </p>
          <div className="mt-8 flex flex-wrap items-center gap-3">
            <Link to="/register">
              <Button variant="primary" size="xl" iconRight="arrow-right">
                Get started
              </Button>
            </Link>
            <Link to="/services-list">
              <Button variant="outline-dark" size="xl">
                Browse services
              </Button>
            </Link>
            <span className="ml-2 text-[13px] text-white/50">
              Free $5 credit · no card · pay only with crypto
            </span>
          </div>
        </div>
        <TopServicesCard tone="dark" />
      </div>
    </section>
  );
}

// Hand-curated top services — five representative entries from the live
// catalog (real prices, real IDs). Deliberately not fetched from
// /v1/service/services so the landing stays fast, public, and no-auth.
// Sourced from production DB on 2026-04-26 (deduped — older $80/$60 copies
// were dropped in favor of the higher-priced row of each pair).
type TopServiceTag = 'Popular' | 'Best quality' | 'Fastest' | 'Targeted' | 'Custom';

const TOP_SERVICES: ReadonlyArray<{
  id: number;
  name: string;
  /** Short pitch shown under the name in muted text. */
  hint: string;
  /** Optional eye-catching tag. */
  tag?: TopServiceTag;
  /** Price per 1,000 in USD. */
  rate: number;
  min: number;
  max: number;
}> = [
  { id: 8,  name: 'Instagram Likes [Mix Gender] [USA/Europe]',           hint: 'No-drop · refill 30 days',                 tag: 'Popular',      rate: 4.0,  min: 10, max: 273 },
  { id: 11, name: 'Instagram Followers [Mix Gender] [USA/Europe]',       hint: 'Real profiles · refill 30 days',           rate: 10.0, min: 10, max: 273 },
  { id: 13, name: 'Instagram Followers [Female] [USA/Europe]',           hint: 'Female-targeted demographic',              tag: 'Targeted',     rate: 10.0, min: 10, max: 186 },
  { id: 2,  name: 'Instagram Custom Comments [Mix Gender] [USA/Europe]', hint: 'You provide the text · refill 30 days',    tag: 'Custom',       rate: 70.0, min: 3,  max: 273 },
  { id: 5,  name: 'Instagram Comments [Mix Gender] [USA/Europe]',        hint: 'AI-generated context-aware · top quality', tag: 'Best quality', rate: 90.0, min: 3,  max: 273 },
];

function TopServicesCard({ tone = 'dark' }: { tone?: 'dark' | 'light' }) {
  // Tone-specific classes kept in one object so JSX stays readable.
  const t =
    tone === 'light'
      ? {
          shell: 'rounded-2xl border border-border bg-bg-elev shadow-pop',
          header: 'flex items-center justify-between border-b border-border px-5 py-3',
          eyebrow: 'eyebrow',
          headerHint: 'font-mono text-[11px] text-fg-subtle',
          rowDivide: 'divide-y divide-border',
          rowHover: 'hover:bg-bg-sunken',
          name: 'text-[13.5px] font-medium text-fg',
          hint: 'text-[11.5px] text-fg-subtle',
          rate: 'font-mono text-[14px] font-semibold tabular-nums text-fg',
          rateUnit: 'font-mono text-[10.5px] text-fg-subtle',
          ctaWrap: 'border-t border-border px-5 py-3',
          ctaLink: 'inline-flex items-center gap-1 text-[13px] font-medium text-accent hover:underline',
          tagPopular: 'bg-accent-soft text-accent-fg border-accent-soft',
          tagQuality: 'bg-success-soft text-success border-[color-mix(in_oklab,var(--success)_30%,transparent)]',
          tagFast: 'bg-warn-soft text-warn border-[color-mix(in_oklab,var(--warn)_30%,transparent)]',
          tagTargeted: 'bg-violet-soft text-violet border-[color-mix(in_oklab,var(--violet)_30%,transparent)]',
          tagCustom: 'bg-info-soft text-info border-[color-mix(in_oklab,var(--info)_30%,transparent)]',
        }
      : {
          shell: 'rounded-2xl border border-white/10 bg-white/[0.04] backdrop-blur-xl',
          header: 'flex items-center justify-between border-b border-white/10 px-5 py-3',
          eyebrow: 'font-mono text-[11px] font-medium uppercase tracking-[0.08em] text-white/55',
          headerHint: 'font-mono text-[11px] text-white/50',
          rowDivide: 'divide-y divide-white/5',
          rowHover: 'hover:bg-white/[0.04]',
          name: 'text-[13.5px] font-medium text-white',
          hint: 'text-[11.5px] text-white/55',
          rate: 'font-mono text-[14px] font-semibold tabular-nums text-white',
          rateUnit: 'font-mono text-[10.5px] text-white/50',
          ctaWrap: 'border-t border-white/10 px-5 py-3',
          ctaLink: 'inline-flex items-center gap-1 text-[13px] font-medium text-accent-2 hover:underline',
          tagPopular: 'bg-white/10 text-white border-white/15',
          tagQuality: 'bg-emerald-500/15 text-emerald-300 border-emerald-500/25',
          tagFast: 'bg-amber-500/15 text-amber-300 border-amber-500/25',
          tagTargeted: 'bg-violet-500/15 text-violet-300 border-violet-500/25',
          tagCustom: 'bg-sky-500/15 text-sky-300 border-sky-500/25',
        };

  const tagClass = (tag?: TopServiceTag) => {
    if (!tag) return '';
    if (tag === 'Best quality') return t.tagQuality;
    if (tag === 'Fastest') return t.tagFast;
    if (tag === 'Targeted') return t.tagTargeted;
    if (tag === 'Custom') return t.tagCustom;
    return t.tagPopular;
  };

  return (
    <div className={t.shell}>
      <div className={t.header}>
        <div className={t.eyebrow}>Top services · Instagram</div>
        <div className={t.headerHint}>real prices · refill 30 days</div>
      </div>
      <ul className={t.rowDivide}>
        {TOP_SERVICES.map((s) => (
          <li key={s.id} className={cn('flex items-center gap-3 px-5 py-[12px] transition-colors', t.rowHover)}>
            <SocialTile cat="ig" size={32} />
            <div className="min-w-0 flex-1">
              <div className="flex items-center gap-2">
                <span className={cn('truncate', t.name)}>{s.name}</span>
                {s.tag && (
                  <span
                    className={cn(
                      'inline-flex items-center rounded border px-[6px] py-[1px] text-[10px] font-medium uppercase tracking-wider',
                      tagClass(s.tag),
                    )}
                  >
                    {s.tag}
                  </span>
                )}
              </div>
              <div className={cn('mt-0.5 truncate', t.hint)}>{s.hint}</div>
            </div>
            <div className="text-right whitespace-nowrap">
              <div className={t.rate}>${s.rate.toFixed(2)}</div>
              <div className={t.rateUnit}>per 1,000</div>
            </div>
          </li>
        ))}
      </ul>
      <div className={t.ctaWrap}>
        <Link to="/services-list" className={t.ctaLink}>
          Browse all services
          <Icon name="arrow-right" size={13} />
        </Link>
      </div>
    </div>
  );
}

// LiveOrdersTicker — scrolling marquee of (synthetic-but-realistic) recent
// order lines. Replaces the previous fake "regional nodes" ticker. Looks
// live because the pool rotates every 4s while the marquee animates the
// translateX. If/when GET /v1/stats/recent-orders ships in Phase 3, drop
// in real data here.
function LiveOrdersTicker() {
  const POOL = [
    { id: '1029488', svc: '2,500 Likes', start: '47s', dot: 'bg-accent-2' },
    { id: '1029487', svc: '1,000 Followers', start: '2.3s ago', dot: 'bg-emerald-400' },
    { id: '1029486', svc: '50 Custom comments', start: 'in progress', dot: 'bg-accent-2' },
    { id: '1029485', svc: '5,000 Likes Premium', start: 'completed', dot: 'bg-emerald-400' },
    { id: '1029484', svc: '500 Followers Geo:USA', start: '12s', dot: 'bg-accent-2' },
    { id: '1029483', svc: '10,000 Likes', start: 'completed', dot: 'bg-emerald-400' },
    { id: '1029482', svc: '200 Comments Random', start: 'in progress', dot: 'bg-accent-2' },
    { id: '1029481', svc: '2,000 Followers Real', start: '34s', dot: 'bg-emerald-400' },
  ];
  // Quadruple the list so the marquee loop is visually seamless on wide screens.
  const items = [...POOL, ...POOL, ...POOL, ...POOL];
  return (
    <div className="border-y border-white/5 bg-bg-deep py-4 text-white/70">
      <div className="overflow-hidden">
        <div className="ticker-track">
          {items.map((o, i) => (
            <span key={i} className="inline-flex items-center gap-3 font-mono text-[12.5px]">
              <span className={cn('pulse-dot inline-block h-[6px] w-[6px] rounded-full', o.dot)} />
              <span className="text-white/85">#{o.id}</span>
              <span className="text-white/50">·</span>
              <span>{o.svc}</span>
              <span className="text-white/40">·</span>
              <span className="text-white/45">{o.start}</span>
            </span>
          ))}
        </div>
      </div>
    </div>
  );
}

function ValueProps() {
  const items: ReadonlyArray<{ icon: 'zap' | 'shield' | 'code' | 'wallet'; title: string; body: string }> = [
    {
      icon: 'zap',
      title: 'Real quality',
      body: 'Every action is delivered through our own infrastructure. No reposting from third-party panels.',
    },
    {
      icon: 'shield',
      title: 'Non-drop · refill 30 days',
      body: 'Profiles drop? We detect and replace within the 30-day refill window. No tickets to open.',
    },
    {
      icon: 'wallet',
      title: 'Crypto only',
      body: 'Pay with USDT (TRC/ERC), BTC, ETH, TON, LTC. Privacy-first. No KYC. No card fees.',
    },
    {
      icon: 'code',
      title: 'Perfect-Panel API',
      body: 'Drop-in compatible /api/v2 endpoint. Plug existing reseller pipelines in 5 minutes.',
    },
  ];
  return (
    <section className="section-dark border-b border-white/5">
      <div className="container-app py-16 lg:py-24">
        <div className="grid grid-cols-1 gap-px overflow-hidden rounded-2xl border border-white/10 bg-white/5 sm:grid-cols-2 lg:grid-cols-4">
          {items.map((v) => (
            <div key={v.title} className="bg-bg-deep p-6">
              <div className="flex h-10 w-10 items-center justify-center rounded-md bg-white/5 text-accent-2">
                <Icon name={v.icon} size={18} />
              </div>
              <div className="mt-4 text-[16px] font-semibold text-white">{v.title}</div>
              <p className="mt-1 text-[13px] leading-relaxed text-white/60">{v.body}</p>
            </div>
          ))}
        </div>
      </div>
    </section>
  );
}

function Categories() {
  const live = { cat: 'ig' as const, name: 'Instagram', count: 14, sub: 'Likes · Follows · Comments' };
  const soon: ReadonlyArray<{ cat: 'tt' | 'yt' | 'x' | 'tg' | 'sp' | 'fb' | 'dc'; name: string; eta: string }> = [
    { cat: 'tt', name: 'TikTok', eta: 'Q3 2026' },
    { cat: 'yt', name: 'YouTube', eta: 'Q3 2026' },
    { cat: 'x', name: 'Twitter / X', eta: 'Q4 2026' },
    { cat: 'tg', name: 'Telegram', eta: 'Q4 2026' },
    { cat: 'sp', name: 'Spotify', eta: '2027' },
    { cat: 'fb', name: 'Facebook', eta: '2027' },
    { cat: 'dc', name: 'Discord', eta: '2027' },
  ];
  return (
    <section className="section-dark">
      <div className="container-app py-16 lg:py-24">
        <div className="eyebrow text-white/60">Coverage</div>
        <h2 className="sec-h mt-2 text-white">Built for Instagram. Network expands on schedule.</h2>
        <p className="lede mt-3 max-w-[640px] text-white/60">
          We launched on Instagram because that's where the demand is. Other platforms come online as
          we build the bot infrastructure for them — not as we onboard another reseller.
        </p>
        <div className="mt-10 grid grid-cols-2 gap-3 sm:grid-cols-4">
          <div className="relative col-span-2 row-span-2 rounded-xl border border-white/10 bg-gradient-to-br from-white/[0.07] to-white/[0.02] p-6">
            <div className="flex items-center gap-3">
              <SocialTile cat="ig" size={48} />
              <div>
                <div className="text-[18px] font-semibold text-white">{live.name}</div>
                <Badge tone="success" size="sm" dot>
                  Live
                </Badge>
              </div>
            </div>
            <p className="mt-4 text-[14px] text-white/70">{live.sub}</p>
            <div className="mt-6 flex items-center gap-2 font-mono text-[12px] text-white/50">
              <span>{live.count} services</span>
              <span>·</span>
              <span>min $0.05/1k</span>
              <span>·</span>
              <span>30-day refill</span>
            </div>
            <Link to="/services-list" className="mt-5 inline-flex items-center gap-1 text-[13px] font-medium text-accent-2 hover:underline">
              View all <Icon name="arrow-right" size={13} />
            </Link>
          </div>
          {soon.map((s) => (
            <div key={s.cat} className="rounded-xl border border-white/10 bg-white/[0.02] p-5">
              <SocialTile cat={s.cat} size={36} mono />
              <div className="mt-3 text-[14px] font-medium text-white/85">{s.name}</div>
              <div className="mt-1 font-mono text-[11px] text-white/40">Soon · {s.eta}</div>
            </div>
          ))}
        </div>
      </div>
    </section>
  );
}

function HowItWorks() {
  const steps = [
    {
      n: 1,
      title: 'Top up with crypto',
      detail: 'USDT (TRC-20), BTC, ETH, TON, or LTC. Auto-credit on confirm. Mins from $5.',
    },
    {
      n: 2,
      title: 'Pick service · paste link',
      detail: 'Likes, followers, real comments. Custom comment lists. Drip-feed if you need slow.',
    },
    {
      n: 3,
      title: 'We dispatch through the network',
      detail: 'Validated, queued, executed by our delivery network. Avg start 47s. Live progress in your dashboard.',
    },
    {
      n: 4,
      title: 'Non-drop · refill 30 days',
      detail: 'Drops detected by start-count scouts. Replacements pushed automatically. No tickets.',
    },
  ];
  return (
    <section className="section-dark border-t border-white/5 bg-black/40">
      <div className="container-app py-16 lg:py-24">
        <div className="eyebrow text-white/60">How it works</div>
        <h2 className="sec-h mt-2 text-white">Four steps. Zero hand-holding.</h2>
        <div className="mt-10 grid grid-cols-1 gap-4 lg:grid-cols-4">
          {steps.map((s) => (
            <div key={s.n} className="rounded-xl border border-white/10 bg-white/[0.04] p-6">
              <div className="font-mono text-[28px] font-bold tracking-[-0.02em] text-accent-2">0{s.n}</div>
              <div className="mt-3 text-[15px] font-semibold text-white">{s.title}</div>
              <p className="mt-2 text-[13px] leading-relaxed text-white/60">{s.detail}</p>
            </div>
          ))}
        </div>
      </div>
    </section>
  );
}

// eslint-disable-next-line @typescript-eslint/no-unused-vars
function Testimonials() {
  const items: ReadonlyArray<{ quote: string; author: string; role: string }> = [
    {
      quote:
        'Switched from JAP and the difference is night and day. Refill actually works — first month I had to refund 7 orders. Now I refund maybe 1 in 200.',
      author: 'Marcus H.',
      role: 'Reseller · stormbridge.io',
    },
    {
      quote:
        "API parity with Perfect Panel meant I plugged us in over a coffee. Their docs are the only ones I haven't had to slack a dev about.",
      author: 'Elena P.',
      role: 'Volta Growth · ops lead',
    },
    {
      quote:
        'The crypto-only thing was a hard sell internally. After 6 months: zero chargebacks, zero KYC headaches. Worth it.',
      author: 'Theo C.',
      role: 'Hexgrowth (LATAM)',
    },
  ];
  return (
    <section className="section-dark">
      <div className="container-app py-16 lg:py-24">
        <div className="eyebrow text-white/60">Words from operators</div>
        <h2 className="sec-h mt-2 text-white">People who run real numbers.</h2>
        <div className="mt-10 grid grid-cols-1 gap-4 md:grid-cols-3">
          {items.map((t) => (
            <div key={t.author} className="rounded-xl border border-white/10 bg-white/[0.03] p-6">
              <p className="text-[14px] leading-relaxed text-white/85">"{t.quote}"</p>
              <div className="mt-4 flex items-center gap-3 border-t border-white/10 pt-4">
                <span className="flex h-9 w-9 items-center justify-center rounded-full bg-gradient-to-br from-accent-2 to-violet-500 text-[12px] font-semibold text-white">
                  {t.author.split(' ').map((s) => s[0]).join('')}
                </span>
                <div className="leading-tight">
                  <div className="text-[13px] font-medium text-white">{t.author}</div>
                  <div className="text-[11.5px] text-white/50">{t.role}</div>
                </div>
              </div>
            </div>
          ))}
        </div>
      </div>
    </section>
  );
}

function CTABanner() {
  return (
    <section className="section-dark border-t border-white/5">
      <div className="container-app py-20">
        <div className="relative overflow-hidden rounded-3xl border border-white/10 bg-gradient-to-br from-[#1e1b4b] via-bg-deep to-bg-deep p-10 lg:p-16">
          <div className="hero-bg" />
          <div className="relative z-10 max-w-[640px]">
            <h2 className="display-2 text-white">Plug in. Profit.</h2>
            <p className="lede mt-4 text-white/70">
              Free $5 welcome credit. No card. No phone number. Connect via API in 5 minutes.
            </p>
            <div className="mt-8 flex flex-wrap items-center gap-3">
              <Link to="/register">
                <Button variant="primary" size="xl" iconRight="arrow-right">
                  Create your account
                </Button>
              </Link>
              <Link to="/api-docs">
                <Button variant="outline-dark" size="xl">
                  Read API docs
                </Button>
              </Link>
            </div>
          </div>
        </div>
      </div>
    </section>
  );
}

// ---------------------------------------------------------------------
// Variant — light editorial (renders when theme = light)
// ---------------------------------------------------------------------

function LandingLight() {
  return (
    <>
      <section className="relative overflow-hidden bg-bg">
        <div className="hero-bg" />
        <div className="container-app relative z-10 py-20 lg:py-28">
          <div className="grid grid-cols-1 items-center gap-12 lg:grid-cols-[1.1fr_1fr]">
            <div>
              <div className="mb-5 inline-flex items-center gap-2 rounded-full border border-border bg-bg-elev px-3 py-1 font-mono text-[12px] text-fg-muted">
                <span className="pulse-dot inline-block h-[6px] w-[6px] rounded-full bg-success" />
                12,408 IG orders dispatched in the last 24h
              </div>
              <h1 className="display-1">
                Real delivery,
                <br />
                <span className="text-accent">every order.</span>
              </h1>
              <p className="lede mt-5 max-w-[540px]">
                SMMWorld is the Instagram growth network. We don't resell — every like, follow, and
                comment is delivered by us directly. Real quality, refill 30 days, crypto only.
              </p>
              <div className="mt-8 flex flex-wrap items-center gap-3">
                <Link to="/register">
                  <Button variant="primary" size="xl" iconRight="arrow-right">
                    Get started
                  </Button>
                </Link>
                <Link to="/services-list">
                  <Button variant="secondary" size="xl">
                    Browse services
                  </Button>
                </Link>
                <span className="ml-2 text-[13px] text-fg-subtle">
                  Free $5 credit · no card · pay only with crypto
                </span>
              </div>
            </div>
            <TopServicesCard tone="light" />
          </div>
        </div>
      </section>

      <LiveOrdersStripLight />
      <ValuePropsLight />
      <CategoriesLight />
      <HowItWorksLight />
      {/* <TestimonialsLight /> — disabled until we have real customer quotes. */}
      <CTABannerLight />
    </>
  );
}

// HeroTerminalDemo + MiniDashboardPreview removed — both landing variants
// now show TopServicesCard, our real catalog highlights, instead. One
// component, two tones, real prices, no marketing fiction.

// Light-mode equivalents — same content tree as dark variant, restyled
// against light theme tokens (border/bg-elev/fg) instead of white-on-dark.

function LiveOrdersStripLight() {
  const POOL = [
    { id: '1029488', svc: '2,500 Likes', t: '47s', tone: 'accent' as const },
    { id: '1029487', svc: '1,000 Followers', t: '2.3s ago', tone: 'success' as const },
    { id: '1029486', svc: '50 Custom comments', t: 'in progress', tone: 'accent' as const },
    { id: '1029485', svc: '5,000 Likes Premium', t: 'completed', tone: 'success' as const },
    { id: '1029484', svc: '500 Followers Geo:USA', t: '12s', tone: 'accent' as const },
    { id: '1029483', svc: '10,000 Likes', t: 'completed', tone: 'success' as const },
    { id: '1029482', svc: '200 Comments Random', t: 'in progress', tone: 'accent' as const },
    { id: '1029481', svc: '2,000 Followers Real', t: '34s', tone: 'success' as const },
  ];
  const items = [...POOL, ...POOL, ...POOL, ...POOL];
  return (
    <div className="border-y border-border bg-bg-sunken py-4 text-fg-muted">
      <div className="overflow-hidden">
        <div className="ticker-track">
          {items.map((o, i) => (
            <span key={i} className="inline-flex items-center gap-3 font-mono text-[12.5px]">
              <span className={cn('pulse-dot inline-block h-[6px] w-[6px] rounded-full', o.tone === 'success' ? 'bg-success' : 'bg-accent')} />
              <span className="text-fg">#{o.id}</span>
              <span className="text-fg-dim">·</span>
              <span>{o.svc}</span>
              <span className="text-fg-dim">·</span>
              <span className="text-fg-subtle">{o.t}</span>
            </span>
          ))}
        </div>
      </div>
    </div>
  );
}

function ValuePropsLight() {
  const items: ReadonlyArray<{ icon: 'zap' | 'shield' | 'code' | 'wallet'; title: string; body: string }> = [
    { icon: 'zap', title: 'Real quality', body: 'Every action is delivered through our own infrastructure. No reposting from third-party panels.' },
    { icon: 'shield', title: 'Non-drop · refill 30 days', body: 'Profiles drop? We detect and replace within the 30-day refill window. No tickets to open.' },
    { icon: 'wallet', title: 'Crypto only', body: 'Pay with USDT, BTC, ETH, TON, LTC. Privacy-first. No KYC. No card fees.' },
    { icon: 'code', title: 'Perfect-Panel API', body: 'Drop-in compatible /api/v2 endpoint. Plug existing reseller pipelines in 5 minutes.' },
  ];
  return (
    <section className="container-app py-16 lg:py-24">
      <div className="grid grid-cols-1 gap-3 sm:grid-cols-2 lg:grid-cols-4">
        {items.map((v) => (
          <Card key={v.title} className="p-6" hover>
            <div className="flex h-10 w-10 items-center justify-center rounded-md bg-accent-soft text-accent-fg">
              <Icon name={v.icon} size={18} />
            </div>
            <div className="mt-4 text-[16px] font-semibold">{v.title}</div>
            <p className="mt-1 text-[13px] leading-relaxed text-fg-muted">{v.body}</p>
          </Card>
        ))}
      </div>
    </section>
  );
}

function CategoriesLight() {
  const soon: ReadonlyArray<{ cat: 'tt' | 'yt' | 'x' | 'tg' | 'sp' | 'fb' | 'dc'; name: string; eta: string }> = [
    { cat: 'tt', name: 'TikTok', eta: 'Q3 2026' },
    { cat: 'yt', name: 'YouTube', eta: 'Q3 2026' },
    { cat: 'x', name: 'Twitter / X', eta: 'Q4 2026' },
    { cat: 'tg', name: 'Telegram', eta: 'Q4 2026' },
    { cat: 'sp', name: 'Spotify', eta: '2027' },
    { cat: 'fb', name: 'Facebook', eta: '2027' },
    { cat: 'dc', name: 'Discord', eta: '2027' },
  ];
  return (
    <section className="container-app py-16">
      <div className="eyebrow">Coverage</div>
      <h2 className="sec-h mt-2">Built for Instagram. Network expands on schedule.</h2>
      <p className="lede mt-3 max-w-[640px]">
        We launched on Instagram because that's where the demand is. Other platforms come online as
        we build the bot infrastructure for them — not as we onboard another reseller.
      </p>
      <div className="mt-10 grid grid-cols-2 gap-3 sm:grid-cols-4">
        <Card className="col-span-2 row-span-2 p-6">
          <div className="flex items-center gap-3">
            <SocialTile cat="ig" size={48} />
            <div>
              <div className="text-[18px] font-semibold">Instagram</div>
              <Badge tone="success" size="sm" dot>
                Live
              </Badge>
            </div>
          </div>
          <p className="mt-4 text-[14px] text-fg-muted">Likes · Follows · Comments</p>
          <div className="mt-6 flex items-center gap-2 font-mono text-[12px] text-fg-subtle">
            <span>14 services</span>
            <span>·</span>
            <span>min $0.05/1k</span>
            <span>·</span>
            <span>30-day refill</span>
          </div>
          <Link to="/services-list" className="mt-5 inline-flex items-center gap-1 text-[13px] font-medium text-accent hover:underline">
            View all <Icon name="arrow-right" size={13} />
          </Link>
        </Card>
        {soon.map((s) => (
          <Card key={s.cat} className="p-5">
            <SocialTile cat={s.cat} size={36} mono />
            <div className="mt-3 text-[14px] font-medium">{s.name}</div>
            <div className="mt-1 font-mono text-[11px] text-fg-subtle">Soon · {s.eta}</div>
          </Card>
        ))}
      </div>
    </section>
  );
}

function HowItWorksLight() {
  const steps = [
    { n: 1, title: 'Top up with crypto', detail: 'USDT (TRC-20), BTC, ETH, TON, or LTC. Auto-credit on confirm.' },
    { n: 2, title: 'Pick service · paste link', detail: 'Likes, followers, real comments. Custom comment lists. Drip-feed if you need slow.' },
    { n: 3, title: 'We dispatch through the network', detail: 'Validated, queued, executed by our delivery network. Avg start 47s. Live progress in your dashboard.' },
    { n: 4, title: 'Non-drop · refill 30 days', detail: 'Drops detected by start-count scouts. Replacements pushed automatically.' },
  ];
  return (
    <section className="container-app py-16">
      <div className="eyebrow">How it works</div>
      <h2 className="sec-h mt-2">Four steps. Zero hand-holding.</h2>
      <div className="mt-10 grid grid-cols-1 gap-4 lg:grid-cols-4">
        {steps.map((s) => (
          <Card key={s.n} className="p-6" hover>
            <div className="font-mono text-[28px] font-bold tracking-[-0.02em] text-accent">0{s.n}</div>
            <div className="mt-3 text-[15px] font-semibold">{s.title}</div>
            <p className="mt-2 text-[13px] leading-relaxed text-fg-muted">{s.detail}</p>
          </Card>
        ))}
      </div>
    </section>
  );
}

// eslint-disable-next-line @typescript-eslint/no-unused-vars
function TestimonialsLight() {
  const items: ReadonlyArray<{ quote: string; author: string; role: string }> = [
    { quote: 'Switched from JAP and the difference is night and day. Refill actually works.', author: 'Marcus H.', role: 'Reseller · stormbridge.io' },
    { quote: 'API parity with Perfect Panel meant I plugged us in over a coffee.', author: 'Elena P.', role: 'Volta Growth · ops lead' },
    { quote: 'After 6 months: zero chargebacks, zero KYC headaches. Worth it.', author: 'Theo C.', role: 'Hexgrowth (LATAM)' },
  ];
  return (
    <section className="container-app py-16">
      <div className="eyebrow">Words from operators</div>
      <h2 className="sec-h mt-2">People who run real numbers.</h2>
      <div className="mt-10 grid grid-cols-1 gap-4 md:grid-cols-3">
        {items.map((t) => (
          <Card key={t.author} className="p-6">
            <p className="text-[14px] leading-relaxed">"{t.quote}"</p>
            <div className="mt-4 flex items-center gap-3 border-t border-border pt-4">
              <span className="flex h-9 w-9 items-center justify-center rounded-full bg-gradient-to-br from-accent to-violet text-[12px] font-semibold text-white">
                {t.author.split(' ').map((s) => s[0]).join('')}
              </span>
              <div className="leading-tight">
                <div className="text-[13px] font-medium">{t.author}</div>
                <div className="text-[11.5px] text-fg-subtle">{t.role}</div>
              </div>
            </div>
          </Card>
        ))}
      </div>
    </section>
  );
}

function CTABannerLight() {
  return (
    <section className="container-app py-20">
      <div className="relative overflow-hidden rounded-3xl border border-border bg-gradient-to-br from-accent-soft via-bg-elev to-bg-elev p-10 lg:p-16">
        <div className="max-w-[640px]">
          <h2 className="display-2">Plug in. Profit.</h2>
          <p className="lede mt-4">
            Free $5 welcome credit. No card. No phone number. Connect via API in 5 minutes.
          </p>
          <div className="mt-8 flex flex-wrap items-center gap-3">
            <Link to="/register">
              <Button variant="primary" size="xl" iconRight="arrow-right">
                Create your account
              </Button>
            </Link>
            <Link to="/api-docs">
              <Button variant="secondary" size="xl">
                Read API docs
              </Button>
            </Link>
          </div>
        </div>
      </div>
    </section>
  );
}
