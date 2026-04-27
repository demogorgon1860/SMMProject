import { type MouseEvent, useState } from 'react';
import { Icon } from './Icon';
import { cn } from '../../lib/utils';

interface CopyIconProps {
  /** String written to the clipboard on click. */
  value: string;
  /** Tooltip on hover. Default: "Copy". */
  title?: string;
  className?: string;
}

/**
 * Tiny clipboard-icon button. Lighter than {@link CopyBtn} (no label, no `<Button>` chrome) —
 * meant to sit inline next to a value that's already rendered (URL, hash, identifier) so the
 * user can grab it without opening the row's drawer / detail page.
 *
 * Stops propagation so an enclosing row {@code onClick} (admin/user Orders tables) doesn't
 * also fire and navigate away while the user is just trying to copy a link.
 */
export function CopyIcon({ value, title = 'Copy', className }: CopyIconProps) {
  const [copied, setCopied] = useState(false);

  const handleClick = (e: MouseEvent<HTMLButtonElement>) => {
    e.stopPropagation();
    e.preventDefault();
    try {
      navigator.clipboard?.writeText(value);
    } catch {
      /* noop — older browsers / insecure contexts */
    }
    setCopied(true);
    window.setTimeout(() => setCopied(false), 1000);
  };

  return (
    <button
      type="button"
      onClick={handleClick}
      onMouseDown={(e) => e.stopPropagation()}
      title={copied ? 'Copied!' : title}
      aria-label={copied ? 'Copied' : title}
      className={cn(
        'inline-flex h-[18px] w-[18px] flex-none items-center justify-center rounded',
        'text-fg-dim transition-colors hover:bg-bg-sunken hover:text-fg',
        'focus-visible:outline-none focus-visible:ring-2 focus-visible:[--tw-ring-color:var(--ring)]',
        className,
      )}
    >
      <Icon name={copied ? 'check' : 'copy'} size={11} />
    </button>
  );
}
