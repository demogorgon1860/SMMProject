import { useEffect, useRef, useState } from 'react';
import { Link, useLocation, useNavigate, useSearchParams } from 'react-router-dom';
import { authAPI } from '../../services/api';
import { Button, useToast } from '../../components/ui';
import { cn } from '../../lib/utils';

// Resolve the user's email from (in order): URL `?email=`, router state set
// by /register, or a stash we wrote to localStorage at registration time.
// This lets the page survive a page refresh after the user comes back from
// their inbox — without it, we'd have an empty email and the resend button
// would be permanently disabled.
function useEmailFromContext(): string {
  const [params] = useSearchParams();
  const location = useLocation();
  const fromQuery = params.get('email');
  if (fromQuery && fromQuery.includes('@')) return fromQuery;
  const fromState = (location.state as { email?: string } | null)?.email;
  if (fromState && fromState.includes('@')) return fromState;
  try {
    const stashed = localStorage.getItem('pending_verify_email');
    if (stashed && stashed.includes('@')) return stashed;
  } catch {
    /* private mode */
  }
  return '';
}

export function VerifyEmailPage() {
  const navigate = useNavigate();
  const toast = useToast();
  const email = useEmailFromContext();

  const [digits, setDigits] = useState<string[]>(['', '', '', '', '', '']);
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [resendIn, setResendIn] = useState(47);
  const inputs = useRef<Array<HTMLInputElement | null>>([]);

  useEffect(() => {
    inputs.current[0]?.focus();
  }, []);

  useEffect(() => {
    if (resendIn <= 0) return;
    const t = window.setInterval(() => setResendIn((s) => Math.max(0, s - 1)), 1000);
    return () => clearInterval(t);
  }, [resendIn]);

  const setDigit = (i: number, v: string) => {
    const ch = v.replace(/\D/g, '').slice(0, 1);
    setDigits((prev) => {
      const next = [...prev];
      next[i] = ch;
      return next;
    });
    if (ch && i < 5) inputs.current[i + 1]?.focus();
  };

  const handleKeyDown = (i: number, e: React.KeyboardEvent<HTMLInputElement>) => {
    if (e.key === 'Backspace' && !digits[i] && i > 0) {
      inputs.current[i - 1]?.focus();
    }
  };

  const handlePaste = (e: React.ClipboardEvent<HTMLInputElement>) => {
    e.preventDefault();
    const text = e.clipboardData.getData('text').replace(/\D/g, '').slice(0, 6);
    if (!text) return;
    const next = ['', '', '', '', '', ''];
    for (let i = 0; i < 6 && i < text.length; i++) next[i] = text[i];
    setDigits(next);
    inputs.current[Math.min(text.length, 5)]?.focus();
  };

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    const code = digits.join('');
    if (code.length !== 6) {
      setError('Enter the 6-digit code from your email.');
      return;
    }
    if (!email) {
      setError('Open the verification link from the email we sent, or sign in to resend.');
      return;
    }
    setSubmitting(true);
    setError(null);
    try {
      await authAPI.verifyEmail(email, code);
      try {
        localStorage.removeItem('pending_verify_email');
      } catch {
        /* noop */
      }
      toast('Email verified.', 'success');
      navigate('/dashboard', { replace: true });
    } catch (err: unknown) {
      const e = err as { response?: { data?: { message?: string } } };
      setError(e.response?.data?.message ?? 'Invalid or expired code.');
    } finally {
      setSubmitting(false);
    }
  };

  const resend = async () => {
    if (resendIn > 0 || !email) return;
    try {
      await authAPI.resendVerification(email);
      toast('Verification code resent.', 'success');
      setResendIn(60);
    } catch {
      toast('Failed to resend code.', 'error');
    }
  };

  return (
    <>
      <div className="eyebrow">Verify email</div>
      <h1 className="mt-2 text-[28px] font-bold tracking-[-0.02em]">Check your inbox.</h1>
      <p className="mt-1 text-[14px] text-fg-muted">
        We sent a 6-digit code{email && ' to '}
        {email && <span className="font-mono text-fg">{email}</span>}.
      </p>

      <form className="mt-8" onSubmit={submit} noValidate>
        <div className="mb-3 flex justify-between gap-2">
          {digits.map((d, i) => (
            <input
              key={i}
              ref={(el) => (inputs.current[i] = el)}
              inputMode="numeric"
              pattern="\d*"
              maxLength={1}
              value={d}
              onChange={(e) => setDigit(i, e.target.value)}
              onKeyDown={(e) => handleKeyDown(i, e)}
              onPaste={handlePaste}
              className={cn(
                'h-[56px] w-[52px] rounded-lg border bg-bg-elev text-center font-mono text-[26px] font-semibold tabular-nums',
                'focus-visible:outline-none focus-visible:ring-2 focus-visible:[--tw-ring-color:var(--ring)]',
                d ? 'border-accent text-fg' : 'border-border-strong text-fg-muted',
              )}
            />
          ))}
        </div>

        {error && (
          <div className="mb-4 rounded-md border border-danger/30 bg-danger-soft px-3 py-2 text-[12.5px] text-danger">
            {error}
          </div>
        )}

        <Button type="submit" variant="primary" size="lg" block loading={submitting}>
          Verify and continue
        </Button>

        <div className="mt-4 flex items-center justify-between text-[13px]">
          <Link to="/login" className="text-fg-muted hover:text-fg">
            Back to sign in
          </Link>
          <button
            type="button"
            onClick={resend}
            disabled={resendIn > 0 || !email}
            className="font-medium text-accent disabled:cursor-not-allowed disabled:text-fg-dim"
          >
            {resendIn > 0 ? `Resend in 0:${resendIn.toString().padStart(2, '0')}` : 'Resend code'}
          </button>
        </div>
      </form>
    </>
  );
}
