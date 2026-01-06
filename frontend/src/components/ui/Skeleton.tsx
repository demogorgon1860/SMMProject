import React from 'react';

interface SkeletonProps {
  className?: string;
  variant?: 'text' | 'circular' | 'rectangular' | 'rounded';
  width?: string | number;
  height?: string | number;
  animation?: 'pulse' | 'shimmer' | 'none';
}

export function Skeleton({
  className = '',
  variant = 'text',
  width,
  height,
  animation = 'pulse',
}: SkeletonProps) {
  const baseStyles = 'bg-dark-200 dark:bg-dark-700';

  const variantStyles = {
    text: 'rounded h-4',
    circular: 'rounded-full',
    rectangular: 'rounded-none',
    rounded: 'rounded-xl',
  };

  const animationStyles = {
    pulse: 'animate-pulse',
    shimmer: 'animate-shimmer bg-gradient-to-r from-dark-200 via-dark-100 to-dark-200 dark:from-dark-700 dark:via-dark-600 dark:to-dark-700 bg-[length:200%_100%]',
    none: '',
  };

  const style: React.CSSProperties = {
    width: width,
    height: height,
  };

  return (
    <div
      className={`${baseStyles} ${variantStyles[variant]} ${animationStyles[animation]} ${className}`}
      style={style}
    />
  );
}

// Predefined skeleton patterns
export function SkeletonText({ lines = 3 }: { lines?: number }) {
  return (
    <div className="space-y-2">
      {Array.from({ length: lines }).map((_, i) => (
        <Skeleton
          key={i}
          variant="text"
          className={i === lines - 1 ? 'w-3/4' : 'w-full'}
        />
      ))}
    </div>
  );
}

export function SkeletonCard() {
  return (
    <div className="bg-white dark:bg-dark-800 rounded-xl p-5 border border-dark-100 dark:border-dark-700">
      <div className="flex items-center gap-4 mb-4">
        <Skeleton variant="circular" width={48} height={48} />
        <div className="flex-1">
          <Skeleton variant="text" className="w-1/2 mb-2" />
          <Skeleton variant="text" className="w-1/3 h-3" />
        </div>
      </div>
      <SkeletonText lines={3} />
    </div>
  );
}

export function SkeletonTable({ rows = 5, cols = 4 }: { rows?: number; cols?: number }) {
  return (
    <div className="bg-white dark:bg-dark-800 rounded-xl border border-dark-100 dark:border-dark-700 overflow-hidden">
      {/* Header */}
      <div className="border-b border-dark-100 dark:border-dark-700 p-4">
        <div className="flex gap-4">
          {Array.from({ length: cols }).map((_, i) => (
            <Skeleton key={i} variant="text" className="flex-1 h-5" />
          ))}
        </div>
      </div>
      {/* Rows */}
      {Array.from({ length: rows }).map((_, rowIndex) => (
        <div
          key={rowIndex}
          className="flex gap-4 p-4 border-b border-dark-100 dark:border-dark-700 last:border-b-0"
        >
          {Array.from({ length: cols }).map((_, colIndex) => (
            <Skeleton key={colIndex} variant="text" className="flex-1" />
          ))}
        </div>
      ))}
    </div>
  );
}

export function SkeletonStats({ count = 4 }: { count?: number }) {
  return (
    <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-4">
      {Array.from({ length: count }).map((_, i) => (
        <div
          key={i}
          className="bg-white dark:bg-dark-800 rounded-xl p-5 border border-dark-100 dark:border-dark-700"
        >
          <Skeleton variant="text" className="w-1/2 h-3 mb-3" />
          <Skeleton variant="text" className="w-3/4 h-8 mb-2" />
          <Skeleton variant="text" className="w-1/3 h-3" />
        </div>
      ))}
    </div>
  );
}
