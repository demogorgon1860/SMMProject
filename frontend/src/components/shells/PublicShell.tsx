import { useEffect, useState, type ReactNode } from 'react';
import { Link, NavLink, Outlet, useLocation } from 'react-router-dom';
import { Icon } from '../ui/Icon';
import { ThemeToggle } from '../ui/ThemeToggle';
import { ToastProvider } from '../ui/Toast';
import { useAuthStore } from '../../store/authStore';
import { cn } from '../../lib/utils';

// =====================================================================
// PublicShell — top nav + footer for /, /pricing, /legal/*, /help,
// /404, /500. The landing dark variant ("hero variant A") uses
// `variant="dark"` to render an inverted topbar.
// =====================================================================

interface PublicShellProps {
  variant?: 'light' | 'dark';
  children?: ReactNode;
}

export function PublicShell({ variant = 'light', children }: PublicShellProps) {
  return (
    <ToastProvider>
      <div className={cn('min-h-screen', variant === 'dark' ? 'bg-bg-deep text-white' : 'bg-bg text-fg')}>
        <PublicNav variant={variant} />
        <main>{children ?? <Outlet />}</main>
        <PublicFooter variant={variant} />
      </div>
    </ToastProvider>
  );
}

const PUBLIC_NAV_LINKS = [
  // { to: '/services-list', label: 'Services' },
  { to: '/pricing', label: 'Pricing' },
  { to: '/docs', label: 'API' },
  { to: '/help', label: 'Help' },
] as const;

function PublicNav({ variant }: { variant: 'light' | 'dark' }) {
  const isDark = variant === 'dark';
  // Read auth state so the nav reflects whether the visitor is signed in.
  // Without this, an authenticated user clicking "API" or "Help" from the
  // AppShell sidebar would land here and see "Sign in / Get started" CTAs,
  // which reads as "I just got logged out" even though the session is intact.
  const isAuthenticated = useAuthStore((s) => s.isAuthenticated);
  const role = useAuthStore((s) => s.user?.role);
  const homeTo = role === 'ADMIN' ? '/admin' : '/dashboard';

  // Mobile menu: below md (768px) the horizontal nav links are hidden, so
  // expose them behind a hamburger. Auto-close on route change so tapping a
  // link doesn't leave the panel stuck open over the new page.
  const [menuOpen, setMenuOpen] = useState(false);
  const location = useLocation();
  useEffect(() => {
    setMenuOpen(false);
  }, [location.pathname]);

  // Esc closes the menu — parity with the app/admin shells and every other overlay.
  useEffect(() => {
    if (!menuOpen) return;
    const onEscape = (e: KeyboardEvent) => {
      if (e.key === 'Escape') setMenuOpen(false);
    };
    document.addEventListener('keydown', onEscape);
    return () => document.removeEventListener('keydown', onEscape);
  }, [menuOpen]);

  return (
    <header
      className={cn(
        'sticky top-0 z-20 backdrop-blur-md',
        isDark ? 'bg-black/60 border-b border-white/10' : 'bg-bg/85 border-b border-border',
      )}
    >
      <div className="container-app flex h-[60px] items-center gap-3 sm:gap-6">
        <Link to="/" className="flex items-center gap-[9px]">
          <img src="/logo-v2.png" alt="SMMWorld" className="h-[36px] w-[36px] object-contain" />
          {/* Wordmark hides on the narrowest phones (<640px) so the logo + theme + CTA + hamburger
              row can't overflow and clip the menu button under body{overflow-x:hidden}. */}
          <span className="hidden text-[15px] font-semibold tracking-[-0.01em] sm:inline">SMMWorld</span>
        </Link>
        <nav className="hidden items-center gap-5 md:flex">
          {PUBLIC_NAV_LINKS.map((l) => (
            <NavLink key={l.to} to={l.to} className={({ isActive }) => cn('navlink', isActive && 'active', isDark && 'text-white/80 hover:text-white')}>
              {l.label}
            </NavLink>
          ))}
        </nav>
        <div className="flex-1" />
        <ThemeToggle />
        {isAuthenticated ? (
          <Link
            to={homeTo}
            className="inline-flex h-[34px] items-center gap-1 rounded-md bg-accent px-3 text-[13px] font-semibold text-white hover:brightness-110"
          >
            {role === 'ADMIN' ? 'Admin panel' : 'Dashboard'}
            <Icon name="arrow-right" size={13} />
          </Link>
        ) : (
          <>
            <Link
              to="/login"
              className={cn(
                'inline-flex h-[34px] items-center rounded-md px-3 text-[13px] font-medium',
                isDark ? 'text-white hover:bg-white/10' : 'text-fg-muted hover:text-fg hover:bg-bg-sunken',
              )}
            >
              Sign in
            </Link>
            {/* TEMP: registration closed — restore by uncommenting
            <Link
              to="/register"
              className="inline-flex h-[34px] items-center gap-1 rounded-md bg-accent px-3 text-[13px] font-semibold text-white hover:brightness-110"
            >
              Get started
              <Icon name="arrow-right" size={13} />
            </Link>
            */}
          </>
        )}
        {/* Hamburger — only below md, where the horizontal nav is hidden. */}
        <button
          type="button"
          aria-label="Menu"
          aria-expanded={menuOpen}
          aria-controls="public-mobile-menu"
          onClick={() => setMenuOpen((v) => !v)}
          className={cn(
            'inline-flex h-10 w-10 flex-none items-center justify-center rounded-md md:hidden',
            isDark ? 'text-white hover:bg-white/10' : 'text-fg-muted hover:bg-bg-sunken hover:text-fg',
          )}
        >
          <Icon name={menuOpen ? 'x' : 'menu'} size={18} />
        </button>
      </div>

      {/* Mobile menu panel — slides down under the bar with the nav links. */}
      {menuOpen && (
        <nav
          id="public-mobile-menu"
          className={cn(
            'fade-in border-t md:hidden',
            isDark ? 'border-white/10 bg-black/80' : 'border-border bg-bg/95',
          )}
        >
          <div className="container-app flex flex-col py-2">
            {PUBLIC_NAV_LINKS.map((l) => (
              <NavLink
                key={l.to}
                to={l.to}
                onClick={() => setMenuOpen(false)}
                className={({ isActive }) =>
                  cn(
                    'rounded-md px-3 py-2.5 text-[14px] font-medium',
                    isActive
                      ? isDark
                        ? 'bg-white/10 text-white'
                        : 'bg-bg-sunken text-fg'
                      : isDark
                        ? 'text-white/80 hover:bg-white/5'
                        : 'text-fg-muted hover:bg-bg-sunken hover:text-fg',
                  )
                }
              >
                {l.label}
              </NavLink>
            ))}
          </div>
        </nav>
      )}
    </header>
  );
}

function PublicFooter({ variant }: { variant: 'light' | 'dark' }) {
  const isDark = variant === 'dark';
  return (
    <footer
      className={cn(
        'mt-20 border-t',
        isDark ? 'border-white/10 bg-black text-white/70' : 'border-border bg-bg text-fg-muted',
      )}
    >
      <div className="container-app grid grid-cols-2 gap-8 py-12 md:grid-cols-4">
        <div>
          <div className="mb-3 flex items-center gap-2">
            <img src="/logo-v2.png" alt="SMMWorld" className="h-[28px] w-[28px] object-contain" />
            <span className="text-[14px] font-semibold tracking-[-0.01em]">SMMWorld</span>
          </div>
          <p className="text-[13px] leading-relaxed">Infrastructure for Instagram growth.</p>
        </div>
        <FooterCol title="Product" links={[['Services', '/services-list'], ['Pricing', '/pricing'], ['API', '/docs']]} />
        <FooterCol title="Company" links={[['Help', '/help'], ['Terms', '/legal/terms'], ['Privacy', '/legal/privacy']]} />
        <FooterCol title="Refund & AML" links={[['Refund Policy', '/legal/refund'], ['AML Policy', '/legal/aml']]} />
      </div>
      <div className={cn('container-app flex items-center justify-between py-6 text-[12px]', isDark ? 'border-t border-white/10' : 'border-t border-border')}>
        <div>© 2026 SMMWorld. All systems normal.</div>
        <div className="font-mono">smmworld.vip</div>
      </div>
    </footer>
  );
}

function FooterCol({ title, links }: { title: string; links: ReadonlyArray<readonly [string, string]> }) {
  return (
    <div>
      <div className="mb-3 text-[12px] font-medium uppercase tracking-[0.06em] opacity-70">{title}</div>
      <ul className="space-y-2 text-[13px]">
        {links.map(([label, to]) => (
          <li key={to}>
            <Link to={to} className="hover:text-fg">
              {label}
            </Link>
          </li>
        ))}
      </ul>
    </div>
  );
}
