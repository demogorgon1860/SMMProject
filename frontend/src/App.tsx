import { Suspense, useEffect } from 'react';
import { BrowserRouter, Navigate, Route, Routes } from 'react-router-dom';
import { ErrorBoundary } from 'react-error-boundary';

import { useAuthStore } from './store/authStore';
import { ThemeProvider } from './contexts/ThemeContext';
import { ProtectedRoute } from './components/auth/ProtectedRoute';
import { AdminShell, AppShell, AuthShell, LandingShell, PublicShell } from './components/shells';
import { PageStub } from './pages/_PageStub';
import { ForgotPage, LoginPage, RegisterPage, ResetPage, VerifyEmailPage } from './pages/auth';
import { LandingPage } from './pages/public/Landing';
import { ApiDocsPage } from './pages/public/ApiDocs';
import { HelpPage } from './pages/public/Help';
import { MobilePage } from './pages/public/Mobile';
import { NotFoundPage, ServerErrorPage } from './pages/public/NotFound';
import { PricingPage } from './pages/public/Pricing';
import { ServicesListPage } from './pages/public/ServicesList';
import { AmlPage } from './pages/public/legal/Aml';
import { PrivacyPage } from './pages/public/legal/Privacy';
import { RefundPage } from './pages/public/legal/Refund';
import { TermsPage } from './pages/public/legal/Terms';
import { AddFundsPage } from './pages/app/AddFunds';
import { DashboardPage } from './pages/app/Dashboard';
import { NewOrderPage } from './pages/app/NewOrder';
import { OrdersPage } from './pages/app/Orders';
import { ProfilePage } from './pages/app/Profile';
import { TransactionsPage } from './pages/app/Transactions';
import { AdminBalancePage } from './pages/admin/Balance';
import { AdminBotPage } from './pages/admin/Bot';
import { AdminDashboardPage } from './pages/admin/Dashboard';
import { AdminOrdersPage } from './pages/admin/Orders';
import { AdminPaymentsPage } from './pages/admin/Payments';
import { AdminRefillRequestsPage } from './pages/admin/RefillRequests';
import { AdminServicesPage } from './pages/admin/Services';
import { AdminSettingsPage } from './pages/admin/Settings';
import { AdminSystemPage } from './pages/admin/System';
import { AdminTelegramPage } from './pages/admin/Telegram';
import { AdminUsersPage } from './pages/admin/Users';

// =====================================================================
// SMMWorld routing — Phase 0 wiring.
// All real pages render PageStub for now; Phase 1 (user) and Phase 2
// (admin) replace each stub with the actual page component. Public/auth
// shells are already final-form so we can demo theme/accent today.
// =====================================================================

function ErrorFallback({ error, resetErrorBoundary }: { error: Error; resetErrorBoundary: () => void }) {
  return (
    <div className="flex min-h-screen items-center justify-center bg-bg p-6">
      <div className="w-full max-w-[420px] rounded-xl border border-border bg-bg-elev p-6 text-center shadow-pop">
        <div className="mx-auto mb-4 flex h-14 w-14 items-center justify-center rounded-full bg-danger-soft">
          <span className="text-[24px] text-danger">!</span>
        </div>
        <h2 className="text-[18px] font-semibold">Something went wrong</h2>
        <p className="mt-2 text-[13px] text-fg-muted">{error.message}</p>
        <button
          onClick={resetErrorBoundary}
          className="mt-5 inline-flex h-[36px] items-center rounded-md bg-accent px-4 text-[13px] font-semibold text-white hover:brightness-110"
        >
          Try again
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
      <ErrorBoundary FallbackComponent={ErrorFallback}>
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
                <Route path="mobile" element={<MobilePage />} />
                <Route path="404" element={<NotFoundPage />} />
                <Route path="500" element={<ServerErrorPage />} />
              </Route>

              {/* ---------------- Auth (split-screen, visual rotates per route) ---------------- */}
              <Route path="login" element={<AuthShell visual="router"><LoginPage /></AuthShell>} />
              <Route path="register" element={<AuthShell visual="stats"><RegisterPage /></AuthShell>} />
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
