import { useEffect, useMemo, useState } from 'react';
import {
  Button,
  ConfirmModal,
  Field,
  Icon,
  Input,
  PageHeader,
  Section,
  Switch,
  useToast,
} from '../../components/ui';
import { adminAPI, type AppSetting } from '../../services/api';

// =====================================================================
// /admin/settings — fees, rate limits, maintenance mode.
//
// Backed by /api/v2/admin/settings (AppSetting key/value store). Every
// PUT writes an operator_logs audit entry server-side.
//
// The earlier Admins / Integrations tabs were mocks (no admin user CRUD,
// no API-key edit, fake bot-02 instance). They were removed rather than
// shipped half-real — a page that lies is worse than no page. User
// management lives at /admin/users; integration credentials live in the
// server's environment file (.env.docker on prod).
// =====================================================================

export function AdminSettingsPage() {
  return (
    <>
      <PageHeader title="Settings" />
      <div className="space-y-4 p-6">
        <GeneralTab />
      </div>
    </>
  );
}

// Server-side keys — must match constants in AppSettingsService.java.
const KEY_MIN_ORDER_CHARGE = 'platform.fee.min_order_charge';
const KEY_MARKUP_PERCENT = 'platform.fee.markup_percent';
const KEY_CRYPTOMUS_PASSTHROUGH = 'platform.fee.cryptomus_passthrough_pct';
const KEY_ORDERS_PER_MINUTE = 'rate.orders_per_minute_per_user';
const KEY_API_PER_MINUTE = 'rate.api_per_minute_per_user';
const KEY_MAX_CONCURRENT = 'rate.max_concurrent_orders_per_user';
const KEY_MAINTENANCE = 'maintenance.enabled';

const FEES_KEYS = [KEY_MIN_ORDER_CHARGE, KEY_MARKUP_PERCENT, KEY_CRYPTOMUS_PASSTHROUGH] as const;
const LIMITS_KEYS = [KEY_ORDERS_PER_MINUTE, KEY_API_PER_MINUTE, KEY_MAX_CONCURRENT] as const;

function GeneralTab() {
  const toast = useToast();
  const [settings, setSettings] = useState<Record<string, AppSetting>>({});
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  // Local edit buffer for the numeric input fields. We don't bind the inputs directly to
  // `settings` because that would round-trip on every keystroke; instead we sync from the
  // server snapshot on load + after each successful save.
  const [draft, setDraft] = useState<Record<string, string>>({});
  const [savingFees, setSavingFees] = useState(false);
  const [savingLimits, setSavingLimits] = useState(false);

  const [maintenanceConfirm, setMaintenanceConfirm] = useState(false);
  const [confirmString, setConfirmString] = useState('');

  const reload = async () => {
    setError(null);
    try {
      const list = await adminAPI.settingsList();
      const map: Record<string, AppSetting> = {};
      const d: Record<string, string> = {};
      for (const s of list) {
        map[s.key] = s;
        d[s.key] = s.value;
      }
      setSettings(map);
      setDraft(d);
    } catch (e) {
      const msg = e instanceof Error ? e.message : 'Failed to load settings';
      setError(msg);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    void reload();
  }, []);

  const maintenance = useMemo(
    () => (settings[KEY_MAINTENANCE]?.value ?? 'false').toLowerCase() === 'true',
    [settings],
  );

  const saveOne = async (key: string, value: string) => {
    const updated = await adminAPI.settingsUpdate(key, value);
    setSettings((prev) => ({ ...prev, [key]: updated }));
    setDraft((prev) => ({ ...prev, [key]: updated.value }));
  };

  const saveGroup = async (
    keys: readonly string[],
    setSaving: (v: boolean) => void,
    label: string,
  ) => {
    setSaving(true);
    try {
      // Save sequentially so an early failure surfaces a clear error instead of a partial
      // success message — settings drive money, the user needs to know what landed.
      for (const k of keys) {
        const next = draft[k];
        if (next == null) continue;
        if (next === settings[k]?.value) continue;
        await saveOne(k, next);
      }
      toast(`${label} saved.`, 'success');
    } catch (e) {
      const msg = extractErrorMessage(e, `Failed to save ${label.toLowerCase()}`);
      toast(msg, 'error');
    } finally {
      setSaving(false);
    }
  };

  const setMaintenanceFlag = async (next: boolean) => {
    try {
      await saveOne(KEY_MAINTENANCE, next ? 'true' : 'false');
      toast(next ? 'Maintenance mode enabled.' : 'Maintenance mode disabled.', 'success');
    } catch (e) {
      toast(extractErrorMessage(e, 'Failed to update maintenance mode'), 'error');
    }
  };

  if (loading) {
    return (
      <div className="flex items-center gap-2 px-4 py-12 text-[13px] text-fg-muted">
        <div className="spin h-4 w-4 rounded-full border-2 border-border border-t-accent" />
        Loading settings…
      </div>
    );
  }

  if (error) {
    return (
      <div className="rounded-md border border-danger/30 bg-danger-soft p-4 text-[13px] text-danger">
        <Icon name="warning" size={14} className="mr-2 inline align-[-2px]" />
        {error}
        <Button variant="ghost" size="sm" className="ml-3" onClick={() => void reload()}>
          Retry
        </Button>
      </div>
    );
  }

  const minCharge = settings[KEY_MIN_ORDER_CHARGE];
  const markup = settings[KEY_MARKUP_PERCENT];
  const cryptomus = settings[KEY_CRYPTOMUS_PASSTHROUGH];
  const ordersPerMin = settings[KEY_ORDERS_PER_MINUTE];
  const apiPerMin = settings[KEY_API_PER_MINUTE];
  const maxConcurrent = settings[KEY_MAX_CONCURRENT];

  return (
    <div className="grid grid-cols-1 gap-4 lg:grid-cols-2">
      <Section title="Platform fees" subtitle="Drives min-order rejection, /v1/services rate display, and credited deposit amount">
        <Field label="Min order charge (USD)" hint={minCharge?.description}>
          <Input
            block
            type="number"
            step="0.01"
            min={0}
            value={draft[KEY_MIN_ORDER_CHARGE] ?? ''}
            onChange={(e) => setDraft((d) => ({ ...d, [KEY_MIN_ORDER_CHARGE]: e.target.value }))}
          />
        </Field>
        <Field label="Markup on service rate (%)" hint={markup?.description}>
          <Input
            block
            type="number"
            step="0.5"
            min={0}
            value={draft[KEY_MARKUP_PERCENT] ?? ''}
            onChange={(e) => setDraft((d) => ({ ...d, [KEY_MARKUP_PERCENT]: e.target.value }))}
          />
        </Field>
        <Field label="Cryptomus fee passthrough (%)" hint={cryptomus?.description}>
          <Input
            block
            type="number"
            step="0.1"
            min={0}
            value={draft[KEY_CRYPTOMUS_PASSTHROUGH] ?? ''}
            onChange={(e) =>
              setDraft((d) => ({ ...d, [KEY_CRYPTOMUS_PASSTHROUGH]: e.target.value }))
            }
          />
        </Field>
        <Button
          variant="primary"
          size="md"
          loading={savingFees}
          onClick={() => void saveGroup(FEES_KEYS, setSavingFees, 'Fees')}
        >
          Save fees
        </Button>
      </Section>

      <Section title="Rate limits" subtitle="Bucket4j limits enforced per user across all panel API requests">
        <Field label="Orders / minute / user" hint={ordersPerMin?.description}>
          <Input
            block
            type="number"
            min={1}
            value={draft[KEY_ORDERS_PER_MINUTE] ?? ''}
            onChange={(e) => setDraft((d) => ({ ...d, [KEY_ORDERS_PER_MINUTE]: e.target.value }))}
          />
        </Field>
        <Field label="API requests / minute / user" hint={apiPerMin?.description}>
          <Input
            block
            type="number"
            min={1}
            value={draft[KEY_API_PER_MINUTE] ?? ''}
            onChange={(e) => setDraft((d) => ({ ...d, [KEY_API_PER_MINUTE]: e.target.value }))}
          />
        </Field>
        <Field label="Max concurrent orders / user (0 = unlimited)" hint={maxConcurrent?.description}>
          <Input
            block
            type="number"
            min={0}
            value={draft[KEY_MAX_CONCURRENT] ?? ''}
            onChange={(e) => setDraft((d) => ({ ...d, [KEY_MAX_CONCURRENT]: e.target.value }))}
          />
        </Field>
        <Button
          variant="primary"
          size="md"
          loading={savingLimits}
          onClick={() => void saveGroup(LIMITS_KEYS, setSavingLimits, 'Limits')}
        >
          Save limits
        </Button>
      </Section>

      <Section
        title="Maintenance mode"
        subtitle="When on, /api/v1/* returns 503 (admin, auth, and webhooks still work)"
        className="lg:col-span-2"
      >
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
              else void setMaintenanceFlag(false);
            }}
          />
        </div>
        {maintenance && (
          <div className="mt-3 rounded-md border border-danger/30 bg-danger-soft p-3 text-[12.5px] text-danger">
            <Icon name="warning" size={12} className="mr-1 inline align-[-1px]" />
            Bot continues processing existing orders. New /api/v1/* requests return 503 except admin
            endpoints, /api/auth/*, and webhook callbacks.
          </div>
        )}
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
          setMaintenanceConfirm(false);
          setConfirmString('');
          void setMaintenanceFlag(true);
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

function extractErrorMessage(e: unknown, fallback: string): string {
  if (
    typeof e === 'object' &&
    e !== null &&
    'response' in e &&
    typeof (e as { response?: { data?: { message?: string } } }).response?.data?.message === 'string'
  ) {
    return (e as { response: { data: { message: string } } }).response.data.message;
  }
  if (e instanceof Error) return e.message;
  return fallback;
}

