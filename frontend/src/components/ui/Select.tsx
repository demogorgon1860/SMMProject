import { forwardRef, type SelectHTMLAttributes } from 'react';
import { Icon } from './Icon';
import { cn } from '../../lib/utils';

// Accepts both `string[]` and `{value, label}[]` for convenience.
export type SelectOption = string | { value: string; label: string };
export type SelectSize = 'sm' | 'md' | 'lg';

interface SelectProps extends Omit<SelectHTMLAttributes<HTMLSelectElement>, 'size'> {
  selectSize?: SelectSize;
  block?: boolean;
  options: readonly SelectOption[];
  placeholder?: string;
  containerClassName?: string;
}

const sizeMap: Record<SelectSize, { h: string; fs: string; pad: string }> = {
  sm: { h: 'h-[30px]', fs: 'text-[12.5px]', pad: 'pl-[10px] pr-[28px]' },
  md: { h: 'h-[38px]', fs: 'text-[13.5px]', pad: 'pl-[12px] pr-[30px]' },
  lg: { h: 'h-[46px]', fs: 'text-[15px]', pad: 'pl-[14px] pr-[34px]' },
};

export const Select = forwardRef<HTMLSelectElement, SelectProps>(function Select(
  { selectSize = 'md', block, options, placeholder, containerClassName, className, ...rest },
  ref,
) {
  const sz = sizeMap[selectSize];
  return (
    <div className={cn('relative inline-block', block && 'block w-full', containerClassName)}>
      <select
        ref={ref}
        className={cn(
          'appearance-none w-full rounded-md border bg-bg-elev text-fg cursor-pointer',
          'border-border-strong outline-none',
          'focus-visible:ring-2 focus-visible:ring-offset-1 focus-visible:[--tw-ring-color:var(--ring)]',
          sz.h,
          sz.fs,
          sz.pad,
          className,
        )}
        {...rest}
      >
        {placeholder !== undefined && (
          <option value="" disabled>
            {placeholder}
          </option>
        )}
        {options.map((o) =>
          typeof o === 'string' ? (
            <option key={o} value={o}>
              {o}
            </option>
          ) : (
            <option key={o.value} value={o.value}>
              {o.label}
            </option>
          ),
        )}
      </select>
      <Icon
        name="chevron-down"
        size={14}
        className="pointer-events-none absolute right-[10px] top-1/2 -translate-y-1/2 text-fg-subtle"
      />
    </div>
  );
});
