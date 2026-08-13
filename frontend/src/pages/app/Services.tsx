import { useEffect, useMemo, useState } from 'react';
import { Link } from 'react-router-dom';
import { Badge, Button, Card, Empty, Input } from '../../components/ui';
import { serviceAPI } from '../../services/api';
import type { Service } from '../../types';
import { fmtMoney } from '../../lib/utils';
import { unwrapList } from '../../lib/api';

// Authenticated client services catalog. Unlike the public /services-list marketing page, this is
// mounted behind auth, so GET /v1/service/services runs with the client's JWT and the backend
// returns ONLY the services their account is granted (user_service_access allow-list). A client
// with no explicit assignment sees the full active catalog (unrestricted default); admins see all.
// This is the per-client "different services per group" view the feature is about.
export function AppServicesPage() {
  const [services, setServices] = useState<Service[]>([]);
  const [loading, setLoading] = useState(true);
  const [err, setErr] = useState<string | null>(null);
  const [q, setQ] = useState('');

  useEffect(() => {
    let cancelled = false;
    serviceAPI
      .list()
      .then((data: unknown) => {
        if (cancelled) return;
        // /v1/service/services may wrap the array in { success, data } or { services }.
        setServices(unwrapList<Service>(data, ['services']));
      })
      .catch(() => setErr('Could not load your services right now.'))
      .finally(() => !cancelled && setLoading(false));
    return () => {
      cancelled = true;
    };
  }, []);

  const filtered = useMemo(() => {
    const needle = q.trim().toLowerCase();
    return services
      .filter(
        (s) =>
          !needle ||
          (s.name?.toLowerCase() ?? '').includes(needle) ||
          String(s.id).includes(needle),
      )
      .slice()
      .sort((a, b) => priceOf(a) - priceOf(b));
  }, [services, q]);

  return (
    <div className="container-app py-8">
      <div className="flex flex-wrap items-center gap-3">
        <h1 className="text-[24px] font-bold tracking-[-0.02em]">Services</h1>
        <span className="font-mono text-[12px] text-fg-subtle">
          {loading ? '…' : `${services.length} available`}
        </span>
        <div className="ml-auto">
          <Link to="/new-order">
            <Button variant="primary" size="md" icon="plus">
              New order
            </Button>
          </Link>
        </div>
      </div>
      <p className="mt-1 max-w-[640px] text-[13px] text-fg-subtle">
        Services available to your account. Rates include markup and match what you're billed at
        order time.
      </p>

      <Card className="mt-6 p-0">
        <div className="px-3 py-2">
          <Input
            block
            icon="search"
            aria-label="Search services"
            placeholder="Search services"
            value={q}
            onChange={(e) => setQ(e.target.value)}
          />
        </div>
        {loading ? (
          <div className="p-12 text-center text-[13px] text-fg-subtle">Loading services…</div>
        ) : err ? (
          <div className="p-12 text-center text-[13px] text-danger">{err}</div>
        ) : filtered.length === 0 ? (
          <Empty
            icon="list"
            title={q ? 'No matches' : 'No services available'}
            subtitle={
              q
                ? `Nothing matches "${q}".`
                : 'Your account has no services assigned yet — contact support.'
            }
          />
        ) : (
          <div className="overflow-x-auto">
            <table className="tbl-u min-w-[720px]">
              <thead>
                <tr>
                  <th>ID</th>
                  <th>Service</th>
                  <th>Category</th>
                  <th className="text-right">Min</th>
                  <th className="text-right">Max</th>
                  <th className="text-right">Rate /1k</th>
                  <th />
                </tr>
              </thead>
              <tbody>
                {filtered.map((s) => (
                  <tr key={s.id}>
                    <td className="font-mono text-[12px] text-fg-muted">#{s.id}</td>
                    <td>
                      <div className="font-medium">{s.name}</div>
                      {s.description && (
                        <div className="mt-0.5 text-[12px] text-fg-subtle">{s.description}</div>
                      )}
                    </td>
                    <td>
                      <Badge tone="muted" size="sm">
                        {prettyCategory(s.category)}
                      </Badge>
                    </td>
                    <td className="text-right font-mono">
                      {(s.min ?? s.minOrder ?? 0).toLocaleString()}
                    </td>
                    <td className="text-right font-mono">
                      {(s.max ?? s.maxOrder ?? 0).toLocaleString()}
                    </td>
                    <td className="text-right font-mono">{fmtMoney(priceOf(s))}</td>
                    <td className="text-right">
                      <Link to="/new-order">
                        <Button size="sm" variant="secondary" iconRight="arrow-right">
                          Order
                        </Button>
                      </Link>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </Card>
    </div>
  );
}

function priceOf(s: Service): number {
  return s.rate ?? s.pricePer1000 ?? s.pricePerThousand ?? 0;
}

function prettyCategory(cat?: string | null): string {
  if (!cat) return '—';
  return cat
    .replace(/_/g, ' ')
    .toLowerCase()
    .replace(/\b\w/g, (c) => c.toUpperCase());
}
