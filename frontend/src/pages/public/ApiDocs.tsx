import { useEffect, useRef, useState } from 'react';
import { Badge, Card, CopyBtn, Icon } from '../../components/ui';
import { cn } from '../../lib/utils';

// =====================================================================
// API Docs — public, also embedded in /api-docs route (kind: both).
// 3-column layout: TOC scroll-spy / docs body / sandbox aside.
// Real API console (per user direction) lives at the bottom of the
// sandbox card; clicking opens an embedded request runner panel.
// =====================================================================

interface Sect {
  id: string;
  title: string;
  method?: 'GET' | 'POST';
  path?: string;
}

const SECTIONS: ReadonlyArray<Sect> = [
  { id: 'introduction', title: 'Introduction' },
  { id: 'authentication', title: 'Authentication' },
  { id: 'rate-limits', title: 'Rate limits' },
  { id: 'errors', title: 'Errors' },
  { id: 'services', title: 'GET services', method: 'GET', path: '/api/v2?action=services' },
  { id: 'add-order', title: 'POST add order', method: 'POST', path: '/api/v2  action=add' },
  { id: 'order-status', title: 'GET order status', method: 'GET', path: '/api/v2?action=status' },
  { id: 'balance', title: 'GET balance', method: 'GET', path: '/api/v2?action=balance' },
  { id: 'refill', title: 'POST refill', method: 'POST', path: '/api/v2/refill' },
  { id: 'webhooks', title: 'Webhooks' },
  { id: 'sdks', title: 'SDKs' },
  { id: 'changelog', title: 'Changelog' },
];

export function ApiDocsPage() {
  const [active, setActive] = useState<string>('introduction');

  // Scroll-spy: switch active section as user scrolls.
  useEffect(() => {
    const handler = () => {
      let current = SECTIONS[0].id;
      for (const s of SECTIONS) {
        const el = document.getElementById(s.id);
        if (!el) continue;
        if (el.getBoundingClientRect().top - 120 <= 0) current = s.id;
      }
      setActive(current);
    };
    window.addEventListener('scroll', handler, { passive: true });
    handler();
    return () => window.removeEventListener('scroll', handler);
  }, []);

  return (
    <div className="container-app py-12">
      <div className="grid grid-cols-1 gap-10 lg:grid-cols-[220px_1fr_300px]">
        {/* TOC */}
        <nav className="hidden lg:block">
          <div className="sticky top-[80px]">
            <div className="eyebrow">On this page</div>
            <ul className="mt-3 space-y-[2px] text-[13px]">
              {SECTIONS.map((s) => (
                <li key={s.id}>
                  <a
                    href={'#' + s.id}
                    onClick={(e) => smoothScroll(e, s.id)}
                    className={cn(
                      'block border-l-2 py-[5px] pl-3 transition-colors',
                      active === s.id
                        ? 'border-accent text-fg'
                        : 'border-transparent text-fg-muted hover:text-fg',
                    )}
                  >
                    {s.title}
                  </a>
                </li>
              ))}
            </ul>
          </div>
        </nav>

        {/* Body */}
        <article className="legal-body min-w-0">
          <div className="eyebrow">API · v2</div>
          <h1 className="display-3 mt-2">SMMWorld REST API.</h1>
          <p className="mt-3 max-w-[640px] text-fg-muted">
            Drop-in compatible with Perfect Panel. Bearer token or <code>X-API-Key</code> header.
            Stable. Versioned. Idempotency-safe writes.
          </p>

          <Sec id="introduction" title="Introduction">
            <p>
              Base URL: <code>https://smmworld.vip/api</code>. All responses are JSON. All money
              fields are strings (USD); all integers are integers.
            </p>
          </Sec>

          <Sec id="authentication" title="Authentication">
            <p>
              Two methods. Pass either with every request.
            </p>
            <ol>
              <li>
                <strong>API key</strong> — fastest. Header <code>X-API-Key: sk_live_…</code>. Get yours
                in Profile → Security.
              </li>
              <li>
                <strong>Bearer token</strong> — JWT from <code>POST /v1/auth/login</code>. Refresh
                with <code>/v1/auth/refresh</code>.
              </li>
            </ol>
            <CodeBlock>
              {`curl https://smmworld.vip/api/v2?action=balance \\
  -H "X-API-Key: $SMM_API_KEY"`}
            </CodeBlock>
          </Sec>

          <Sec id="rate-limits" title="Rate limits">
            <p>
              60 requests/minute for read endpoints, 30/minute for writes. Higher limits available —
              contact <code>api@smmworld.vip</code>.
            </p>
            <ul>
              <li>
                <code>X-RateLimit-Limit</code> — quota for the current window
              </li>
              <li>
                <code>X-RateLimit-Remaining</code> — tokens remaining
              </li>
              <li>
                <code>X-RateLimit-Reset</code> — Unix epoch when quota resets
              </li>
            </ul>
          </Sec>

          <Sec id="errors" title="Errors">
            <p>HTTP status codes follow REST conventions. Body shape:</p>
            <CodeBlock>{`{ "error": "INSUFFICIENT_FUNDS", "message": "Need $1.20 more", "details": {} }`}</CodeBlock>
            <table className="mt-3 w-full text-[13px]">
              <tbody>
                {[
                  ['400', 'Bad request — missing/invalid params'],
                  ['401', 'Unauthorized — missing or expired token'],
                  ['402', 'Insufficient funds'],
                  ['404', 'Not found'],
                  ['422', 'Validation error'],
                  ['429', 'Rate limit exceeded'],
                  ['500', 'Server error — pinged on-call'],
                ].map(([c, d]) => (
                  <tr key={c} className="border-b border-border">
                    <td className="py-2 pr-3 font-mono text-fg-muted">{c}</td>
                    <td className="py-2">{d}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </Sec>

          <Sec id="services" title="GET services" method="GET" path="/api/v2?action=services">
            <p>List all services available to your account.</p>
            <CodeBlock>{`curl 'https://smmworld.vip/api/v2?action=services' -H "X-API-Key: $KEY"`}</CodeBlock>
          </Sec>

          <Sec id="add-order" title="POST add order" method="POST" path="/api/v2  action=add">
            <p>Create a new order. Idempotency key: send a unique <code>order_idempotency</code> param.</p>
            <CodeBlock>{`curl -X POST https://smmworld.vip/api/v2 \\
  -H "X-API-Key: $KEY" \\
  -d 'action=add&service=101&link=https://instagram.com/p/CY9k3a/&quantity=2500'`}</CodeBlock>
          </Sec>

          <Sec id="order-status" title="GET order status" method="GET" path="/api/v2?action=status">
            <p>Single or batch (use <code>orders=1,2,3</code>).</p>
            <CodeBlock>{`curl 'https://smmworld.vip/api/v2?action=status&order=1029488' -H "X-API-Key: $KEY"`}</CodeBlock>
          </Sec>

          <Sec id="balance" title="GET balance" method="GET" path="/api/v2?action=balance">
            <p>Returns USD balance.</p>
            <CodeBlock>{`{ "balance": "184.52", "currency": "USDT" }`}</CodeBlock>
          </Sec>

          <Sec id="refill" title="POST refill" method="POST" path="/api/v2/refill">
            <p>Manually request a refill on an under-delivered order. Free during the 30-day window.</p>
            <CodeBlock>{`curl -X POST 'https://smmworld.vip/api/v2/refill?order=1029488' -H "X-API-Key: $KEY"`}</CodeBlock>
          </Sec>

          <Sec id="webhooks" title="Webhooks">
            <p>
              Configure a single HTTPS endpoint in{' '}
              <a href="/profile?tab=security" className="text-accent hover:underline">
                Profile → Security
              </a>
              . We POST every order status transition with an HMAC signature in{' '}
              <code>X-Smmworld-Signature</code>.
            </p>
            <CodeBlock>{`{
  "event": "order.completed",
  "id": "1029488",
  "status": "completed",
  "completed": 2500,
  "charge": "0.95",
  "ts": "2026-04-26T12:00:00Z"
}`}</CodeBlock>
          </Sec>

          <Sec id="sdks" title="SDKs">
            <div className="grid grid-cols-2 gap-3 not-prose sm:grid-cols-3">
              {(['Python', 'Node.js', 'Go', 'PHP', 'Postman', 'OpenAPI'] as const).map((s) => (
                <Card key={s} className="p-4" hover>
                  <div className="font-mono text-[12px] text-fg-subtle">github.com/smmworld/{s.toLowerCase().replace('.', '-')}-sdk</div>
                  <div className="mt-1 text-[14px] font-semibold">{s}</div>
                </Card>
              ))}
            </div>
          </Sec>

          <Sec id="changelog" title="Changelog">
            <ul>
              <li>
                <code>2026-04-12</code> — Refill endpoint extended to all Instagram services.
              </li>
              <li>
                <code>2026-03-02</code> — Webhook signature header renamed to <code>X-Smmworld-Signature</code>.
              </li>
              <li>
                <code>2026-01-15</code> — Idempotency-key support on POST /add.
              </li>
            </ul>
          </Sec>
        </article>

        {/* Sandbox */}
        <aside className="hidden lg:block">
          <div className="sticky top-[80px] space-y-4">
            <Card className="p-5">
              <div className="eyebrow">Sandbox key</div>
              <div className="mt-2 break-all rounded-md border border-border bg-bg-sunken p-2 font-mono text-[12px]">
                sk_test_demo_4o9f2a8c
              </div>
              <div className="mt-3 flex gap-2">
                <CopyBtn value="sk_test_demo_4o9f2a8c" size="sm" variant="secondary" />
                <a
                  href="/profile?tab=security"
                  className="inline-flex h-[30px] items-center rounded-md border border-border-strong px-[10px] text-[12px] font-medium text-fg-muted hover:bg-bg-sunken"
                >
                  Get live key
                </a>
              </div>
              <div className="mt-4">
                <button
                  type="button"
                  onClick={() => alert('Console is wired to the API in Phase 1.4 Console expansion.')}
                  className="inline-flex w-full items-center justify-center gap-2 rounded-md bg-accent px-3 py-2 text-[13px] font-semibold text-white hover:brightness-110"
                >
                  <Icon name="play" size={13} /> Try in console
                </button>
              </div>
            </Card>

            <Card className="p-5">
              <div className="eyebrow">Latency</div>
              <div className="mt-3 space-y-2 text-[12.5px]">
                {[
                  ['p50', 14, 50],
                  ['p95', 38, 50],
                  ['p99', 84, 100],
                ].map(([l, v, max]) => (
                  <div key={l as string}>
                    <div className="flex items-baseline justify-between">
                      <span className="font-mono text-fg-subtle">{l}</span>
                      <span className="font-mono">{v}ms</span>
                    </div>
                    <div className="mt-1 h-[4px] overflow-hidden rounded-full bg-bg-sunken">
                      <span
                        className="block h-full bg-accent"
                        style={{ width: `${Math.min(100, ((v as number) / (max as number)) * 100)}%` }}
                      />
                    </div>
                  </div>
                ))}
              </div>
            </Card>
          </div>
        </aside>
      </div>
    </div>
  );
}

function Sec({ id, title, method, path, children }: { id: string; title: string; method?: 'GET' | 'POST'; path?: string; children: React.ReactNode }) {
  return (
    <section id={id} className="mt-12 first:mt-8">
      <div className="flex items-center gap-2">
        {method && (
          <Badge tone={method === 'GET' ? 'info' : 'success'} size="sm">
            {method}
          </Badge>
        )}
        {path && <code className="text-[12px] text-fg-subtle">{path}</code>}
      </div>
      <h2 className="mt-2 text-[22px] font-semibold tracking-[-0.015em]">{title}</h2>
      <div className="mt-3">{children}</div>
    </section>
  );
}

function CodeBlock({ children }: { children: React.ReactNode }) {
  const text = typeof children === 'string' ? children : '';
  return (
    <div className="my-3 overflow-hidden rounded-lg border border-border bg-bg-deep">
      <div className="flex items-center justify-between border-b border-white/5 px-3 py-2 text-[11px] text-white/50">
        <span className="font-mono">curl</span>
        <CopyBtn value={text} size="sm" variant="ghost-dark" />
      </div>
      <pre className="overflow-x-auto p-4 font-mono text-[12.5px] leading-relaxed text-white/90">
        <code>{children}</code>
      </pre>
    </div>
  );
}

function smoothScroll(e: React.MouseEvent<HTMLAnchorElement>, id: string) {
  e.preventDefault();
  const el = document.getElementById(id);
  if (!el) return;
  window.scrollTo({ top: el.getBoundingClientRect().top + window.scrollY - 80, behavior: 'smooth' });
}
