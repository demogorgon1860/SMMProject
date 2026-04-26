import { forwardRef, type InputHTMLAttributes, useEffect, useRef, useImperativeHandle } from 'react';
import { cn } from '../../lib/utils';

interface CheckboxProps extends Omit<InputHTMLAttributes<HTMLInputElement>, 'type'> {
  indeterminate?: boolean;
}

// We use the native <input type=checkbox> styled with `accent-color` so it
// follows the design accent automatically. Indeterminate is set imperatively.
export const Checkbox = forwardRef<HTMLInputElement, CheckboxProps>(function Checkbox(
  { indeterminate = false, className, ...rest },
  ref,
) {
  const innerRef = useRef<HTMLInputElement | null>(null);
  useImperativeHandle(ref, () => innerRef.current as HTMLInputElement, []);
  useEffect(() => {
    if (innerRef.current) innerRef.current.indeterminate = indeterminate;
  }, [indeterminate]);
  return (
    <input
      ref={innerRef}
      type="checkbox"
      className={cn(
        'h-[14px] w-[14px] cursor-pointer',
        'focus-visible:outline-none focus-visible:ring-2 focus-visible:[--tw-ring-color:var(--ring)]',
        // accent-* colors map to var(--accent) via Tailwind config
        'accent-accent',
        className,
      )}
      {...rest}
    />
  );
});
