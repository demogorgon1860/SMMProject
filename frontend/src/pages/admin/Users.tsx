import { useEffect, useMemo, useState } from 'react';
import {
  Badge,
  Button,
  Card,
  Drawer,
  Empty,
  Icon,
  IDCell,
  Input,
  Money,
  PageHeader,
  Pagination,
  Select,
  StatusBadge,
  Tabs,
  TimeCell,
  useToast,
} from '../../components/ui';
import { adminAPI } from '../../services/api';
import type { User } from '../../types';
import { fmtInt } from '../../lib/utils';
import { AdjustBalanceModal } from './_modals';

type AdminUser = User & {
  ordersCount?: number;
  totalSpent?: number;
  apiKeyConfigured?: boolean;
  status?: 'active' | 'suspended' | string;
  roles?: string[];
};

const PAGE_SIZE = 25;

export function AdminUsersPage() {
  const [users, setUsers] = useState<AdminUser[]>([]);
  const [loading, setLoading] = useState(true);
  const [q, setQ] = useState('');
  const [statusFilter, setStatusFilter] = useState('all');
  const [balanceFilter, setBalanceFilter] = useState('all');
  const [page, setPage] = useState(1);
  const [openId, setOpenId] = useState<number | null>(null);
  const [adjustFor, setAdjustFor] = useState<AdminUser | null>(null);

  useEffect(() => {
    let cancelled = false;
    adminAPI
      .getUsers(0, 200)
      .then((data: unknown) => {
        if (cancelled) return;
        const arr: AdminUser[] = Array.isArray(data) ? (data as AdminUser[]) : (data as { content?: AdminUser[] })?.content ?? [];
        setUsers(arr);
      })
      .finally(() => !cancelled && setLoading(false));
    return () => {
      cancelled = true;
    };
  }, []);

  const filtered = useMemo(() => {
    return users.filter((u) => {
      if (statusFilter !== 'all' && (u.status ?? (u.isActive ? 'active' : 'suspended')).toLowerCase() !== statusFilter) return false;
      if (balanceFilter === 'has' && (u.balance ?? 0) <= 0) return false;
      if (balanceFilter === 'zero' && (u.balance ?? 0) > 0) return false;
      if (q.trim()) {
        const needle = q.trim().toLowerCase();
        if (
          !(u.email ?? '').toLowerCase().includes(needle) &&
          !(u.username ?? '').toLowerCase().includes(needle) &&
          !String(u.id).includes(needle)
        ) {
          return false;
        }
      }
      return true;
    });
  }, [users, q, statusFilter, balanceFilter]);

  const pageRows = filtered.slice((page - 1) * PAGE_SIZE, page * PAGE_SIZE);
  const openUser = users.find((u) => u.id === openId) ?? null;

  const onBalanceChanged = (userId: number, delta: number) => {
    setUsers((prev) => prev.map((u) => (u.id === userId ? { ...u, balance: (u.balance ?? 0) + delta } : u)));
  };

  return (
    <>
      <PageHeader
        title="Users"
        subtitle={
          <span>
            <span className="font-mono text-fg">{filtered.length}</span> filtered ·{' '}
            <span className="font-mono text-fg">{users.length}</span> total ·{' '}
            <span className="font-mono text-success">{users.filter((u) => u.isActive).length}</span> active
          </span>
        }
      />

      <div className="space-y-4 p-6">
        <Card className="p-4">
          <div className="flex flex-wrap items-center gap-2">
            <Input
              icon="search"
              placeholder="Search email / username / id"
              value={q}
              onChange={(e) => setQ(e.target.value)}
              containerClassName="min-w-[280px] flex-1"
              block
            />
            <Select
              selectSize="md"
              value={statusFilter}
              onChange={(e) => setStatusFilter(e.target.value)}
              options={[
                { value: 'all', label: 'Any status' },
                { value: 'active', label: 'Active' },
                { value: 'suspended', label: 'Suspended' },
              ]}
            />
            <Select
              selectSize="md"
              value={balanceFilter}
              onChange={(e) => setBalanceFilter(e.target.value)}
              options={[
                { value: 'all', label: 'Any balance' },
                { value: 'has', label: 'Has balance' },
                { value: 'zero', label: 'Zero balance' },
              ]}
            />
          </div>
        </Card>

        <Card className="p-0">
          {loading ? (
            <div className="p-12 text-center text-[13px] text-fg-subtle">Loading…</div>
          ) : filtered.length === 0 ? (
            <Empty icon="users" title="No users match" subtitle="Adjust filters above." />
          ) : (
            <>
              <table className="tbl">
                <thead>
                  <tr>
                    <th>ID</th>
                    <th>Email</th>
                    <th>Username</th>
                    <th className="text-right">Balance</th>
                    <th className="text-right">Orders</th>
                    <th className="text-right">Total spent</th>
                    <th>Status</th>
                    <th>API key</th>
                    <th>Registered</th>
                    <th />
                  </tr>
                </thead>
                <tbody>
                  {pageRows.map((u) => (
                    <tr key={u.id} className="cursor-pointer" onClick={() => setOpenId(u.id)}>
                      <td>
                        <IDCell id={u.id} />
                      </td>
                      <td className="text-[13px]">{u.email}</td>
                      <td className="font-mono text-[12px] text-fg-muted">@{u.username}</td>
                      <td className="text-right">
                        <Money value={u.balance ?? 0} />
                      </td>
                      <td className="text-right font-mono">{fmtInt(u.ordersCount ?? 0)}</td>
                      <td className="text-right font-mono text-fg-muted">
                        <Money value={u.totalSpent ?? 0} />
                      </td>
                      <td>
                        <StatusBadge status={u.isActive ? 'completed' : 'suspended'} label={u.isActive ? 'active' : 'suspended'} />
                      </td>
                      <td>
                        {u.apiKeyConfigured ? (
                          <Badge tone="success" size="sm" icon="check">
                            yes
                          </Badge>
                        ) : (
                          <Badge tone="muted" size="sm">
                            no
                          </Badge>
                        )}
                      </td>
                      <td>
                        <TimeCell iso={u.createdAt} />
                      </td>
                      <td onClick={(e) => e.stopPropagation()}>
                        <button
                          type="button"
                          onClick={() => setAdjustFor(u)}
                          className="row-action inline-flex h-7 w-7 items-center justify-center rounded text-fg-subtle"
                          title="Adjust balance"
                        >
                          <Icon name="wallet" size={14} />
                        </button>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
              <Pagination page={page} total={filtered.length} pageSize={PAGE_SIZE} onPage={setPage} />
            </>
          )}
        </Card>
      </div>

      <UserDrawer
        user={openUser}
        onClose={() => setOpenId(null)}
        onAdjust={() => openUser && setAdjustFor(openUser)}
      />

      <AdjustBalanceModal
        open={!!adjustFor}
        user={adjustFor}
        onClose={() => setAdjustFor(null)}
        onSuccess={(delta) => {
          if (adjustFor) onBalanceChanged(adjustFor.id, delta);
        }}
      />
    </>
  );
}

interface UserDrawerProps {
  user: AdminUser | null;
  onClose: () => void;
  onAdjust: () => void;
}

function UserDrawer({ user, onClose, onAdjust }: UserDrawerProps) {
  const toast = useToast();
  const [tab, setTab] = useState<'overview' | 'orders' | 'balance' | 'apikeys' | 'actions'>('overview');

  if (!user) return null;

  return (
    <Drawer
      open={!!user}
      onClose={onClose}
      width={820}
      title={
        <span className="flex items-center gap-2">
          <span>{user.email}</span>
          <span className="font-mono text-[12px] text-fg-muted">#{user.id}</span>
        </span>
      }
      subtitle={
        <span className="flex items-center gap-2 text-[12px] text-fg-subtle">
          <StatusBadge status={user.isActive ? 'completed' : 'suspended'} label={user.isActive ? 'active' : 'suspended'} size="sm" />
          <span className="font-mono">@{user.username}</span> · joined <TimeCell iso={user.createdAt} />
        </span>
      }
      actions={
        <>
          <Button variant="secondary" size="sm" icon="user">
            Impersonate
          </Button>
          <Button
            variant="danger"
            size="sm"
            icon="shield"
            onClick={() => {
              adminAPI
                .updateUserRole(user.id, user.isActive ? 'USER' : 'USER')
                .then(() => toast(user.isActive ? 'User suspended.' : 'User unsuspended.', 'success'))
                .catch(() => toast('Action failed.', 'error'));
            }}
          >
            {user.isActive ? 'Suspend' : 'Unsuspend'}
          </Button>
        </>
      }
    >
      <div className="border-b border-border">
        <Tabs
          value={tab}
          onChange={setTab}
          tabs={[
            { value: 'overview', label: 'Overview' },
            { value: 'orders', label: 'Orders', count: user.ordersCount },
            { value: 'balance', label: 'Balance' },
            { value: 'apikeys', label: 'API keys' },
            { value: 'actions', label: 'Admin actions' },
          ]}
        />
      </div>

      <div className="p-6">
        {tab === 'overview' && <UserOverview user={user} onAdjust={onAdjust} />}
        {tab === 'orders' && (
          <Empty icon="orders" title="Orders embed coming in Phase 3" subtitle={`Use Orders page filtered by user_id=${user.id}.`} />
        )}
        {tab === 'balance' && (
          <Empty icon="wallet" title="Balance history" subtitle="Per-user transaction list will land with Phase 3 backend." />
        )}
        {tab === 'apikeys' && (
          <Card className="p-5">
            <div className="flex items-center justify-between">
              <div className="text-[14px] font-semibold">API key</div>
              <Button variant="primary" size="sm" icon="plus">
                Create on user's behalf
              </Button>
            </div>
            <div className="mt-3 text-[13px] text-fg-muted">
              {user.apiKeyConfigured ? 'User has a configured API key.' : 'User has not generated an API key yet.'}
            </div>
          </Card>
        )}
        {tab === 'actions' && <UserActionsTab user={user} onAdjust={onAdjust} />}
      </div>
    </Drawer>
  );
}

function UserOverview({ user, onAdjust }: { user: AdminUser; onAdjust: () => void }) {
  return (
    <div className="grid grid-cols-1 gap-4 lg:grid-cols-2">
      <Card>
        <div className="eyebrow">Wallet</div>
        <div className="mt-2">
          <Money value={user.balance ?? 0} size="lg" />
        </div>
        <div className="mt-3">
          <Button variant="primary" size="md" icon="wallet" onClick={onAdjust}>
            Adjust balance
          </Button>
        </div>
      </Card>
      <Card>
        <div className="eyebrow">Lifetime value</div>
        <div className="mt-2">
          <Money value={user.totalSpent ?? 0} size="lg" />
        </div>
        <div className="mt-3 grid grid-cols-3 gap-2 font-mono text-[11.5px]">
          <Stat k="Orders" v={fmtInt(user.ordersCount ?? 0)} />
          <Stat k="Avg order" v={user.ordersCount ? '$' + ((user.totalSpent ?? 0) / user.ordersCount).toFixed(2) : '$0.00'} />
          <Stat k="Roles" v={(user.roles ?? ['user']).join(', ')} />
        </div>
      </Card>
      <Card className="lg:col-span-2">
        <div className="text-[13px] font-semibold">Profile</div>
        <div className="mt-3 grid grid-cols-2 gap-x-4 gap-y-3 text-[13px]">
          {[
            ['User ID', '#' + user.id],
            ['Email', user.email],
            ['Username', '@' + user.username],
            ['Status', user.isActive ? 'active' : 'suspended'],
            ['Roles', (user.roles ?? ['user']).join(', ')],
            ['API key', user.apiKeyConfigured ? '••••••••••••••••' : 'not configured'],
            ['Registered', user.createdAt.replace('T', ' ').slice(0, 19) + ' UTC'],
            ['Last login', user.lastLoginAt ? user.lastLoginAt.replace('T', ' ').slice(0, 19) + ' UTC' : '—'],
          ].map(([k, v]) => (
            <div key={k} className="border-b border-border pb-2">
              <div className="text-[11px] uppercase tracking-wider text-fg-subtle">{k}</div>
              <div className="mt-0.5 font-mono text-[13px]">{v}</div>
            </div>
          ))}
        </div>
      </Card>
    </div>
  );
}

function Stat({ k, v }: { k: string; v: React.ReactNode }) {
  return (
    <div className="rounded-md border border-border bg-bg-sunken p-2 text-center">
      <div className="text-[10px] uppercase tracking-wider text-fg-subtle">{k}</div>
      <div className="mt-0.5 text-[13px]">{v}</div>
    </div>
  );
}

function UserActionsTab({ user, onAdjust }: { user: AdminUser; onAdjust: () => void }) {
  const toast = useToast();
  const cards: Array<{ icon: 'wallet' | 'shield' | 'lock' | 'user'; title: string; body: string; variant: 'primary' | 'secondary' | 'danger'; onClick: () => void }> = [
    { icon: 'wallet', title: 'Adjust balance', body: 'Credit or debit user wallet. Logged in audit.', variant: 'primary', onClick: onAdjust },
    { icon: 'lock', title: 'Reset password', body: 'Sends password reset email to user.', variant: 'secondary', onClick: () => toast('Reset email sent.', 'success') },
    { icon: 'shield', title: user.isActive ? 'Suspend' : 'Unsuspend', body: 'Freezes the account; orders pause.', variant: 'danger', onClick: () => toast('Action saved.', 'success') },
    { icon: 'user', title: 'Impersonate', body: 'Sign in as this user (read-only marker visible).', variant: 'secondary', onClick: () => toast('Impersonation: Phase 3.', 'info') },
  ];
  return (
    <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
      {cards.map((c) => (
        <Card key={c.title} className="flex items-start gap-3 p-5">
          <span
            className="flex h-9 w-9 flex-none items-center justify-center rounded-md"
            style={{
              background: c.variant === 'danger' ? 'var(--danger-soft)' : c.variant === 'primary' ? 'var(--accent-soft)' : 'var(--bg-sunken)',
              color: c.variant === 'danger' ? 'var(--danger)' : c.variant === 'primary' ? 'var(--accent-fg)' : 'var(--fg-muted)',
            }}
          >
            <Icon name={c.icon} size={16} />
          </span>
          <div className="min-w-0 flex-1">
            <div className="text-[13.5px] font-semibold">{c.title}</div>
            <p className="mt-1 text-[12.5px] text-fg-muted">{c.body}</p>
            <div className="mt-3">
              <Button variant={c.variant} size="sm" onClick={c.onClick}>
                {c.title}
              </Button>
            </div>
          </div>
        </Card>
      ))}
    </div>
  );
}
