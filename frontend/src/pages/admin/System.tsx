import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import {
  Button,
  Card,
  ConfirmModal,
  Dot,
  Empty,
  Input,
  PageHeader,
  Section,
  Select,
  Tabs,
  useToast,
} from '../../components/ui';
import { useVisibilityAwarePoll } from '../../hooks/usePolling';
import {
  adminAPI,
  type CacheStats,
  type LogLevel,
  type QueueStats,
  type SystemErrorGroup,
  type SystemLogEntry,
} from '../../services/api';
import { cn, fmtInt } from '../../lib/utils';

// =====================================================================
// /admin/system — real backend wiring.
//   Logs    → REST /v2/admin/system/logs + SSE /v2/admin/system/logs/stream
//   Errors  → REST /v2/admin/system/errors (grouped by message hash)
//   Queues  → REST /v2/admin/system/queues  (RabbitMQ HTTP mgmt API or AMQP fallback)
//   Cache   → REST /v2/admin/system/cache   (Redis INFO snapshot)
//
// Source-unreachable (Redis/RabbitMQ down) → backend returns 503 → tabs render
// an honest "Source unreachable" empty state. No fake fallback.
// =====================================================================

type TabKey = 'logs' | 'errors' | 'queues' | 'cache';
type SourceState = 'idle' | 'loading' | 'ok' | 'unreachable' | 'error';

const LEVEL_TONE: Record<LogLevel, string> = {
  TRACE: 'text-[#a1a1aa]',
  DEBUG: 'text-[#a1a1aa]',
  INFO: 'text-[#a7f3d0]',
  WARN: 'text-[#fbbf24]',
  ERROR: 'text-[#f87171]',
};

export function AdminSystemPage() {
  const [tab, setTab] = useState<TabKey>('logs');
  const [errorCount, setErrorCount] = useState<number | null>(null);

  // Refresh the badge independently of the active tab so the count stays current
  // even while the user is on Logs / Queues / Cache.
  const loadErrorCount = useCallback(() => {
    adminAPI
      .systemErrorsCount(24)
      .then((r) => setErrorCount(r.count))
      .catch(() => setErrorCount(null));
  }, []);
  useVisibilityAwarePoll(loadErrorCount, 30_000);

  return (
    <>
      <PageHeader
        title="System monitoring"
        subtitle="Live application internals · logs, errors, queues, cache"
      />
      <div className="space-y-4 p-6">
        <Tabs
          value={tab}
          onChange={setTab}
          tabs={[
            { value: 'logs', label: 'Logs' },
            { value: 'errors', label: 'Errors', count: errorCount ?? undefined },
            { value: 'queues', label: 'Queues' },
            { value: 'cache', label: 'Cache' },
          ]}
        />
        {tab === 'logs' && <LogsTab />}
        {tab === 'errors' && <ErrorsTab />}
        {tab === 'queues' && <QueuesTab />}
        {tab === 'cache' && <CacheTab />}
      </div>
    </>
  );
}

// =====================================================================
// LOGS
// =====================================================================

function LogsTab() {
  const [level, setLevel] = useState<'all' | LogLevel>('all');
  const [search, setSearch] = useState('');
  const [source, setSource] = useState('');
  const [paused, setPaused] = useState(false);
  const [streamState, setStreamState] = useState<'idle' | 'connected' | 'error'>('idle');
  const [entries, setEntries] = useState<SystemLogEntry[]>([]);
  const [loadState, setLoadState] = useState<SourceState>('idle');
  const pausedRef = useRef(paused);
  pausedRef.current = paused;

  const cap = (list: SystemLogEntry[]) => list.slice(0, 500);

  // Build a debounced query for the REST seed. The live SSE stream isn't filtered server-side —
  // we apply the same filter client-side to the incoming events below.
  const reload = useCallback(() => {
    setLoadState('loading');
    adminAPI
      .systemLogs({
        level: level === 'all' ? undefined : level,
        search: search.trim() || undefined,
        source: source.trim() || undefined,
        limit: 200,
      })
      .then((data) => {
        setEntries(cap(data));
        setLoadState('ok');
      })
      .catch((err) => {
        setEntries([]);
        setLoadState(err?.response?.status === 503 ? 'unreachable' : 'error');
      });
  }, [level, search, source]);

  useEffect(() => {
    const t = setTimeout(reload, 250);
    return () => clearTimeout(t);
  }, [reload]);

  // SSE subscription — same fetch+ReadableStream pattern as Bot.tsx so the JWT
  // travels in an Authorization header (EventSource can't send custom headers).
  useEffect(() => {
    let aborted = false;
    let abortController: AbortController | null = null;

    const subscribe = async () => {
      const token = localStorage.getItem('token');
      abortController = new AbortController();
      try {
        const resp = await fetch('/api/v2/admin/system/logs/stream', {
          headers: token
            ? { Authorization: `Bearer ${token}`, Accept: 'text/event-stream' }
            : { Accept: 'text/event-stream' },
          signal: abortController.signal,
        });
        if (resp.status === 401 || resp.status === 403) {
          setStreamState('error');
          return;
        }
        if (!resp.ok || !resp.body) throw new Error(`HTTP ${resp.status}`);
        setStreamState('connected');

        const reader = resp.body.getReader();
        const decoder = new TextDecoder();
        let buffer = '';
        while (!aborted) {
          const { value, done } = await reader.read();
          if (done) break;
          buffer += decoder.decode(value, { stream: true });
          let sep = buffer.indexOf('\n\n');
          while (sep !== -1) {
            const frame = buffer.slice(0, sep);
            buffer = buffer.slice(sep + 2);
            sep = buffer.indexOf('\n\n');
            const dataLines: string[] = [];
            for (const rawLine of frame.split('\n')) {
              if (rawLine.startsWith('data:')) dataLines.push(rawLine.slice(5).trimStart());
            }
            if (dataLines.length === 0) continue;
            try {
              const ev = JSON.parse(dataLines.join('\n')) as SystemLogEntry;
              if (!pausedRef.current) {
                setEntries((prev) => cap([ev, ...prev]));
              }
            } catch {
              // ignore malformed payload
            }
          }
        }
        if (!aborted) {
          setStreamState('error');
          setTimeout(() => {
            if (!aborted) subscribe();
          }, 1_000);
        }
      } catch {
        if (!aborted) {
          setStreamState('error');
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

  // Apply the same filters client-side to the live feed so the visible list stays consistent
  // with the seed query. (The server seed already applied them; SSE arrives unfiltered.)
  const visible = useMemo(() => {
    const lvl = level === 'all' ? null : level;
    const s = search.trim().toLowerCase();
    const src = source.trim().toLowerCase();
    return entries.filter((e) => {
      if (lvl && e.level !== lvl) return false;
      if (src && !(e.source ?? '').toLowerCase().includes(src)) return false;
      if (s) {
        const hay = `${e.msg} ${e.source ?? ''} ${e.logger ?? ''} ${e.throwable ?? ''}`.toLowerCase();
        if (!hay.includes(s)) return false;
      }
      return true;
    });
  }, [entries, level, search, source]);

  return (
    <Section
      title="Application logs"
      subtitle={
        <span className="inline-flex items-center gap-2">
          <Dot
            color={
              streamState === 'connected'
                ? 'var(--success)'
                : streamState === 'error'
                  ? 'var(--warn)'
                  : 'var(--fg-subtle)'
            }
            animate={streamState === 'connected' && !paused}
          />
          {streamState === 'connected'
            ? paused
              ? 'Paused'
              : 'Live tail'
            : streamState === 'error'
              ? 'Reconnecting…'
              : 'Connecting…'}
          <span className="text-fg-subtle">·</span>
          <span className="font-mono text-fg-subtle">{visible.length} shown</span>
        </span>
      }
      action={
        <div className="flex flex-wrap items-center gap-2">
          <Input
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            placeholder="Search message"
            inputSize="sm"
            className="w-[180px]"
          />
          <Input
            value={source}
            onChange={(e) => setSource(e.target.value)}
            placeholder="Source class"
            inputSize="sm"
            className="w-[140px]"
          />
          <Select
            selectSize="sm"
            value={level}
            onChange={(e) => setLevel(e.target.value as 'all' | LogLevel)}
            options={[
              { value: 'all', label: 'All levels' },
              { value: 'ERROR', label: 'ERROR' },
              { value: 'WARN', label: 'WARN' },
              { value: 'INFO', label: 'INFO' },
              { value: 'DEBUG', label: 'DEBUG' },
              { value: 'TRACE', label: 'TRACE' },
            ]}
          />
          <Button variant="ghost" size="sm" onClick={() => setPaused((p) => !p)}>
            {paused ? 'Resume' : 'Pause'}
          </Button>
        </div>
      }
      pad={false}
    >
      {loadState === 'unreachable' ? (
        <UnreachableEmpty source="Redis" />
      ) : loadState === 'error' ? (
        <div className="bg-bg-deep p-6 text-center text-[12px] text-danger">
          Failed to load logs.
        </div>
      ) : (
        <div className="max-h-[560px] min-h-[200px] overflow-auto bg-bg-deep p-4 font-mono text-[12px] text-white/85">
          {visible.length === 0 ? (
            <div className="py-6 text-center text-[12px] text-white/55">
              {loadState === 'loading'
                ? 'Loading…'
                : entries.length === 0
                  ? 'No log events yet.'
                  : 'No entries match the current filter.'}
            </div>
          ) : (
            visible.map((e, i) => <LogRow key={`${e.ts}-${i}`} entry={e} />)
          )}
        </div>
      )}
    </Section>
  );
}

function LogRow({ entry }: { entry: SystemLogEntry }) {
  return (
    <div className="flex gap-3 py-[2px]">
      <span className="text-white/40">{fmtTime(entry.ts)}</span>
      <span className={cn('min-w-[44px] font-semibold', LEVEL_TONE[entry.level])}>
        {entry.level}
      </span>
      <span className="min-w-[180px] truncate text-white/55" title={entry.logger}>
        {entry.source}
      </span>
      <span className="flex-1 break-all text-white/85">
        {entry.msg}
        {entry.throwableClass ? (
          <span className="ml-2 text-[#f87171]">[{entry.throwableClass}]</span>
        ) : null}
      </span>
    </div>
  );
}

// =====================================================================
// ERRORS
// =====================================================================

function ErrorsTab() {
  const [groups, setGroups] = useState<SystemErrorGroup[]>([]);
  const [loadState, setLoadState] = useState<SourceState>('idle');
  const [expanded, setExpanded] = useState<string | null>(null);
  const [sinceHours, setSinceHours] = useState<number>(24);

  const load = useCallback(() => {
    // Only flash 'loading' on the very first fetch — polling refreshes shouldn't flicker the tab.
    setLoadState((s) => (s === 'idle' ? 'loading' : s));
    adminAPI
      .systemErrors(sinceHours)
      .then((data) => {
        setGroups(data);
        setLoadState('ok');
      })
      .catch((err) => {
        setGroups([]);
        setLoadState(err?.response?.status === 503 ? 'unreachable' : 'error');
      });
  }, [sinceHours]);

  // 30s instead of 15s — operators don't need errors more frequently than that
  // and visibility-aware polling stops the request stream when the tab is hidden.
  useVisibilityAwarePoll(load, 30_000);

  return (
    <Section
      title={`Errors grouped by message · last ${sinceHours}h`}
      subtitle={
        loadState === 'ok' ? (
          <span className="font-mono text-fg-subtle">{groups.length} distinct</span>
        ) : null
      }
      action={
        <div className="flex items-center gap-2">
          <Select
            selectSize="sm"
            value={String(sinceHours)}
            onChange={(e) => setSinceHours(Number.parseInt(e.target.value, 10) || 24)}
            options={[
              { value: '1', label: 'Last 1h' },
              { value: '6', label: 'Last 6h' },
              { value: '24', label: 'Last 24h' },
              { value: '168', label: 'Last 7d' },
            ]}
          />
          <Button variant="ghost" size="sm" icon="refresh" onClick={load}>
            Refresh
          </Button>
        </div>
      }
      pad={false}
    >
      {loadState === 'unreachable' ? (
        <UnreachableEmpty source="Redis" />
      ) : loadState === 'error' ? (
        <div className="p-6 text-center text-[12px] text-danger">Failed to load errors.</div>
      ) : groups.length === 0 ? (
        <div className="p-8">
          <Empty
            title={loadState === 'loading' ? 'Loading…' : 'No errors in the selected window'}
            subtitle={loadState === 'loading' ? undefined : 'A clean log is a good log.'}
          />
        </div>
      ) : (
        <table className="tbl">
          <thead>
            <tr>
              <th>Hash</th>
              <th className="text-right">Count</th>
              <th>Sample message</th>
              <th>Throwable</th>
              <th>First seen</th>
              <th>Last seen</th>
              <th />
            </tr>
          </thead>
          <tbody>
            {groups.flatMap((g) => {
              const isOpen = expanded === g.hash;
              const rows = [
                <tr key={g.hash}>
                  <td>
                    <span className="font-mono text-[11px] rounded bg-bg-sunken px-2 py-[2px]">
                      {g.hash}
                    </span>
                  </td>
                  <td
                    className={cn(
                      'text-right font-mono',
                      g.count > 100 ? 'text-danger' : g.count > 10 ? 'text-warn' : 'text-fg',
                    )}
                  >
                    {fmtInt(g.count)}
                  </td>
                  <td className="max-w-[460px] truncate text-[12.5px]" title={g.sample ?? ''}>
                    {g.sample ?? '—'}
                  </td>
                  <td className="font-mono text-[11.5px] text-fg-muted">
                    {g.throwableClass ?? '—'}
                  </td>
                  <td className="font-mono text-[11.5px] text-fg-muted">
                    {fmtTime(g.firstSeen)}
                  </td>
                  <td className="font-mono text-[11.5px] text-fg-muted">{fmtTime(g.lastSeen)}</td>
                  <td>
                    <Button
                      variant="ghost"
                      size="sm"
                      iconRight={isOpen ? 'chevron-down' : 'chevron-right'}
                      onClick={() => setExpanded(isOpen ? null : g.hash)}
                    >
                      {isOpen ? 'Hide' : 'Trace'}
                    </Button>
                  </td>
                </tr>,
              ];
              if (isOpen) {
                rows.push(
                  <tr key={g.hash + '-trace'}>
                    <td colSpan={7} className="bg-bg-deep p-0">
                      <div className="space-y-3 p-4 font-mono text-[11.5px]">
                        {g.sources && g.sources.length > 0 && (
                          <div className="text-white/55">
                            Sources:{' '}
                            <span className="text-white/85">{g.sources.join(', ')}</span>
                          </div>
                        )}
                        {(g.samples ?? []).map((s, i) => (
                          <div key={i} className="rounded bg-bg-sunken p-3">
                            <div className="text-white/55">
                              {fmtTime(s.ts)} · {s.thread ?? '?'} · {s.logger ?? s.source}
                            </div>
                            <div className="mt-1 whitespace-pre-wrap break-words text-[#f87171]">
                              {s.msg}
                            </div>
                            {s.throwable && (
                              <pre className="mt-2 max-h-[260px] overflow-auto whitespace-pre-wrap break-words text-[11px] text-white/70">
                                {s.throwable}
                              </pre>
                            )}
                          </div>
                        ))}
                      </div>
                    </td>
                  </tr>,
                );
              }
              return rows;
            })}
          </tbody>
        </table>
      )}
    </Section>
  );
}

// =====================================================================
// QUEUES
// =====================================================================

function QueuesTab() {
  const toast = useToast();
  const [rows, setRows] = useState<QueueStats[]>([]);
  const [loadState, setLoadState] = useState<SourceState>('idle');
  const [purgeTarget, setPurgeTarget] = useState<string | null>(null);
  const [purging, setPurging] = useState(false);

  const load = useCallback(() => {
    setLoadState((s) => (s === 'idle' ? 'loading' : s));
    adminAPI
      .systemQueues()
      .then((data) => {
        setRows(data);
        setLoadState('ok');
      })
      .catch((err) => {
        setRows([]);
        setLoadState(err?.response?.status === 503 ? 'unreachable' : 'error');
      });
  }, []);

  // RabbitMQ queue counters: 15s instead of 5s — depths only matter at the
  // "is this stuck for minutes?" granularity, not at every-frame freshness.
  // The old 5s interval generated 12 RPM per open tab on a metric we read
  // from RabbitMQ's management API (~80ms/call) for nothing.
  useVisibilityAwarePoll(load, 15_000);

  const onPurge = async () => {
    if (!purgeTarget) return;
    setPurging(true);
    try {
      const r = await adminAPI.systemQueuePurge(purgeTarget);
      toast(`Purged ${fmtInt(r.purged)} message${r.purged === 1 ? '' : 's'} from ${r.queue}`, 'success');
      setPurgeTarget(null);
      load();
    } catch (e) {
      const msg = e instanceof Error ? e.message : 'request failed';
      toast(`Purge failed: ${msg}`, 'error');
    } finally {
      setPurging(false);
    }
  };

  return (
    <>
      <Section
        title="RabbitMQ queues"
        subtitle={
          loadState === 'ok' ? (
            <span className="font-mono text-fg-subtle">{rows.length} queues</span>
          ) : null
        }
        action={
          <Button variant="ghost" size="sm" icon="refresh" onClick={load}>
            Refresh
          </Button>
        }
        pad={false}
      >
        {loadState === 'unreachable' ? (
          <UnreachableEmpty source="RabbitMQ" />
        ) : loadState === 'error' ? (
          <div className="p-6 text-center text-[12px] text-danger">Failed to load queues.</div>
        ) : rows.length === 0 ? (
          <div className="p-8">
            <Empty
              title={loadState === 'loading' ? 'Loading…' : 'No queues found'}
              subtitle={loadState === 'loading' ? undefined : 'Nothing declared on the configured vhost.'}
            />
          </div>
        ) : (
          <table className="tbl">
            <thead>
              <tr>
                <th>Queue</th>
                <th className="text-right">Depth</th>
                <th className="text-right">Unacked</th>
                <th className="text-right">Consumers</th>
                <th className="text-right">In/s</th>
                <th className="text-right">Out/s</th>
                <th className="text-right">Ack/s</th>
                <th className="text-right">DLQ depth</th>
                <th>Type</th>
                <th />
              </tr>
            </thead>
            <tbody>
              {rows.map((q) => {
                const heavy = q.depth > 200;
                return (
                  <tr key={q.name} className={cn(q.isDlq && 'bg-danger/[0.04]')}>
                    <td className="font-mono text-[12px]">
                      {q.name}
                      {q.consumers === 0 && q.depth > 0 && (
                        <span className="ml-2 rounded bg-warn/20 px-2 py-[1px] text-[10px] uppercase tracking-wider text-warn">
                          no consumer
                        </span>
                      )}
                    </td>
                    <td className={cn('text-right font-mono', heavy && 'text-warn')}>
                      {fmtInt(q.depth)}
                    </td>
                    <td className="text-right font-mono text-fg-muted">{fmtInt(q.unacked)}</td>
                    <td className="text-right font-mono">{q.consumers}</td>
                    <td className="text-right font-mono">{fmtRate(q.publishRate)}</td>
                    <td className="text-right font-mono">{fmtRate(q.deliverRate)}</td>
                    <td className="text-right font-mono">{fmtRate(q.ackRate)}</td>
                    <td className="text-right font-mono text-fg-muted">
                      {q.dlqDepth < 0 ? '—' : fmtInt(q.dlqDepth)}
                    </td>
                    <td>
                      {q.isDlq ? (
                        <span className="inline-flex items-center gap-1.5 text-[12px] text-danger">
                          <Dot color="var(--danger)" size={6} /> DLQ
                        </span>
                      ) : (
                        <span className="inline-flex items-center gap-1.5 text-[12px]">
                          <Dot
                            color={
                              q.consumers === 0
                                ? 'var(--warn)'
                                : q.depth > 0
                                  ? 'var(--success)'
                                  : 'var(--fg-dim)'
                            }
                            size={6}
                          />
                          work
                        </span>
                      )}
                    </td>
                    <td>
                      {q.isDlq && (
                        <Button
                          variant="ghost"
                          size="sm"
                          onClick={() => setPurgeTarget(q.name)}
                        >
                          Purge
                        </Button>
                      )}
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        )}
      </Section>

      <ConfirmModal
        open={purgeTarget !== null}
        onClose={() => (purging ? undefined : setPurgeTarget(null))}
        onConfirm={onPurge}
        title={`Purge ${purgeTarget ?? ''}`}
        confirmText="Purge"
        cancelText="Cancel"
        variant="danger"
        loading={purging}
        message={
          <>
            All messages currently in the dead-letter queue will be permanently deleted. This cannot
            be undone.
          </>
        }
      />
    </>
  );
}

function fmtRate(r: number): string {
  if (r < 0) return '—';
  if (r === 0) return '0';
  if (r >= 100) return r.toFixed(0);
  if (r >= 10) return r.toFixed(1);
  return r.toFixed(2);
}

// =====================================================================
// CACHE
// =====================================================================

function CacheTab() {
  const toast = useToast();
  const [stats, setStats] = useState<CacheStats | null>(null);
  const [loadState, setLoadState] = useState<SourceState>('idle');
  const [opsHistory, setOpsHistory] = useState<number[]>([]);
  const [flushOpen, setFlushOpen] = useState(false);
  const [flushConfirm, setFlushConfirm] = useState('');
  const [flushing, setFlushing] = useState(false);

  const load = useCallback(() => {
    setLoadState((s) => (s === 'idle' ? 'loading' : s));
    adminAPI
      .systemCache()
      .then((data) => {
        setStats(data);
        setLoadState('ok');
        if (data.opsPerSec >= 0) {
          setOpsHistory((prev) => [...prev.slice(-59), data.opsPerSec]);
        }
      })
      .catch((err) => {
        setStats(null);
        setLoadState(err?.response?.status === 503 ? 'unreachable' : 'error');
      });
  }, []);

  // Redis ops/sec sparkline: 10s — gives a smooth-enough chart without DDoS-ing
  // INFO + DBSIZE on every Redis instance. Old 5s interval was too aggressive.
  useVisibilityAwarePoll(load, 10_000);

  const onFlush = async () => {
    if (flushConfirm !== 'FLUSH') return;
    setFlushing(true);
    try {
      const r = await adminAPI.systemCacheFlush('FLUSH');
      toast(`Flushed ${fmtInt(r.keysBefore < 0 ? 0 : r.keysBefore)} keys`, 'success');
      setFlushOpen(false);
      setFlushConfirm('');
      load();
    } catch (e) {
      const msg = e instanceof Error ? e.message : 'request failed';
      toast(`Flush failed: ${msg}`, 'error');
    } finally {
      setFlushing(false);
    }
  };

  if (loadState === 'unreachable') {
    return (
      <Section title="Redis cache" pad={false}>
        <UnreachableEmpty source="Redis" />
      </Section>
    );
  }

  if (loadState === 'error') {
    return (
      <Section title="Redis cache">
        <div className="p-6 text-center text-[12px] text-danger">Failed to load cache stats.</div>
      </Section>
    );
  }

  if (!stats) {
    return (
      <Section title="Redis cache">
        <div className="p-6 text-center text-[12px] text-fg-muted">Loading…</div>
      </Section>
    );
  }

  const memPct =
    stats.maxMemory > 0 && stats.usedMemory > 0
      ? Math.min(100, (stats.usedMemory / stats.maxMemory) * 100)
      : 0;

  return (
    <>
      <div className="space-y-4">
        {/* KPI strip */}
        <div className="grid grid-cols-2 gap-3 md:grid-cols-4">
          <Kpi
            label="Memory used"
            value={stats.usedMemoryHuman}
            sub={
              stats.maxMemory > 0
                ? `${formatBytes(stats.maxMemory)} max · ${memPct.toFixed(1)}%`
                : 'uncapped'
            }
            barPct={stats.maxMemory > 0 ? memPct : undefined}
          />
          <Kpi
            label="Hit rate"
            value={stats.hitRate < 0 ? '—' : `${stats.hitRate.toFixed(1)}%`}
            sub={
              stats.keyspaceHits >= 0 && stats.keyspaceMisses >= 0
                ? `${fmtInt(stats.keyspaceHits)} hits / ${fmtInt(stats.keyspaceMisses)} misses`
                : undefined
            }
            tone={
              stats.hitRate < 0
                ? 'muted'
                : stats.hitRate > 90
                  ? 'success'
                  : stats.hitRate > 70
                    ? 'fg'
                    : 'warn'
            }
          />
          <Kpi
            label="Ops / sec"
            value={stats.opsPerSec < 0 ? '—' : stats.opsPerSec.toFixed(0)}
            sub={`${fmtInt(stats.totalKeys < 0 ? 0 : stats.totalKeys)} keys`}
            sparkline={opsHistory}
          />
          <Kpi
            label="Connections"
            value={stats.connectedClients < 0 ? '—' : String(stats.connectedClients)}
            sub={`Redis ${stats.version} · ${formatUptime(stats.uptimeSeconds)}`}
          />
        </div>

        {/* Detail strip + actions */}
        <Section title="Details" pad={false}>
          <div className="grid grid-cols-2 gap-x-6 gap-y-2 p-4 text-[12.5px] sm:grid-cols-3 lg:grid-cols-4">
            <Detail k="Memory peak" v={stats.usedMemoryPeak < 0 ? '—' : formatBytes(stats.usedMemoryPeak)} />
            <Detail k="Memory raw" v={stats.usedMemory < 0 ? '—' : formatBytes(stats.usedMemory)} />
            <Detail k="Evicted keys" v={stats.evictedKeys < 0 ? '—' : fmtInt(stats.evictedKeys)} />
            <Detail k="Expired keys" v={stats.expiredKeys < 0 ? '—' : fmtInt(stats.expiredKeys)} />
          </div>
        </Section>

        <Card className="border-danger/30 p-4">
          <div className="flex flex-wrap items-center justify-between gap-3">
            <div>
              <div className="text-[13px] font-semibold text-danger">Flush cache</div>
              <div className="mt-1 text-[12px] text-fg-muted">
                Drops every key in the active Redis database. Cached services, balances, sessions,
                rate-limit counters — all gone. Application keeps working but the next minute will
                be slower until the cache warms back up.
              </div>
            </div>
            <Button variant="danger" size="sm" onClick={() => setFlushOpen(true)}>
              Flush…
            </Button>
          </div>
        </Card>
      </div>

      <ConfirmModal
        open={flushOpen}
        onClose={() => {
          if (flushing) return;
          setFlushOpen(false);
          setFlushConfirm('');
        }}
        onConfirm={onFlush}
        title="Flush Redis cache"
        variant="danger"
        loading={flushing}
        confirmText={flushConfirm === 'FLUSH' ? 'Flush cache' : 'Type FLUSH to confirm'}
      >
        <p className="text-[13px] text-fg-muted">
          Type <span className="font-mono font-semibold text-fg">FLUSH</span> to confirm. This will
          delete every key currently in Redis.
        </p>
        <input
          type="text"
          value={flushConfirm}
          onChange={(e) => setFlushConfirm(e.target.value)}
          placeholder="Type FLUSH"
          autoFocus
          className="mt-3 w-full rounded-md border border-border bg-bg-sunken px-3 py-2 font-mono text-[13px] focus:border-accent focus:outline-none"
        />
      </ConfirmModal>
    </>
  );
}

// =====================================================================
// helpers
// =====================================================================

function Kpi({
  label,
  value,
  sub,
  tone = 'fg',
  barPct,
  sparkline,
}: {
  label: string;
  value: string;
  sub?: string;
  tone?: 'fg' | 'success' | 'warn' | 'muted';
  barPct?: number;
  sparkline?: number[];
}) {
  return (
    <Card className="p-4">
      <div className="text-[10.5px] uppercase tracking-wider text-fg-subtle">{label}</div>
      <div
        className={cn(
          'mt-1 font-mono text-[20px] font-bold tabular-nums',
          tone === 'success' && 'text-success',
          tone === 'warn' && 'text-warn',
          tone === 'muted' && 'text-fg-muted',
        )}
      >
        {value}
      </div>
      {sub && <div className="mt-1 text-[11px] text-fg-subtle">{sub}</div>}
      {typeof barPct === 'number' && (
        <div className="mt-2 h-[6px] overflow-hidden rounded-full bg-bg-sunken">
          <span
            className="block h-full bg-accent"
            style={{ width: `${Math.max(0, Math.min(100, barPct))}%` }}
          />
        </div>
      )}
      {sparkline && sparkline.length > 1 && <MiniSpark values={sparkline} />}
    </Card>
  );
}

function MiniSpark({ values }: { values: number[] }) {
  // Simple inline SVG sparkline — no extra dep, no animation, just a path.
  const max = Math.max(1, ...values);
  const min = Math.min(0, ...values);
  const range = Math.max(0.001, max - min);
  const w = 120;
  const h = 24;
  const step = values.length > 1 ? w / (values.length - 1) : w;
  const d = values
    .map((v, i) => {
      const x = i * step;
      const y = h - ((v - min) / range) * h;
      return `${i === 0 ? 'M' : 'L'}${x.toFixed(1)},${y.toFixed(1)}`;
    })
    .join(' ');
  return (
    <svg width={w} height={h} className="mt-2 block" viewBox={`0 0 ${w} ${h}`}>
      <path d={d} fill="none" stroke="var(--accent)" strokeWidth="1.5" />
    </svg>
  );
}

function Detail({ k, v }: { k: string; v: string }) {
  return (
    <div className="flex items-baseline justify-between gap-2 border-b border-border py-[6px] last:border-b-0">
      <span className="text-fg-subtle">{k}</span>
      <span className="font-mono tabular-nums text-fg">{v}</span>
    </div>
  );
}

function UnreachableEmpty({ source }: { source: string }) {
  return (
    <div className="bg-bg-deep p-10 text-center">
      <div className="inline-flex items-center gap-2 rounded-full bg-warn/10 px-3 py-1 text-[12px] text-warn">
        <Dot color="var(--warn)" /> {source} unreachable
      </div>
      <div className="mt-3 text-[13px] text-fg">Source unreachable</div>
      <div className="mt-1 text-[12px] text-fg-subtle">
        The backend returned 503 for this tab. Check that {source} is up and that the panel can
        reach it. Falls back automatically once the source returns.
      </div>
    </div>
  );
}

function fmtTime(iso?: string): string {
  if (!iso) return '—';
  try {
    const d = new Date(iso);
    return (
      d.toLocaleTimeString('en-GB', { hour12: false }) +
      '.' +
      d.getMilliseconds().toString().padStart(3, '0').slice(0, 3)
    );
  } catch {
    return iso;
  }
}

function formatBytes(bytes: number): string {
  if (bytes < 0) return '—';
  if (bytes < 1024) return bytes + ' B';
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB';
  if (bytes < 1024 * 1024 * 1024) return (bytes / (1024 * 1024)).toFixed(1) + ' MB';
  return (bytes / (1024 * 1024 * 1024)).toFixed(2) + ' GB';
}

function formatUptime(sec: number): string {
  if (sec < 0) return '—';
  const d = Math.floor(sec / 86400);
  const h = Math.floor((sec % 86400) / 3600);
  const m = Math.floor((sec % 3600) / 60);
  if (d > 0) return `${d}d ${h}h up`;
  if (h > 0) return `${h}h ${m}m up`;
  return `${m}m up`;
}
