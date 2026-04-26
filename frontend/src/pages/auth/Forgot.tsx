import { useState } from 'react';
import { Link } from 'react-router-dom';
import { authAPI } from '../../services/api';
import { Button, Field, Icon, Input } from '../../components/ui';

export function ForgotPage() {
  const [email, setEmail] = useState('');
  const [sent, setSent] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email)) {
      setError('Enter a valid email address.');
      return;
    }
    setSubmitting(true);
    setError(null);
    try {
      await authAPI.forgotPassword(email.trim());
      setSent(true);
    } catch (err: unknown) {
      // Always succeed visually to prevent email enumeration.
      setSent(true);
    } finally {
      setSubmitting(false);
    }
  };

  if (sent) {
    return (
      <>
        <div className="mb-4 flex h-12 w-12 items-center justify-center rounded-full bg-success-soft text-success">
          <Icon name="check" size={24} />
        </div>
        <h1 className="text-[28px] font-bold tracking-[-0.02em]">Check your email.</h1>
        <p className="mt-2 text-[14px] text-fg-muted">
          If an account exists for <span className="font-mono text-fg">{email}</span>, we just sent
          a password-reset link. It expires in 1 hour.
        </p>
        <div className="mt-6 flex gap-2">
          <Button variant="secondary" size="lg" onClick={() => setSent(false)}>
            Try another email
          </Button>
          <Link to="/login">
            <Button variant="ghost" size="lg">
              Back to sign in
            </Button>
          </Link>
        </div>
      </>
    );
  }

  return (
    <>
      <div className="eyebrow">Reset password</div>
      <h1 className="mt-2 text-[28px] font-bold tracking-[-0.02em]">Forgot your password?</h1>
      <p className="mt-1 text-[14px] text-fg-muted">
        Enter the email on your account and we'll send you a reset link.
      </p>

      <form className="mt-8" onSubmit={submit} noValidate>
        <Field label="Email">
          <Input
            block
            inputSize="lg"
            type="email"
            autoComplete="email"
            autoFocus
            placeholder="you@example.com"
            value={email}
            onChange={(e) => setEmail(e.target.value)}
          />
        </Field>

        {error && (
          <div className="mb-4 rounded-md border border-danger/30 bg-danger-soft px-3 py-2 text-[12.5px] text-danger">
            {error}
          </div>
        )}

        <Button type="submit" variant="primary" size="lg" block loading={submitting}>
          Send reset link
        </Button>

        <p className="mt-4 text-center text-[13px] text-fg-muted">
          Remembered it?{' '}
          <Link to="/login" className="font-medium text-accent hover:underline">
            Sign in
          </Link>
        </p>
      </form>
    </>
  );
}
