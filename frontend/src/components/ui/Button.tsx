import React from 'react';
import { Loader2 } from 'lucide-react';

type ButtonVariant = 'primary' | 'secondary' | 'outline' | 'ghost' | 'danger' | 'success';
type ButtonSize = 'xs' | 'sm' | 'md' | 'lg' | 'xl';

interface ButtonProps extends React.ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: ButtonVariant;
  size?: ButtonSize;
  loading?: boolean;
  icon?: React.ReactNode;
  iconPosition?: 'left' | 'right';
  fullWidth?: boolean;
  children?: React.ReactNode;
}

const variantStyles: Record<ButtonVariant, string> = {
  primary: `
    bg-primary-600 text-white
    hover:bg-primary-700
    focus:ring-primary-500/50
    dark:bg-primary-500 dark:hover:bg-primary-600
    shadow-sm hover:shadow-md
  `,
  secondary: `
    bg-dark-100 text-dark-700
    hover:bg-dark-200
    focus:ring-dark-500/30
    dark:bg-dark-700 dark:text-dark-100 dark:hover:bg-dark-600
  `,
  outline: `
    border-2 border-dark-200 text-dark-700 bg-transparent
    hover:bg-dark-50 hover:border-dark-300
    focus:ring-dark-500/30
    dark:border-dark-600 dark:text-dark-200 dark:hover:bg-dark-800 dark:hover:border-dark-500
  `,
  ghost: `
    text-dark-600 bg-transparent
    hover:bg-dark-100 hover:text-dark-900
    focus:ring-dark-500/30
    dark:text-dark-300 dark:hover:bg-dark-800 dark:hover:text-dark-100
  `,
  danger: `
    bg-red-600 text-white
    hover:bg-red-700
    focus:ring-red-500/50
    dark:bg-red-500 dark:hover:bg-red-600
    shadow-sm hover:shadow-md
  `,
  success: `
    bg-accent-600 text-white
    hover:bg-accent-700
    focus:ring-accent-500/50
    dark:bg-accent-500 dark:hover:bg-accent-600
    shadow-sm hover:shadow-md
  `,
};

const sizeStyles: Record<ButtonSize, string> = {
  xs: 'px-2.5 py-1 text-xs rounded-md gap-1',
  sm: 'px-3 py-1.5 text-sm rounded-lg gap-1.5',
  md: 'px-4 py-2 text-sm rounded-lg gap-2',
  lg: 'px-5 py-2.5 text-base rounded-xl gap-2',
  xl: 'px-6 py-3 text-lg rounded-xl gap-2.5',
};

export function Button({
  variant = 'primary',
  size = 'md',
  loading = false,
  icon,
  iconPosition = 'left',
  fullWidth = false,
  disabled,
  className = '',
  children,
  ...props
}: ButtonProps) {
  const isDisabled = disabled || loading;

  return (
    <button
      className={`
        inline-flex items-center justify-center font-medium
        transition-all duration-200 ease-out
        focus:outline-none focus:ring-2 focus:ring-offset-2
        dark:focus:ring-offset-dark-900
        disabled:opacity-50 disabled:cursor-not-allowed disabled:transform-none
        active:scale-[0.98]
        ${variantStyles[variant]}
        ${sizeStyles[size]}
        ${fullWidth ? 'w-full' : ''}
        ${className}
      `}
      disabled={isDisabled}
      {...props}
    >
      {loading ? (
        <>
          <Loader2 className="animate-spin" size={size === 'xs' ? 12 : size === 'sm' ? 14 : 16} />
          {children && <span>{children}</span>}
        </>
      ) : (
        <>
          {icon && iconPosition === 'left' && icon}
          {children}
          {icon && iconPosition === 'right' && icon}
        </>
      )}
    </button>
  );
}
