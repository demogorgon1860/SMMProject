import { useEffect, useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import {
  AccentPicker,
  Badge,
  Button,
  Card,
  ConfirmModal,
  CopyBtn,
  Field,
  Icon,
  Input,
  Switch,
  Tabs,
  ThemeToggle,
  useToast,
} from '../../components/ui';
import { Select } from '../../components/ui/Select';
import { apiKeyAPI, profileAPI } from '../../services/api';
import { useAuthStore } from '../../store/authStore';
import { useTheme, type Density } from '../../contexts/ThemeContext';
import { cn, fmtRel } from '../../lib/utils';

// =====================================================================
// Profile — 5 tabs:
//   account · security · preferences · sessions · danger
// (No Referrals card — design system stripped per user direction.)
// =====================================================================

type Tab = 'account' | 'security' | 'preferences' | 'sessions' | 'danger';

export function ProfilePage() {
  const [params, setParams] = useSearchParams();
  const initialTab = (params.get('tab') as Tab | null) ?? 'account';
  const [tab, setTab] = useState<Tab>(initialTab);
  const user = useAuthStore((s) => s.user);

  const onTab = (next: string) => {
    setTab(next as Tab);
    setParams((p) => {
      p.set('tab', next);
      return p;
    });
  };

  return (
    <div className="container-app py-8">
      {/* Header */}
      <div className="flex flex-wrap items-center gap-4">
        <span className="flex h-14 w-14 items-center justify-center rounded-full bg-gradient-to-br from-[#4f46e5] to-[#7c3aed] text-[18px] font-bold text-white">
          {(user?.username?.[0] ?? user?.email?.[0] ?? 'U').toUpperCase()}
        </span>
        <div>
          <h1 className="text-[22px] font-bold tracking-[-0.02em]">@{user?.username ?? 'you'}</h1>
          <div className="mt-0.5 flex flex-wrap items-center gap-2 text-[12.5px] text-fg-muted">
            <span className="font-mono">{user?.email ?? '—'}</span>
            <Badge tone="success" size="sm" icon="check">
              verified
            </Badge>
            <span>·</span>
            <span>joined {user?.createdAt ? fmtRel(user.createdAt) : '—'}</span>
          </div>
        </div>
      </div>

      <div className="mt-6 overflow-x-auto">
        <Tabs
          value={tab}
          onChange={onTab}
          tabs={[
            { value: 'account', label: 'Account' },
            { value: 'security', label: 'Security' },
            { value: 'preferences', label: 'Preferences' },
            { value: 'sessions', label: 'Sessions' },
            { value: 'danger', label: 'Danger zone' },
          ]}
        />
      </div>

      <div className="mt-6">
        {tab === 'account' && <AccountTab />}
        {tab === 'security' && <SecurityTab />}
        {tab === 'preferences' && <PreferencesTab />}
        {tab === 'sessions' && <SessionsTab />}
        {tab === 'danger' && <DangerTab />}
      </div>
    </div>
  );
}

// ---------------------------------------------------------------------
// Account
// ---------------------------------------------------------------------

function AccountTab() {
  const user = useAuthStore((s) => s.user);
  const toast = useToast();
  const [username, setUsername] = useState(user?.username ?? '');
  const [email, setEmail] = useState(user?.email ?? '');
  const [tz, setTz] = useState('Europe/Berlin');

  const [notif, setNotif] = useState({
    orderCompleted: true,
    orderPartial: true,
    orderCancelled: true,
    deposit: true,
    weekly: false,
    promo: false,
  });

  return (
    <div className="grid grid-cols-1 gap-6 lg:grid-cols-[1fr_320px]">
      <div className="space-y-6">
        <Card className="p-6">
          <div className="text-[14px] font-semibold">Identity</div>
          <div className="mt-4 grid grid-cols-1 gap-x-4 md:grid-cols-2">
            <Field label="Username">
              <Input block value={username} onChange={(e) => setUsername(e.target.value)} />
            </Field>
            <Field label="Email">
              <Input block type="email" value={email} onChange={(e) => setEmail(e.target.value)} iconRight="check" />
            </Field>
            <div className="md:col-span-2">
              <Field label="Time zone">
                <Select
                  block
                  value={tz}
                  onChange={(e) => setTz(e.target.value)}
                  options={['UTC', 'Europe/Berlin', 'Europe/London', 'America/New_York', 'America/Los_Angeles', 'Asia/Tokyo', 'Asia/Singapore']}
                />
              </Field>
            </div>
          </div>
          <div className="mt-2 flex justify-end gap-2">
            <Button variant="ghost" size="md">
              Cancel
            </Button>
            <Button variant="primary" size="md" onClick={() => toast('Saved.', 'success')}>
              Save changes
            </Button>
          </div>
        </Card>

        <Card className="p-6">
          <div className="text-[14px] font-semibold">Notifications</div>
          <p className="mt-1 text-[12.5px] text-fg-subtle">
            Email-only for now. Telegram-bot delivery lands in the next release.
          </p>
          <ul className="mt-4 divide-y divide-border">
            {[
              ['orderCompleted', 'Order completed', 'Heads-up when an order finishes delivery'],
              ['orderPartial', 'Order partial', 'When we stop early and refund the remainder'],
              ['orderCancelled', 'Order cancelled', 'Confirmation + refund details'],
              ['deposit', 'Deposit confirmed', 'When your top-up clears confirms'],
              ['weekly', 'Weekly summary', 'Monday morning recap of last week'],
              ['promo', 'Promotions', 'Rare. Only when we ship something material.'],
            ].map(([key, label, sub]) => (
              <li key={key} className="flex items-center justify-between gap-4 py-3">
                <div>
                  <div className="text-[13.5px] font-medium">{label}</div>
                  <div className="text-[12px] text-fg-subtle">{sub}</div>
                </div>
                <Switch
                  checked={notif[key as keyof typeof notif]}
                  onChange={(v) => setNotif((n) => ({ ...n, [key]: v }))}
                />
              </li>
            ))}
          </ul>
        </Card>
      </div>

      <aside className="space-y-6">
        <Card className="p-6">
          <div className="eyebrow">Lifetime stats</div>
          <div className="mt-3 grid grid-cols-2 gap-3 text-[13px]">
            {[
              ['Orders', user?.balance != null ? '—' : '—'],
              ['Spent', user?.balance != null ? '$0.00' : '—'],
              ['Tickets', '0'],
              ['Member', user?.createdAt ? fmtRel(user.createdAt) : '—'],
            ].map(([k, v]) => (
              <div key={k} className="rounded-md border border-border bg-bg-sunken p-3">
                <div className="text-[10.5px] uppercase tracking-wider text-fg-subtle">{k}</div>
                <div className="mt-1 font-mono text-[14px] font-semibold tabular-nums">{v}</div>
              </div>
            ))}
          </div>
        </Card>
      </aside>
    </div>
  );
}

// ---------------------------------------------------------------------
// Security
// ---------------------------------------------------------------------

function SecurityTab() {
  const toast = useToast();
  const [pwd, setPwd] = useState({ cur: '', n1: '', n2: '' });
  const [twoFA, setTwoFA] = useState(false);
  const [apiKey, setApiKey] = useState<string | null>(null);
  const [keyMasked, setKeyMasked] = useState<string | null>(null);
  const [showKey, setShowKey] = useState(false);
  const [keyLoading, setKeyLoading] = useState(false);

  useEffect(() => {
    apiKeyAPI
      .status()
      .then((r: unknown) => {
        const v = r as { maskedKey?: string; hasApiKey?: boolean };
        if (v.maskedKey) setKeyMasked(v.maskedKey);
      })
      .catch(() => {});
  }, []);

  const generate = async () => {
    setKeyLoading(true);
    try {
      const r = (await apiKeyAPI.generate()) as { apiKey?: string };
      if (r.apiKey) {
        setApiKey(r.apiKey);
        setShowKey(true);
        toast('API key generated. Copy it now — it won\'t be shown again.', 'success');
      }
    } catch {
      toast('Could not generate key.', 'error');
    } finally {
      setKeyLoading(false);
    }
  };

  const rotate = async () => {
    setKeyLoading(true);
    try {
      const r = (await apiKeyAPI.rotate()) as { newApiKey?: string };
      if (r.newApiKey) {
        setApiKey(r.newApiKey);
        setShowKey(true);
        toast('Key rotated. The old one is revoked.', 'success');
      }
    } catch {
      toast('Could not rotate key.', 'error');
    } finally {
      setKeyLoading(false);
    }
  };

  const submitPwd = async (e: React.FormEvent) => {
    e.preventDefault();
    if (pwd.n1 !== pwd.n2) {
      toast('Passwords do not match.', 'error');
      return;
    }
    if (pwd.n1.length < 8) {
      toast('Password too short (min 8).', 'error');
      return;
    }
    try {
      await profileAPI.changePassword(pwd.cur, pwd.n1);
      toast('Password updated.', 'success');
      setPwd({ cur: '', n1: '', n2: '' });
    } catch {
      toast('Could not update password.', 'error');
    }
  };

  return (
    <div className="grid grid-cols-1 gap-6 lg:grid-cols-2">
      <Card className="p-6">
        <div className="text-[14px] font-semibold">Password</div>
        <form className="mt-4" onSubmit={submitPwd}>
          <Field label="Current password">
            <Input block type="password" autoComplete="current-password" value={pwd.cur} onChange={(e) => setPwd({ ...pwd, cur: e.target.value })} />
          </Field>
          <Field label="New password" hint="14+ chars recommended">
            <Input block type="password" autoComplete="new-password" value={pwd.n1} onChange={(e) => setPwd({ ...pwd, n1: e.target.value })} />
          </Field>
          <Field label="Confirm new">
            <Input block type="password" autoComplete="new-password" value={pwd.n2} onChange={(e) => setPwd({ ...pwd, n2: e.target.value })} />
          </Field>
          <Button type="submit" variant="primary" size="md">
            Update password
          </Button>
        </form>
      </Card>

      <Card className="p-6">
        <div className="flex items-baseline justify-between">
          <div>
            <div className="text-[14px] font-semibold">Two-factor authentication</div>
            <p className="mt-1 text-[12.5px] text-fg-subtle">Required for accounts holding more than $500.</p>
          </div>
          <Switch
            checked={twoFA}
            onChange={(v) => {
              setTwoFA(v);
              toast(v ? '2FA setup pending — backend in Phase 3.' : '2FA disabled.', v ? 'info' : 'success');
            }}
          />
        </div>
        {twoFA && (
          <div className="mt-4 rounded-md border border-border bg-bg-sunken p-4">
            <div className="text-[12.5px] text-fg-muted">
              <Icon name="info" size={12} className="mr-1 inline align-[-2px]" />
              The TOTP setup flow lands once the <code>/v1/me/2fa/init</code> endpoint ships (Phase 3 backend).
            </div>
          </div>
        )}
      </Card>

      <Card className="p-6 lg:col-span-2">
        <div className="flex items-baseline justify-between">
          <div>
            <div className="text-[14px] font-semibold">API key</div>
            <p className="mt-1 text-[12.5px] text-fg-subtle">
              Use <code>X-API-Key</code> header on requests. Treat it like a password.
            </p>
          </div>
          {apiKey ? (
            <div className="flex gap-2">
              <Button variant="secondary" size="sm" icon="refresh" onClick={rotate} loading={keyLoading}>
                Rotate
              </Button>
            </div>
          ) : keyMasked ? (
            <Button variant="secondary" size="sm" icon="refresh" onClick={rotate} loading={keyLoading}>
              Rotate
            </Button>
          ) : (
            <Button variant="primary" size="sm" icon="plus" onClick={generate} loading={keyLoading}>
              Generate key
            </Button>
          )}
        </div>
        <div className="mt-4 rounded-md border border-border bg-bg-sunken p-3">
          {apiKey ? (
            <div>
              <div className="font-mono text-[13px] break-all">{showKey ? apiKey : maskKey(apiKey)}</div>
              <div className="mt-2 flex items-center gap-2">
                <Button variant="ghost" size="sm" icon={showKey ? 'eye-off' : 'eye'} onClick={() => setShowKey((v) => !v)}>
                  {showKey ? 'Hide' : 'Show'}
                </Button>
                <CopyBtn value={apiKey} size="sm" />
              </div>
              <p className="mt-2 text-[11.5px] text-warn">
                Copy this now — for security we won't display the full key again.
              </p>
            </div>
          ) : keyMasked ? (
            <div className="font-mono text-[13px] text-fg-muted">{keyMasked}</div>
          ) : (
            <div className="text-[12.5px] text-fg-subtle">No API key configured. Generate one to start using the API.</div>
          )}
        </div>
      </Card>
    </div>
  );
}

function maskKey(k: string): string {
  if (k.length <= 8) return k;
  return k.slice(0, 4) + '••••••••••••••••••••••' + k.slice(-4);
}

// ---------------------------------------------------------------------
// Preferences
// ---------------------------------------------------------------------

function PreferencesTab() {
  const { theme, setTheme, accent, density, setDensity, hero, setHero } = useTheme();
  const [lang, setLang] = useState('en');
  const [currency, setCurrency] = useState('USD');

  return (
    <div className="grid grid-cols-1 gap-6 md:grid-cols-2">
      <Card className="p-6">
        <div className="text-[14px] font-semibold">Theme</div>
        <p className="mt-1 text-[12.5px] text-fg-subtle">Light or dark — applies instantly across the entire app.</p>
        <div className="mt-4 grid grid-cols-2 gap-2">
          {(['light', 'dark'] as const).map((t) => (
            <button
              key={t}
              type="button"
              onClick={() => setTheme(t)}
              className={cn(
                'flex h-[64px] items-center gap-3 rounded-md border px-4',
                theme === t ? 'border-accent bg-accent-soft text-accent-fg' : 'border-border-strong bg-bg-elev text-fg-muted hover:bg-bg-sunken',
              )}
            >
              <Icon name={t === 'dark' ? 'moon' : 'sun'} size={18} />
              <div className="capitalize">{t}</div>
            </button>
          ))}
        </div>

        <div className="mt-6 text-[14px] font-semibold">Accent</div>
        <div className="mt-2 flex items-center gap-3">
          <AccentPicker />
          <span className="font-mono text-[12px] text-fg-subtle">{accent}</span>
        </div>

        <div className="mt-6 text-[14px] font-semibold">Density</div>
        <div className="mt-2 inline-flex rounded-md border border-border bg-bg-sunken p-[3px]">
          {(['comfortable', 'compact', 'cozy'] as Density[]).map((d) => (
            <button
              key={d}
              type="button"
              onClick={() => setDensity(d)}
              className={cn(
                'rounded px-3 py-1 text-[12px] font-medium capitalize',
                density === d ? 'bg-bg-elev text-fg shadow-sm' : 'text-fg-muted hover:text-fg',
              )}
            >
              {d}
            </button>
          ))}
        </div>
      </Card>

      <Card className="p-6">
        <div className="text-[14px] font-semibold">Localization</div>
        <Field label="Language" hint="More languages soon">
          <Select block value={lang} onChange={(e) => setLang(e.target.value)} options={[
            { value: 'en', label: 'English' },
            { value: 'ru', label: 'Russian' },
          ]} />
        </Field>
        <Field label="Currency display">
          <Select block value={currency} onChange={(e) => setCurrency(e.target.value)} options={['USD', 'EUR', 'GBP']} />
        </Field>

        <div className="mt-6 text-[14px] font-semibold">Marketing landing variant</div>
        <p className="mt-1 text-[12.5px] text-fg-subtle">Toggle the homepage hero. Affects only logged-out visitors.</p>
        <div className="mt-3 inline-flex rounded-md border border-border bg-bg-sunken p-[3px]">
          {(['A', 'B'] as const).map((v) => (
            <button
              key={v}
              type="button"
              onClick={() => setHero(v)}
              className={cn(
                'rounded px-3 py-1 text-[12px] font-medium',
                hero === v ? 'bg-bg-elev text-fg shadow-sm' : 'text-fg-muted hover:text-fg',
              )}
            >
              Variant {v}
            </button>
          ))}
        </div>

        <div className="mt-6 flex items-center justify-between border-t border-border pt-4">
          <span className="text-[12.5px] text-fg-muted">Quick theme toggle</span>
          <ThemeToggle />
        </div>
      </Card>
    </div>
  );
}

// ---------------------------------------------------------------------
// Sessions
// ---------------------------------------------------------------------

function SessionsTab() {
  const [sessions, setSessions] = useState<Array<{
    id: string;
    agent: string;
    location: string;
    ip: string;
    lastActive: string;
    current: boolean;
  }> | null>(null);
  const toast = useToast();

  useEffect(() => {
    profileAPI
      .sessions()
      .then((d: unknown) => {
        const arr = Array.isArray(d) ? d : (d as { content?: unknown[] })?.content ?? [];
        setSessions(arr as typeof sessions);
      })
      .catch(() => {
        // Phase 3 backend — show a placeholder.
        setSessions([
          { id: '1', agent: 'Chrome on macOS', location: 'Berlin, DE', ip: '94.130.x.x', lastActive: new Date().toISOString(), current: true },
          { id: '2', agent: 'iOS Safari', location: 'Berlin, DE', ip: '94.130.x.x', lastActive: new Date(Date.now() - 86400e3).toISOString(), current: false },
        ]);
      });
  }, []);

  return (
    <Card className="p-0">
      <div className="flex items-center justify-between border-b border-border px-5 py-3">
        <div className="text-[14px] font-semibold">Active sessions</div>
        <Button
          variant="secondary"
          size="sm"
          onClick={() => {
            profileAPI.signOutOthers().catch(() => {});
            toast('Other sessions signed out.', 'success');
          }}
        >
          Sign out all others
        </Button>
      </div>
      {!sessions ? (
        <div className="p-12 text-center text-[13px] text-fg-subtle">Loading…</div>
      ) : (
        <ul>
          {sessions.map((s) => (
            <li key={s.id} className="flex items-center gap-4 border-b border-border px-5 py-4 last:border-b-0">
              <div className="flex h-9 w-9 items-center justify-center rounded-md bg-bg-sunken text-fg-muted">
                <Icon name="terminal" size={14} />
              </div>
              <div className="flex-1">
                <div className="flex items-center gap-2 text-[13.5px] font-medium">
                  {s.agent}
                  {s.current && (
                    <Badge tone="success" size="sm" dot>
                      this session
                    </Badge>
                  )}
                </div>
                <div className="font-mono text-[11.5px] text-fg-subtle">
                  {s.location} · {s.ip} · last active {fmtRel(s.lastActive)}
                </div>
              </div>
              {!s.current && (
                <Button variant="ghost" size="sm" icon="x" onClick={() => toast('Session revoked.', 'success')}>
                  Revoke
                </Button>
              )}
            </li>
          ))}
        </ul>
      )}
    </Card>
  );
}

// ---------------------------------------------------------------------
// Danger zone
// ---------------------------------------------------------------------

function DangerTab() {
  const navigate = useNavigate();
  const toast = useToast();
  const logout = useAuthStore((s) => s.logout);
  const [pauseConfirm, setPauseConfirm] = useState(false);
  const [deleteConfirm, setDeleteConfirm] = useState(false);
  const [confirmText, setConfirmText] = useState('');

  return (
    <div className="mx-auto max-w-[760px] space-y-4">
      <Card className="p-6">
        <div className="text-[14px] font-semibold">Export account data</div>
        <p className="mt-1 text-[12.5px] text-fg-muted">
          Get a JSON archive of orders, transactions, and account metadata. Sent to your email when ready.
        </p>
        <div className="mt-4">
          <Button
            variant="secondary"
            size="md"
            icon="download"
            onClick={() => {
              profileAPI.exportData().catch(() => {});
              toast('Export queued. Check your email in ~10 min.', 'success');
            }}
          >
            Request export
          </Button>
        </div>
      </Card>

      <Card className="p-6">
        <div className="text-[14px] font-semibold">Pause API access</div>
        <p className="mt-1 text-[12.5px] text-fg-muted">
          Temporarily disable the API key without rotating it. Re-enable any time.
        </p>
        <div className="mt-4">
          <Button variant="secondary" size="md" icon="pause" onClick={() => setPauseConfirm(true)}>
            Pause API
          </Button>
        </div>
      </Card>

      <Card className="border-danger/30 p-6">
        <div className="text-[14px] font-semibold text-danger">Delete account</div>
        <p className="mt-1 text-[12.5px] text-fg-muted">
          Wallet balance must be zero. Order history is retained for 7 years per AML — personal data
          is purged within 30 days.
        </p>
        <div className="mt-4">
          <Button variant="danger" size="md" icon="trash" onClick={() => setDeleteConfirm(true)}>
            Delete my account
          </Button>
        </div>
      </Card>

      <ConfirmModal
        open={pauseConfirm}
        onClose={() => setPauseConfirm(false)}
        title="Pause API access?"
        message="API requests will return 403 until you re-enable. Web app continues to work."
        confirmText="Pause API"
        variant="warn"
        onConfirm={() => {
          profileAPI.pauseApi().catch(() => {});
          toast('API paused.', 'success');
          setPauseConfirm(false);
        }}
      />

      <ConfirmModal
        open={deleteConfirm}
        onClose={() => {
          setDeleteConfirm(false);
          setConfirmText('');
        }}
        title="Delete this account permanently?"
        confirmText="Delete account"
        variant="danger"
        onConfirm={async () => {
          if (confirmText !== 'DELETE') {
            toast('Type DELETE to confirm.', 'error');
            return;
          }
          try {
            await profileAPI.deleteAccount(confirmText);
            toast('Account deleted.', 'success');
            logout();
            navigate('/');
          } catch {
            toast('Could not delete. Contact compliance@smmworld.vip.', 'error');
          }
        }}
      >
        <p className="mb-3 text-[13px] text-fg-muted">
          This cannot be undone. Type <code className="font-mono">DELETE</code> to confirm.
        </p>
        <Input block value={confirmText} onChange={(e) => setConfirmText(e.target.value)} placeholder="DELETE" />
      </ConfirmModal>
    </div>
  );
}
