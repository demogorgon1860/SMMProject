import { useMemo, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { useAuthStore } from '../../store/authStore';
import { Button, Checkbox, Field, Icon, Input, useToast } from '../../components/ui';
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

export function RegisterPage() {
  const navigate = useNavigate();
  const toast = useToast();
  const register = useAuthStore((s) => s.register);
  const isLoading = useAuthStore((s) => s.isLoading);

  const [username, setUsername] = useState('');
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [showPwd, setShowPwd] = useState(false);
  const [acceptTerms, setAcceptTerms] = useState(false);
  const [error, setError] = useState<string | null>(null);
  // Honeypot. Hidden from real users (off-screen, tab-skipped, autocomplete off); naive bots
  // fill every visible-by-DOM field and trip it. Backend AuthController.register quietly drops
  // any registration where this is non-blank — bot sees a 200 and moves on.
  const [website, setWebsite] = useState('');

  const strength = useMemo(() => passwordScore(password), [password]);
  const valid =
    username.trim().length >= 3 &&
    /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email) &&
    password.length >= 8 &&
    acceptTerms;

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    setError(null);
    if (!valid) {
      setError('Please fill in all fields and accept the Terms.');
      return;
    }
    try {
      await register(username.trim(), email.trim(), password, website);
      const trimmed = email.trim();
      // Stash so /verify-email survives a page refresh — router state alone is
      // wiped by F5, leaving the verify form with no way to resend the code.
      try {
        localStorage.setItem('pending_verify_email', trimmed);
      } catch {
        /* private mode */
      }
      toast('Account created. Verify your email to continue.', 'success');
      navigate(`/verify-email?email=${encodeURIComponent(trimmed)}`, {
        replace: true,
        state: { email: trimmed },
      });
    } catch {
      setError(useAuthStore.getState().error ?? 'Registration failed.');
      toast('Registration failed', 'error');
    }
  };

  return (
    <>
      <div className="eyebrow">Create account</div>
      <h1 className="mt-2 text-[28px] font-bold tracking-[-0.02em]">Join SMMWorld.</h1>
      <p className="mt-1 text-[14px] text-fg-muted">
        Already have an account?{' '}
        <Link to="/login" className="font-medium text-accent hover:underline">
          Sign in
        </Link>
      </p>

      <form className="mt-8" onSubmit={submit} noValidate>
        {/* Honeypot — hidden from real users (off-screen + tab-skipped + autocomplete off).
            Bots crawl every input and submit; the backend silently drops requests with this
            field set. Do NOT replace `display:none`-only with hidden=true: some bots skip
            display:none fields, defeating the trap. */}
        <div aria-hidden="true" style={{ position: 'absolute', left: '-9999px', top: 'auto', width: 1, height: 1, overflow: 'hidden' }}>
          <label htmlFor="website">Website (leave blank)</label>
          <input
            id="website"
            type="text"
            name="website"
            tabIndex={-1}
            autoComplete="off"
            value={website}
            onChange={(e) => setWebsite(e.target.value)}
          />
        </div>

        <Field label="Username" hint="3+ characters, letters/digits/_">
          <Input
            block
            inputSize="lg"
            placeholder="your_username"
            autoComplete="username"
            value={username}
            onChange={(e) => setUsername(e.target.value)}
          />
        </Field>
        <Field label="Email">
          <Input
            block
            inputSize="lg"
            type="email"
            placeholder="you@example.com"
            autoComplete="email"
            value={email}
            onChange={(e) => setEmail(e.target.value)}
          />
        </Field>
        <Field
          label="Password"
          hint={password ? strength.label : '8+ characters'}
        >
          <Input
            block
            inputSize="lg"
            type={showPwd ? 'text' : 'password'}
            placeholder="••••••••"
            autoComplete="new-password"
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

        {/* Strength meter */}
        <div className="mt-[-8px] mb-4 grid grid-cols-4 gap-[3px]">
          {[0, 1, 2, 3].map((i) => (
            <span
              key={i}
              className={cn('h-[3px] rounded-full transition-colors')}
              style={{
                background: i < strength.score ? strength.color : 'var(--bg-sunken)',
              }}
            />
          ))}
        </div>

        <label className="mb-5 flex items-start gap-2 text-[12.5px] text-fg-muted">
          <Checkbox checked={acceptTerms} onChange={(e) => setAcceptTerms(e.target.checked)} className="mt-[2px]" />
          <span>
            I agree to the{' '}
            <Link to="/legal/terms" className="text-accent hover:underline">
              Terms
            </Link>{' '}
            and{' '}
            <Link to="/legal/privacy" className="text-accent hover:underline">
              Privacy Policy
            </Link>
            .
          </span>
        </label>

        {error && (
          <div className="mb-4 rounded-md border border-danger/30 bg-danger-soft px-3 py-2 text-[12.5px] text-danger">
            {error}
          </div>
        )}

        <Button type="submit" variant="primary" size="lg" block loading={isLoading} disabled={!valid}>
          Create account
        </Button>
      </form>
    </>
  );
}
