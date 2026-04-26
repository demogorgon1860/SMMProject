import { useEffect, useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { Badge, Button, Card, Field, Icon, Input, Tabs, Textarea, useToast } from '../../components/ui';
import { Select } from '../../components/ui/Select';
import { supportAPI } from '../../services/api';
import type { Ticket } from '../../types';
import { cn, fmtRel } from '../../lib/utils';

const FAQ: ReadonlyArray<{ q: string; a: string }> = [
  {
    q: 'How long until my order starts?',
    a: 'Median start time is 47 seconds for Instagram services. The first batch usually completes within 5 minutes; the rest dripfeeds based on the service profile.',
  },
  {
    q: 'What payment methods do you accept?',
    a: 'Crypto only — USDT (TRC-20 + ERC-20), BTC, ETH, TON, LTC. No cards, no PayPal, no bank transfer. USDT TRC-20 has the lowest network fees.',
  },
  {
    q: 'Why are my likes/followers dropping?',
    a: 'Standard Instagram churn. We auto-detect and refill during the 30-day refill window. No need to open a ticket — replacements push automatically.',
  },
  {
    q: 'Can I cancel an order in progress?',
    a: 'Pending orders cancel instantly with full refund. In-progress orders cancel partial — delivered amount is charged, the rest is refunded.',
  },
  {
    q: 'What\'s your API rate limit?',
    a: '60 requests/minute for read endpoints, 30 for writes. Higher limits available — open a ticket from the API tab if you need more.',
  },
  {
    q: 'Do you offer non-Instagram services?',
    a: 'Not yet. TikTok and YouTube are scheduled for Q3 2026; X, Telegram for Q4. Subscribe to the changelog to be notified.',
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
        Most answers live in the FAQ below. Need something specific? Open a ticket — average reply
        under 12 minutes during business hours.
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
  const [tickets, setTickets] = useState<Ticket[] | null>(null);
  const [err, setErr] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    supportAPI
      .tickets()
      .then((data: unknown) => {
        if (cancelled) return;
        const arr: Ticket[] = Array.isArray(data) ? (data as Ticket[]) : (data as { content?: Ticket[] })?.content ?? [];
        setTickets(arr);
      })
      .catch(() => setErr('Tickets endpoint not available yet.'));
    return () => {
      cancelled = true;
    };
  }, []);

  if (err) {
    return (
      <Card className="p-12 text-center">
        <div className="text-[14px] font-semibold">Tickets coming soon</div>
        <p className="mt-1 text-[13px] text-fg-muted">
          Use the New ticket tab to email support@smmworld.vip directly.
        </p>
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
  const [topic, setTopic] = useState('order');
  const [orderId, setOrderId] = useState('');
  const [subject, setSubject] = useState('');
  const [body, setBody] = useState('');
  const [submitting, setSubmitting] = useState(false);

  const valid = subject.trim().length >= 3 && body.trim().length >= 10;

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!valid) return;
    setSubmitting(true);
    try {
      await supportAPI.createTicket({ topic, orderId: orderId.trim() || undefined, subject, description: body });
      toast('Ticket sent.', 'success');
      setSubject('');
      setBody('');
      setOrderId('');
    } catch {
      toast('Could not submit ticket. Email support@smmworld.vip directly.', 'error');
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
              { value: 'order', label: 'Order issue' },
              { value: 'billing', label: 'Billing / refund' },
              { value: 'api', label: 'API / integration' },
              { value: 'security', label: 'Security' },
              { value: 'enterprise', label: 'Enterprise / custom' },
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
  const cards = [
    { icon: 'mail' as const, title: 'General', email: 'hello@smmworld.vip', sub: 'Replies in <12 min business hours' },
    { icon: 'shield' as const, title: 'Security', email: 'security@smmworld.vip', sub: 'PGP available · ack within 1 business day' },
    { icon: 'code' as const, title: 'API / dev', email: 'api@smmworld.vip', sub: 'Integration help & rate-limit increases' },
    { icon: 'help' as const, title: 'Compliance / legal', email: 'compliance@smmworld.vip', sub: 'AML, takedown, ToS' },
    { icon: 'paper-plane' as const, title: 'Press', email: 'press@smmworld.vip', sub: 'Reviews, podcast, interview' },
    { icon: 'wallet' as const, title: 'Billing dispute', email: 'billing@smmworld.vip', sub: 'Webhook miss, refund delayed' },
  ];
  return (
    <div className="grid grid-cols-1 gap-3 sm:grid-cols-2 lg:grid-cols-3">
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
  );
}
