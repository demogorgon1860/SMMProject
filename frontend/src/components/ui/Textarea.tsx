import { forwardRef, type TextareaHTMLAttributes } from 'react';
import { cn } from '../../lib/utils';

interface TextareaProps extends TextareaHTMLAttributes<HTMLTextAreaElement> {
  error?: boolean;
  block?: boolean;
}

export const Textarea = forwardRef<HTMLTextAreaElement, TextareaProps>(function Textarea(
  { error, block, className, rows = 3, ...rest },
  ref,
) {
  return (
    <textarea
      ref={ref}
      rows={rows}
      className={cn(
        'rounded-md border bg-bg-elev text-fg text-[13.5px] leading-[1.55]',
        'px-[12px] py-[9px] outline-none placeholder:text-fg-dim',
        'transition-[border-color,box-shadow] duration-120',
        'focus-visible:ring-2 focus-visible:ring-offset-1 focus-visible:[--tw-ring-color:var(--ring)]',
        error ? 'border-danger' : 'border-border-strong',
        block && 'w-full block',
        className,
      )}
      {...rest}
    />
  );
});
