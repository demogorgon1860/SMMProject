import { type MouseEvent, useState } from 'react';
import { Icon } from './Icon';
import { cn } from '../../lib/utils';

interface IDCellProps {
  id: number | string;
  prefix?: string;
  /** Custom click handler. If absent, button copies the id to clipboard. */
  onClick?: (e: MouseEvent<HTMLButtonElement>) => void;
  className?: string;
}

export function IDCell({ id, prefix = '#', onClick, className }: IDCellProps) {
  const [copied, setCopied] = useState(false);

  const handleClick = (e: MouseEvent<HTMLButtonElement>) => {
    if (onClick) {
      onClick(e);
      return;
    }
    e.stopPropagation();
    try {
      navigator.clipboard?.writeText(String(id));
    } catch {
      /* noop */
    }
    setCopied(true);
    window.setTimeout(() => setCopied(false), 1000);
  };

  return (
    <button
      type="button"
      onClick={handleClick}
      title={copied ? 'Copied!' : 'Copy ID'}
      className={cn(
        'inline-flex items-center gap-[5px] rounded px-[6px] py-[2px] font-mono text-[12px] text-fg',
        'transition-colors hover:bg-bg-sunken',
        'focus-visible:outline-none focus-visible:ring-2 focus-visible:[--tw-ring-color:var(--ring)]',
        className,
      )}
    >
      <span className="text-fg-dim">{prefix}</span>
      {id}
      {copied ? <Icon name="check" size={11} /> : <Icon name="copy" size={11} className="opacity-35" />}
    </button>
  );
}
