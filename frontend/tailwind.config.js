/** @type {import('tailwindcss').Config} */
//
// SMMWorld design tokens — every color is wired to a CSS custom property
// defined in src/index.css. That lets the Tailwind classes (`bg-bg`, `text-fg`,
// `border-border`, `bg-accent`, etc.) react automatically to:
//   - dark mode    (html.dark)
//   - accent picker (html[data-accent="violet|emerald|amber"])
//   - density      (html[data-density="compact|cozy|comfortable"])
//
// If you need to add a new token, add the CSS var first, then expose it here.
export default {
  content: ['./index.html', './src/**/*.{js,ts,jsx,tsx}'],
  darkMode: 'class',
  theme: {
    extend: {
      colors: {
        // Surfaces
        bg: 'var(--bg)',
        'bg-elev': 'var(--bg-elev)',
        'bg-sunken': 'var(--bg-sunken)',
        'bg-deep': 'var(--bg-deep)',

        // Borders
        border: 'var(--border)',
        'border-strong': 'var(--border-strong)',
        'border-deep': 'var(--border-deep)',

        // Foreground (text)
        fg: 'var(--fg)',
        'fg-muted': 'var(--fg-muted)',
        'fg-subtle': 'var(--fg-subtle)',
        'fg-dim': 'var(--fg-dim)',

        // Accent (palette-switchable)
        accent: 'var(--accent)',
        'accent-2': 'var(--accent-2)',
        'accent-soft': 'var(--accent-soft)',
        'accent-fg': 'var(--accent-fg)',

        // Semantic
        success: 'var(--success)',
        'success-soft': 'var(--success-soft)',
        warn: 'var(--warn)',
        'warn-soft': 'var(--warn-soft)',
        danger: 'var(--danger)',
        'danger-soft': 'var(--danger-soft)',
        info: 'var(--info)',
        'info-soft': 'var(--info-soft)',
        violet: 'var(--violet)',
        'violet-soft': 'var(--violet-soft)',

        // Interaction state
        'row-hover': 'var(--row-hover)',
        selected: 'var(--selected)',
      },
      fontFamily: {
        sans: ['Inter', 'ui-sans-serif', 'system-ui', '-apple-system', 'sans-serif'],
        mono: ['"JetBrains Mono"', 'ui-monospace', 'SFMono-Regular', 'Menlo', 'monospace'],
      },
      fontSize: {
        '2xs': ['0.625rem', { lineHeight: '0.875rem' }],
        // Marketing display scale (used on landing/legal/pricing/hero)
        'display-1': ['clamp(44px, 6.4vw, 72px)', { lineHeight: '1.04', letterSpacing: '-0.035em', fontWeight: '700' }],
        'display-2': ['clamp(34px, 4.4vw, 48px)', { lineHeight: '1.08', letterSpacing: '-0.028em', fontWeight: '700' }],
        'display-3': ['clamp(24px, 2.6vw, 32px)', { lineHeight: '1.15', letterSpacing: '-0.02em', fontWeight: '600' }],
      },
      borderRadius: {
        // 6/8/12/16/20 — design tokens
        'sm': '6px',
        'md': '8px',
        'lg': '12px',
        'xl': '16px',
        '2xl': '20px',
      },
      boxShadow: {
        'pop': '0 12px 24px -16px rgba(0, 0, 0, 0.12)',
        'modal': '0 20px 50px -12px rgba(0, 0, 0, 0.25)',
        'drawer': '-20px 0 50px -12px rgba(0, 0, 0, 0.15)',
        'menu': '0 20px 40px -12px rgba(0, 0, 0, 0.20)',
      },
      ringColor: {
        DEFAULT: 'var(--ring)',
      },
      animation: {
        'fade-in': 'fadeIn 0.28s ease-out both',
        'pulse-dot': 'pulseDot 1.6s ease-in-out infinite',
        'shimmer': 'shimmer 1.8s linear infinite',
        'spin-slow': 'spin 1.1s linear infinite',
        'ticker': 'ticker 40s linear infinite',
      },
      keyframes: {
        fadeIn: {
          '0%': { opacity: '0', transform: 'translateY(6px)' },
          '100%': { opacity: '1', transform: 'translateY(0)' },
        },
        pulseDot: {
          '0%, 100%': { opacity: '1', transform: 'scale(1)' },
          '50%': { opacity: '0.4', transform: 'scale(0.7)' },
        },
        shimmer: {
          '0%': { backgroundPosition: '-1200px 0' },
          '100%': { backgroundPosition: '1200px 0' },
        },
        ticker: {
          '0%': { transform: 'translateX(0)' },
          '100%': { transform: 'translateX(-50%)' },
        },
      },
      backgroundImage: {
        'gradient-radial': 'radial-gradient(var(--tw-gradient-stops))',
        'gradient-conic': 'conic-gradient(from 180deg at 50% 50%, var(--tw-gradient-stops))',
      },
      transitionDuration: {
        '120': '120ms',
        '180': '180ms',
        '400': '400ms',
      },
      backdropBlur: {
        xs: '2px',
      },
    },
  },
  plugins: [],
};
