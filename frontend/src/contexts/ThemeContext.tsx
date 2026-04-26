import React, { createContext, useCallback, useContext, useEffect, useState } from 'react';

// =====================================================================
// SMMWorld theme system — four orthogonal axes:
//   theme    'light' | 'dark'                        (html.dark)
//   accent   'indigo' | 'violet' | 'emerald' | 'amber'  (html[data-accent])
//   density  'comfortable' | 'compact' | 'cozy'     (html[data-density])
//   hero     'A' | 'B'                               (html[data-hero])  -- landing only
//
// All persisted under `smm:*` keys in localStorage.
// FOUC-prevention runs in index.html <script> before first paint.
// =====================================================================

export type Theme = 'light' | 'dark';
export type Accent = 'indigo' | 'violet' | 'emerald' | 'amber';
export type Density = 'comfortable' | 'compact' | 'cozy';
export type HeroVariant = 'A' | 'B';

interface ThemeContextType {
  theme: Theme;
  accent: Accent;
  density: Density;
  hero: HeroVariant;
  toggleTheme: () => void;
  setTheme: (t: Theme) => void;
  setAccent: (a: Accent) => void;
  setDensity: (d: Density) => void;
  setHero: (h: HeroVariant) => void;
}

const KEY = {
  theme: 'smm:theme',
  accent: 'smm:accent',
  density: 'smm:density',
  hero: 'smm:hero',
} as const;

function readInitial<T extends string>(key: string, fallback: T, allowed: readonly T[]): T {
  try {
    const v = localStorage.getItem(key);
    if (v && (allowed as readonly string[]).includes(v)) return v as T;
  } catch {
    /* localStorage may be unavailable */
  }
  return fallback;
}

function readInitialTheme(): Theme {
  try {
    const v = localStorage.getItem(KEY.theme);
    if (v === 'dark' || v === 'light') return v;
  } catch {
    /* noop */
  }
  if (typeof window !== 'undefined' && window.matchMedia('(prefers-color-scheme: dark)').matches) {
    return 'dark';
  }
  return 'light';
}

const ThemeContext = createContext<ThemeContextType | undefined>(undefined);

export function ThemeProvider({ children }: { children: React.ReactNode }) {
  const [theme, setThemeState] = useState<Theme>(readInitialTheme);
  const [accent, setAccentState] = useState<Accent>(() =>
    readInitial<Accent>(KEY.accent, 'indigo', ['indigo', 'violet', 'emerald', 'amber'] as const),
  );
  const [density, setDensityState] = useState<Density>(() =>
    readInitial<Density>(KEY.density, 'comfortable', ['comfortable', 'compact', 'cozy'] as const),
  );
  const [hero, setHeroState] = useState<HeroVariant>(() =>
    readInitial<HeroVariant>(KEY.hero, 'A', ['A', 'B'] as const),
  );

  // Apply theme + persist
  useEffect(() => {
    const root = document.documentElement;
    root.classList.add('theme-transition');
    root.classList.toggle('dark', theme === 'dark');

    try {
      localStorage.setItem(KEY.theme, theme);
    } catch {
      /* noop */
    }

    // Sync the OS-level theme-color meta so PWA chrome matches
    const meta = document.querySelector('meta[name="theme-color"]:not([media])');
    if (meta) meta.setAttribute('content', theme === 'dark' ? '#0a0a0a' : '#fafaf9');

    const t = setTimeout(() => root.classList.remove('theme-transition'), 350);
    return () => clearTimeout(t);
  }, [theme]);

  // Apply accent + persist
  useEffect(() => {
    const root = document.documentElement;
    if (accent === 'indigo') root.removeAttribute('data-accent');
    else root.setAttribute('data-accent', accent);
    try {
      localStorage.setItem(KEY.accent, accent);
    } catch {
      /* noop */
    }
  }, [accent]);

  // Apply density + persist
  useEffect(() => {
    const root = document.documentElement;
    if (density === 'comfortable') root.removeAttribute('data-density');
    else root.setAttribute('data-density', density);
    try {
      localStorage.setItem(KEY.density, density);
    } catch {
      /* noop */
    }
  }, [density]);

  // Apply hero variant + persist
  useEffect(() => {
    const root = document.documentElement;
    if (hero === 'A') root.removeAttribute('data-hero');
    else root.setAttribute('data-hero', hero);
    try {
      localStorage.setItem(KEY.hero, hero);
    } catch {
      /* noop */
    }
  }, [hero]);

  // Follow OS theme only when user hasn't explicitly chosen one this session
  useEffect(() => {
    const mq = window.matchMedia('(prefers-color-scheme: dark)');
    const handler = (e: MediaQueryListEvent) => {
      try {
        if (!localStorage.getItem(KEY.theme)) {
          setThemeState(e.matches ? 'dark' : 'light');
        }
      } catch {
        /* noop */
      }
    };
    mq.addEventListener('change', handler);
    return () => mq.removeEventListener('change', handler);
  }, []);

  const toggleTheme = useCallback(() => {
    setThemeState((p) => (p === 'light' ? 'dark' : 'light'));
  }, []);

  return (
    <ThemeContext.Provider
      value={{
        theme,
        accent,
        density,
        hero,
        toggleTheme,
        setTheme: setThemeState,
        setAccent: setAccentState,
        setDensity: setDensityState,
        setHero: setHeroState,
      }}
    >
      {children}
    </ThemeContext.Provider>
  );
}

export function useTheme() {
  const ctx = useContext(ThemeContext);
  if (!ctx) throw new Error('useTheme must be used within a ThemeProvider');
  return ctx;
}
