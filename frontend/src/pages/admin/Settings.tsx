import { useState } from 'react';
import {
  Badge,
  Button,
  Card,
  ConfirmModal,
  Dot,
  Field,
  Icon,
  Input,
  PageHeader,
  Section,
  Switch,
  Tabs,
  TimeCell,
  useToast,
} from '../../components/ui';

export function AdminSettingsPage() {
  const [tab, setTab] = useState<'general' | 'admins' | 'integrations'>('general');
  return (
    <>
      <PageHeader title="Settings" />
      <div className="space-y-4 p-6">
        <Tabs
          value={tab}
          onChange={setTab}
          tabs={[
            { value: 'general', label: 'General' },
            { value: 'admins', label: 'Admins', count: 4 },
            { value: 'integrations', label: 'Integrations' },
          ]}
        />
        {tab === 'general' && <GeneralTab />}
        {tab === 'admins' && <AdminsTab />}
        {tab === 'integrations' && <IntegrationsTab />}
      </div>
    </>
  );
}

function GeneralTab() {
  const toast = useToast();
  const [maintenance, setMaintenance] = useState(false);
  const [maintenanceConfirm, setMaintenanceConfirm] = useState(false);
  const [confirmString, setConfirmString] = useState('');
  return (
    <div className="grid grid-cols-1 gap-4 lg:grid-cols-2">
      <Section title="Platform fees">
        <Field label="Min order charge (USD)">
          <Input block type="number" step="0.01" defaultValue={0.05} />
        </Field>
        <Field label="Markup on reseller pricing (%)">
          <Input block type="number" step="0.5" defaultValue={15} />
        </Field>
        <Field label="Cryptomus fee passthrough (%)">
          <Input block type="number" step="0.1" defaultValue={1.0} />
        </Field>
        <Button variant="primary" size="md" onClick={() => toast('Saved.', 'success')}>
          Save fees
        </Button>
      </Section>
      <Section title="Rate limits">
        <Field label="Orders / minute / user">
          <Input block type="number" defaultValue={20} />
        </Field>
        <Field label="API requests / minute">
          <Input block type="number" defaultValue={60} />
        </Field>
        <Field label="Max concurrent orders / user">
          <Input block type="number" defaultValue={10} />
        </Field>
        <Button variant="primary" size="md" onClick={() => toast('Saved.', 'success')}>
          Save limits
        </Button>
      </Section>
      <Section title="Maintenance mode">
        <div className="flex items-center justify-between">
          <div>
            <div className="text-[13px] font-medium">Maintenance mode</div>
            <p className="mt-1 text-[12.5px] text-fg-muted">
              {maintenance ? (
                <span className="text-warn">Enabled — new orders are rejected globally.</span>
              ) : (
                'Disabled — system accepting orders.'
              )}
            </p>
          </div>
          <Switch
            checked={maintenance}
            onChange={(v) => {
              if (v) setMaintenanceConfirm(true);
              else setMaintenance(false);
            }}
          />
        </div>
        {maintenance && (
          <div className="mt-3 rounded-md border border-danger/30 bg-danger-soft p-3 text-[12.5px] text-danger">
            <Icon name="warning" size={12} className="mr-1 inline align-[-1px]" />
            Bot continues processing existing orders. New /v1/orders POSTs return 503.
          </div>
        )}
      </Section>
      <Section title="Feature flags">
        {[
          ['Bot-02 round-robin', 'Distribute new orders across both bot instances', true],
          ['GPT-4o comment service', 'Use OpenAI for context-aware Instagram comments', true],
          ['Automatic refunds', 'Auto-refund on partial / failed orders', true],
          ['Reseller portal (beta)', 'New /reseller layout for high-volume accounts', false],
        ].map(([title, desc, def]) => (
          <div key={title as string} className="flex items-start justify-between gap-3 border-b border-border py-3 last:border-b-0">
            <div>
              <div className="text-[13px] font-medium">{title as string}</div>
              <div className="mt-0.5 text-[11.5px] text-fg-muted">{desc as string}</div>
            </div>
            <Switch checked={def as boolean} onChange={() => {}} />
          </div>
        ))}
      </Section>

      <ConfirmModal
        open={maintenanceConfirm}
        onClose={() => {
          setMaintenanceConfirm(false);
          setConfirmString('');
        }}
        onConfirm={() => {
          if (confirmString !== 'MAINTENANCE') {
            toast('Type MAINTENANCE to confirm.', 'error');
            return;
          }
          setMaintenance(true);
          setMaintenanceConfirm(false);
          setConfirmString('');
          toast('Maintenance mode enabled.', 'success');
        }}
        title="Enable maintenance mode?"
        confirmText="Enable maintenance"
        variant="danger"
      >
        <p className="mb-3 text-[13px] text-fg-muted">
          New orders will be rejected globally. Type <code className="font-mono">MAINTENANCE</code> to confirm.
        </p>
        <Input block value={confirmString} onChange={(e) => setConfirmString(e.target.value)} placeholder="MAINTENANCE" />
      </ConfirmModal>
    </div>
  );
}

function AdminsTab() {
  const admins = [
    { id: 1, email: 'root@smm.local', role: 'superadmin', actions7d: 84, lastActive: new Date(Date.now() - 60_000).toISOString() },
    { id: 2, email: 'elena.p@smm.local', role: 'operator', actions7d: 312, lastActive: new Date(Date.now() - 20 * 60_000).toISOString() },
    { id: 3, email: 'marcus.a@smm.local', role: 'operator', actions7d: 121, lastActive: new Date(Date.now() - 3 * 3600_000).toISOString() },
    { id: 4, email: 'oksana.k@smm.local', role: 'readonly', actions7d: 0, lastActive: new Date(Date.now() - 22 * 3600_000).toISOString() },
  ];
  const audit = [
    { t: new Date().toISOString(), actor: 'elena.p', action: 'order.force_cancel', target: 'order:1028471', reason: 'Per ticket #8821' },
    { t: new Date(Date.now() - 600_000).toISOString(), actor: 'marcus.a', action: 'balance.manual_adjust', target: 'user:10299', reason: 'Webhook miss compensation' },
    { t: new Date(Date.now() - 3600_000).toISOString(), actor: 'elena.p', action: 'service.deactivate', target: 'service:322', reason: 'Quality below threshold' },
    { t: new Date(Date.now() - 4 * 3600_000).toISOString(), actor: 'root', action: 'admin.invite', target: 'admin@smm.local', reason: 'New ops hire' },
  ];

  return (
    <div className="space-y-4">
      <Section
        title="Admin users"
        action={
          <Button variant="primary" size="sm" icon="plus">
            Invite admin
          </Button>
        }
        pad={false}
      >
        <table className="tbl">
          <thead>
            <tr>
              <th>Email</th>
              <th>Role</th>
              <th className="text-right">Actions / 7d</th>
              <th>Last active</th>
              <th />
            </tr>
          </thead>
          <tbody>
            {admins.map((a) => (
              <tr key={a.id}>
                <td>
                  <div className="flex items-center gap-2">
                    <span className="flex h-6 w-6 items-center justify-center rounded-full bg-gradient-to-br from-accent to-violet text-[10px] font-semibold text-white">
                      {a.email[0].toUpperCase()}
                    </span>
                    <span className="text-[13px]">{a.email}</span>
                  </div>
                </td>
                <td>
                  <Badge tone={a.role === 'superadmin' ? 'danger' : a.role === 'operator' ? 'accent' : 'muted'} size="sm">
                    <span className="font-mono">{a.role}</span>
                  </Badge>
                </td>
                <td className="text-right font-mono">{a.actions7d}</td>
                <td>
                  <TimeCell iso={a.lastActive} />
                </td>
                <td>
                  <div className="flex justify-end gap-1">
                    <Button variant="ghost" size="sm">
                      Edit
                    </Button>
                    {a.role !== 'superadmin' && (
                      <Button variant="ghost" size="sm" className="text-danger">
                        Remove
                      </Button>
                    )}
                  </div>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </Section>

      <Section title="Admin action audit log" pad={false}>
        <table className="tbl">
          <thead>
            <tr>
              <th>When</th>
              <th>Admin</th>
              <th>Action</th>
              <th>Target</th>
              <th>Reason</th>
            </tr>
          </thead>
          <tbody>
            {audit.map((a, i) => (
              <tr key={i}>
                <td>
                  <TimeCell iso={a.t} />
                </td>
                <td>
                  <span className="rounded bg-accent-soft px-1.5 py-[1px] font-mono text-[11.5px] text-accent-fg">
                    @{a.actor}
                  </span>
                </td>
                <td className="font-mono text-[12px]">{a.action}</td>
                <td className="font-mono text-[12px] text-fg-muted">{a.target}</td>
                <td className="text-[12.5px]">{a.reason}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </Section>
    </div>
  );
}

function IntegrationsTab() {
  const [showCryptomus, setShowCryptomus] = useState(false);
  const [showTelegram, setShowTelegram] = useState(false);
  return (
    <div className="grid grid-cols-1 gap-4 lg:grid-cols-2">
      <Section title="Cryptomus">
        <Field label="API key">
          <Input
            block
            value={showCryptomus ? 'cm_live_2x9k8h7g6f5d4s3a2p1o0i9u8y7t6r5e4w3q' : '•'.repeat(40)}
            readOnly
            iconRight={
              <button onClick={() => setShowCryptomus((v) => !v)} className="text-fg-subtle hover:text-fg" type="button">
                <Icon name={showCryptomus ? 'eye-off' : 'eye'} size={14} />
              </button>
            }
          />
        </Field>
        <Button variant="secondary" size="md">
          Edit
        </Button>
      </Section>
      <Section title="Telegram">
        <Field label="Bot token">
          <Input
            block
            value={showTelegram ? '7081234521:AAGz...rZ4' : '•'.repeat(40)}
            readOnly
            iconRight={
              <button onClick={() => setShowTelegram((v) => !v)} className="text-fg-subtle hover:text-fg" type="button">
                <Icon name={showTelegram ? 'eye-off' : 'eye'} size={14} />
              </button>
            }
          />
        </Field>
        <div className="flex items-center gap-2 text-[12.5px]">
          <Dot color="var(--success)" animate />
          <span className="text-fg-muted">
            Connected as <span className="font-mono text-fg">@smm_ops_bot</span>
          </span>
        </div>
        <div className="mt-3">
          <Button variant="secondary" size="md">
            Edit
          </Button>
        </div>
      </Section>

      <Section
        title="Instagram bot URLs"
        subtitle="The Go bot fleet — referenced in /admin/bot too"
        action={
          <Button variant="secondary" size="sm" icon="plus">
            Add bot instance
          </Button>
        }
        pad={false}
        className="lg:col-span-2"
      >
        <table className="tbl">
          <thead>
            <tr>
              <th>Instance</th>
              <th>URL</th>
              <th>Status</th>
              <th>Since</th>
              <th />
            </tr>
          </thead>
          <tbody>
            {[
              { id: 'bot-01', url: 'http://45.142.211.90:8080', status: 'up', since: '2026-04-01 11:42 UTC' },
              { id: 'bot-02', url: 'http://45.142.211.91:8080', status: 'degraded', since: '2026-04-01 11:42 UTC' },
            ].map((b) => (
              <tr key={b.id}>
                <td className="font-mono text-[12px] font-semibold">{b.id}</td>
                <td className="font-mono text-[11.5px]">{b.url}</td>
                <td>
                  <Badge tone={b.status === 'up' ? 'success' : 'warn'} size="sm" dot>
                    {b.status}
                  </Badge>
                </td>
                <td className="font-mono text-[11.5px] text-fg-muted">{b.since}</td>
                <td>
                  <Button variant="ghost" size="sm">
                    Edit
                  </Button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </Section>
    </div>
  );
}
