import { useEffect, useState, type ReactNode } from 'react';
import { Button } from '../../../components/ui';
import { cn } from '../../../lib/utils';

// Shared chrome for /legal/* pages — header + sticky TOC scroll-spy +
// prose body. The body uses `.legal-body` class (defined in index.css).
export interface LegalSection {
  id: string;
  title: string;
}

interface LegalLayoutProps {
  eyebrow: string;
  title: string;
  lastUpdated: string;
  sections: ReadonlyArray<LegalSection>;
  children: ReactNode;
}

export function LegalLayout({ eyebrow, title, lastUpdated, sections, children }: LegalLayoutProps) {
  const [active, setActive] = useState<string>(sections[0]?.id ?? '');

  useEffect(() => {
    const handler = () => {
      let cur = sections[0]?.id ?? '';
      for (const s of sections) {
        const el = document.getElementById(s.id);
        if (!el) continue;
        if (el.getBoundingClientRect().top - 100 <= 0) cur = s.id;
      }
      setActive(cur);
    };
    window.addEventListener('scroll', handler, { passive: true });
    handler();
    return () => window.removeEventListener('scroll', handler);
  }, [sections]);

  const onJump = (e: React.MouseEvent<HTMLAnchorElement>, id: string) => {
    e.preventDefault();
    const el = document.getElementById(id);
    if (!el) return;
    window.scrollTo({ top: el.getBoundingClientRect().top + window.scrollY - 80, behavior: 'smooth' });
  };

  return (
    <div className="container-legal py-12">
      <div className="flex items-baseline justify-between gap-4 border-b border-border pb-6">
        <div>
          <div className="eyebrow">{eyebrow}</div>
          <h1 className="display-3 mt-2">{title}</h1>
          <div className="mt-1 font-mono text-[12px] text-fg-subtle">Last updated {lastUpdated}</div>
        </div>
        <div className="hidden gap-2 md:flex">
          <Button size="sm" variant="ghost" icon="copy" onClick={() => window.print()}>
            Print
          </Button>
          <a href="mailto:compliance@smmworld.vip">
            <Button size="sm" variant="secondary">
              Contact
            </Button>
          </a>
        </div>
      </div>

      <div className="mt-8 grid grid-cols-1 gap-10 lg:grid-cols-[240px_1fr]">
        <nav className="hidden lg:block">
          <div className="sticky top-[80px]">
            <div className="eyebrow">Sections</div>
            <ul className="mt-3 space-y-1 text-[13px]">
              {sections.map((s, i) => (
                <li key={s.id}>
                  <a
                    href={'#' + s.id}
                    onClick={(e) => onJump(e, s.id)}
                    className={cn(
                      'block border-l-2 py-[5px] pl-3',
                      active === s.id
                        ? 'border-accent text-fg'
                        : 'border-transparent text-fg-muted hover:text-fg',
                    )}
                  >
                    <span className="mr-2 font-mono text-[11px] text-fg-dim">{(i + 1).toString().padStart(2, '0')}</span>
                    {s.title}
                  </a>
                </li>
              ))}
            </ul>
            <p className="mt-6 text-[12px] text-fg-subtle">
              This is the legally binding version. A plain-English summary lives at the top of each
              document.
            </p>
          </div>
        </nav>
        <article className="legal-body min-w-0">{children}</article>
      </div>
    </div>
  );
}
