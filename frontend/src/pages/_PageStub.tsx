import { type ReactNode } from 'react';
import { Card } from '../components/ui';
import { AccentPicker } from '../components/ui/ThemeToggle';

// =====================================================================
// PageStub — placeholder used during Phase 0 to verify shells render +
// theme/accent picker work. Each real page will replace its stub in
// Phases 1–2.
// =====================================================================

interface PageStubProps {
  title: string;
  phase: 1 | 2 | 3;
  description?: ReactNode;
}

export function PageStub({ title, phase, description }: PageStubProps) {
  return (
    <div className="container-app py-10">
      <div className="eyebrow">Phase 0 placeholder</div>
      <h1 className="mt-3 text-[40px] font-bold tracking-[-0.025em]">{title}</h1>
      <p className="mt-3 text-fg-muted lede">
        {description ?? 'This page is wired into the new shell. Real content lands in the upcoming phase.'}
      </p>
      <div className="mt-6 inline-flex items-center gap-2 rounded-md border border-border bg-bg-elev px-3 py-2 text-[12px] text-fg-muted">
        <span className="font-mono">Coming in Phase {phase}</span>
      </div>

      <div className="mt-12 grid grid-cols-1 gap-4 md:grid-cols-3">
        <Card>
          <div className="eyebrow">Theme</div>
          <p className="mt-2 text-[13px] text-fg-muted">Click the moon/sun in the topbar.</p>
        </Card>
        <Card>
          <div className="eyebrow">Accent</div>
          <p className="mt-2 mb-3 text-[13px] text-fg-muted">Try a different color palette:</p>
          <AccentPicker />
        </Card>
        <Card>
          <div className="eyebrow">Tokens</div>
          <div className="mt-3 space-y-1.5 font-mono text-[11.5px] text-fg-muted">
            <div>--bg / --bg-elev / --bg-sunken</div>
            <div>--fg / --fg-muted / --fg-subtle</div>
            <div>--accent / --accent-soft / --accent-fg</div>
            <div>--success / --warn / --danger</div>
          </div>
        </Card>
      </div>
    </div>
  );
}
