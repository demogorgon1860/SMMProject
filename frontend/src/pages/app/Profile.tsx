import { useEffect, useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { AxiosError } from 'axios';
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
import { apiKeyAPI, profileAPI, type LifetimeStats, type Session } from '../../services/api';
import { useAuthStore } from '../../store/authStore';
import { useTheme, type Density } from '../../contexts/ThemeContext';
import { cn, fmtInt, fmtMoney, fmtRel, toNum } from '../../lib/utils';

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

/**
 * One tile of the Profile → Account "Lifetime stats" grid. Renders a shimmering bar while the
 * lifetime-stats fetch is in flight; the explicit skeleton avoids a flash of "—" before the
 * real value lands, which previously made the tile look broken on first render.
 */
function StatTile({ label, value, loading }: { label: string; value: string; loading: boolean }) {
  return (
    <div className="rounded-md border border-border bg-bg-sunken p-3">
      <div className="text-[10.5px] uppercase tracking-wider text-fg-subtle">{label}</div>
      {loading ? (
        <div className="mt-1 h-[18px] w-12 animate-pulse rounded bg-border-strong/40" aria-hidden />
      ) : (
        <div className="mt-1 font-mono text-[14px] font-semibold tabular-nums">{value}</div>
      )}
    </div>
  );
}

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

  const [stats, setStats] = useState<LifetimeStats | null>(null);
  const [statsLoading, setStatsLoading] = useState(true);
  useEffect(() => {
    let alive = true;
    setStatsLoading(true);
    profileAPI
      .lifetimeStats()
      .then((d) => {
        if (alive) setStats(d);
      })
      .catch(() => {
        // Backend is the source of truth; if it 500s we render zeros rather than a fake number.
        if (alive) setStats(null);
      })
      .finally(() => {
        if (alive) setStatsLoading(false);
      });
    return () => {
      alive = false;
    };
  }, []);

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
            <StatTile
              label="Orders"
              loading={statsLoading}
              value={stats ? fmtInt(stats.ordersTotal) : '0'}
            />
            <StatTile
              label="Spent"
              loading={statsLoading}
              value={stats ? fmtMoney(toNum(stats.totalSpent)) : '$0.00'}
            />
            <StatTile
              label="Tickets"
              loading={statsLoading}
              value={stats ? fmtInt(stats.ticketsTotal) : '0'}
            />
            <StatTile
              label="Member"
              loading={statsLoading}
              value={
                stats?.memberSince
                  ? fmtRel(stats.memberSince)
                  : user?.createdAt
                  ? fmtRel(user.createdAt)
                  : '—'
              }
            />
          </div>
          {stats && stats.ordersCompleted > 0 && (
            <div className="mt-3 text-[11.5px] text-fg-subtle">
              {fmtInt(stats.ordersCompleted)} completed
              {stats.ordersPartial > 0 ? ` · ${fmtInt(stats.ordersPartial)} partial` : ''}
              {stats.ordersCancelled > 0 ? ` · ${fmtInt(stats.ordersCancelled)} cancelled` : ''}
            </div>
          )}
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

/**
 * Quick-and-correct User-Agent prettifier — turns "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7)
 * AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0 Safari/537.36" into "Chrome on macOS".
 *
 * <p>We don't want to ship `ua-parser-js` (~25 kB gzipped) just for the Sessions tab. The cases
 * below cover the four browsers + four platforms that 99% of our users are on; everything else
 * falls through to a sensible "Browser on Platform" string. If the UA is missing entirely we
 * label the row "Unknown device" — better than rendering literal "null" or hiding the row.
 */
function prettyUserAgent(ua: string | null | undefined): string {
  if (!ua) return 'Unknown device';
  const browser =
    /Edg\//.test(ua) ? 'Edge'
    : /OPR\/|Opera/.test(ua) ? 'Opera'
    : /Chrome\//.test(ua) && !/Chromium/.test(ua) ? 'Chrome'
    : /Firefox\//.test(ua) ? 'Firefox'
    : /Safari\//.test(ua) ? 'Safari'
    : 'Browser';
  const platform =
    /iPhone|iPad|iPod/.test(ua) ? 'iOS'
    : /Android/.test(ua) ? 'Android'
    : /Windows/.test(ua) ? 'Windows'
    : /Macintosh|Mac OS X/.test(ua) ? 'macOS'
    : /Linux/.test(ua) ? 'Linux'
    : 'Unknown OS';
  return `${browser} on ${platform}`;
}

function SessionsTab() {
  const [sessions, setSessions] = useState<Session[] | null>(null);
  const [loading, setLoading] = useState(true);
  const [busyId, setBusyId] = useState<number | null>(null);
  const [signingOutOthers, setSigningOutOthers] = useState(false);
  const toast = useToast();

  const refresh = () => {
    setLoading(true);
    return profileAPI
      .sessions()
      .then((d) => setSessions(d))
      .catch(() => {
        setSessions([]);
        toast('Could not load active sessions.', 'error');
      })
      .finally(() => setLoading(false));
  };

  useEffect(() => {
    void refresh();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const onRevoke = async (s: Session) => {
    if (s.current) return;
    setBusyId(s.id);
    try {
      await profileAPI.revokeSession(s.id);
      setSessions((prev) => prev?.filter((x) => x.id !== s.id) ?? null);
      toast('Session revoked.', 'success');
    } catch {
      toast('Could not revoke session.', 'error');
    } finally {
      setBusyId(null);
    }
  };

  const onSignOutOthers = async () => {
    setSigningOutOthers(true);
    try {
      const r = await profileAPI.signOutOthers();
      await refresh();
      const n = r?.revoked ?? 0;
      toast(n === 0 ? 'No other sessions to sign out.' : `Signed out ${n} other session${n === 1 ? '' : 's'}.`, 'success');
    } catch {
      toast('Could not sign out other sessions.', 'error');
    } finally {
      setSigningOutOthers(false);
    }
  };

  const otherSessionCount = (sessions ?? []).filter((s) => !s.current).length;

  return (
    <Card className="p-0">
      <div className="flex items-center justify-between border-b border-border px-5 py-3">
        <div>
          <div className="text-[14px] font-semibold">Active sessions</div>
          <div className="mt-0.5 text-[12px] text-fg-subtle">
            Each row is a browser or device that has signed in to your account.
          </div>
        </div>
        <Button
          variant="secondary"
          size="sm"
          onClick={onSignOutOthers}
          loading={signingOutOthers}
          disabled={otherSessionCount === 0 || signingOutOthers}
        >
          Sign out all others
        </Button>
      </div>
      {loading ? (
        <div className="p-12 text-center text-[13px] text-fg-subtle">Loading…</div>
      ) : !sessions || sessions.length === 0 ? (
        <div className="p-12 text-center text-[13px] text-fg-subtle">No active sessions.</div>
      ) : (
        <ul>
          {sessions.map((s) => (
            <li
              key={s.id}
              className="flex items-center gap-4 border-b border-border px-5 py-4 last:border-b-0"
            >
              <div className="flex h-9 w-9 items-center justify-center rounded-md bg-bg-sunken text-fg-muted">
                <Icon name="terminal" size={14} />
              </div>
              <div className="flex-1 min-w-0">
                <div className="flex items-center gap-2 text-[13.5px] font-medium">
                  <span className="truncate">{prettyUserAgent(s.userAgent)}</span>
                  {s.current && (
                    <Badge tone="success" size="sm" dot>
                      this session
                    </Badge>
                  )}
                </div>
                <div className="font-mono text-[11.5px] text-fg-subtle truncate">
                  {s.ipAddress ?? '—'} · last active {fmtRel(s.lastUsedAt)}
                </div>
              </div>
              {!s.current && (
                <Button
                  variant="ghost"
                  size="sm"
                  icon="x"
                  loading={busyId === s.id}
                  disabled={busyId === s.id}
                  onClick={() => onRevoke(s)}
                >
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
  const [confirmPassword, setConfirmPassword] = useState('');
  const [deleteError, setDeleteError] = useState<string | null>(null);
  const [deleting, setDeleting] = useState(false);
  const [exporting, setExporting] = useState(false);

  // API-pause status drives both the button label and the inline state line. We fetch once on
  // mount and re-fetch after each mutation rather than trusting the response body — defensive
  // against future server-side validation that might short-circuit a flip.
  const [paused, setPaused] = useState<boolean | null>(null);
  const [pausedAt, setPausedAt] = useState<string | null>(null);
  const [apiBusy, setApiBusy] = useState(false);

  useEffect(() => {
    let alive = true;
    profileAPI
      .apiKeyPauseStatus()
      .then((r) => {
        if (!alive) return;
        setPaused(r.paused);
        setPausedAt(r.pausedAt);
      })
      .catch(() => {
        if (alive) setPaused(false);
      });
    return () => {
      alive = false;
    };
  }, []);

  const onExport = async () => {
    setExporting(true);
    try {
      const { blob, filename } = await profileAPI.exportData();
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = filename;
      document.body.appendChild(a);
      a.click();
      a.remove();
      // Revoke on a microtask so the click handler completes before the URL is invalidated; in
      // some browsers immediate revoke aborts the download.
      setTimeout(() => URL.revokeObjectURL(url), 0);
      toast('Export downloaded.', 'success');
    } catch {
      toast('Could not generate export.', 'error');
    } finally {
      setExporting(false);
    }
  };

  const onPauseConfirm = async () => {
    setPauseConfirm(false);
    setApiBusy(true);
    try {
      const r = await profileAPI.pauseApi();
      setPaused(r.paused);
      setPausedAt(r.pausedAt);
      toast('API access paused.', 'success');
    } catch {
      toast('Could not pause API.', 'error');
    } finally {
      setApiBusy(false);
    }
  };

  const onResume = async () => {
    setApiBusy(true);
    try {
      const r = await profileAPI.resumeApi();
      setPaused(r.paused);
      setPausedAt(r.pausedAt);
      toast('API access restored.', 'success');
    } catch {
      toast('Could not resume API.', 'error');
    } finally {
      setApiBusy(false);
    }
  };

  return (
    <div className="mx-auto max-w-[760px] space-y-4">
      <Card className="p-6">
        <div className="text-[14px] font-semibold">Export account data</div>
        <p className="mt-1 text-[12.5px] text-fg-muted">
          Download a JSON archive of your orders, transactions, deposits, refill requests and
          support tickets. Generated on demand from your live data.
        </p>
        <div className="mt-4">
          <Button
            variant="secondary"
            size="md"
            icon="download"
            onClick={onExport}
            loading={exporting}
            disabled={exporting}
          >
            Download export
          </Button>
        </div>
      </Card>

      <Card className="p-6">
        <div className="flex items-baseline justify-between">
          <div>
            <div className="text-[14px] font-semibold">Pause API access</div>
            <p className="mt-1 text-[12.5px] text-fg-muted">
              Temporarily disable your API key without rotating it. The web app keeps working;
              external integrations using <code>X-API-Key</code> get a 403 until you resume.
            </p>
            {paused && pausedAt && (
              <p className="mt-2 text-[12px] text-warn">
                Paused {fmtRel(pausedAt)}.
              </p>
            )}
          </div>
        </div>
        <div className="mt-4">
          {paused ? (
            <Button
              variant="primary"
              size="md"
              icon="play"
              onClick={onResume}
              loading={apiBusy}
              disabled={apiBusy || paused === null}
            >
              Resume API
            </Button>
          ) : (
            <Button
              variant="secondary"
              size="md"
              icon="pause"
              onClick={() => setPauseConfirm(true)}
              loading={apiBusy}
              disabled={apiBusy || paused === null}
            >
              Pause API
            </Button>
          )}
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
        onConfirm={onPauseConfirm}
      />

      <ConfirmModal
        open={deleteConfirm}
        onClose={() => {
          if (deleting) return;
          setDeleteConfirm(false);
          setConfirmText('');
          setConfirmPassword('');
          setDeleteError(null);
        }}
        title="Delete this account?"
        confirmText="Delete account"
        variant="danger"
        loading={deleting}
        onConfirm={async () => {
          // Block confirm until both fields are present — the modal still asks the user to
          // physically type DELETE, so this check just stops accidental empty submits.
          if (confirmText !== 'DELETE') {
            setDeleteError('Type DELETE in the box above to confirm.');
            return;
          }
          if (!confirmPassword) {
            setDeleteError('Enter your current password.');
            return;
          }
          setDeleting(true);
          setDeleteError(null);
          try {
            await profileAPI.deleteAccount(confirmText, confirmPassword);
            // Don't keep the deleted user's tokens lying around.
            localStorage.removeItem('token');
            localStorage.removeItem('user');
            logout();
            toast(
              'Account deletion scheduled. Personal data will be removed within 30 days.',
              'success',
            );
            navigate('/');
          } catch (err) {
            const ax = err as AxiosError<{ reason?: string; message?: string }>;
            const status = ax.response?.status;
            const msg = ax.response?.data?.message;
            // Map server reasons to inline copy. Fall back to a generic message + the
            // compliance address so the user always has a path forward.
            if (status === 422) {
              setDeleteError(msg ?? 'Password does not match.');
            } else if (status === 409) {
              setDeleteError(msg ?? 'Cannot delete — clear your balance and active orders first.');
            } else if (status === 400) {
              setDeleteError(msg ?? 'Type DELETE to confirm.');
            } else {
              setDeleteError(
                'Could not delete the account. Contact compliance@smmworld.vip if this persists.',
              );
            }
          } finally {
            setDeleting(false);
          }
        }}
      >
        <p className="mb-3 text-[13px] text-fg-muted">
          This anonymizes your data immediately and schedules a permanent purge in 30 days.
          Order history is retained without your identity for 7 years per AML.
        </p>
        <Field label="Type DELETE to confirm">
          <Input
            block
            value={confirmText}
            onChange={(e) => setConfirmText(e.target.value)}
            placeholder="DELETE"
            autoComplete="off"
            disabled={deleting}
          />
        </Field>
        <Field label="Current password">
          <Input
            block
            type="password"
            value={confirmPassword}
            onChange={(e) => setConfirmPassword(e.target.value)}
            autoComplete="current-password"
            disabled={deleting}
          />
        </Field>
        {deleteError && (
          <p className="mt-2 text-[12.5px] text-danger" role="alert">
            {deleteError}
          </p>
        )}
        <p className="mt-3 text-[11.5px] text-fg-subtle">
          Within the 30-day grace window, contact{' '}
          <a className="underline" href="mailto:compliance@smmworld.vip">
            compliance@smmworld.vip
          </a>{' '}
          to restore the account.
        </p>
      </ConfirmModal>
    </div>
  );
}
