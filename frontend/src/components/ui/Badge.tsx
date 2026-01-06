import React from 'react';

type BadgeVariant = 'default' | 'primary' | 'success' | 'warning' | 'danger' | 'info';
type BadgeSize = 'xs' | 'sm' | 'md';

interface BadgeProps {
  children: React.ReactNode;
  variant?: BadgeVariant;
  size?: BadgeSize;
  dot?: boolean;
  icon?: React.ReactNode;
  className?: string;
}

const variantStyles: Record<BadgeVariant, string> = {
  default: `
    bg-dark-100 text-dark-700
    dark:bg-dark-700 dark:text-dark-200
  `,
  primary: `
    bg-primary-100 text-primary-700
    dark:bg-primary-900/50 dark:text-primary-300
  `,
  success: `
    bg-accent-100 text-accent-700
    dark:bg-accent-900/50 dark:text-accent-300
  `,
  warning: `
    bg-amber-100 text-amber-700
    dark:bg-amber-900/50 dark:text-amber-300
  `,
  danger: `
    bg-red-100 text-red-700
    dark:bg-red-900/50 dark:text-red-300
  `,
  info: `
    bg-blue-100 text-blue-700
    dark:bg-blue-900/50 dark:text-blue-300
  `,
};

const dotStyles: Record<BadgeVariant, string> = {
  default: 'bg-dark-500',
  primary: 'bg-primary-500',
  success: 'bg-accent-500',
  warning: 'bg-amber-500',
  danger: 'bg-red-500',
  info: 'bg-blue-500',
};

const sizeStyles: Record<BadgeSize, string> = {
  xs: 'px-1.5 py-0.5 text-2xs rounded',
  sm: 'px-2 py-0.5 text-xs rounded-md',
  md: 'px-2.5 py-1 text-sm rounded-lg',
};

export function Badge({
  children,
  variant = 'default',
  size = 'sm',
  dot = false,
  icon,
  className = '',
}: BadgeProps) {
  return (
    <span
      className={`
        inline-flex items-center gap-1.5 font-medium
        ${variantStyles[variant]}
        ${sizeStyles[size]}
        ${className}
      `}
    >
      {dot && (
        <span
          className={`w-1.5 h-1.5 rounded-full ${dotStyles[variant]} animate-pulse-soft`}
        />
      )}
      {icon && icon}
      {children}
    </span>
  );
}

// Predefined status badges for orders
type OrderStatus = 'pending' | 'processing' | 'in_progress' | 'completed' | 'partial' | 'cancelled' | 'failed' | 'refunded';

const statusConfig: Record<OrderStatus, { variant: BadgeVariant; label: string }> = {
  pending: { variant: 'warning', label: 'Pending' },
  processing: { variant: 'info', label: 'Processing' },
  in_progress: { variant: 'primary', label: 'In Progress' },
  completed: { variant: 'success', label: 'Completed' },
  partial: { variant: 'warning', label: 'Partial' },
  cancelled: { variant: 'danger', label: 'Cancelled' },
  failed: { variant: 'danger', label: 'Failed' },
  refunded: { variant: 'default', label: 'Refunded' },
};

interface StatusBadgeProps {
  status: string;
  size?: BadgeSize;
  className?: string;
}

export function StatusBadge({ status, size = 'sm', className = '' }: StatusBadgeProps) {
  const normalizedStatus = status.toLowerCase().replace(/-/g, '_') as OrderStatus;
  const config = statusConfig[normalizedStatus] || { variant: 'default' as BadgeVariant, label: status };

  return (
    <Badge variant={config.variant} size={size} dot className={className}>
      {config.label}
    </Badge>
  );
}
