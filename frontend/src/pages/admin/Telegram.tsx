import { useEffect, useMemo, useState } from 'react';
import {
  Badge,
  Button,
  Card,
  CopyBtn,
  Dot,
  Empty,
  Icon,
  IDCell,
  Input,
  KV,
  Money,
  PageHeader,
  Section,
  Tabs,
  TimeCell,
  useToast,
} from '../../components/ui';
import { adminAPI } from '../../services/api';
import { useAdminActions } from '../../store/adminActions';
import { cn, fmtDur, fmtInt } from '../../lib/utils';

// =====================================================================
// Admin Telegram Control —
//   Pending decisions: bot circuit-breaker pauses awaiting admin action
//   Notifications history: outgoing messages
//   Daily profit summary: month calendar heatmap + day report
//   Webhook config: bot token, secret, registered URL
// =====================================================================

interface PendingDecision {
  orderId: number;
  completed: number;
  quantity: number;
  reason: string;
  createdAt: string;
  expiresInMs: number;
}

export function AdminTelegramPage() {
  const [tab, setTab] = useState<'decisions' | 'history' | 'profit' | 'webhook'>('decisions');
  const [pending, setPending] = useState<PendingDecision[]>([]);

  useEffect(() => {
    let cancelled = false;
    adminAPI
      .telegramPending()
      .then((data: unknown) => {
        if (cancelled) return;
        const arr = Array.isArray(data) ? (data as PendingDecision[]) : (data as { content?: PendingDecision[] })?.content ?? [];
        setPending(arr);
      })
      .catch(() => {});
    return () => {
      cancelled = true;
    };
  }, []);

  return (
    <>
      <PageHeader
        title="Telegram control"
        subtitle={
          <span className="flex items-center gap-2">
            <Dot color="var(--success)" animate />
            <span>
              <span className="font-mono text-fg">@smm_ops_bot</span> · webhook verified ·{' '}
              <span className={cn('font-mono', pending.length > 0 ? 'text-warn' : 'text-fg-muted')}>
                {pending.length} pending
              </span>
            </span>
          </span>
        }
      />

      <div className="space-y-4 p-6">
        <Tabs
          value={tab}
          onChange={setTab}
          tabs={[
            { value: 'decisions', label: 'Pending decisions', count: pending.length },
            { value: 'history', label: 'Notifications history' },
            { value: 'profit', label: 'Daily profit summary' },
            { value: 'webhook', label: 'Webhook config' },
          ]}
        />

        {tab === 'decisions' && <DecisionsTab pending={pending} setPending={setPending} />}
        {tab === 'history' && <HistoryTab />}
        {tab === 'profit' && <ProfitTab />}
        {tab === 'webhook' && <WebhookTab />}
      </div>
    </>
  );
}

function DecisionsTab({ pending, setPending }: { pending: PendingDecision[]; setPending: (p: PendingDecision[]) => void }) {
  const toast = useToast();
  const pushAction = useAdminActions((s) => s.push);
  const [busy, setBusy] = useState<number | null>(null);

  const decide = async (orderId: number, choice: 'proceed' | 'cancel') => {
    setBusy(orderId);
    try {
      if (choice === 'proceed') await adminAPI.telegramProceed(orderId);
      else await adminAPI.telegramCancel(orderId);
      pushAction({
        action: 'telegram.' + choice,
        target: 'order:' + orderId,
        targetLabel: 'Order #' + orderId,
        summary: choice === 'proceed' ? `Resumed paused order #${orderId}` : `Cancelled paused order #${orderId} from Telegram queue`,
      });
      toast(choice === 'proceed' ? 'Order resumed.' : 'Order cancelled · refund posted.', 'success');
      setPending(pending.filter((p) => p.orderId !== orderId));
    } catch (err: unknown) {
      const e = err as { response?: { data?: { message?: string } } };
      toast(e.response?.data?.message ?? 'Decision failed.', 'error');
    } finally {
      setBusy(null);
    }
  };

  return (
    <Card className="p-0">
      {pending.length === 0 ? (
        <Empty
          icon="paper-plane"
          title="No pending decisions"
          subtitle="When the bot circuit-breaker pauses an order it shows up here within seconds."
        />
      ) : (
        <table className="tbl">
          <thead>
            <tr>
              <th>Order ID</th>
              <th>Progress</th>
              <th>Reason</th>
              <th>Created</th>
              <th>Expires</th>
              <th className="text-right">Decision</th>
            </tr>
          </thead>
          <tbody>
            {pending.map((p) => {
              const pct = p.quantity > 0 ? (p.completed / p.quantity) * 100 : 0;
              const expiringSoon = p.expiresInMs < 1.5 * 60 * 60 * 1000;
              return (
                <tr key={p.orderId}>
                  <td>
                    <IDCell id={p.orderId} />
                  </td>
                  <td>
                    <div className="flex items-center gap-2">
                      <div className="h-[4px] w-[120px] overflow-hidden rounded-full bg-bg-sunken">
                        <span className="block h-full bg-violet" style={{ width: `${pct.toFixed(0)}%` }} />
                      </div>
                      <span className="font-mono text-[12px]">
                        {fmtInt(p.completed)}/{fmtInt(p.quantity)}
                      </span>
                      <span className="font-mono text-[10.5px] text-fg-subtle">{pct.toFixed(0)}%</span>
                    </div>
                  </td>
                  <td className="text-[12.5px] text-fg-muted">{p.reason}</td>
                  <td>
                    <TimeCell iso={p.createdAt} />
                  </td>
                  <td>
                    <span className={cn('font-mono text-[12px]', expiringSoon ? 'text-danger' : 'text-fg-muted')}>
                      {fmtDur(p.expiresInMs)}
                      {expiringSoon && ' ⚠'}
                    </span>
                  </td>
                  <td>
                    <div className="flex justify-end gap-1.5">
                      <button
                        type="button"
                        onClick={() => decide(p.orderId, 'proceed')}
                        disabled={busy === p.orderId}
                        className="inline-flex h-7 items-center gap-1 rounded-md border border-success/30 bg-success-soft px-3 text-[12px] font-semibold text-success hover:brightness-95 focus-visible:outline-none focus-visible:ring-2 focus-visible:[--tw-ring-color:var(--ring)] disabled:opacity-60"
                      >
                        ✅ Proceed
                      </button>
                      <button
                        type="button"
                        onClick={() => decide(p.orderId, 'cancel')}
                        disabled={busy === p.orderId}
                        className="inline-flex h-7 items-center gap-1 rounded-md border border-danger/30 bg-danger-soft px-3 text-[12px] font-semibold text-danger hover:brightness-95 focus-visible:outline-none focus-visible:ring-2 focus-visible:[--tw-ring-color:var(--ring)] disabled:opacity-60"
                      >
                        ❌ Cancel
                      </button>
                    </div>
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
      )}
      <div className="border-t border-border bg-bg-sunken px-4 py-3 text-[11.5px] text-fg-subtle">
        Decisions are stored in Redis (<code className="font-mono">telegram:cancel_pending:&#123;orderId&#125;</code>) with a 4-hour TTL.
        On expiry the system applies <code className="font-mono">app.telegram.cancel.default-action</code> (currently <strong>proceed</strong>).
      </div>
    </Card>
  );
}

function HistoryTab() {
  const [history] = useState([
    { t: '14:28:44', kind: 'cancel_pending', chat: '@smm_ops', text: 'Order #1028471 · paused at 340/1000 · awaiting decision' },
    { t: '14:12:30', kind: 'completed', chat: '@smm_ops', text: 'Order #1028487 completed · $4.40 · user @elena_v' },
    { t: '13:54:02', kind: 'new_order', chat: '@smm_ops', text: 'New order #1028511 · 2500 Followers — Real · $10.50' },
    { t: '12:01:18', kind: 'partial', chat: '@smm_ops', text: 'Order #1028498 partial · 640/1000 · refund $1.44 issued' },
    { t: '09:00:00', kind: 'daily_summary', chat: '@smm_ops', text: 'Daily profit 2026-04-25 — $1,284.60 · 312 completed · 18 partial' },
  ]);

  return (
    <Card className="p-0">
      <table className="tbl">
        <thead>
          <tr>
            <th>When</th>
            <th>Kind</th>
            <th>Chat</th>
            <th>Message</th>
          </tr>
        </thead>
        <tbody>
          {history.map((h, i) => (
            <tr key={i}>
              <td className="font-mono text-[12px] text-fg-muted">{h.t}</td>
              <td>
                <Badge tone="muted" size="sm">
                  <span className="font-mono">{h.kind}</span>
                </Badge>
              </td>
              <td className="font-mono text-[12px]">{h.chat}</td>
              <td className="text-[13px]">{h.text}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </Card>
  );
}

function ProfitTab() {
  // Build a 5x7 calendar for current month from synthetic daily totals.
  const today = new Date();
  const year = today.getUTCFullYear();
  const month = today.getUTCMonth();
  const monthStart = new Date(Date.UTC(year, month, 1));
  const daysInMonth = new Date(Date.UTC(year, month + 1, 0)).getUTCDate();
  const firstDow = (monthStart.getUTCDay() + 6) % 7; // Monday=0

  const cells = useMemo(() => {
    const out: Array<{ day: number | null; profit: number; isToday: boolean }> = [];
    for (let i = 0; i < firstDow; i++) out.push({ day: null, profit: 0, isToday: false });
    for (let d = 1; d <= daysInMonth; d++) {
      const profit = Math.max(0, 800 + Math.sin(d * 0.5) * 600 + (d - 15) * 30 + (d % 7 === 6 ? 400 : 0));
      out.push({ day: d, profit, isToday: d === today.getUTCDate() });
    }
    return out;
  }, [year, month, daysInMonth, firstDow]);

  const [selected, setSelected] = useState<number>(today.getUTCDate());
  const max = Math.max(...cells.map((c) => c.profit));
  const day = cells.find((c) => c.day === selected);

  return (
    <div className="grid grid-cols-1 gap-4 lg:grid-cols-[1.2fr_1fr]">
      <Section title={`${monthStart.toLocaleString('en-US', { month: 'long' })} ${year} — daily profit`} pad>
        <div className="grid grid-cols-7 gap-1.5">
          {['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'].map((d) => (
            <div key={d} className="text-center text-[10.5px] font-medium uppercase tracking-wider text-fg-subtle">
              {d}
            </div>
          ))}
          {cells.map((c, i) => {
            if (c.day == null) return <div key={i} />;
            const heat = max > 0 ? c.profit / max : 0;
            const isSelected = selected === c.day;
            return (
              <button
                key={i}
                type="button"
                onClick={() => setSelected(c.day!)}
                className={cn(
                  'aspect-square rounded-md border p-1 text-left transition-colors',
                  isSelected ? 'border-accent ring-1 ring-accent' : 'border-border hover:border-border-strong',
                  c.isToday && !isSelected && 'border-accent',
                )}
                style={{
                  background: `color-mix(in oklab, var(--accent) ${(heat * 22).toFixed(0)}%, var(--bg-elev))`,
                }}
              >
                <div className={cn('text-[11px] font-mono', c.isToday && 'font-bold text-accent-fg')}>
                  {c.day.toString().padStart(2, '0')}
                </div>
                <div className="mt-1 font-mono text-[10.5px] text-fg-muted">${(c.profit / 1000).toFixed(1)}k</div>
              </button>
            );
          })}
        </div>
      </Section>

      <Section title={`Day report · ${year}-${String(month + 1).padStart(2, '0')}-${String(selected).padStart(2, '0')}`}>
        <div>
          <Money value={day?.profit ?? 0} size="lg" />
          <div className="mt-1 text-[11.5px] text-fg-subtle">Daily profit · posted at 23:55</div>
        </div>
        <div className="mt-4 grid grid-cols-2 gap-3">
          <KV k="Orders completed" v={fmtInt(Math.floor((day?.profit ?? 0) / 4))} mono />
          <KV k="Orders partial" v={fmtInt(Math.floor((day?.profit ?? 0) / 60))} mono />
          <KV k="Top service" v="Likes — Standard" />
          <KV k="Top user" v="@elena_v" />
        </div>
        <div className="mt-4 flex gap-2">
          <Button variant="secondary" size="sm" icon="external">
            View post
          </Button>
          <Button variant="ghost" size="sm" icon="refresh">
            Re-send
          </Button>
        </div>
      </Section>
    </div>
  );
}

function WebhookTab() {
  const [showSecret, setShowSecret] = useState(false);
  const secret = 'whsec_a8f9c2d0e7b6c4a3f2e1d09b8c7a6f5e4d3c2b1a';
  return (
    <div className="grid grid-cols-1 gap-4 lg:grid-cols-[1.2fr_1fr]">
      <Card className="p-5">
        <div className="text-[14px] font-semibold">Webhook configuration</div>
        <p className="mt-1 text-[12.5px] text-fg-subtle">
          Registered with the Telegram Bot API on every Spring Boot startup (<code>TelegramWebhookRegistrar</code>).
        </p>
        <div className="mt-4 space-y-3">
          <div>
            <div className="text-[11px] uppercase tracking-wider text-fg-subtle">Webhook URL</div>
            <div className="mt-1 flex items-center gap-2">
              <Input block value="https://smmworld.vip/api/telegram/webhook" readOnly inputSize="md" />
              <CopyBtn value="https://smmworld.vip/api/telegram/webhook" />
            </div>
          </div>
          <div>
            <div className="text-[11px] uppercase tracking-wider text-fg-subtle">Secret</div>
            <div className="mt-1 flex items-center gap-2">
              <Input
                block
                inputSize="md"
                value={showSecret ? secret : '•'.repeat(secret.length)}
                readOnly
                iconRight={
                  <button type="button" onClick={() => setShowSecret((v) => !v)} className="text-fg-subtle hover:text-fg">
                    <Icon name={showSecret ? 'eye-off' : 'eye'} size={14} />
                  </button>
                }
              />
              <CopyBtn value={secret} />
              <Button variant="secondary" size="md" icon="refresh">
                Regenerate
              </Button>
            </div>
          </div>
        </div>
      </Card>

      <Card className="p-5">
        <div className="flex items-center gap-2 text-[14px] font-semibold">
          <Dot color="var(--success)" animate /> Connected bot info
        </div>
        <div className="mt-4 space-y-1">
          <KV k="Bot username" v="@smm_ops_bot" mono />
          <KV k="Bot ID" v="7081234521" mono />
          <KV k="Admin chat" v="-1002134457689" mono />
          <KV k="Has webhook" v="yes · since 2026-04-26 03:14 UTC" />
          <KV k="Pending updates" v="0" mono />
          <KV k="Last error" v="—" />
          <KV k="Max connections" v="40" mono />
          <KV k="IP address" v="91.108.4.5" mono />
        </div>
      </Card>
    </div>
  );
}
