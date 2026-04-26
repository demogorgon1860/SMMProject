import { Donut, Icon, Sparkline, StatusBadge } from '../../components/ui';

// Mobile — marketing showcase of phone frames. Static. Each phone
// frame shows a different flow (login, dashboard, catalog, etc.).
export function MobilePage() {
  return (
    <div className="container-app py-14">
      <div className="text-center">
        <div className="eyebrow">On the go</div>
        <h1 className="display-2 mt-2">Built mobile-first.</h1>
        <p className="lede mt-3 mx-auto max-w-[560px]">
          The web app collapses into clean responsive layouts on mobile. A native iOS app is on the
          roadmap — drop your email below to be notified.
        </p>
      </div>

      <div className="mt-14 overflow-x-auto">
        <div className="flex gap-8 px-2 pb-6">
          <PhoneFrame title="Sign in" caption="Passkey-first.">
            <PhoneLogin />
          </PhoneFrame>
          <PhoneFrame title="Dashboard" caption="At-a-glance.">
            <PhoneDashboard />
          </PhoneFrame>
          <PhoneFrame title="Catalog" caption="Search, pick, order.">
            <PhoneCatalog />
          </PhoneFrame>
          <PhoneFrame title="Order detail" caption="Live progress.">
            <PhoneOrderDetail />
          </PhoneFrame>
          <PhoneFrame title="Add funds" caption="QR + countdown.">
            <PhoneAddFunds />
          </PhoneFrame>
        </div>
      </div>

      <div className="mt-12 mx-auto max-w-[420px] text-center">
        <div className="text-[14px] text-fg-muted">Notify me when iOS launches:</div>
        <form
          className="mt-3 flex gap-2"
          onSubmit={(e) => {
            e.preventDefault();
            alert('Added to mobile waitlist.');
          }}
        >
          <input
            type="email"
            placeholder="you@example.com"
            className="h-[40px] flex-1 rounded-md border border-border-strong bg-bg-elev px-3 text-[14px]"
          />
          <button type="submit" className="rounded-md bg-accent px-4 text-[13px] font-semibold text-white">
            Notify me
          </button>
        </form>
      </div>
    </div>
  );
}

function PhoneFrame({ children, title, caption }: { children: React.ReactNode; title: string; caption: string }) {
  return (
    <div className="flex flex-none flex-col items-center">
      <div className="phone-frame">
        <div className="notch" />
        <div className="h-full w-full overflow-hidden">{children}</div>
      </div>
      <div className="mt-4 text-center">
        <div className="font-mono text-[11px] text-fg-subtle">01</div>
        <div className="mt-1 text-[14px] font-semibold">{title}</div>
        <div className="text-[12px] text-fg-muted">{caption}</div>
      </div>
    </div>
  );
}

function PhoneLogin() {
  return (
    <div className="section-dark relative h-full overflow-hidden p-6 pt-12 text-white">
      <div className="hero-bg" />
      <div className="relative">
        <div className="font-mono text-[10px] uppercase tracking-wider text-white/60">SMMWorld</div>
        <h2 className="mt-3 text-[22px] font-bold tracking-[-0.02em]">Welcome back.</h2>
        <p className="mt-1 text-[12px] text-white/60">Sign in to your account.</p>
        <div className="mt-6 space-y-3">
          <input className="h-[40px] w-full rounded-md border border-white/15 bg-white/5 px-3 text-[13px]" placeholder="you@example.com" />
          <input className="h-[40px] w-full rounded-md border border-white/15 bg-white/5 px-3 text-[13px]" type="password" placeholder="••••••••" />
          <button className="h-[42px] w-full rounded-md bg-accent text-[13px] font-semibold">Sign in</button>
        </div>
        <div className="mt-5 text-center text-[12px] text-white/50">or</div>
        <button className="mt-3 h-[42px] w-full rounded-md border border-white/20 text-[13px]">Sign in with passkey</button>
      </div>
    </div>
  );
}

function PhoneDashboard() {
  return (
    <div className="h-full bg-bg p-4 pt-10">
      <div className="text-[10px] uppercase tracking-wider text-fg-subtle">Wallet</div>
      <div className="mt-1 font-mono text-[26px] font-bold">$184.52</div>
      <div className="mt-3 grid grid-cols-2 gap-2">
        <div className="rounded-md border border-border bg-bg-elev p-3">
          <div className="text-[10px] text-fg-subtle">Active</div>
          <div className="mt-1 font-mono text-[16px] font-bold">3</div>
        </div>
        <div className="rounded-md border border-border bg-bg-elev p-3">
          <div className="text-[10px] text-fg-subtle">30d spent</div>
          <div className="mt-1 font-mono text-[16px] font-bold">$87.20</div>
        </div>
      </div>
      <div className="mt-4 text-[10px] uppercase tracking-wider text-fg-subtle">Recent</div>
      {[
        { id: '#1029488', s: 'Likes — Standard', q: '2.5k', st: 'in_progress' },
        { id: '#1029487', s: 'Followers — Real', q: '1k', st: 'completed' },
        { id: '#1029486', s: 'Comments', q: '50', st: 'completed' },
      ].map((o) => (
        <div key={o.id} className="mt-2 flex items-center justify-between rounded-md border border-border bg-bg-elev p-3">
          <div>
            <div className="font-mono text-[11px] text-fg-muted">{o.id}</div>
            <div className="text-[12px] font-medium">{o.s}</div>
          </div>
          <StatusBadge status={o.st} size="sm" />
        </div>
      ))}
    </div>
  );
}

function PhoneCatalog() {
  return (
    <div className="h-full bg-bg p-4 pt-10">
      <input className="h-[36px] w-full rounded-md border border-border bg-bg-elev px-3 text-[12px]" placeholder="Search services" />
      <div className="mt-3 flex gap-2 overflow-x-auto">
        {['IG', 'TT', 'YT', 'X', 'TG'].map((c, i) => (
          <span key={c} className={`rounded-full border px-3 py-1 text-[11px] ${i === 0 ? 'border-accent bg-accent-soft text-accent-fg' : 'border-border text-fg-muted'}`}>
            {c}
          </span>
        ))}
      </div>
      <div className="mt-3 space-y-2">
        {[
          { name: 'Likes — Standard', rate: '$0.38' },
          { name: 'Likes — Premium', rate: '$1.10' },
          { name: 'Followers — Real', rate: '$4.20' },
          { name: 'Comments — Random', rate: '$18.00' },
        ].map((s) => (
          <div key={s.name} className="flex items-center justify-between rounded-md border border-border bg-bg-elev p-3">
            <div className="text-[12px] font-medium">{s.name}</div>
            <div className="font-mono text-[11px] text-fg-muted">{s.rate}/1k</div>
          </div>
        ))}
      </div>
    </div>
  );
}

function PhoneOrderDetail() {
  return (
    <div className="h-full bg-bg p-4 pt-10">
      <div className="font-mono text-[10px] text-fg-muted">#1029488</div>
      <div className="text-[14px] font-semibold">Likes — Standard</div>
      <div className="mt-4 flex justify-center">
        <Donut progress={0.62} size={140} stroke={10} label="62%" sublabel="1,540 / 2,500" />
      </div>
      <div className="mt-4 grid grid-cols-3 gap-2 text-center">
        {[
          ['Done', '1.5k'],
          ['Remain', '960'],
          ['ETA', '4m'],
        ].map(([k, v]) => (
          <div key={k} className="rounded-md border border-border bg-bg-elev p-2">
            <div className="text-[9px] text-fg-subtle">{k}</div>
            <div className="mt-1 font-mono text-[12px]">{v}</div>
          </div>
        ))}
      </div>
      <div className="mt-4">
        <Sparkline data={[10, 50, 130, 250, 420, 680, 980, 1280, 1540]} width={300} height={40} />
      </div>
    </div>
  );
}

function PhoneAddFunds() {
  return (
    <div className="section-dark relative h-full overflow-hidden p-4 pt-10 text-white">
      <div className="hero-bg" />
      <div className="relative">
        <div className="text-[10px] uppercase tracking-wider text-white/60">Add funds</div>
        <div className="mt-1 text-[16px] font-semibold">$50.00 · USDT TRC-20</div>
        <div className="mt-4 mx-auto flex h-[170px] w-[170px] items-center justify-center rounded-md bg-white">
          <div className="font-mono text-[10px] text-black/60">[QR pattern]</div>
        </div>
        <div className="mt-3 break-all rounded-md border border-white/10 bg-white/5 p-2 font-mono text-[10px]">
          TXyzAbCdEfGh12345MnOpQrStUv6789KlMn
        </div>
        <div className="mt-3 flex items-center justify-between">
          <span className="font-mono text-[12px]">19:42</span>
          <span className="rounded-full bg-warn-soft px-2 py-[2px] text-[10px] text-warn">Awaiting</span>
        </div>
        <div className="mt-3 flex items-center gap-2 text-[11px] text-white/60">
          <Icon name="info" size={12} /> Send only USDT (TRC-20)
        </div>
      </div>
    </div>
  );
}
