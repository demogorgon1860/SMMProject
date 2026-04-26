import { type SVGProps } from 'react';

// =====================================================================
// Icon — single component with the union of admin + user icon sets from
// the SMMWorld design prototype. Feather-ish stroke (1.6–1.75px), all
// paths use `currentColor` so they inherit text color.
// =====================================================================

export type IconName =
  // Brand / nav
  | 'logo'
  | 'dashboard'
  | 'orders'
  | 'list'
  | 'plus'
  | 'wallet'
  | 'receipt'
  | 'users'
  | 'user'
  | 'card'
  | 'coin'
  | 'grid'
  | 'bot'
  | 'paper-plane'
  | 'activity'
  | 'settings'
  | 'shield'
  | 'code'
  | 'help'
  | 'menu'
  | 'logout'
  // UI controls
  | 'search'
  | 'bell'
  | 'chevron-up'
  | 'chevron-down'
  | 'chevron-right'
  | 'chevron-left'
  | 'arrow-up'
  | 'arrow-down'
  | 'arrow-right'
  | 'arrow-up-right'
  | 'x'
  | 'check'
  | 'copy'
  | 'eye'
  | 'eye-off'
  | 'sun'
  | 'moon'
  | 'dots'
  | 'external'
  | 'filter'
  | 'download'
  | 'refresh'
  | 'circle'
  | 'warning'
  | 'alert'
  | 'info'
  | 'link'
  | 'play'
  | 'pause'
  | 'zap'
  | 'calendar'
  | 'terminal'
  | 'database'
  | 'mail'
  | 'lock'
  | 'trash'
  | 'spark'
  | 'trending-up'
  // Socials
  | 'instagram'
  | 'tiktok'
  | 'youtube'
  | 'twitter'
  | 'telegram'
  | 'spotify'
  | 'facebook'
  | 'discord'
  // Crypto (stylized — never real logos)
  | 'usdt'
  | 'btc'
  | 'eth'
  | 'ton'
  | 'ltc';

interface IconProps extends Omit<SVGProps<SVGSVGElement>, 'name' | 'stroke'> {
  name: IconName;
  size?: number;
  /** SVG stroke width in user units (e.g. 1.6, 1.75). */
  stroke?: number;
}

export function Icon({ name, size = 16, stroke = 1.75, className, ...rest }: IconProps) {
  const common = {
    width: size,
    height: size,
    viewBox: '0 0 24 24',
    fill: 'none',
    stroke: 'currentColor',
    strokeWidth: stroke,
    strokeLinecap: 'round' as const,
    strokeLinejoin: 'round' as const,
    'aria-hidden': true,
    className,
    ...rest,
  };

  switch (name) {
    // Brand / nav
    case 'logo':
      return (
        <svg {...common}>
          <path d="M4 17 L12 4 L20 17" />
          <path d="M4 17 L20 17" />
          <circle cx="12" cy="11" r="2" fill="currentColor" stroke="none" />
        </svg>
      );
    case 'dashboard':
      return (
        <svg {...common}>
          <rect x="3" y="3" width="7" height="9" rx="1.5" />
          <rect x="14" y="3" width="7" height="5" rx="1.5" />
          <rect x="14" y="12" width="7" height="9" rx="1.5" />
          <rect x="3" y="16" width="7" height="5" rx="1.5" />
        </svg>
      );
    case 'orders':
      return (
        <svg {...common}>
          <path d="M4 7h16M4 12h16M4 17h10" />
        </svg>
      );
    case 'list':
      return (
        <svg {...common}>
          <path d="M8 6h13M8 12h13M8 18h13" />
          <circle cx="4" cy="6" r="1" fill="currentColor" stroke="none" />
          <circle cx="4" cy="12" r="1" fill="currentColor" stroke="none" />
          <circle cx="4" cy="18" r="1" fill="currentColor" stroke="none" />
        </svg>
      );
    case 'plus':
      return (
        <svg {...common}>
          <path d="M12 5v14M5 12h14" />
        </svg>
      );
    case 'wallet':
      return (
        <svg {...common}>
          <rect x="3" y="6" width="18" height="14" rx="2" />
          <path d="M3 10h18" />
          <circle cx="16" cy="15" r="1.5" fill="currentColor" stroke="none" />
        </svg>
      );
    case 'receipt':
      return (
        <svg {...common}>
          <path d="M6 3h12v18l-3-2-3 2-3-2-3 2z" />
          <path d="M9 9h6M9 13h6M9 17h4" />
        </svg>
      );
    case 'users':
      return (
        <svg {...common}>
          <circle cx="9" cy="8" r="3.5" />
          <path d="M3 20c0-3 2.7-5 6-5s6 2 6 5" />
          <circle cx="17" cy="9" r="2.5" />
          <path d="M15 20c0-2 1.8-3.5 4-3.5" />
        </svg>
      );
    case 'user':
      return (
        <svg {...common}>
          <circle cx="12" cy="8" r="4" />
          <path d="M4 21c0-4.4 3.6-8 8-8s8 3.6 8 8" />
        </svg>
      );
    case 'card':
      return (
        <svg {...common}>
          <rect x="3" y="6" width="18" height="13" rx="2" />
          <path d="M3 10h18M7 15h3" />
        </svg>
      );
    case 'coin':
      return (
        <svg {...common}>
          <circle cx="12" cy="12" r="9" />
          <path d="M12 7v10M9.5 9.5c.5-1 1.5-1.5 2.5-1.5 1.5 0 2.5 1 2.5 2s-1 1.8-2.5 2c-1.5.2-2.5 1-2.5 2s1 2 2.5 2c1 0 2-.5 2.5-1.5" />
        </svg>
      );
    case 'grid':
      return (
        <svg {...common}>
          <rect x="3" y="3" width="8" height="8" rx="1.5" />
          <rect x="13" y="3" width="8" height="8" rx="1.5" />
          <rect x="3" y="13" width="8" height="8" rx="1.5" />
          <rect x="13" y="13" width="8" height="8" rx="1.5" />
        </svg>
      );
    case 'bot':
      return (
        <svg {...common}>
          <rect x="4" y="7" width="16" height="12" rx="2.5" />
          <path d="M12 3v4M8 12v2M16 12v2" />
          <circle cx="9.5" cy="12.5" r=".5" fill="currentColor" stroke="none" />
          <circle cx="14.5" cy="12.5" r=".5" fill="currentColor" stroke="none" />
        </svg>
      );
    case 'paper-plane':
      return (
        <svg {...common}>
          <path d="M22 3 11 14M22 3l-7 18-4-7-7-4z" />
        </svg>
      );
    case 'activity':
      return (
        <svg {...common}>
          <path d="M3 12h4l3-8 4 16 3-8h4" />
        </svg>
      );
    case 'settings':
      return (
        <svg {...common}>
          <circle cx="12" cy="12" r="3" />
          <path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 1 1-2.83 2.83l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 1 1-4 0v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 1 1-2.83-2.83l.06-.06A1.65 1.65 0 0 0 4.6 15a1.65 1.65 0 0 0-1.51-1H3a2 2 0 1 1 0-4h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 1 1 2.83-2.83l.06.06a1.65 1.65 0 0 0 1.82.33H9a1.65 1.65 0 0 0 1-1.51V3a2 2 0 1 1 4 0v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 1 1 2.83 2.83l-.06.06a1.65 1.65 0 0 0-.33 1.82V9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 1 1 0 4h-.09a1.65 1.65 0 0 0-1.51 1z" />
        </svg>
      );
    case 'shield':
      return (
        <svg {...common}>
          <path d="M12 3 4 6v6c0 5 3.5 8 8 9 4.5-1 8-4 8-9V6l-8-3z" />
        </svg>
      );
    case 'code':
      return (
        <svg {...common}>
          <path d="M8 7l-5 5 5 5M16 7l5 5-5 5M14 4l-4 16" />
        </svg>
      );
    case 'help':
      return (
        <svg {...common}>
          <circle cx="12" cy="12" r="9" />
          <path d="M9.5 9a2.5 2.5 0 1 1 3.5 2.3c-.7.3-1 1-1 1.7V13M12 17h.01" />
        </svg>
      );
    case 'menu':
      return (
        <svg {...common}>
          <path d="M3 6h18M3 12h18M3 18h18" />
        </svg>
      );
    case 'logout':
      return (
        <svg {...common}>
          <path d="M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4M16 17l5-5-5-5M21 12H9" />
        </svg>
      );

    // UI controls
    case 'search':
      return (
        <svg {...common}>
          <circle cx="11" cy="11" r="7" />
          <path d="m20 20-3.5-3.5" />
        </svg>
      );
    case 'bell':
      return (
        <svg {...common}>
          <path d="M18 16V11a6 6 0 1 0-12 0v5l-2 3h16l-2-3zM10 21a2 2 0 0 0 4 0" />
        </svg>
      );
    case 'chevron-up':
      return (
        <svg {...common}>
          <path d="m6 15 6-6 6 6" />
        </svg>
      );
    case 'chevron-down':
      return (
        <svg {...common}>
          <path d="m6 9 6 6 6-6" />
        </svg>
      );
    case 'chevron-right':
      return (
        <svg {...common}>
          <path d="m9 6 6 6-6 6" />
        </svg>
      );
    case 'chevron-left':
      return (
        <svg {...common}>
          <path d="m15 6-6 6 6 6" />
        </svg>
      );
    case 'arrow-up':
      return (
        <svg {...common}>
          <path d="M12 19V5M5 12l7-7 7 7" />
        </svg>
      );
    case 'arrow-down':
      return (
        <svg {...common}>
          <path d="M12 5v14M5 12l7 7 7-7" />
        </svg>
      );
    case 'arrow-right':
      return (
        <svg {...common}>
          <path d="M5 12h14M13 6l6 6-6 6" />
        </svg>
      );
    case 'arrow-up-right':
      return (
        <svg {...common}>
          <path d="M7 17 17 7M9 7h8v8" />
        </svg>
      );
    case 'x':
      return (
        <svg {...common}>
          <path d="M18 6 6 18M6 6l12 12" />
        </svg>
      );
    case 'check':
      return (
        <svg {...common}>
          <path d="m5 13 4 4L20 6" />
        </svg>
      );
    case 'copy':
      return (
        <svg {...common}>
          <rect x="8" y="8" width="12" height="12" rx="2" />
          <path d="M16 8V6a2 2 0 0 0-2-2H6a2 2 0 0 0-2 2v8a2 2 0 0 0 2 2h2" />
        </svg>
      );
    case 'eye':
      return (
        <svg {...common}>
          <path d="M2 12s3.5-7 10-7 10 7 10 7-3.5 7-10 7S2 12 2 12z" />
          <circle cx="12" cy="12" r="3" />
        </svg>
      );
    case 'eye-off':
      return (
        <svg {...common}>
          <path d="M17.94 17.94A10.94 10.94 0 0 1 12 19c-6.5 0-10-7-10-7a19.89 19.89 0 0 1 4.22-4.73m4.12-1.93A10.94 10.94 0 0 1 12 5c6.5 0 10 7 10 7a19.87 19.87 0 0 1-2.16 3.24M9.9 9.9a3 3 0 1 0 4.2 4.2M2 2l20 20" />
        </svg>
      );
    case 'sun':
      return (
        <svg {...common}>
          <circle cx="12" cy="12" r="4" />
          <path d="M12 2v2M12 20v2M4 12H2M22 12h-2M5 5l1.4 1.4M17.6 17.6 19 19M5 19l1.4-1.4M17.6 6.4 19 5" />
        </svg>
      );
    case 'moon':
      return (
        <svg {...common}>
          <path d="M21 12.8A9 9 0 1 1 11.2 3a7 7 0 0 0 9.8 9.8z" />
        </svg>
      );
    case 'dots':
      return (
        <svg {...common}>
          <circle cx="5" cy="12" r="1.5" fill="currentColor" stroke="none" />
          <circle cx="12" cy="12" r="1.5" fill="currentColor" stroke="none" />
          <circle cx="19" cy="12" r="1.5" fill="currentColor" stroke="none" />
        </svg>
      );
    case 'external':
      return (
        <svg {...common}>
          <path d="M14 4h6v6M10 14 20 4M18 14v6H4V6h6" />
        </svg>
      );
    case 'filter':
      return (
        <svg {...common}>
          <path d="M4 5h16l-6 8v5l-4 2v-7L4 5z" />
        </svg>
      );
    case 'download':
      return (
        <svg {...common}>
          <path d="M12 3v12M6 11l6 6 6-6M4 21h16" />
        </svg>
      );
    case 'refresh':
      return (
        <svg {...common}>
          <path d="M3 12a9 9 0 0 1 15-6.7L21 8M21 3v5h-5M21 12a9 9 0 0 1-15 6.7L3 16M3 21v-5h5" />
        </svg>
      );
    case 'circle':
      return (
        <svg {...common}>
          <circle cx="12" cy="12" r="9" />
        </svg>
      );
    case 'warning':
    case 'alert':
      return (
        <svg {...common}>
          <path d="M12 3 2 20h20L12 3zM12 10v5M12 18v.5" />
        </svg>
      );
    case 'info':
      return (
        <svg {...common}>
          <circle cx="12" cy="12" r="9" />
          <path d="M12 11v5M12 8v.5" />
        </svg>
      );
    case 'link':
      return (
        <svg {...common}>
          <path d="M10 14a5 5 0 0 1 0-7l3-3a5 5 0 0 1 7 7l-1 1" />
          <path d="M14 10a5 5 0 0 1 0 7l-3 3a5 5 0 0 1-7-7l1-1" />
        </svg>
      );
    case 'play':
      return (
        <svg {...common}>
          <path d="M6 4v16l14-8L6 4z" fill="currentColor" stroke="none" />
        </svg>
      );
    case 'pause':
      return (
        <svg {...common}>
          <rect x="6" y="4" width="4" height="16" fill="currentColor" stroke="none" />
          <rect x="14" y="4" width="4" height="16" fill="currentColor" stroke="none" />
        </svg>
      );
    case 'zap':
      return (
        <svg {...common}>
          <path d="m13 2-10 12h7l-1 8 10-12h-7l1-8z" />
        </svg>
      );
    case 'calendar':
      return (
        <svg {...common}>
          <rect x="3" y="5" width="18" height="16" rx="2" />
          <path d="M3 9h18M8 3v4M16 3v4" />
        </svg>
      );
    case 'terminal':
      return (
        <svg {...common}>
          <rect x="3" y="4" width="18" height="16" rx="2" />
          <path d="m7 9 3 3-3 3M13 15h5" />
        </svg>
      );
    case 'database':
      return (
        <svg {...common}>
          <ellipse cx="12" cy="5" rx="8" ry="3" />
          <path d="M4 5v7c0 1.7 3.6 3 8 3s8-1.3 8-3V5M4 12v7c0 1.7 3.6 3 8 3s8-1.3 8-3v-7" />
        </svg>
      );
    case 'mail':
      return (
        <svg {...common}>
          <rect x="3" y="5" width="18" height="14" rx="2" />
          <path d="m3 7 9 6 9-6" />
        </svg>
      );
    case 'lock':
      return (
        <svg {...common}>
          <rect x="4" y="11" width="16" height="10" rx="2" />
          <path d="M8 11V7a4 4 0 0 1 8 0v4" />
        </svg>
      );
    case 'trash':
      return (
        <svg {...common}>
          <path d="M3 6h18M8 6V4a1 1 0 0 1 1-1h6a1 1 0 0 1 1 1v2M6 6l1 14a2 2 0 0 0 2 2h6a2 2 0 0 0 2-2l1-14" />
        </svg>
      );
    case 'spark':
      return (
        <svg {...common}>
          <path d="M12 3v4M12 17v4M3 12h4M17 12h4M5.6 5.6l2.8 2.8M15.6 15.6l2.8 2.8M5.6 18.4l2.8-2.8M15.6 8.4l2.8-2.8" />
        </svg>
      );
    case 'trending-up':
      return (
        <svg {...common}>
          <path d="m3 17 6-6 4 4 8-8M21 7v6h-6" />
        </svg>
      );

    // Socials
    case 'instagram':
      return (
        <svg {...common}>
          <rect x="3" y="3" width="18" height="18" rx="5" />
          <circle cx="12" cy="12" r="4" />
          <circle cx="17.5" cy="6.5" r="1" fill="currentColor" stroke="none" />
        </svg>
      );
    case 'tiktok':
      return (
        <svg {...common}>
          <path d="M14 4v10.5a3.5 3.5 0 1 1-3.5-3.5" />
          <path d="M14 4c.3 2.5 2 4.5 5 5" />
        </svg>
      );
    case 'youtube':
      return (
        <svg {...common}>
          <rect x="2" y="6" width="20" height="12" rx="3" />
          <path d="m10 9 5 3-5 3z" fill="currentColor" stroke="none" />
        </svg>
      );
    case 'twitter':
      return (
        <svg {...common}>
          <path d="M4 4h4l4 6 5-6h3l-7 9 8 11h-4l-5-7-6 7H2l8-10z" />
        </svg>
      );
    case 'telegram':
      return (
        <svg {...common}>
          <path d="m3 11 17-7-3 16-6-3-3 4v-5z" />
          <path d="m10 15 7-8" />
        </svg>
      );
    case 'spotify':
      return (
        <svg {...common}>
          <circle cx="12" cy="12" r="9" />
          <path d="M7 10c4-1 8-.5 11 1M7.5 13c3-.5 6 0 8.5 1.2M8 16c2-.3 4 0 6 .8" />
        </svg>
      );
    case 'facebook':
      return (
        <svg {...common}>
          <path d="M15 3h-3a4 4 0 0 0-4 4v3H5v4h3v7h4v-7h3l1-4h-4V7a1 1 0 0 1 1-1h3z" />
        </svg>
      );
    case 'discord':
      return (
        <svg {...common}>
          <path d="M6 6c3-1 9-1 12 0 2 3 3 8 2 13-2 1-4 2-6 2l-1-2M6 6c-2 3-3 8-2 13 2 1 4 2 6 2l1-2" />
          <circle cx="9" cy="13" r="1.2" fill="currentColor" stroke="none" />
          <circle cx="15" cy="13" r="1.2" fill="currentColor" stroke="none" />
        </svg>
      );

    // Crypto (stylized — never real logos)
    case 'usdt':
      return (
        <svg {...common}>
          <circle cx="12" cy="12" r="9" />
          <path d="M8 9h8M12 9v3M9 13c0 1 1.3 2 3 2s3-1 3-2-1.3-2-3-2" />
        </svg>
      );
    case 'btc':
      return (
        <svg {...common}>
          <circle cx="12" cy="12" r="9" />
          <path d="M10 7v10M13 7v10M8 9h6a2 2 0 0 1 0 4H8M8 13h7a2 2 0 0 1 0 4H8" />
        </svg>
      );
    case 'eth':
      return (
        <svg {...common}>
          <path d="M12 3v18M4 12l8-9 8 9-8 5zM4 12l8 5 8-5-8 9z" />
        </svg>
      );
    case 'ton':
      return (
        <svg {...common}>
          <circle cx="12" cy="12" r="9" />
          <path d="M7 9h10L12 18z" />
        </svg>
      );
    case 'ltc':
      return (
        <svg {...common}>
          <circle cx="12" cy="12" r="9" />
          <path d="m11 6-3 12h9M7 12h6" />
        </svg>
      );

    default:
      return (
        <svg {...common}>
          <circle cx="12" cy="12" r="9" />
        </svg>
      );
  }
}
