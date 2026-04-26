import { Link } from 'react-router-dom';
import { Button } from '../../components/ui';

interface ErrorPageProps {
  code?: '404' | '500';
  title?: string;
  message?: string;
}

export function NotFoundPage({
  code = '404',
  title = "Page not found",
  message = "We couldn't route this URL anywhere. Maybe it moved, maybe it never existed.",
}: ErrorPageProps) {
  return (
    <div className="container-narrow flex min-h-[70vh] flex-col items-center justify-center text-center">
      <div className="font-mono text-[140px] font-bold leading-none tracking-[-0.04em] text-accent">
        {code}
      </div>
      <h1 className="mt-4 text-[28px] font-bold tracking-[-0.02em]">{title}</h1>
      <p className="mt-3 max-w-[480px] text-[15px] text-fg-muted">{message}</p>
      <div className="mt-8 flex gap-3">
        <Link to="/dashboard">
          <Button variant="primary" size="lg">
            Back to dashboard
          </Button>
        </Link>
        <Link to="/">
          <Button variant="secondary" size="lg">
            Go home
          </Button>
        </Link>
      </div>
    </div>
  );
}

export function ServerErrorPage() {
  return (
    <NotFoundPage
      code="500"
      title="Our nodes blew a fuse."
      message="Something went wrong on our side. We've been pinged. Try again, and check status if it persists."
    />
  );
}
