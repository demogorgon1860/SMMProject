import { type CSSProperties } from 'react';
import { cn } from '../../lib/utils';

interface SkeletonProps {
  /** Width: number → px, string → CSS value (`100%`, `4rem`, etc.). */
  w?: number | string;
  /** Height: number → px. */
  h?: number;
  rounded?: number;
  className?: string;
}

export function Skeleton({ w = '100%', h = 12, rounded = 4, className }: SkeletonProps) {
  const style: CSSProperties = {
    width: typeof w === 'number' ? `${w}px` : w,
    height: `${h}px`,
    borderRadius: `${rounded}px`,
  };
  return <span className={cn('shimmer block', className)} style={style} />;
}

export function SkeletonText({ lines = 3 }: { lines?: number }) {
  return (
    <div className="space-y-[6px]">
      {Array.from({ length: lines }).map((_, i) => (
        <Skeleton key={i} h={10} w={i === lines - 1 ? '70%' : '100%'} />
      ))}
    </div>
  );
}
