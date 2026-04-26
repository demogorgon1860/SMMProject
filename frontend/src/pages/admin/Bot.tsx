import { useEffect, useState } from 'react';
import {
  Badge,
  Button,
  Card,
  Dot,
  Icon,
  PageHeader,
  Section,
  StatusBadge,
  useToast,
} from '../../components/ui';
import { adminAPI } from '../../services/api';
import { fmtInt, fmtRel } from '../../lib/utils';
import { cn } from '../../lib/utils';

interface Bot {
  id: string;
  url: string;
  status: 'up' | 'degraded' | 'down';
  queueDepth: number;
  inProgress: number;
  workers: number;
  lastHeartbeat: string;
}

interface ProfileGroup {
  group: string;
  available: number;
  inUse: number;
  error: number;
}

export function AdminBotPage() {
  const toast = useToast();
  const [bots, setBots] = useState<Bot[]>([
    { id: 'bot-01', url: 'http://45.142.211.90:8080', status: 'up', queueDepth: 248, inProgress: 18, workers: 6, lastHeartbeat: new Date().toISOString() },
    { id: 'bot-02', url: 'http://45.142.211.91:8080', status: 'degraded', queueDepth: 1042, inProgress: 9, workers: 4, lastHeartbeat: new Date(Date.now() - 3 * 60_000).toISOString() },
  ]);
  const [groups, setGroups] = useState<ProfileGroup[]>([
    { group: 'Success', available: 142, inUse: 18, error: 4 },
    { group: 'Start_count', available: 24, inUse: 2, error: 0 },
  ]);
  const [tail, setTail] = useState([
    { t: '14:31:52', event: 'order.completed', id: 'igb_j8x2k1', ext: '1028523', completed: 1000, status: 'ok' as const },
    { t: '14:31:44', event: 'order.progress', id: 'igb_p2a11q', ext: '1028511', completed: 820, status: 'ok' as const },
    { t: '14:31:08', event: 'order.partial', id: 'igb_x0lk2a', ext: '1028498', completed: 640, status: 'ok' as const },
    { t: '14:30:41', event: 'order.error', id: 'igb_n3mf0p', ext: '1028492', completed: 62, status: 'warn' as const, msg: 'profile pool low' },
    { t: '14:28:44', event: 'order.pending_cancel', id: 'igb_fbbm2e', ext: '1028471', completed: 340, status: 'warn' as const, msg: 'circuit breaker: 10 consecutive errors' },
  ]);

  useEffect(() => {
    adminAPI
      .bots()
      .then((data: unknown) => {
        if (Array.isArray(data) && data.length > 0) setBots(data as Bot[]);
      })
      .catch(() => {});
    adminAPI
      .botProfileGroups()
      .then((data: unknown) => {
        if (Array.isArray(data) && data.length > 0) setGroups(data as ProfileGroup[]);
      })
      .catch(() => {});
  }, []);

  const scale = async (id: string, delta: number) => {
    try {
      await adminAPI.scaleBot(id, delta);
      toast(`${id}: ${delta > 0 ? '+' : ''}${delta} worker`, 'success');
      setBots((prev) => prev.map((b) => (b.id === id ? { ...b, workers: Math.max(0, b.workers + delta) } : b)));
    } catch {
      toast('Scale endpoint not live yet (Phase 3).', 'info');
    }
  };

  return (
    <>
      <PageHeader
        title="Instagram bot"
        subtitle={
          <span>
            <span className="font-mono text-fg">{bots.length}</span> instances ·{' '}
            <span className="font-mono text-fg">{bots.reduce((s, b) => s + b.workers, 0)}</span> total workers
          </span>
        }
        actions={
          <>
            <Button variant="ghost" size="sm" icon="pause">
              Stop all
            </Button>
            <Button variant="danger" size="sm" icon="trash">
              Clear queue
            </Button>
            <Button variant="primary" size="sm" icon="play">
              Start all
            </Button>
          </>
        }
      />

      <div className="space-y-6 p-6">
        {/* Bot instances */}
        <div className="grid grid-cols-1 gap-4 lg:grid-cols-2">
          {bots.map((b) => (
            <Card key={b.id} className="p-5">
              <div className="flex items-center justify-between">
                <div className="flex items-center gap-2">
                  <Icon name="bot" size={16} className="text-fg-muted" />
                  <span className="font-mono text-[14px] font-semibold">{b.id}</span>
                  <StatusBadge status={b.status} />
                </div>
                <div className="flex gap-1">
                  <Button variant="ghost" size="sm" icon="refresh">
                    Reload
                  </Button>
                  <Button variant="secondary" size="sm">
                    Logs
                  </Button>
                </div>
              </div>
              <div className="mt-1 font-mono text-[11.5px] text-fg-subtle">{b.url}</div>
              <div className="mt-4 grid grid-cols-4 gap-2">
                <Stat
                  label="Queue depth"
                  value={fmtInt(b.queueDepth)}
                  warn={b.queueDepth > 500}
                />
                <Stat label="In progress" value={fmtInt(b.inProgress)} />
                <Stat label="Workers" value={fmtInt(b.workers)} />
                <Stat label="Heartbeat" value={fmtRel(b.lastHeartbeat)} mono={false} />
              </div>
              <div className="mt-3 flex justify-between gap-2 border-t border-border pt-3">
                <Button variant="secondary" size="sm" icon="plus" onClick={() => scale(b.id, +1)}>
                  Scale up
                </Button>
                <Button variant="secondary" size="sm" onClick={() => scale(b.id, -1)}>
                  Scale down
                </Button>
                <Button variant="ghost" size="sm">
                  Drain
                </Button>
              </div>
            </Card>
          ))}
        </div>

        {/* AdsPower profile groups */}
        <Section title="AdsPower profile groups" subtitle="Pools used by the Go bot">
          <table className="tbl">
            <thead>
              <tr>
                <th>Group</th>
                <th className="text-right">Available</th>
                <th className="text-right">In use</th>
                <th className="text-right">Error</th>
                <th>Utilization</th>
                <th />
              </tr>
            </thead>
            <tbody>
              {groups.map((g) => {
                const total = g.available + g.inUse + g.error;
                const utilPct = total > 0 ? (g.inUse / total) * 100 : 0;
                const utilWarn = utilPct > 80;
                return (
                  <tr key={g.group}>
                    <td className="font-mono text-[13px] font-semibold">{g.group}</td>
                    <td className="text-right font-mono">{fmtInt(g.available)}</td>
                    <td className="text-right font-mono">{fmtInt(g.inUse)}</td>
                    <td className={cn('text-right font-mono', g.error > 0 && 'text-danger')}>{fmtInt(g.error)}</td>
                    <td>
                      <div className="flex items-center gap-2">
                        <div className="h-[6px] w-[240px] overflow-hidden rounded-full bg-bg-sunken">
                          <span
                            className="block h-full"
                            style={{
                              width: `${utilPct.toFixed(0)}%`,
                              background: utilWarn ? 'var(--warn)' : 'var(--accent)',
                            }}
                          />
                        </div>
                        <span className="font-mono text-[11.5px]">{utilPct.toFixed(0)}%</span>
                      </div>
                    </td>
                    <td>
                      <Button variant="ghost" size="sm">
                        Recycle errors
                      </Button>
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </Section>

        {/* Webhook live tail */}
        <Section
          title="Recent bot webhooks"
          subtitle={
            <span className="inline-flex items-center gap-2">
              <Dot color="var(--success)" animate />
              Live tail · updates every few seconds
            </span>
          }
          pad={false}
        >
          <div className="bg-bg-deep p-4 font-mono text-[12px] text-white/85">
            {tail.map((t, i) => (
              <div key={i} className="flex flex-wrap gap-3 py-[2px]">
                <span className="text-white/40">{t.t}</span>
                <span className={cn('font-semibold', t.status === 'warn' ? 'text-[#fbbf24]' : 'text-[#a7f3d0]')}>
                  {t.event}
                </span>
                <span className="text-white/55">{t.id}</span>
                <span className="text-white/55">ext={t.ext}</span>
                <span className="text-white">completed={t.completed}</span>
                {t.msg && <span className="text-[#f87171]">· {t.msg}</span>}
              </div>
            ))}
          </div>
        </Section>
      </div>
    </>
  );
}

function Stat({ label, value, warn, mono = true }: { label: string; value: React.ReactNode; warn?: boolean; mono?: boolean }) {
  return (
    <div className="rounded-md border border-border bg-bg-sunken p-3">
      <div className="text-[10.5px] uppercase tracking-wider text-fg-subtle">{label}</div>
      <div className={cn('mt-1 text-[14px] font-semibold', mono && 'font-mono tabular-nums', warn && 'text-warn')}>{value}</div>
    </div>
  );
}

void Badge;
