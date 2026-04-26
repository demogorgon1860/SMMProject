import { Icon, type IconName } from './Icon';

// Platform color tiles. `mono` renders a muted version for "soon" platforms.
type Cat = 'ig' | 'tt' | 'yt' | 'x' | 'tg' | 'sp' | 'fb' | 'dc';

const map: Record<Cat, { icon: IconName; bg: string; fg: string }> = {
  ig: { icon: 'instagram', bg: 'linear-gradient(135deg,#f9ce34,#ee2a7b,#6228d7)', fg: '#fff' },
  tt: { icon: 'tiktok', bg: '#000', fg: '#fff' },
  yt: { icon: 'youtube', bg: '#ff0033', fg: '#fff' },
  x:  { icon: 'twitter', bg: '#000', fg: '#fff' },
  tg: { icon: 'telegram', bg: '#2ba0de', fg: '#fff' },
  sp: { icon: 'spotify', bg: '#1db954', fg: '#fff' },
  fb: { icon: 'facebook', bg: '#1877f2', fg: '#fff' },
  dc: { icon: 'discord', bg: '#5865f2', fg: '#fff' },
};

interface SocialTileProps {
  cat: Cat;
  size?: number;
  mono?: boolean;
  className?: string;
}

export function SocialTile({ cat, size = 44, mono = false, className }: SocialTileProps) {
  const m = map[cat];
  const bg = mono ? 'var(--bg-sunken)' : m.bg;
  const fg = mono ? 'var(--fg-subtle)' : m.fg;
  const border = mono ? '1px solid var(--border)' : 'none';
  return (
    <div
      className={className}
      style={{
        width: size,
        height: size,
        borderRadius: size > 36 ? 10 : 7,
        background: bg,
        color: fg,
        border,
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        flexShrink: 0,
      }}
    >
      <Icon name={m.icon} size={Math.round(size * 0.56)} stroke={1.8} />
    </div>
  );
}
