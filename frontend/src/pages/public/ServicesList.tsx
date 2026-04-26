import { useEffect, useMemo, useState } from 'react';
import { Link } from 'react-router-dom';
import { Badge, Button, Card, Icon, Input, SocialTile } from '../../components/ui';
import { serviceAPI } from '../../services/api';
import type { Service } from '../../types';
import { fmtMoney } from '../../lib/utils';

// Public services catalog. Read-only, no auth required.
// Categories: Instagram is live. Other platforms render a "soon" card.
//
// The live catalog has duplicate rows for some services (an older $80/$60
// copy alongside the current $90/$70 one), and a single legacy YouTube
// entry that's no longer relevant. We dedupe in-browser by name, keeping
// the higher-priced row, and drop everything that isn't Instagram-prefixed.
export function ServicesListPage() {
  const [services, setServices] = useState<Service[]>([]);
  const [loading, setLoading] = useState(true);
  const [err, setErr] = useState<string | null>(null);
  const [cat, setCat] = useState<'ig' | 'tt' | 'yt' | 'x' | 'tg' | 'fb'>('ig');
  const [q, setQ] = useState('');

  useEffect(() => {
    let cancelled = false;
    serviceAPI
      .list()
      .then((data: unknown) => {
        if (cancelled) return;
        const arr: Service[] = Array.isArray(data) ? (data as Service[]) : (data as { content?: Service[] })?.content ?? [];
        setServices(arr);
      })
      .catch(() => setErr('Could not load services right now.'))
      .finally(() => !cancelled && setLoading(false));
    return () => {
      cancelled = true;
    };
  }, []);

  const cleanInsta = useMemo(() => dedupeInstagram(services), [services]);

  const filtered = cleanInsta.filter((s) =>
    (s.name?.toLowerCase() ?? '').includes(q.trim().toLowerCase()),
  );

  return (
    <div className="container-app py-14">
      <div className="eyebrow">Catalog</div>
      <h1 className="display-2 mt-2">Every service we run.</h1>
      <p className="lede mt-3 max-w-[640px]">
        Instagram is live with {cleanInsta.length} services across Likes, Followers, and Comments.
        Other platforms come online as we add them.
      </p>

      <div className="mt-8 flex flex-wrap items-center gap-2">
        <CatChip active={cat === 'ig'} onClick={() => setCat('ig')} cat="ig" label="Instagram" live />
        {(['tt', 'yt', 'x', 'tg', 'fb'] as const).map((c) => (
          <CatChip key={c} active={cat === c} onClick={() => setCat(c)} cat={c} label={catLabel(c)} />
        ))}
        <div className="ml-auto w-full max-w-[260px]">
          <Input block icon="search" placeholder="Search services" value={q} onChange={(e) => setQ(e.target.value)} />
        </div>
      </div>

      <div className="mt-6">
        {cat !== 'ig' ? (
          <SoonCard cat={cat} />
        ) : (
          <Card className="p-0">
            {loading ? (
              <div className="p-12 text-center text-[13px] text-fg-subtle">Loading services…</div>
            ) : err ? (
              <div className="p-12 text-center text-[13px] text-danger">{err}</div>
            ) : filtered.length === 0 ? (
              <div className="p-12 text-center text-[13px] text-fg-subtle">No services match "{q}".</div>
            ) : (
              <table className="tbl-u">
                <thead>
                  <tr>
                    <th>ID</th>
                    <th>Service</th>
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
                        {s.description && <div className="mt-0.5 text-[12px] text-fg-subtle">{s.description}</div>}
                      </td>
                      <td className="text-right font-mono">{(s.min ?? s.minOrder ?? 0).toLocaleString()}</td>
                      <td className="text-right font-mono">{(s.max ?? s.maxOrder ?? 0).toLocaleString()}</td>
                      <td className="text-right font-mono">
                        {fmtMoney(priceOf(s))}
                      </td>
                      <td className="text-right">
                        <Link to="/login">
                          <Button size="sm" variant="secondary" iconRight="arrow-right">
                            Order
                          </Button>
                        </Link>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
          </Card>
        )}
      </div>
    </div>
  );
}

// ---------------------------------------------------------------------
// Dedupe + filter logic
// ---------------------------------------------------------------------

function priceOf(s: Service): number {
  return s.rate ?? s.pricePer1000 ?? s.pricePerThousand ?? 0;
}

/**
 * Keep only Instagram services, drop name-duplicates by keeping the
 * higher-priced row, sort by price ascending then name.
 */
function dedupeInstagram(services: Service[]): Service[] {
  const insta = services.filter((s) => {
    const cat = (s.category ?? '').toUpperCase();
    // Match both legacy single-word "Instagram" and the new
    // INSTAGRAM_LIKES / INSTAGRAM_FOLLOWERS / INSTAGRAM_COMMENTS values.
    return cat === 'INSTAGRAM' || cat.startsWith('INSTAGRAM_');
  });

  const byName = new Map<string, Service>();
  for (const s of insta) {
    const key = (s.name ?? '').trim();
    if (!key) continue;
    const existing = byName.get(key);
    if (!existing || priceOf(s) > priceOf(existing)) {
      byName.set(key, s);
    }
  }

  return Array.from(byName.values()).sort((a, b) => {
    const ap = priceOf(a);
    const bp = priceOf(b);
    if (ap !== bp) return ap - bp;
    return (a.name ?? '').localeCompare(b.name ?? '');
  });
}

function catLabel(c: 'tt' | 'yt' | 'x' | 'tg' | 'fb'): string {
  return { tt: 'TikTok', yt: 'YouTube', x: 'Twitter', tg: 'Telegram', fb: 'Facebook' }[c];
}

function CatChip({
  active,
  onClick,
  cat,
  label,
  live,
}: {
  active: boolean;
  onClick: () => void;
  cat: 'ig' | 'tt' | 'yt' | 'x' | 'tg' | 'fb';
  label: string;
  live?: boolean;
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      className={`inline-flex items-center gap-2 rounded-full border px-3 py-[6px] text-[13px] font-medium transition-colors ${
        active ? 'border-accent bg-accent-soft text-accent-fg' : 'border-border bg-bg-elev text-fg-muted hover:bg-bg-sunken'
      }`}
    >
      <SocialTile cat={cat} size={20} mono={!live} />
      {label}
      {!live && <span className="font-mono text-[10.5px] text-fg-dim">soon</span>}
    </button>
  );
}

function SoonCard({ cat }: { cat: 'tt' | 'yt' | 'x' | 'tg' | 'fb' }) {
  return (
    <Card className="flex items-center gap-6 p-12">
      <SocialTile cat={cat} size={64} mono />
      <div className="min-w-0">
        <Badge tone="muted" size="md">
          Soon
        </Badge>
        <div className="mt-2 text-[20px] font-semibold tracking-[-0.015em]">{catLabel(cat)} services aren't live yet.</div>
        <p className="mt-1 text-[14px] text-fg-muted">
          We bring platforms online as we add them to the network. Subscribe to the changelog to be
          notified.
        </p>
        <Link to="/services-list?cat=ig" className="mt-3 inline-flex items-center gap-1 text-[13px] font-medium text-accent hover:underline">
          Browse Instagram services <Icon name="arrow-right" size={13} />
        </Link>
      </div>
    </Card>
  );
}
