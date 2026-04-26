import { useMemo, useState } from 'react';
import { Link, useNavigate, useSearchParams } from 'react-router-dom';
import { authAPI } from '../../services/api';
import { Button, Field, Icon, Input, useToast } from '../../components/ui';
import { cn } from '../../lib/utils';

function passwordScore(pwd: string): { score: number; label: string; color: string } {
  let s = 0;
  if (pwd.length >= 8) s++;
  if (pwd.length >= 12) s++;
  if (/[A-Z]/.test(pwd) && /[a-z]/.test(pwd)) s++;
  if (/\d/.test(pwd)) s++;
  if (/[^A-Za-z0-9]/.test(pwd)) s++;
  if (pwd.length < 8) return { score: 0, label: 'Too short', color: '#a8a29e' };
  if (s <= 2) return { score: 1, label: 'Weak', color: '#b91c1c' };
  if (s <= 3) return { score: 2, label: 'OK', color: '#a16207' };
  if (s <= 4) return { score: 3, label: 'Strong', color: '#047857' };
  return { score: 4, label: 'Excellent', color: '#047857' };
}

export function ResetPage() {
  const navigate = useNavigate();
  const toast = useToast();
  const [params] = useSearchParams();
  const token = params.get('token') ?? '';

  const [pwd, setPwd] = useState('');
  const [confirm, setConfirm] = useState('');
  const [showPwd, setShowPwd] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const strength = useMemo(() => passwordScore(pwd), [pwd]);
  const mismatch = confirm.length > 0 && pwd !== confirm;
  const valid = pwd.length >= 8 && !mismatch;

  if (!token) {
    return (
      <>
        <div className="mb-4 flex h-12 w-12 items-center justify-center rounded-full bg-danger-soft text-danger">
          <Icon name="warning" size={22} />
        </div>
        <h1 className="text-[28px] font-bold tracking-[-0.02em]">Reset link expired or invalid.</h1>
        <p className="mt-2 text-[14px] text-fg-muted">Request a fresh link to continue.</p>
        <div className="mt-6">
          <Link to="/forgot">
            <Button variant="primary" size="lg">
              Send new reset link
            </Button>
          </Link>
        </div>
      </>
    );
  }

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!valid) {
      setError(mismatch ? 'Passwords do not match.' : 'Password must be at least 8 characters.');
      return;
    }
    setSubmitting(true);
    setError(null);
    try {
      await authAPI.resetPassword(token, pwd);
      toast('Password updated. Sign in with the new one.', 'success');
      navigate('/login', { replace: true });
    } catch (err: unknown) {
      const e = err as { response?: { data?: { message?: string } } };
      setError(e.response?.data?.message ?? 'Reset failed. Link may be expired.');
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <>
      <div className="eyebrow">Reset password</div>
      <h1 className="mt-2 text-[28px] font-bold tracking-[-0.02em]">Set a new password.</h1>
      <p className="mt-1 text-[14px] text-fg-muted">Use 12+ characters with mixed case and a digit.</p>

      <form className="mt-8" onSubmit={submit} noValidate>
        <Field label="New password" hint={pwd ? strength.label : '8+ characters'}>
          <Input
            block
            inputSize="lg"
            type={showPwd ? 'text' : 'password'}
            autoComplete="new-password"
            value={pwd}
            onChange={(e) => setPwd(e.target.value)}
            iconRight={
              <button
                type="button"
                tabIndex={-1}
                onClick={() => setShowPwd((v) => !v)}
                className="text-fg-subtle hover:text-fg"
                aria-label={showPwd ? 'Hide' : 'Show'}
              >
                <Icon name={showPwd ? 'eye-off' : 'eye'} size={16} />
              </button>
            }
          />
        </Field>
        <div className="mt-[-8px] mb-4 grid grid-cols-4 gap-[3px]">
          {[0, 1, 2, 3].map((i) => (
            <span
              key={i}
              className={cn('h-[3px] rounded-full transition-colors')}
              style={{ background: i < strength.score ? strength.color : 'var(--bg-sunken)' }}
            />
          ))}
        </div>

        <Field label="Confirm password" hint={mismatch ? 'Does not match' : undefined} error={mismatch}>
          <Input
            block
            inputSize="lg"
            type={showPwd ? 'text' : 'password'}
            autoComplete="new-password"
            error={mismatch}
            value={confirm}
            onChange={(e) => setConfirm(e.target.value)}
          />
        </Field>

        {error && (
          <div className="mb-4 rounded-md border border-danger/30 bg-danger-soft px-3 py-2 text-[12.5px] text-danger">
            {error}
          </div>
        )}

        <Button type="submit" variant="primary" size="lg" block loading={submitting} disabled={!valid}>
          Update password
        </Button>
      </form>
    </>
  );
}
