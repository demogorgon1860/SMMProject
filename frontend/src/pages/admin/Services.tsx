import { useEffect, useMemo, useState } from 'react';
import {
  Badge,
  Button,
  Card,
  Drawer,
  Empty,
  Field,
  Icon,
  Input,
  Money,
  PageHeader,
  Select,
  Switch,
  Textarea,
  useToast,
} from '../../components/ui';
import { serviceAPI } from '../../services/api';
import type { Service } from '../../types';
import { cn, fmtInt, fmtMoney } from '../../lib/utils';

export function AdminServicesPage() {
  const [services, setServices] = useState<Service[]>([]);
  const [loading, setLoading] = useState(true);
  const [view, setView] = useState<'table' | 'grid'>('table');
  const [q, setQ] = useState('');
  const [cat, setCat] = useState('any');
  const [editing, setEditing] = useState<Service | null>(null);
  const [creating, setCreating] = useState(false);

  useEffect(() => {
    let cancelled = false;
    serviceAPI
      .list()
      .then((data: unknown) => {
        if (cancelled) return;
        // /api/v1/service/services returns { success: true, data: [...] } (PerfectPanelResponse).
        // Accept Spring Page (`content`), admin-controller (`orders`-style with key `services`),
        // and the wrapped `data` shape so the page survives future envelope changes.
        const d = data as { data?: Service[]; content?: Service[]; services?: Service[] } | null;
        const arr: Service[] = Array.isArray(data) ? (data as Service[]) : d?.data ?? d?.content ?? d?.services ?? [];
        setServices(arr);
      })
      .finally(() => !cancelled && setLoading(false));
    return () => {
      cancelled = true;
    };
  }, []);

  const filtered = useMemo(
    () =>
      services.filter((s) => {
        if (cat !== 'any' && s.category !== cat) return false;
        if (q.trim() && !(s.name ?? '').toLowerCase().includes(q.trim().toLowerCase())) return false;
        return true;
      }),
    [services, q, cat],
  );

  return (
    <>
      <PageHeader
        title="Services"
        actions={
          <>
            <div className="inline-flex rounded-md border border-border bg-bg-elev p-[3px]">
              {(['table', 'grid'] as const).map((v) => (
                <button
                  key={v}
                  type="button"
                  onClick={() => setView(v)}
                  className={cn(
                    'rounded px-3 py-1 text-[12px] font-medium capitalize',
                    view === v ? 'bg-bg-sunken text-fg' : 'text-fg-muted hover:text-fg',
                  )}
                >
                  {v}
                </button>
              ))}
            </div>
            <Button variant="primary" size="sm" icon="plus" onClick={() => setCreating(true)}>
              Add service
            </Button>
          </>
        }
      />

      <div className="space-y-4 p-6">
        <Card className="p-4">
          <div className="flex flex-wrap items-center gap-2">
            <Input
              icon="search"
              placeholder="Search service name"
              value={q}
              onChange={(e) => setQ(e.target.value)}
              containerClassName="flex-1 min-w-[260px]"
              block
            />
            <Select
              selectSize="md"
              value={cat}
              onChange={(e) => setCat(e.target.value)}
              options={[
                { value: 'any', label: 'Any category' },
                { value: 'likes', label: 'Likes' },
                { value: 'follows', label: 'Followers' },
                { value: 'comments', label: 'Comments' },
              ]}
            />
          </div>
        </Card>

        {loading ? (
          <Card className="p-12 text-center text-[13px] text-fg-subtle">Loading…</Card>
        ) : filtered.length === 0 ? (
          <Card>
            <Empty icon="grid" title="No services" subtitle="Click Add service to create one." />
          </Card>
        ) : view === 'table' ? (
          <Card className="p-0">
            <table className="tbl">
              <thead>
                <tr>
                  <th>ID</th>
                  <th>Name</th>
                  <th>Category</th>
                  <th className="text-right">Rate /1k</th>
                  <th className="text-right">Min</th>
                  <th className="text-right">Max</th>
                  <th className="text-right">Coef</th>
                  <th>Active</th>
                  <th />
                </tr>
              </thead>
              <tbody>
                {filtered.map((s) => (
                  <tr key={s.id} className="cursor-pointer" onClick={() => setEditing(s)} style={{ opacity: s.active === false ? 0.55 : 1 }}>
                    <td className="font-mono text-[12px]">#{s.id}</td>
                    <td>
                      <div className="text-[13px] font-medium">{s.name}</div>
                      {s.description && <div className="mt-0.5 text-[11.5px] text-fg-subtle">{s.description}</div>}
                    </td>
                    <td>
                      <Badge tone="muted" size="sm">
                        {s.category}
                      </Badge>
                    </td>
                    <td className="text-right">
                      <Money value={s.rate ?? s.pricePer1000 ?? s.pricePerThousand ?? 0} />
                    </td>
                    <td className="text-right font-mono">{fmtInt(s.min ?? s.minOrder ?? 0)}</td>
                    <td className="text-right font-mono">{fmtInt(s.max ?? s.maxOrder ?? 0)}</td>
                    <td className="text-right font-mono">{(s.conversionCoefficient ?? 1).toFixed(2)}</td>
                    <td onClick={(e) => e.stopPropagation()}>
                      <Switch checked={s.active !== false && s.isActive !== false} onChange={() => {}} />
                    </td>
                    <td>
                      <Icon name="chevron-right" size={14} className="text-fg-dim" />
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </Card>
        ) : (
          <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-3">
            {filtered.map((s) => (
              <Card key={s.id} className="p-5 cursor-pointer" hover onClick={() => setEditing(s)}>
                <Badge tone="muted" size="sm">
                  {s.category}
                </Badge>
                <div className="mt-2 text-[15px] font-semibold">{s.name}</div>
                <div className="mt-1 font-mono text-[11px] text-fg-subtle">SVC #{s.id}</div>
                {s.description && <p className="mt-2 text-[12.5px] text-fg-muted">{s.description}</p>}
                <div className="mt-4 flex items-baseline justify-between border-t border-border pt-3">
                  <Money value={s.rate ?? s.pricePer1000 ?? s.pricePerThousand ?? 0} size="md" />
                  <span className="font-mono text-[11px] text-fg-subtle">/ 1,000</span>
                </div>
              </Card>
            ))}
          </div>
        )}
      </div>

      <ServiceDrawer
        open={!!editing || creating}
        service={editing}
        creating={creating}
        onClose={() => {
          setEditing(null);
          setCreating(false);
        }}
      />
    </>
  );
}

function ServiceDrawer({ open, service, creating, onClose }: { open: boolean; service: Service | null; creating: boolean; onClose: () => void }) {
  const toast = useToast();
  return (
    <Drawer
      open={open}
      onClose={onClose}
      width={640}
      title={creating ? 'Add service' : <span>Edit service <span className="font-mono text-[12px] text-fg-muted">#{service?.id}</span></span>}
      actions={
        <>
          <Button variant="ghost" size="sm" onClick={onClose}>
            Cancel
          </Button>
          <Button variant="primary" size="sm" onClick={() => { toast(creating ? 'Service created.' : 'Saved.', 'success'); onClose(); }}>
            {creating ? 'Create' : 'Save'}
          </Button>
        </>
      }
    >
      <div className="p-6">
        <Field label="Name">
          <Input block defaultValue={service?.name} placeholder="Instagram Likes — Standard" />
        </Field>
        <div className="grid grid-cols-1 gap-x-4 md:grid-cols-2">
          <Field label="Category">
            <Select
              block
              defaultValue={service?.category ?? 'likes'}
              options={[
                { value: 'likes', label: 'Likes' },
                { value: 'follows', label: 'Followers' },
                { value: 'comments', label: 'Comments' },
              ]}
            />
          </Field>
          <Field label="Price per 1,000">
            <Input block type="number" step="0.01" defaultValue={service?.rate ?? service?.pricePer1000 ?? 0} />
          </Field>
          <Field label="Min quantity">
            <Input block type="number" defaultValue={service?.min ?? 50} />
          </Field>
          <Field label="Max quantity">
            <Input block type="number" defaultValue={service?.max ?? 25000} />
          </Field>
          <Field label="Conversion coefficient" hint="Multiplier on final charge">
            <Input block type="number" step="0.01" defaultValue={service?.conversionCoefficient ?? 1} />
          </Field>
          <div>
            <div className="mb-[6px] text-[12.5px] font-medium text-fg-muted">Active</div>
            <div className="flex items-center gap-2">
              <Switch checked={service?.active !== false} onChange={() => {}} />
              <span className="text-[12.5px] text-fg-muted">{service?.active !== false ? 'Available to users' : 'Disabled'}</span>
            </div>
          </div>
        </div>
        <Field label="Description">
          <Textarea block rows={4} defaultValue={service?.description ?? ''} placeholder="Markdown supported. Visible on user-facing service catalog." />
        </Field>
      </div>
    </Drawer>
  );
}
