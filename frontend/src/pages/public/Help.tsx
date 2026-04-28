import { useEffect, useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { Badge, Button, Card, Field, Icon, Input, Tabs, Textarea, useToast } from '../../components/ui';
import { Select } from '../../components/ui/Select';
import { supportAPI } from '../../services/api';
import { useAuthStore } from '../../store/authStore';
import type { Ticket } from '../../types';
import { cn, fmtRel } from '../../lib/utils';
import { unwrapList } from '../../lib/api';

const FAQ: ReadonlyArray<{ q: string; a: string }> = [
  {
    q: 'How long until my order starts?',
    a: 'Most Instagram services start within minutes once dispatched. The first batch usually completes within 5 minutes; the rest dripfeeds based on the service profile.',
  },
  {
    q: 'What payment methods do you accept?',
    a: 'Crypto only — USDT (TRC-20 + ERC-20), BTC, ETH, TON, LTC. No cards, no PayPal, no bank transfer. USDT TRC-20 has the lowest network fees.',
  },
  {
    q: 'Why are my likes/followers dropping?',
    a: 'Standard Instagram churn — the platform itself filters or unfollows over time. If you see a drop within 30 days of the order completing, request a free refill from the order detail page. An operator reviews the request and re-runs the order at the dropped quantity. We do not monitor every order automatically; the refill window starts when the order is marked completed.',
  },
  {
    q: 'Can I cancel an order in progress?',
    a: 'Pending orders cancel instantly with full refund. In-progress orders cancel partial — delivered amount is charged, the rest is refunded.',
  },
  {
    q: "What's your API rate limit?",
    a: 'Read endpoints: 60 requests per minute. Order creation: 10 per minute. Auth flows: 5 per minute. Need higher limits for a production integration? Open a ticket and explain your traffic profile.',
  },
  {
    q: 'Do you offer non-Instagram services?',
    a: 'Not yet. TikTok, YouTube, X, Telegram and Facebook are on the roadmap and will come online as we build the bot infrastructure for each. No firm dates — subscribe to the changelog to be notified.',
  },
];

export function HelpPage() {
  const [params] = useSearchParams();
  const initialTab = (params.get('tab') as 'faq' | 'tickets' | 'new' | 'contact' | null) ?? 'faq';
  const [tab, setTab] = useState<'faq' | 'tickets' | 'new' | 'contact'>(initialTab);

  return (
    <div className="container-narrow py-14">
      <div className="eyebrow">Help center</div>
      <h1 className="display-3 mt-2">How can we help?</h1>
      <p className="mt-2 max-w-[560px] text-[14px] text-fg-muted">
        Most answers live in the FAQ below. Need something specific? Sign in and open a ticket —
        we read every one and reply with order context attached.
      </p>

      <div className="mt-8">
        <Tabs
          value={tab}
          onChange={setTab}
          tabs={[
            { value: 'faq', label: 'FAQ' },
            { value: 'tickets', label: 'My tickets' },
            { value: 'new', label: 'New ticket' },
            { value: 'contact', label: 'Contact' },
          ]}
        />
      </div>

      <div className="mt-6">
        {tab === 'faq' && <FaqTab />}
        {tab === 'tickets' && <TicketsTab />}
        {tab === 'new' && <NewTicketTab />}
        {tab === 'contact' && <ContactTab />}
      </div>
    </div>
  );
}

function FaqTab() {
  const [open, setOpen] = useState<number | null>(0);
  return (
    <Card className="p-0">
      <ul>
        {FAQ.map((f, i) => {
          const isOpen = open === i;
          return (
            <li key={f.q} className="border-b border-border last:border-b-0">
              <button
                type="button"
                onClick={() => setOpen((c) => (c === i ? null : i))}
                className="flex w-full items-center justify-between gap-4 px-5 py-4 text-left transition-colors hover:bg-bg-sunken"
              >
                <span className="text-[14.5px] font-medium">{f.q}</span>
                <Icon
                  name="chevron-down"
                  size={16}
                  className={cn('text-fg-subtle transition-transform', isOpen && 'rotate-180')}
                />
              </button>
              {isOpen && (
                <div className="px-5 pb-4 text-[13.5px] leading-relaxed text-fg-muted">{f.a}</div>
              )}
            </li>
          );
        })}
      </ul>
    </Card>
  );
}

function TicketsTab() {
  const isAuthenticated = useAuthStore((s) => s.isAuthenticated);
  const [tickets, setTickets] = useState<Ticket[] | null>(null);

  useEffect(() => {
    if (!isAuthenticated) return;
    let cancelled = false;
    supportAPI
      .tickets()
      .then((data: unknown) => {
        if (cancelled) return;
        setTickets(unwrapList<Ticket>(data));
      })
      .catch(() => {
        if (!cancelled) setTickets([]);
      });
    return () => {
      cancelled = true;
    };
  }, [isAuthenticated]);

  // Help is a public page. Skip the tickets fetch entirely for anonymous visitors —
  // hitting /v1/tickets unauthenticated would trip the global 401 interceptor and
  // force-redirect them to /login, which is hostile when they were just browsing FAQ.
  if (!isAuthenticated) {
    return (
      <Card className="p-12 text-center">
        <div className="text-[14px] font-semibold">Sign in to see your tickets</div>
        <p className="mt-1 text-[13px] text-fg-muted">
          Tickets are tied to your account. Anyone can browse the FAQ — opening or reading tickets
          requires an account.
        </p>
        <div className="mt-4 flex justify-center gap-2">
          <Link to="/login">
            <Button variant="primary" size="sm">
              Sign in
            </Button>
          </Link>
          {/* TEMP: registration closed — restore by uncommenting
          <Link to="/register">
            <Button variant="secondary" size="sm">
              Create account
            </Button>
          </Link>
          */}
        </div>
      </Card>
    );
  }
  if (!tickets) {
    return <div className="p-12 text-center text-[13px] text-fg-subtle">Loading…</div>;
  }
  if (tickets.length === 0) {
    return (
      <Card className="p-12 text-center">
        <div className="text-[14px] font-semibold">No tickets yet</div>
        <p className="mt-1 text-[13px] text-fg-muted">When you open one, it appears here.</p>
      </Card>
    );
  }
  return (
    <Card className="p-0">
      <table className="tbl-u">
        <thead>
          <tr>
            <th>ID</th>
            <th>Subject</th>
            <th>Status</th>
            <th>Updated</th>
          </tr>
        </thead>
        <tbody>
          {tickets.map((t) => (
            <tr key={t.id}>
              <td className="font-mono text-[12px]">{t.id}</td>
              <td>
                <span className="font-medium">{t.subject}</span>
                {t.unread && <Badge tone="accent" size="sm" className="ml-2">New</Badge>}
              </td>
              <td>
                <Badge tone={t.status === 'OPEN' ? 'info' : t.status === 'CLOSED' ? 'muted' : 'warn'} size="sm">
                  {t.status.toLowerCase()}
                </Badge>
              </td>
              <td className="font-mono text-[12px] text-fg-muted">{fmtRel(t.updatedAt)}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </Card>
  );
}

function NewTicketTab() {
  const toast = useToast();
  const isAuthenticated = useAuthStore((s) => s.isAuthenticated);
  const [topic, setTopic] = useState('order');
  const [orderId, setOrderId] = useState('');
  const [subject, setSubject] = useState('');
  const [body, setBody] = useState('');
  const [submitting, setSubmitting] = useState(false);

  const valid = subject.trim().length >= 3 && body.trim().length >= 10;

  if (!isAuthenticated) {
    return (
      <Card className="p-12 text-center">
        <div className="text-[14px] font-semibold">Sign in to open a ticket</div>
        <p className="mt-1 text-[13px] text-fg-muted">
          Replies land in your account inbox. Browse the FAQ above without signing in.
        </p>
        <div className="mt-4 flex justify-center gap-2">
          <Link to="/login">
            <Button variant="primary" size="sm">
              Sign in
            </Button>
          </Link>
          {/* TEMP: registration closed — restore by uncommenting
          <Link to="/register">
            <Button variant="secondary" size="sm">
              Create account
            </Button>
          </Link>
          */}
        </div>
      </Card>
    );
  }

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!valid) return;
    setSubmitting(true);
    try {
      const parsedOrderId = orderId.trim() ? Number(orderId.trim()) : undefined;
      await supportAPI.createTicket({
        topic,
        orderId: Number.isFinite(parsedOrderId) ? (parsedOrderId as number) : undefined,
        subject,
        description: body,
      });
      toast('Ticket sent.', 'success');
      setSubject('');
      setBody('');
      setOrderId('');
    } catch {
      toast('Could not submit ticket. Email hello@smmworld.vip directly.', 'error');
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <Card>
      <form className="grid grid-cols-1 gap-0 md:grid-cols-2 md:gap-x-6" onSubmit={submit} noValidate>
        <Field label="Topic">
          <Select
            block
            value={topic}
            onChange={(e) => setTopic(e.target.value)}
            options={[
              // Values must match the backend Pattern whitelist
              // (see CreateTicketRequest.topic): billing|order|technical|account|abuse|other.
              { value: 'order', label: 'Order issue' },
              { value: 'billing', label: 'Billing / refund' },
              { value: 'technical', label: 'Technical / API' },
              { value: 'account', label: 'Account access' },
              { value: 'abuse', label: 'Abuse / ToS violation' },
              { value: 'other', label: 'Other' },
            ]}
          />
        </Field>
        <Field label="Order ID (optional)">
          <Input block placeholder="#1029488" value={orderId} onChange={(e) => setOrderId(e.target.value)} />
        </Field>
        <div className="md:col-span-2">
          <Field label="Subject">
            <Input block value={subject} onChange={(e) => setSubject(e.target.value)} placeholder="Brief description" />
          </Field>
        </div>
        <div className="md:col-span-2">
          <Field label="Description">
            <Textarea block rows={6} value={body} onChange={(e) => setBody(e.target.value)} placeholder="What happened, what you expected, links to relevant orders…" />
          </Field>
        </div>
        <div className="md:col-span-2">
          <Button type="submit" variant="primary" size="lg" loading={submitting} disabled={!valid}>
            Send ticket
          </Button>
        </div>
      </form>
    </Card>
  );
}

function ContactTab() {
  // Tickets are the primary support channel — they live in the user's account inbox and
  // attach context (orders, topics, history). Email is a fallback for two specific cases:
  // a general inbox for visitors who can't sign in, and a separate security inbox so
  // vulnerability reports don't sit in a customer-support queue. No fake reply-time SLA,
  // no fake "PGP available" claim — both were unverifiable marketing fluff.
  const cards = [
    {
      icon: 'mail' as const,
      title: 'General',
      email: 'hello@smmworld.vip',
      sub: 'Account questions, anything that doesn’t fit a ticket.',
    },
    {
      icon: 'shield' as const,
      title: 'Security',
      email: 'security@smmworld.vip',
      sub: 'Vulnerability disclosure. Please don’t use this for billing or order issues.',
    },
  ];
  return (
    <>
      <Card className="mb-4 flex items-start gap-3 p-5">
        <div className="flex h-9 w-9 flex-none items-center justify-center rounded-md bg-accent-soft text-accent-fg">
          <Icon name="info" size={16} />
        </div>
        <div className="min-w-0">
          <div className="text-[14px] font-semibold">Open a ticket first</div>
          <p className="mt-1 text-[13px] text-fg-muted">
            Tickets are tied to your account and orders, so the response is faster and we have
            full context. Use the email below only for things you can’t (or shouldn’t) put through
            an authenticated channel.
          </p>
        </div>
      </Card>
      <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
        {cards.map((c) => (
          <Card key={c.title} className="p-5" hover>
            <div className="flex h-9 w-9 items-center justify-center rounded-md bg-accent-soft text-accent-fg">
              <Icon name={c.icon} size={16} />
            </div>
            <div className="mt-3 text-[14px] font-semibold">{c.title}</div>
            <a href={`mailto:${c.email}`} className="mt-1 block font-mono text-[13px] text-accent hover:underline">
              {c.email}
            </a>
            <p className="mt-1 text-[12px] text-fg-subtle">{c.sub}</p>
          </Card>
        ))}
      </div>
    </>
  );
}
