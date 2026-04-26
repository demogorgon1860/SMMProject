import { type ButtonHTMLAttributes, forwardRef, type ReactNode } from 'react';
import { Icon, type IconName } from './Icon';
import { cn } from '../../lib/utils';

// =====================================================================
// Button — covers both admin (dense) and user (marketing) prototypes.
// Variants: primary | secondary | ghost | danger | warn | success | soft
//           ghost-dark | on-dark | outline-dark   (for hero/auth dark sections)
//           link
// Sizes:    sm | md | lg | xl
// Optional: icon | iconRight | loading (spins refresh icon) | block
// =====================================================================

export type ButtonVariant =
  | 'primary'
  | 'secondary'
  | 'ghost'
  | 'danger'
  | 'warn'
  | 'success'
  | 'soft'
  | 'ghost-dark'
  | 'on-dark'
  | 'outline-dark'
  | 'link';

export type ButtonSize = 'sm' | 'md' | 'lg' | 'xl';

interface ButtonProps extends Omit<ButtonHTMLAttributes<HTMLButtonElement>, 'children'> {
  variant?: ButtonVariant;
  size?: ButtonSize;
  icon?: IconName;
  iconRight?: IconName;
  loading?: boolean;
  block?: boolean;
  children?: ReactNode;
}

const sizeStyles: Record<ButtonSize, string> = {
  sm: 'h-[30px] px-[10px] text-[12.5px] gap-[6px] rounded-[6px]',
  md: 'h-[36px] px-[14px] text-[13.5px] gap-[8px] rounded-[8px]',
  lg: 'h-[44px] px-[20px] text-[15px] gap-[10px] rounded-[10px]',
  xl: 'h-[52px] px-[26px] text-[16px] gap-[12px] rounded-[12px]',
};

const variantStyles: Record<ButtonVariant, string> = {
  primary: 'bg-accent text-white border border-accent hover:brightness-110',
  secondary: 'bg-bg-elev text-fg border border-border-strong hover:bg-bg-sunken',
  ghost: 'bg-transparent text-fg-muted border border-transparent hover:bg-bg-sunken hover:text-fg',
  danger: 'bg-bg-elev text-danger border border-border-strong hover:bg-danger-soft',
  warn: 'bg-warn text-white border border-warn hover:brightness-110',
  success: 'bg-success text-white border border-success hover:brightness-110',
  soft: 'bg-accent-soft text-accent-fg border border-accent-soft hover:brightness-95',
  'ghost-dark': 'bg-transparent text-white border border-white/15 hover:bg-white/5',
  'on-dark': 'bg-white text-[#0a0a0a] border border-white hover:brightness-95',
  'outline-dark': 'bg-transparent text-white border border-white/25 hover:bg-white/5',
  link: 'bg-transparent text-accent border-none px-0 py-0 h-auto hover:underline',
};

const iconSize: Record<ButtonSize, number> = { sm: 13, md: 14, lg: 16, xl: 17 };

export const Button = forwardRef<HTMLButtonElement, ButtonProps>(function Button(
  { variant = 'secondary', size = 'md', icon, iconRight, loading, block, disabled, className, children, type = 'button', ...rest },
  ref,
) {
  const isDisabled = disabled || loading;
  return (
    <button
      ref={ref}
      type={type}
      disabled={isDisabled}
      className={cn(
        'inline-flex items-center justify-center font-medium whitespace-nowrap select-none',
        'transition-[filter,background-color,border-color,transform] duration-120',
        'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-offset-1 focus-visible:[--tw-ring-color:var(--ring)]',
        sizeStyles[size],
        variantStyles[variant],
        block && 'w-full',
        isDisabled && 'opacity-50 cursor-not-allowed pointer-events-none',
        className,
      )}
      {...rest}
    >
      {loading && <Icon name="refresh" size={iconSize[size]} className="spin" />}
      {!loading && icon && <Icon name={icon} size={iconSize[size]} />}
      {children}
      {iconRight && <Icon name={iconRight} size={iconSize[size]} />}
    </button>
  );
});
