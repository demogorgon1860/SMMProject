import { useCallback, useEffect, useRef, useState } from 'react';
import {
  Button,
  Card,
  ConfirmModal,
  Dot,
  Empty,
  Icon,
  PageHeader,
  Section,
  StatusBadge,
  useToast,
} from '../../components/ui';
import { adminAPI, type BotInstanceStatus, type BotWebhookEvent } from '../../services/api';
import { cn, fmtDateTime, fmtInt } from '../../lib/utils';

type Severity = NonNullable<BotWebhookEvent['severity']>;

const SEVERITY_TONE: Record<Severity, string> = {
  info: 'text-[#a7f3d0]',
  success: 'text-[#86efac]',
  warn: 'text-[#fbbf24]',
  error: 'text-[#f87171]',
};

const STATUS_BADGE_FROM_INSTANCE = (s: BotInstanceStatus): 'up' | 'degraded' | 'down' => {
  if (!s.online) return 'down';
  if (s.draining) return 'degraded';
  if (!s.running) return 'degraded';
  if (s.status === 'unhealthy') return 'down';
  if (s.status === 'degraded') return 'degraded';
  return 'up';
};

const STATUS_LABEL_FROM_INSTANCE = (s: BotInstanceStatus): string => {
  if (!s.online) return 'Offline';
  if (s.draining) return 'Draining';
  if (!s.running) return 'Stopped';
  if (s.status === 'unhealthy') return 'Unhealthy';
  if (s.status === 'degraded') return 'Degraded';
  return 'Up';
};

export function AdminBotPage() {
  const toast = useToast();
  const [bots, setBots] = useState<BotInstanceStatus[]>([]);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [busy, setBusy] = useState<Record<string, boolean>>({});
  const [drainTarget, setDrainTarget] = useState<string | null>(null);
  const [drainConfirm, setDrainConfirm] = useState('');

  const refresh = useCallback(async () => {
    try {
      const data = await adminAPI.botStatus();
      setBots(Array.isArray(data) ? (data as BotInstanceStatus[]) : []);
      setLoadError(null);
    } catch (e) {
      const msg = e instanceof Error ? e.message : 'request failed';
      setLoadError(msg);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    refresh();
    const t = setInterval(refresh, 10_000);
    return () => clearInterval(t);
  }, [refresh]);

  const withBusy = async (id: string, fn: () => Promise<unknown>, msg: string) => {
    setBusy((b) => ({ ...b, [id]: true }));
    try {
      await fn();
      toast(msg, 'success');
      await refresh();
    } catch (e) {
      const detail = e instanceof Error ? e.message : 'request failed';
      toast(`${msg.replace(/\.$/, '')} failed: ${detail}`, 'error');
    } finally {
      setBusy((b) => ({ ...b, [id]: false }));
    }
  };

  const onStart = (id: string) =>
    withBusy(id, () => adminAPI.botWorkersStart(id), `${id}: workers started`);
  const onStop = (id: string) =>
    withBusy(id, () => adminAPI.botWorkersStop(id), `${id}: workers stopped`);
  const onResume = (id: string) =>
    withBusy(id, () => adminAPI.botWorkersResume(id), `${id}: drain stopped`);
  const onReload = (id: string) =>
    withBusy(id, () => adminAPI.botReload(id), `${id}: config reloaded`);

  const askDrain = (id: string) => {
    setDrainTarget(id);
    setDrainConfirm('');
  };
  const confirmDrain = async () => {
    // Guard: text-only validation in the modal is purely cosmetic — enforce it here.
    if (!drainTarget || drainConfirm !== 'DRAIN') return;
    const id = drainTarget;
    setDrainTarget(null);
    setDrainConfirm('');
    await withBusy(id, () => adminAPI.botWorkersDrain(id), `${id}: draining`);
  };

  const onlineCount = bots.filter((b) => b.online).length;
  const totalActive = bots.reduce((s, b) => s + (b.activeWorkers ?? 0), 0);

  return (
    <>
      <PageHeader
        title="Instagram bot"
        subtitle={
          <span>
            <span className="font-mono text-fg">{onlineCount}</span> /{' '}
            <span className="font-mono text-fg">{bots.length}</span> online ·{' '}
            <span className="font-mono text-fg">{totalActive}</span> active worker{totalActive === 1 ? '' : 's'}
          </span>
        }
        actions={
          <Button variant="ghost" size="sm" icon="refresh" onClick={refresh}>
            Refresh
          </Button>
        }
      />

      <div className="space-y-6 p-6">
        {loadError && bots.length === 0 && !loading && (
          <Card className="border-danger/40 p-4 text-[13px] text-danger">
            Failed to load bot status: <span className="font-mono">{loadError}</span>
          </Card>
        )}

        {/* Instances */}
        {loading && bots.length === 0 ? (
          <Card className="p-5 text-[13px] text-fg-muted">Loading bot status…</Card>
        ) : bots.length === 0 ? (
          <Card className="p-5">
            <Empty title="No bot instances configured" subtitle="Set INSTAGRAM_BOT_URL in the backend env." />
          </Card>
        ) : (
          <div className="grid grid-cols-1 gap-4">
            {bots.map((b) => (
              <InstanceCard
                key={b.id}
                instance={b}
                busy={!!busy[b.id]}
                onStart={() => onStart(b.id)}
                onStop={() => onStop(b.id)}
                onResume={() => onResume(b.id)}
                onDrain={() => askDrain(b.id)}
                onReload={() => onReload(b.id)}
              />
            ))}
          </div>
        )}

        {/* AdsPower groups (NOT exposed by bot — honest empty state) */}
        <Section title="AdsPower profile groups" subtitle="Pools used by the Go bot">
          <Card className="p-5">
            <Empty
              title="Not exposed by bot API"
              subtitle="The bot does not surface AdsPower group counts on a cheap endpoint. Adding /api/profiles/groups (with caching) is a follow-up — see commands/05-real-admin-bot-page.md."
            />
          </Card>
        </Section>

        {/* Live webhook tail */}
        <WebhookTail />

        {/* Queue snapshot per instance */}
        {bots
          .filter((b) => b.online)
          .map((b) => (
            <QueueSnapshot key={b.id} id={b.id} />
          ))}
      </div>

      {/* Drain confirm */}
      <ConfirmModal
        open={drainTarget !== null}
        onClose={() => {
          setDrainTarget(null);
          setDrainConfirm('');
        }}
        onConfirm={confirmDrain}
        title={`Drain ${drainTarget ?? ''}`}
        confirmText={drainConfirm === 'DRAIN' ? 'Drain' : 'Type DRAIN to confirm'}
        variant="danger"
      >
        <p className="text-[13px] text-fg-muted">
          The bot will stop pulling new orders from the queue. In-flight workers will continue until they
          complete. Reversible via <span className="font-mono">Stop drain</span>.
        </p>
        <input
          type="text"
          value={drainConfirm}
          onChange={(e) => setDrainConfirm(e.target.value)}
          placeholder="Type DRAIN"
          autoFocus
          className="mt-3 w-full rounded-md border border-border bg-bg-sunken px-3 py-2 font-mono text-[13px] focus:border-accent focus:outline-none"
        />
      </ConfirmModal>
    </>
  );
}

// =====================================================================

function InstanceCard({
  instance: b,
  busy,
  onStart,
  onStop,
  onResume,
  onDrain,
  onReload,
}: {
  instance: BotInstanceStatus;
  busy: boolean;
  onStart: () => void;
  onStop: () => void;
  onResume: () => void;
  onDrain: () => void;
  onReload: () => void;
}) {
  const statusKey = STATUS_BADGE_FROM_INSTANCE(b);
  return (
    <Card className="p-5">
      <div className="flex items-center justify-between">
        <div className="flex items-center gap-2">
          <Icon name="bot" size={16} className="text-fg-muted" />
          <span className="font-mono text-[14px] font-semibold">{b.id}</span>
          <StatusBadge status={statusKey} />
          <span className="text-[12px] text-fg-subtle">{STATUS_LABEL_FROM_INSTANCE(b)}</span>
          {b.draining && (
            <span className="rounded-md bg-warn/15 px-2 py-[2px] text-[10.5px] font-semibold uppercase tracking-wider text-warn">
              Draining
            </span>
          )}
          {b.version && <span className="font-mono text-[11.5px] text-fg-subtle">v{b.version}</span>}
        </div>
        <div className="flex gap-1">
          <Button variant="ghost" size="sm" icon="refresh" onClick={onReload} disabled={!b.online || busy}>
            Reload
          </Button>
        </div>
      </div>
      <div className="mt-1 font-mono text-[11.5px] text-fg-subtle">{b.baseUrl}</div>

      {!b.online && b.lastError && (
        <div className="mt-3 rounded-md border border-danger/40 bg-danger/5 px-3 py-2 font-mono text-[11.5px] text-danger">
          {b.lastError}
        </div>
      )}

      {b.online && (
        <>
          <div className="mt-4 grid grid-cols-2 gap-2 sm:grid-cols-3 lg:grid-cols-6">
            <Stat label="Queue depth" value={fmtInt(b.queueDepth ?? 0)} warn={(b.queueDepth ?? 0) > 500} />
            <Stat label="In progress" value={fmtInt(b.inProgress ?? 0)} />
            <Stat label="Active workers" value={fmtInt(b.activeWorkers ?? 0)} />
            <Stat label="Completed" value={fmtInt(b.completedOrders ?? 0)} />
            <Stat label="Uptime" value={b.uptime ?? '—'} mono={false} />
            <Stat
              label="Heartbeat"
              value={b.heartbeatAt ? fmtDateTime(b.heartbeatAt) : '—'}
              mono={false}
            />
          </div>
          <div className="mt-3 flex flex-wrap justify-end gap-2 border-t border-border pt-3">
            {b.running ? (
              <Button variant="secondary" size="sm" icon="pause" onClick={onStop} disabled={busy}>
                Stop workers
              </Button>
            ) : (
              <Button variant="secondary" size="sm" icon="play" onClick={onStart} disabled={busy}>
                Start workers
              </Button>
            )}
            {b.draining ? (
              <Button variant="primary" size="sm" onClick={onResume} disabled={busy}>
                Stop drain
              </Button>
            ) : (
              <Button variant="ghost" size="sm" onClick={onDrain} disabled={busy || !b.running}>
                Drain
              </Button>
            )}
          </div>
        </>
      )}
    </Card>
  );
}

// =====================================================================

function Stat({
  label,
  value,
  warn,
  mono = true,
}: {
  label: string;
  value: React.ReactNode;
  warn?: boolean;
  mono?: boolean;
}) {
  return (
    <div className="rounded-md border border-border bg-bg-sunken p-3">
      <div className="text-[10.5px] uppercase tracking-wider text-fg-subtle">{label}</div>
      <div
        className={cn(
          'mt-1 text-[14px] font-semibold',
          mono && 'font-mono tabular-nums',
          warn && 'text-warn',
        )}
      >
        {value}
      </div>
    </div>
  );
}

// =====================================================================

function WebhookTail() {
  const [events, setEvents] = useState<BotWebhookEvent[]>([]);
  const [paused, setPaused] = useState(false);
  const [streamState, setStreamState] = useState<'idle' | 'connected' | 'error'>('idle');
  const pausedRef = useRef(paused);
  pausedRef.current = paused;

  const cap = (list: BotWebhookEvent[]) => list.slice(0, 50);

  // Initial load + live SSE subscription.
  useEffect(() => {
    let aborted = false;
    let abortController: AbortController | null = null;

    adminAPI
      .botRecentWebhooks(50)
      .then((data) => {
        if (!aborted) setEvents(cap(data));
      })
      .catch(() => {
        if (!aborted) setEvents([]);
      });

    const subscribe = async () => {
      const token = localStorage.getItem('token');
      abortController = new AbortController();
      try {
        const resp = await fetch('/api/v2/admin/bot/webhooks/stream', {
          headers: token
            ? { Authorization: `Bearer ${token}`, Accept: 'text/event-stream' }
            : { Accept: 'text/event-stream' },
          signal: abortController.signal,
        });
        // Auth-related failures: bail out instead of looping every 5s. The next
        // axios call will pick up the 401 and bounce the user to /login; we
        // shouldn't shadow that with a hot reconnect storm.
        if (resp.status === 401 || resp.status === 403) {
          setStreamState('error');
          return;
        }
        if (!resp.ok || !resp.body) {
          throw new Error(`HTTP ${resp.status}`);
        }
        setStreamState('connected');

        const reader = resp.body.getReader();
        const decoder = new TextDecoder();
        let buffer = '';
        while (!aborted) {
          const { value, done } = await reader.read();
          if (done) break;
          buffer += decoder.decode(value, { stream: true });
          // SSE frames end on "\n\n"; a frame can contain multiple "data:" lines.
          let sep = buffer.indexOf('\n\n');
          while (sep !== -1) {
            const frame = buffer.slice(0, sep);
            buffer = buffer.slice(sep + 2);
            sep = buffer.indexOf('\n\n');
            // Each frame: lines starting with "data:" carry payload (joined by \n).
            const dataLines: string[] = [];
            for (const rawLine of frame.split('\n')) {
              if (rawLine.startsWith('data:')) {
                dataLines.push(rawLine.slice(5).trimStart());
              }
            }
            if (dataLines.length === 0) continue;
            const payload = dataLines.join('\n');
            try {
              const ev = JSON.parse(payload) as BotWebhookEvent;
              if (!pausedRef.current) {
                setEvents((prev) => cap([ev, ...prev]));
              }
            } catch {
              // ignore malformed payload
            }
          }
        }
        // Stream ended cleanly (server SseEmitter timeout / completion). Reconnect
        // shortly so the live tail keeps working without a manual page refresh —
        // servlet containers default to a 30s async timeout, so this can fire often.
        if (!aborted) {
          setStreamState('error');
          setTimeout(() => {
            if (!aborted) subscribe();
          }, 1_000);
        }
      } catch (e) {
        if (!aborted) {
          setStreamState('error');
          // Reconnect after a short delay (SSE is lossy by design — this is fine)
          setTimeout(() => {
            if (!aborted) subscribe();
          }, 5_000);
        }
      }
    };

    subscribe();

    return () => {
      aborted = true;
      abortController?.abort();
    };
  }, []);

  return (
    <Section
      title="Recent bot webhooks"
      subtitle={
        <span className="inline-flex items-center gap-2">
          <Dot
            color={streamState === 'connected' ? 'var(--success)' : streamState === 'error' ? 'var(--warn)' : 'var(--fg-subtle)'}
            animate={streamState === 'connected'}
          />
          {streamState === 'connected'
            ? paused
              ? 'Paused'
              : 'Live tail'
            : streamState === 'error'
              ? 'Reconnecting…'
              : 'Connecting…'}
        </span>
      }
      pad={false}
      action={
        <Button variant="ghost" size="sm" onClick={() => setPaused((p) => !p)}>
          {paused ? 'Resume' : 'Pause'}
        </Button>
      }
    >
      <div className="bg-bg-deep p-4 font-mono text-[12px] text-white/85">
        {events.length === 0 ? (
          <div className="py-6 text-center text-[12px] text-white/55">
            No events yet. Webhooks from the bot will appear here in real time.
          </div>
        ) : (
          events.map((e, i) => <EventRow key={`${e.ts}-${i}`} event={e} />)
        )}
      </div>
    </Section>
  );
}

function EventRow({ event }: { event: BotWebhookEvent }) {
  const sev = (event.severity ?? 'info') as Severity;
  return (
    <div className="flex flex-wrap gap-3 py-[2px]">
      <span className="text-white/40">{fmtTs(event.ts)}</span>
      <span className={cn('font-semibold', SEVERITY_TONE[sev])}>
        {event.event ?? `order.${event.status ?? 'unknown'}`}
      </span>
      {event.botOrderId && <span className="text-white/55">{event.botOrderId}</span>}
      {event.externalId && <span className="text-white/55">ext={event.externalId}</span>}
      {typeof event.completed === 'number' && (
        <span className="text-white">completed={event.completed}</span>
      )}
      {typeof event.failed === 'number' && event.failed > 0 && (
        <span className="text-[#f87171]">failed={event.failed}</span>
      )}
      {event.message && <span className="text-[#f87171]">· {event.message}</span>}
      {event.source && <span className="ml-auto text-white/30">[{event.source}]</span>}
    </div>
  );
}

function fmtTs(iso: string): string {
  try {
    const d = new Date(iso);
    return d.toLocaleTimeString('en-GB', { hour12: false });
  } catch {
    return iso;
  }
}

// =====================================================================

function QueueSnapshot({ id }: { id: string }) {
  const [rows, setRows] = useState<Array<Record<string, unknown>> | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    const load = () => {
      adminAPI
        .botQueue(id, { limit: 10 })
        .then((data) => {
          if (cancelled) return;
          // Sort by createdAt desc when available; otherwise leave bot order.
          const sorted = [...data].sort((a, b) => {
            const ta = parseTs(a.created_at);
            const tb = parseTs(b.created_at);
            return tb - ta;
          });
          setRows(sorted.slice(0, 10));
          setError(null);
        })
        .catch((e) => {
          if (cancelled) return;
          setError(e instanceof Error ? e.message : 'request failed');
        });
    };
    load();
    const t = setInterval(load, 15_000);
    return () => {
      cancelled = true;
      clearInterval(t);
    };
  }, [id]);

  return (
    <Section title={`${id} — queue snapshot`} subtitle="Last 10 orders on this instance">
      {error ? (
        <Card className="p-4 text-[13px] text-danger">{error}</Card>
      ) : rows === null ? (
        <Card className="p-4 text-[13px] text-fg-muted">Loading…</Card>
      ) : rows.length === 0 ? (
        <Card className="p-4">
          <Empty title="No orders" subtitle="The bot's queue is empty right now." />
        </Card>
      ) : (
        <table className="tbl">
          <thead>
            <tr>
              <th>ID</th>
              <th>External</th>
              <th>Type</th>
              <th>Status</th>
              <th className="text-right">Done</th>
              <th className="text-right">Total</th>
              <th>Created</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((o, i) => {
              const status = String(o.status ?? '');
              return (
                <tr key={String(o.id ?? i)}>
                  <td className="font-mono text-[12px]">{trunc(o.id, 16)}</td>
                  <td className="font-mono text-[12px]">{trunc(o.external_id, 12) || '—'}</td>
                  <td>{String(o.type ?? '')}</td>
                  <td>
                    <span className={cn('text-[12px]', statusColor(status))}>{status}</span>
                  </td>
                  <td className="text-right font-mono">{fmtInt(toInt(o.completed))}</td>
                  <td className="text-right font-mono">{fmtInt(toInt(o.count))}</td>
                  <td className="text-[12px] text-fg-muted">{fmtDateTimeOr(o.created_at)}</td>
                </tr>
              );
            })}
          </tbody>
        </table>
      )}
    </Section>
  );
}

function trunc(v: unknown, n: number): string {
  const s = String(v ?? '');
  return s.length > n ? s.slice(0, n) + '…' : s;
}
function toInt(v: unknown): number {
  const n = typeof v === 'number' ? v : Number.parseFloat(String(v ?? 0));
  return Number.isFinite(n) ? n : 0;
}
function parseTs(v: unknown): number {
  if (!v) return 0;
  const t = Date.parse(String(v));
  return Number.isFinite(t) ? t : 0;
}
function fmtDateTimeOr(v: unknown): string {
  const t = parseTs(v);
  return t > 0 ? fmtDateTime(new Date(t)) : '—';
}
function statusColor(s: string): string {
  switch (s) {
    case 'completed':
      return 'text-success';
    case 'failed':
    case 'cancelled':
      return 'text-danger';
    case 'partial':
    case 'paused':
      return 'text-warn';
    case 'processing':
    case 'scouting':
      return 'text-accent';
    default:
      return 'text-fg-muted';
  }
}
