import { forwardRef, type InputHTMLAttributes, type ReactNode } from 'react';
import { Icon, type IconName } from './Icon';
import { cn } from '../../lib/utils';

// =====================================================================
// Input — left/right icon support, sm/md/lg sizes, error state, block.
// Composable: pair with <Field> for label + hint.
// =====================================================================

export type InputSize = 'sm' | 'md' | 'lg';

interface InputProps extends Omit<InputHTMLAttributes<HTMLInputElement>, 'size'> {
  inputSize?: InputSize;
  block?: boolean;
  error?: boolean;
  /** Left-side decoration: icon name or arbitrary node (e.g. `$` prefix). */
  icon?: IconName | ReactNode;
  /** Right-side decoration. */
  iconRight?: IconName | ReactNode;
  containerClassName?: string;
}

const sizeMap: Record<InputSize, { h: string; fs: string; padX: string; iconPad: string }> = {
  sm: { h: 'h-[30px]', fs: 'text-[12.5px]', padX: 'px-[10px]', iconPad: 'pl-[28px]' },
  md: { h: 'h-[38px]', fs: 'text-[13.5px]', padX: 'px-[12px]', iconPad: 'pl-[34px]' },
  lg: { h: 'h-[46px]', fs: 'text-[15px]', padX: 'px-[14px]', iconPad: 'pl-[40px]' },
};

const iconPx: Record<InputSize, number> = { sm: 13, md: 15, lg: 17 };

export const Input = forwardRef<HTMLInputElement, InputProps>(function Input(
  { inputSize = 'md', block, error, icon, iconRight, className, containerClassName, disabled, ...rest },
  ref,
) {
  const sz = sizeMap[inputSize];
  const ico = iconPx[inputSize];
  return (
    <label className={cn('relative inline-block', block && 'block w-full', containerClassName)}>
      {icon &&
        (typeof icon === 'string' ? (
          <Icon
            name={icon as IconName}
            size={ico}
            className="pointer-events-none absolute left-[10px] top-1/2 -translate-y-1/2 text-fg-subtle"
          />
        ) : (
          <span className="pointer-events-none absolute left-[10px] top-1/2 -translate-y-1/2 text-fg-subtle">
            {icon}
          </span>
        ))}
      <input
        ref={ref}
        disabled={disabled}
        className={cn(
          'w-full rounded-md border bg-bg-elev text-fg outline-none',
          'transition-[border-color,box-shadow] duration-120',
          'placeholder:text-fg-dim',
          'focus-visible:ring-2 focus-visible:ring-offset-1 focus-visible:[--tw-ring-color:var(--ring)]',
          sz.h,
          sz.fs,
          icon ? sz.iconPad : sz.padX,
          iconRight ? 'pr-[34px]' : '',
          error ? 'border-danger' : 'border-border-strong',
          disabled ? 'bg-bg-sunken opacity-60 cursor-not-allowed' : '',
          className,
        )}
        {...rest}
      />
      {iconRight && (
        <span className="absolute right-[10px] top-1/2 -translate-y-1/2 text-fg-subtle">
          {typeof iconRight === 'string' ? <Icon name={iconRight as IconName} size={ico} /> : iconRight}
        </span>
      )}
    </label>
  );
});
