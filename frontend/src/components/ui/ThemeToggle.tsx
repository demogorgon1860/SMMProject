import React from 'react';
import { Sun, Moon, Monitor } from 'lucide-react';
import { useTheme } from '../../contexts/ThemeContext';

interface ThemeToggleProps {
  size?: 'sm' | 'md' | 'lg';
  showLabel?: boolean;
}

const sizeStyles = {
  sm: 'p-1.5',
  md: 'p-2',
  lg: 'p-2.5',
};

const iconSizes = {
  sm: 16,
  md: 18,
  lg: 20,
};

export function ThemeToggle({ size = 'md', showLabel = false }: ThemeToggleProps) {
  const { theme, toggleTheme } = useTheme();

  return (
    <button
      onClick={toggleTheme}
      className={`
        ${sizeStyles[size]}
        inline-flex items-center gap-2
        rounded-xl
        text-dark-500 hover:text-dark-700
        dark:text-dark-400 dark:hover:text-dark-200
        bg-dark-100 hover:bg-dark-200
        dark:bg-dark-700 dark:hover:bg-dark-600
        transition-all duration-200
        focus:outline-none focus:ring-2 focus:ring-primary-500/30
      `}
      title={theme === 'light' ? 'Switch to dark mode' : 'Switch to light mode'}
    >
      {theme === 'light' ? (
        <Moon size={iconSizes[size]} />
      ) : (
        <Sun size={iconSizes[size]} />
      )}
      {showLabel && (
        <span className="text-sm font-medium">
          {theme === 'light' ? 'Dark' : 'Light'}
        </span>
      )}
    </button>
  );
}

// Advanced theme selector with system option
interface ThemeSelectorProps {
  className?: string;
}

export function ThemeSelector({ className = '' }: ThemeSelectorProps) {
  const { theme, setTheme } = useTheme();

  const options = [
    { value: 'light', icon: Sun, label: 'Light' },
    { value: 'dark', icon: Moon, label: 'Dark' },
  ];

  return (
    <div className={`inline-flex rounded-xl bg-dark-100 dark:bg-dark-800 p-1 ${className}`}>
      {options.map((option) => {
        const Icon = option.icon;
        const isActive = theme === option.value;

        return (
          <button
            key={option.value}
            onClick={() => setTheme(option.value as 'light' | 'dark')}
            className={`
              inline-flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-sm font-medium
              transition-all duration-200
              ${isActive
                ? 'bg-white dark:bg-dark-700 text-dark-900 dark:text-white shadow-sm'
                : 'text-dark-500 dark:text-dark-400 hover:text-dark-700 dark:hover:text-dark-200'
              }
            `}
          >
            <Icon size={14} />
            {option.label}
          </button>
        );
      })}
    </div>
  );
}
