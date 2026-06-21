import { ComponentType, lazy, Suspense, useEffect } from 'react';
import { BrowserRouter, Navigate, Route, Routes } from 'react-router-dom';
import { ErrorBoundary, FallbackProps } from 'react-error-boundary';

import { Sentry } from './lib/sentry';
import { useAuthStore } from './store/authStore';
import { ThemeProvider } from './contexts/ThemeContext';
import { ProtectedRoute } from './components/auth/ProtectedRoute';
import { AdminShell, AppShell, AuthShell, LandingShell, PublicShell } from './components/shells';

// ---- Eager (critical-path) imports --------------------------------------
// Lazy-loading these would cost more than it saves: they're either the first
// paint (Landing) or the immediate post-login destination (Dashboard).
// Login is small enough that the extra round-trip isn't worth a separate chunk.
import { LoginPage /* RegisterPage — TEMP: registration closed */ } from './pages/auth';
import { LandingPage } from './pages/public/Landing';
import { NotFoundPage, ServerErrorPage } from './pages/public/NotFound';
import { DashboardPage } from './pages/app/Dashboard';

// ---- Lazy-loaded route helpers ------------------------------------------
// `lazyNamed` adapts a named export to React.lazy's default-export contract.
// `withChunkRetry` retries once after a hard reload when a chunk fetch fails
// after a deploy (the user's cached index.html still references old hashes).
const CHUNK_RELOAD_KEY = '__chunk_reload__';

function isChunkLoadError(error: unknown): boolean {
  const message = error instanceof Error ? error.message : String(error);
  return (
    /Loading chunk [\d]+ failed/i.test(message) ||
    /Failed to fetch dynamically imported module/i.test(message) ||
    /Importing a module script failed/i.test(message)
  );
}

function withChunkRetry<T>(loader: () => Promise<T>): () => Promise<T> {
  return () =>
    loader().then(
      (value) => {
        // Clear the reload guard on success so a *second* deploy in the same
        // session can also auto-retry once.
        if (typeof window !== 'undefined' && sessionStorage.getItem(CHUNK_RELOAD_KEY)) {
          sessionStorage.removeItem(CHUNK_RELOAD_KEY);
        }
        return value;
      },
      (err: unknown) => {
        if (
          isChunkLoadError(err) &&
          typeof window !== 'undefined' &&
          !sessionStorage.getItem(CHUNK_RELOAD_KEY)
        ) {
          sessionStorage.setItem(CHUNK_RELOAD_KEY, '1');
          window.location.reload();
          return new Promise<T>(() => {}); // never resolves; page is reloading
        }
        throw err;
      },
    );
}

function lazyNamed<K extends string, M extends Record<K, ComponentType<unknown>>>(
  loader: () => Promise<M>,
  name: K,
) {
  return lazy(withChunkRetry(() => loader().then((m) => ({ default: m[name] }))));
}

// ---- Auth (rarely hit — recovery flows) ---------------------------------
const ForgotPage = lazyNamed(() => import('./pages/auth/Forgot'), 'ForgotPage');
const ResetPage = lazyNamed(() => import('./pages/auth/Reset'), 'ResetPage');
const VerifyEmailPage = lazyNamed(() => import('./pages/auth/VerifyEmail'), 'VerifyEmailPage');

// ---- Public (off the Landing → Login → Dashboard hot path) --------------
const ApiDocsPage = lazyNamed(() => import('./pages/public/ApiDocs'), 'ApiDocsPage');
const HelpPage = lazyNamed(() => import('./pages/public/Help'), 'HelpPage');
const PricingPage = lazyNamed(() => import('./pages/public/Pricing'), 'PricingPage');
const ServicesListPage = lazyNamed(() => import('./pages/public/ServicesList'), 'ServicesListPage');
const AmlPage = lazyNamed(() => import('./pages/public/legal/Aml'), 'AmlPage');
const PrivacyPage = lazyNamed(() => import('./pages/public/legal/Privacy'), 'PrivacyPage');
const RefundPage = lazyNamed(() => import('./pages/public/legal/Refund'), 'RefundPage');
const TermsPage = lazyNamed(() => import('./pages/public/legal/Terms'), 'TermsPage');

// ---- User-app secondary screens -----------------------------------------
// Dashboard is eager (first thing after login). Everything else is one click
// away and benefits from a separate chunk so the initial bundle doesn't carry
// AddFunds + NewOrder + Orders + Profile + Transactions for visitors who
// might never log in.
const AddFundsPage = lazyNamed(() => import('./pages/app/AddFunds'), 'AddFundsPage');
const NewOrderPage = lazyNamed(() => import('./pages/app/NewOrder'), 'NewOrderPage');
const OrdersPage = lazyNamed(() => import('./pages/app/Orders'), 'OrdersPage');
const RefillPage = lazyNamed(() => import('./pages/app/Refill'), 'RefillPage');
const ProfilePage = lazyNamed(() => import('./pages/app/Profile'), 'ProfilePage');
const TransactionsPage = lazyNamed(() => import('./pages/app/Transactions'), 'TransactionsPage');

// ---- Admin (gated by role; visitors never need this code) ---------------
const AdminBalancePage = lazyNamed(() => import('./pages/admin/Balance'), 'AdminBalancePage');
const AdminBotPage = lazyNamed(() => import('./pages/admin/Bot'), 'AdminBotPage');
const AdminDashboardPage = lazyNamed(() => import('./pages/admin/Dashboard'), 'AdminDashboardPage');
const AdminOrdersPage = lazyNamed(() => import('./pages/admin/Orders'), 'AdminOrdersPage');
const AdminPaymentsPage = lazyNamed(() => import('./pages/admin/Payments'), 'AdminPaymentsPage');
const AdminRefillRequestsPage = lazyNamed(
  () => import('./pages/admin/RefillRequests'),
  'AdminRefillRequestsPage',
);
const AdminRefillPage = lazyNamed(() => import('./pages/admin/Refill'), 'AdminRefillPage');
const AdminServicesPage = lazyNamed(() => import('./pages/admin/Services'), 'AdminServicesPage');
const AdminSettingsPage = lazyNamed(() => import('./pages/admin/Settings'), 'AdminSettingsPage');
const AdminSystemPage = lazyNamed(() => import('./pages/admin/System'), 'AdminSystemPage');
const AdminTelegramPage = lazyNamed(() => import('./pages/admin/Telegram'), 'AdminTelegramPage');
const AdminUsersPage = lazyNamed(() => import('./pages/admin/Users'), 'AdminUsersPage');

function ErrorFallback({ error, resetErrorBoundary }: FallbackProps) {
  const chunkError = isChunkLoadError(error);
  const title = chunkError ? "Couldn't load this section" : 'Something went wrong';
  const description = chunkError
    ? 'A new version may have been deployed, or your connection dropped. Refresh to load the latest.'
    : error instanceof Error
      ? error.message
      : String(error);
  const cta = chunkError ? 'Refresh' : 'Try again';
  const onClick = chunkError ? () => window.location.reload() : resetErrorBoundary;

  return (
    <div className="flex min-h-screen items-center justify-center bg-bg p-6">
      <div className="w-full max-w-[420px] rounded-xl border border-border bg-bg-elev p-6 text-center shadow-pop">
        <div className="mx-auto mb-4 flex h-14 w-14 items-center justify-center rounded-full bg-danger-soft">
          <span className="text-[24px] text-danger">!</span>
        </div>
        <h2 className="text-[18px] font-semibold">{title}</h2>
        <p className="mt-2 text-[13px] text-fg-muted">{description}</p>
        <button
          onClick={onClick}
          className="mt-5 inline-flex h-[36px] items-center rounded-md bg-accent px-4 text-[13px] font-semibold text-white hover:brightness-110"
        >
          {cta}
        </button>
      </div>
    </div>
  );
}

function PageLoader() {
  return (
    <div className="flex min-h-screen items-center justify-center bg-bg">
      <div className="relative">
        <div className="h-10 w-10 rounded-full border-2 border-border" />
        <div className="spin absolute inset-0 h-10 w-10 rounded-full border-2 border-accent border-t-transparent" />
      </div>
    </div>
  );
}

function App() {
  const checkAuth = useAuthStore((s) => s.checkAuth);
  useEffect(() => {
    checkAuth();
  }, [checkAuth]);

  return (
    <ThemeProvider>
      <ErrorBoundary
        FallbackComponent={ErrorFallback}
        onError={(error, info) => {
          // Forward to Sentry. Chunk-load errors after a deploy are noise — the user
          // just has a stale index.html and a refresh fixes it; skip those so the
          // Sentry quota goes to real bugs.
          if (isChunkLoadError(error)) return;
          Sentry.captureException(error, {
            contexts: { react: { componentStack: info.componentStack ?? '' } },
          });
        }}
      >
        <BrowserRouter>
          <Suspense fallback={<PageLoader />}>
            <Routes>
              {/* ---------------- Landing (own shell — follows hero variant) ---------------- */}
              <Route element={<LandingShell />}>
                <Route index element={<LandingPage />} />
              </Route>

              {/* ---------------- Public (no auth) ---------------- */}
              <Route element={<PublicShell />}>
                <Route path="services-list" element={<ServicesListPage />} />
                <Route path="pricing" element={<PricingPage />} />
                <Route path="help" element={<HelpPage />} />
                <Route path="docs" element={<ApiDocsPage />} />
                <Route path="legal/terms" element={<TermsPage />} />
                <Route path="legal/privacy" element={<PrivacyPage />} />
                <Route path="legal/refund" element={<RefundPage />} />
                <Route path="legal/aml" element={<AmlPage />} />
                <Route path="404" element={<NotFoundPage />} />
                <Route path="500" element={<ServerErrorPage />} />
              </Route>

              {/* ---------------- Auth (split-screen, visual rotates per route) ---------------- */}
              <Route path="login" element={<AuthShell visual="router"><LoginPage /></AuthShell>} />
              {/* TEMP: registration closed — restore by uncommenting */}
              {/* <Route path="register" element={<AuthShell visual="stats"><RegisterPage /></AuthShell>} /> */}
              <Route path="verify-email" element={<AuthShell visual="check"><VerifyEmailPage /></AuthShell>} />
              <Route path="forgot" element={<AuthShell visual="router"><ForgotPage /></AuthShell>} />
              <Route path="reset" element={<AuthShell visual="check"><ResetPage /></AuthShell>} />

              {/* ---------------- App (auth required) ---------------- */}
              <Route
                element={
                  <ProtectedRoute>
                    <AppShell />
                  </ProtectedRoute>
                }
              >
                <Route path="dashboard" element={<DashboardPage />} />
                <Route path="new-order" element={<NewOrderPage />} />
                <Route path="orders" element={<OrdersPage />} />
                <Route path="orders/:id" element={<OrdersPage />} />
                <Route path="refill" element={<RefillPage />} />
                <Route path="add-funds" element={<AddFundsPage />} />
                <Route path="transactions" element={<TransactionsPage />} />
                <Route path="profile" element={<ProfilePage />} />
              </Route>

              {/* ---------------- Admin (admin role required) ---------------- */}
              <Route
                path="admin"
                element={
                  <ProtectedRoute requiredRole="ADMIN">
                    <AdminShell />
                  </ProtectedRoute>
                }
              >
                <Route index element={<AdminDashboardPage />} />
                <Route path="orders" element={<AdminOrdersPage />} />
                <Route path="refill" element={<AdminRefillPage />} />
                <Route path="refill-requests" element={<AdminRefillRequestsPage />} />
                <Route path="users" element={<AdminUsersPage />} />
                <Route path="services" element={<AdminServicesPage />} />
                <Route path="payments" element={<AdminPaymentsPage />} />
                <Route path="balance" element={<AdminBalancePage />} />
                <Route path="bot" element={<AdminBotPage />} />
                <Route path="telegram" element={<AdminTelegramPage />} />
                <Route path="system" element={<AdminSystemPage />} />
                <Route path="settings" element={<AdminSettingsPage />} />
              </Route>

              {/* ---------------- Catch-all → 404 ---------------- */}
              <Route path="*" element={<Navigate to="/404" replace />} />
            </Routes>
          </Suspense>
        </BrowserRouter>
      </ErrorBoundary>
    </ThemeProvider>
  );
}

export default App;
