import React from 'react';

interface CardProps {
  children: React.ReactNode;
  className?: string;
  padding?: 'none' | 'sm' | 'md' | 'lg' | 'xl';
  hover?: boolean;
  onClick?: () => void;
}

const paddingStyles = {
  none: '',
  sm: 'p-3',
  md: 'p-4 sm:p-5',
  lg: 'p-5 sm:p-6',
  xl: 'p-6 sm:p-8',
};

export function Card({
  children,
  className = '',
  padding = 'md',
  hover = false,
  onClick,
}: CardProps) {
  return (
    <div
      className={`
        bg-white dark:bg-dark-800
        border border-dark-100 dark:border-dark-700
        rounded-xl
        shadow-soft dark:shadow-dark-soft
        ${paddingStyles[padding]}
        ${hover ? 'hover:shadow-soft-lg hover:border-dark-200 dark:hover:border-dark-600 transition-all duration-200 cursor-pointer' : ''}
        ${onClick ? 'cursor-pointer' : ''}
        ${className}
      `}
      onClick={onClick}
    >
      {children}
    </div>
  );
}

interface CardHeaderProps {
  children: React.ReactNode;
  className?: string;
  action?: React.ReactNode;
}

export function CardHeader({ children, className = '', action }: CardHeaderProps) {
  return (
    <div className={`flex items-center justify-between mb-4 ${className}`}>
      <div>{children}</div>
      {action && <div>{action}</div>}
    </div>
  );
}

interface CardTitleProps {
  children: React.ReactNode;
  className?: string;
  subtitle?: string;
}

export function CardTitle({ children, className = '', subtitle }: CardTitleProps) {
  return (
    <div>
      <h3 className={`text-lg font-semibold text-dark-900 dark:text-white ${className}`}>
        {children}
      </h3>
      {subtitle && (
        <p className="text-sm text-dark-500 dark:text-dark-400 mt-0.5">{subtitle}</p>
      )}
    </div>
  );
}

interface CardContentProps {
  children: React.ReactNode;
  className?: string;
}

export function CardContent({ children, className = '' }: CardContentProps) {
  return <div className={className}>{children}</div>;
}

interface CardFooterProps {
  children: React.ReactNode;
  className?: string;
}

export function CardFooter({ children, className = '' }: CardFooterProps) {
  return (
    <div className={`mt-4 pt-4 border-t border-dark-100 dark:border-dark-700 ${className}`}>
      {children}
    </div>
  );
}
