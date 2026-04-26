import { fmtMoney } from '../../lib/utils';
import { cn } from '../../lib/utils';

interface MoneyProps {
  value: number;
  /** Force a specific text color. Overrides auto sign-coloring. */
  color?: string;
  /** Show explicit `+` sign for positive values. */
  sign?: boolean;
  size?: 'sm' | 'md' | 'lg';
  className?: string;
}

const sizeMap = { sm: 'text-[13px]', md: 'text-[14px]', lg: 'text-[20px]' };

export function Money({ value, color, sign = false, size = 'sm', className }: MoneyProps) {
  const neg = value < 0;
  const pos = sign && value > 0;
  const autoColor = neg ? 'text-danger' : pos ? 'text-success' : 'text-fg';
  return (
    <span
      className={cn('font-mono tabular-nums font-medium', !color && autoColor, sizeMap[size], className)}
      style={color ? { color } : undefined}
    >
      {pos ? '+' : ''}
      {fmtMoney(value)}
    </span>
  );
}
