import { useState } from 'react';
import { Link, useLocation, useNavigate } from 'react-router-dom';
import { useAuthStore } from '../../store/authStore';
import { Button, Field, Icon, Input, useToast } from '../../components/ui';

interface FromState {
  from?: { pathname: string };
}

export function LoginPage() {
  const navigate = useNavigate();
  const location = useLocation();
  const toast = useToast();
  const login = useAuthStore((s) => s.login);
  const isLoading = useAuthStore((s) => s.isLoading);

  const [identifier, setIdentifier] = useState('');
  const [password, setPassword] = useState('');
  const [showPwd, setShowPwd] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    setError(null);
    if (!identifier.trim() || !password) {
      setError('Email and password are required.');
      return;
    }
    try {
      await login(identifier.trim(), password);
      // Honor the "you tried to visit X, sign in first" return URL when present.
      // Otherwise: admins land in the admin panel by default, regular users on the dashboard.
      const fromState = (location.state as FromState | null)?.from?.pathname;
      const role = useAuthStore.getState().user?.role;
      const fallback = role === 'ADMIN' ? '/admin' : '/dashboard';
      navigate(fromState ?? fallback, { replace: true });
    } catch (err) {
      setError(useAuthStore.getState().error ?? 'Sign-in failed.');
      toast('Sign-in failed', 'error');
    }
  };

  return (
    <>
      <div className="eyebrow">Sign in</div>
      <h1 className="mt-2 text-[28px] font-bold tracking-[-0.02em]">Welcome back.</h1>
      <p className="mt-1 text-[14px] text-fg-muted">
        New to SMMWorld?{' '}
        <Link to="/register" className="font-medium text-accent hover:underline">
          Create account
        </Link>
      </p>

      <form className="mt-8" onSubmit={submit} noValidate>
        <Field label="Email or username">
          <Input
            block
            inputSize="lg"
            type="text"
            autoComplete="username"
            autoFocus
            placeholder="you@example.com"
            value={identifier}
            onChange={(e) => setIdentifier(e.target.value)}
          />
        </Field>

        <Field
          label="Password"
          hint={
            <Link to="/forgot" className="text-accent hover:underline">
              Forgot?
            </Link>
          }
        >
          <Input
            block
            inputSize="lg"
            type={showPwd ? 'text' : 'password'}
            autoComplete="current-password"
            placeholder="••••••••"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            iconRight={
              <button
                type="button"
                onClick={() => setShowPwd((v) => !v)}
                tabIndex={-1}
                className="text-fg-subtle hover:text-fg"
                aria-label={showPwd ? 'Hide password' : 'Show password'}
              >
                <Icon name={showPwd ? 'eye-off' : 'eye'} size={16} />
              </button>
            }
          />
        </Field>

        {error && (
          <div className="mb-4 rounded-md border border-danger/30 bg-danger-soft px-3 py-2 text-[12.5px] text-danger">
            {error}
          </div>
        )}

        <Button type="submit" variant="primary" size="lg" block loading={isLoading}>
          Sign in
        </Button>
      </form>
    </>
  );
}
