import { useState, type MouseEvent } from 'react';
import { Button, type ButtonSize, type ButtonVariant } from './Button';

interface CopyBtnProps {
  value: string;
  label?: string;
  size?: ButtonSize;
  variant?: ButtonVariant;
  className?: string;
}

export function CopyBtn({ value, label = 'Copy', size = 'sm', variant = 'ghost', className }: CopyBtnProps) {
  const [copied, setCopied] = useState(false);
  const handle = (e: MouseEvent<HTMLButtonElement>) => {
    e.stopPropagation();
    try {
      navigator.clipboard?.writeText(value);
    } catch {
      /* noop */
    }
    setCopied(true);
    window.setTimeout(() => setCopied(false), 1400);
  };
  return (
    <Button size={size} variant={variant} icon={copied ? 'check' : 'copy'} onClick={handle} className={className}>
      {copied ? 'Copied' : label}
    </Button>
  );
}
