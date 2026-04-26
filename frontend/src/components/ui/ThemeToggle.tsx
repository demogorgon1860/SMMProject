import { useTheme, type Accent } from '../../contexts/ThemeContext';
import { Icon } from './Icon';
import { cn } from '../../lib/utils';

// =====================================================================
// ThemeToggle — single icon button (sun/moon swap).
// AccentPicker — 4 colored squares for accent palette (used in
// /profile preferences and the public Tweaks panel).
// =====================================================================

interface ThemeToggleProps {
  size?: 'sm' | 'md';
  className?: string;
}

export function ThemeToggle({ size = 'sm', className }: ThemeToggleProps) {
  const { theme, toggleTheme } = useTheme();
  const px = size === 'md' ? 16 : 14;
  return (
    <button
      type="button"
      onClick={toggleTheme}
      title={theme === 'dark' ? 'Switch to light' : 'Switch to dark'}
      aria-label="Toggle theme"
      className={cn(
        'inline-flex h-[30px] w-[30px] items-center justify-center rounded-md border border-border bg-bg-elev text-fg-muted',
        'transition-colors hover:bg-bg-sunken hover:text-fg',
        'focus-visible:outline-none focus-visible:ring-2 focus-visible:[--tw-ring-color:var(--ring)]',
        className,
      )}
    >
      <Icon name={theme === 'dark' ? 'sun' : 'moon'} size={px} />
    </button>
  );
}

const accentSwatches: Record<Accent, string> = {
  indigo: '#4f46e5',
  violet: '#7c3aed',
  emerald: '#059669',
  amber: '#d97706',
};

export function AccentPicker({ className }: { className?: string }) {
  const { accent, setAccent } = useTheme();
  return (
    <div className={cn('inline-flex items-center gap-[6px] rounded-md border border-border bg-bg-elev p-[3px]', className)}>
      {(Object.keys(accentSwatches) as Accent[]).map((key) => {
        const active = accent === key;
        return (
          <button
            key={key}
            type="button"
            onClick={() => setAccent(key)}
            aria-pressed={active}
            title={`Accent: ${key}`}
            className={cn(
              'h-[20px] w-[20px] rounded-[4px] transition-transform',
              active ? 'ring-2 ring-offset-2 ring-offset-bg-elev [--tw-ring-color:var(--accent)] scale-105' : 'hover:scale-105',
            )}
            style={{ background: accentSwatches[key] }}
          />
        );
      })}
    </div>
  );
}
