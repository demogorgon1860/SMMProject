import { useState } from 'react';
import {
  Button,
  Card,
  Dot,
  PageHeader,
  Section,
  Select,
  Tabs,
} from '../../components/ui';
import { cn } from '../../lib/utils';

export function AdminSystemPage() {
  const [tab, setTab] = useState<'logs' | 'errors' | 'queues' | 'cache'>('logs');
  return (
    <>
      <PageHeader title="System monitoring" subtitle="Application internals · Phase 3 backend wires SSE / Redis stats" />
      <div className="space-y-4 p-6">
        <Tabs
          value={tab}
          onChange={setTab}
          tabs={[
            { value: 'logs', label: 'Logs' },
            { value: 'errors', label: 'Errors', count: 5 },
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

function LogsTab() {
  const [level, setLevel] = useState('all');
  const lines = [
    { t: '14:32:08.442', level: 'INFO', src: 'OrderService', msg: 'Order 1028523 status transition: in_progress → completed' },
    { t: '14:32:08.391', level: 'DEBUG', src: 'RedisClient', msg: 'SET telegram:profit:2026-04-26 total=2134.50' },
    { t: '14:32:07.912', level: 'INFO', src: 'InstagramResultConsumer', msg: 'Received result webhook for igb_j8x2k1 (completed=1000)' },
    { t: '14:32:04.218', level: 'WARN', src: 'InstagramBotClient', msg: 'Bot #2 latency 312ms — circuit half-open' },
    { t: '14:32:01.004', level: 'INFO', src: 'BalanceService', msg: 'Charged $4.40 from user 10011 for order 1028523' },
    { t: '14:31:31.208', level: 'ERROR', src: 'InstagramService', msg: 'Profile pool exhausted for group Success — pausing order 1028471' },
    { t: '14:30:42.002', level: 'WARN', src: 'CancelDecisionService', msg: 'Pending cancel decision for 1028471 stored in Redis (ttl=4h)' },
    { t: '14:29:42.001', level: 'INFO', src: 'CryptomusService', msg: 'Payment cmp_9a8bc2d0 confirmed (BTC, $200.00)' },
  ];
  const filtered = level === 'all' ? lines : lines.filter((l) => l.level === level);
  return (
    <Section
      title="Application logs"
      subtitle={
        <span className="inline-flex items-center gap-2">
          <Dot color="var(--success)" animate /> Live tail
        </span>
      }
      action={
        <Select
          selectSize="sm"
          value={level}
          onChange={(e) => setLevel(e.target.value)}
          options={[
            { value: 'all', label: 'All levels' },
            { value: 'INFO', label: 'INFO' },
            { value: 'WARN', label: 'WARN' },
            { value: 'ERROR', label: 'ERROR' },
            { value: 'DEBUG', label: 'DEBUG' },
          ]}
        />
      }
      pad={false}
    >
      <div className="max-h-[520px] overflow-auto bg-bg-deep p-4 font-mono text-[12px] text-white/85">
        {filtered.map((l, i) => (
          <div key={i} className="flex gap-3 py-[2px]">
            <span className="text-white/40">{l.t}</span>
            <span
              className={cn(
                'min-w-[42px] font-semibold',
                l.level === 'ERROR' && 'text-[#f87171]',
                l.level === 'WARN' && 'text-[#fbbf24]',
                l.level === 'INFO' && 'text-[#a7f3d0]',
                l.level === 'DEBUG' && 'text-[#a1a1aa]',
              )}
            >
              {l.level}
            </span>
            <span className="min-w-[180px] text-white/55">{l.src}</span>
            <span className="flex-1 text-white/85">{l.msg}</span>
          </div>
        ))}
      </div>
    </Section>
  );
}

function ErrorsTab() {
  const errors = [
    { hash: 'a3f1e', count: 184, lastSeen: '14:31:31', title: 'RuntimeException: Profile pool exhausted (group=Success)', location: 'InstagramService.executeOrder:412' },
    { hash: '7b9cd', count: 42, lastSeen: '14:28:12', title: 'SocketTimeoutException: connect timed out', location: 'InstagramBotClient.createOrder:118' },
    { hash: 'd20a1', count: 18, lastSeen: '13:02:04', title: 'DataIntegrityViolationException: duplicate key', location: 'OrderRepository.save:71' },
    { hash: '0ef42', count: 9, lastSeen: '11:44:51', title: 'HttpClientErrorException: 429 Too Many Requests (cryptomus.com)', location: 'CryptomusService.createPayment:88' },
    { hash: 'c1c02', count: 3, lastSeen: '08:11:18', title: 'NullPointerException: order.service was null', location: 'OrderService.createOrder:142' },
  ];
  return (
    <Section title="Errors grouped by stack trace · Last 24h" pad={false}>
      <table className="tbl">
        <thead>
          <tr>
            <th>Hash</th>
            <th className="text-right">Count</th>
            <th>Title</th>
            <th>Location</th>
            <th>Last seen</th>
            <th />
          </tr>
        </thead>
        <tbody>
          {errors.map((e) => (
            <tr key={e.hash}>
              <td>
                <span className="font-mono text-[11px] rounded bg-bg-sunken px-2 py-[2px]">{e.hash}</span>
              </td>
              <td
                className={cn(
                  'text-right font-mono',
                  e.count > 100 ? 'text-danger' : e.count > 10 ? 'text-warn' : 'text-fg',
                )}
              >
                {e.count}
              </td>
              <td className="text-[13px]">{e.title}</td>
              <td className="font-mono text-[11.5px] text-fg-muted">{e.location}</td>
              <td className="font-mono text-[11.5px] text-fg-muted">{e.lastSeen}</td>
              <td>
                <Button variant="ghost" size="sm" iconRight="chevron-right">
                  Trace
                </Button>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </Section>
  );
}

function QueuesTab() {
  const queues = [
    { name: 'instagram.new.queue', messages: 248, consumers: 3, rate: 12.4 },
    { name: 'instagram.result.queue', messages: 3, consumers: 2, rate: 8.1 },
    { name: 'telegram.notify.queue', messages: 0, consumers: 1, rate: 0.4 },
    { name: 'balance.charge.queue', messages: 1, consumers: 2, rate: 2.1 },
    { name: 'instagram.result.dlq', messages: 0, consumers: 0, rate: 0 },
    { name: 'cryptomus.webhook.queue', messages: 0, consumers: 1, rate: 0.2 },
  ];
  return (
    <Section title="RabbitMQ queues" pad={false}>
      <table className="tbl">
        <thead>
          <tr>
            <th>Queue</th>
            <th className="text-right">Messages</th>
            <th className="text-right">Consumers</th>
            <th className="text-right">Rate (msg/s)</th>
            <th>State</th>
            <th />
          </tr>
        </thead>
        <tbody>
          {queues.map((q) => {
            const state = q.messages > 200 ? 'backlog' : q.consumers === 0 && q.messages === 0 ? 'idle' : 'healthy';
            return (
              <tr key={q.name}>
                <td className="font-mono text-[12px]">{q.name}</td>
                <td className={cn('text-right font-mono', q.messages > 100 && 'text-warn')}>{q.messages}</td>
                <td className="text-right font-mono">{q.consumers}</td>
                <td className="text-right font-mono">{q.rate.toFixed(1)}</td>
                <td>
                  <span className="inline-flex items-center gap-1.5 text-[12px]">
                    <Dot color={state === 'backlog' ? 'var(--warn)' : state === 'idle' ? 'var(--fg-dim)' : 'var(--success)'} size={6} />
                    {state}
                  </span>
                </td>
                <td>
                  <Button variant="ghost" size="sm">
                    Purge
                  </Button>
                </td>
              </tr>
            );
          })}
        </tbody>
      </table>
    </Section>
  );
}

function CacheTab() {
  const keys = [
    { prefix: 'telegram:cancel_pending:*', count: 6, memoryMB: 0.4, ttl: '3h 42m' },
    { prefix: 'telegram:profit:*', count: 8, memoryMB: 0.1, ttl: '7d 12h' },
    { prefix: 'session:*', count: 134, memoryMB: 12.8, ttl: '24h' },
    { prefix: 'rate_limit:*', count: 4212, memoryMB: 48.1, ttl: '1m' },
    { prefix: 'cache:services:*', count: 1, memoryMB: 0.8, ttl: '10m' },
    { prefix: 'order:lock:*', count: 12, memoryMB: 0.2, ttl: '30s' },
  ];
  return (
    <div className="grid grid-cols-1 gap-4 lg:grid-cols-[1fr_280px]">
      <Section title="Redis keys by prefix" pad={false}>
        <table className="tbl">
          <thead>
            <tr>
              <th>Prefix</th>
              <th className="text-right">Count</th>
              <th className="text-right">Memory MB</th>
              <th>Median TTL</th>
            </tr>
          </thead>
          <tbody>
            {keys.map((k) => (
              <tr key={k.prefix}>
                <td className="font-mono text-[12px]">{k.prefix}</td>
                <td className="text-right font-mono">{k.count}</td>
                <td className="text-right font-mono">{k.memoryMB.toFixed(1)}</td>
                <td className="font-mono text-[11.5px] text-fg-muted">{k.ttl}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </Section>
      <div className="space-y-3">
        <Card className="p-5">
          <div className="text-[13px] font-semibold">Redis memory</div>
          <div className="mt-2 font-mono text-[20px] font-bold">412 MB <span className="text-fg-subtle">/ 1.5 GB</span></div>
          <div className="mt-2 h-[6px] overflow-hidden rounded-full bg-bg-sunken">
            <span className="block h-full bg-accent" style={{ width: '27%' }} />
          </div>
          <div className="mt-1 font-mono text-[11px] text-fg-subtle">27% used</div>
        </Card>
        <Card className="p-5">
          <div className="text-[13px] font-semibold">Hit rate · last 1h</div>
          <div className="mt-2 font-mono text-[20px] font-bold text-success">98.4%</div>
          <div className="mt-1 font-mono text-[11px] text-fg-subtle">214,182 hits / 3,514 misses</div>
        </Card>
      </div>
    </div>
  );
}
