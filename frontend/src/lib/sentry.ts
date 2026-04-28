import * as Sentry from '@sentry/react';

/**
 * Sentry initialization for the SPA.
 *
 * Activates only when VITE_SENTRY_DSN is set at build time. Leaving it unset is the
 * correct dev/CI behavior — the SDK becomes a no-op and never tries to ship events.
 *
 * Environment + release are stamped from Vite-time env so source maps line up:
 *   - VITE_SENTRY_DSN          → DSN (separate frontend project from backend)
 *   - VITE_SENTRY_ENVIRONMENT  → prod | stage | dev (defaults to import.meta.env.MODE)
 *   - VITE_SENTRY_RELEASE      → release tag, e.g. git SHA from `git rev-parse --short HEAD`
 */
export function initSentry(): void {
  const dsn = import.meta.env.VITE_SENTRY_DSN as string | undefined;
  if (!dsn) {
    return;
  }

  Sentry.init({
    dsn,
    environment:
      (import.meta.env.VITE_SENTRY_ENVIRONMENT as string | undefined) ||
      import.meta.env.MODE,
    release: import.meta.env.VITE_SENTRY_RELEASE as string | undefined,
    integrations: [Sentry.browserTracingIntegration(), Sentry.replayIntegration()],
    // Sample 5% of routine page navigations. Bump in stage to debug specific issues.
    tracesSampleRate: 0.05,
    // Replay: never on routine sessions; always when an error fires (so we get the run-up).
    replaysSessionSampleRate: 0,
    replaysOnErrorSampleRate: 1.0,
    // Don't ship raw URLs/UA-headers to Sentry — minimal PII surface.
    sendDefaultPii: false,
  });
}

export const SentryErrorBoundary = Sentry.ErrorBoundary;
export { Sentry };
