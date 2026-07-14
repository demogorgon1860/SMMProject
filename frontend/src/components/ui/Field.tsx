import { cloneElement, isValidElement, useId, type ReactElement, type ReactNode } from 'react';
import { cn } from '../../lib/utils';

interface FieldProps {
  label?: ReactNode;
  hint?: ReactNode;
  error?: boolean;
  children: ReactNode;
  className?: string;
}

// Field — label + optional hint row + children. Hint goes right of the label
// and turns red when `error` is set. Numeric-looking hints render in mono.
export function Field({ label, hint, error, children, className }: FieldProps) {
  const hintIsNumeric = typeof hint === 'string' && /^[\d/$.,\s\-+%]+$/.test(hint);

  // Associate the <label> with the control so screen readers announce a name. Reuse the child's
  // own id if it has one, otherwise generate a stable id and inject it into the single child input.
  const generatedId = useId();
  const childIsElement = isValidElement(children);
  const childId =
    childIsElement && (children as ReactElement<{ id?: string }>).props.id
      ? (children as ReactElement<{ id?: string }>).props.id
      : generatedId;
  const control =
    childIsElement && !(children as ReactElement<{ id?: string }>).props.id
      ? cloneElement(children as ReactElement<{ id?: string }>, { id: childId })
      : children;

  return (
    <div className={cn('mb-4', className)}>
      {(label || hint) && (
        <div className="mb-[6px] flex items-baseline justify-between gap-3">
          {label && (
            <label htmlFor={childId} className="text-[12.5px] font-medium text-fg-muted">
              {label}
            </label>
          )}
          {hint && (
            <span
              className={cn(
                'text-[11.5px]',
                error ? 'text-danger' : 'text-fg-subtle',
                hintIsNumeric && 'font-mono',
              )}
            >
              {hint}
            </span>
          )}
        </div>
      )}
      {control}
    </div>
  );
}
