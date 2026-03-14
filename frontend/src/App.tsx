import React, { Suspense, useEffect } from 'react';
import { BrowserRouter as Router, Routes, Route, Navigate } from 'react-router-dom';
import { ErrorBoundary } from 'react-error-boundary';
import { useAuthStore } from './store/authStore';
import { ThemeProvider } from './contexts/ThemeContext';

// Components (always loaded)
import { Layout } from './components/Layout';
import { LoginForm } from './components/auth/LoginForm';
import { RegisterForm } from './components/auth/RegisterForm';
import { ProtectedRoute } from './components/auth/ProtectedRoute';

// Lazy-loaded pages
const Dashboard = React.lazy(() => import('./pages/Dashboard').then(m => ({ default: m.Dashboard })));
const Services = React.lazy(() => import('./pages/Services').then(m => ({ default: m.Services })));
const Orders = React.lazy(() => import('./pages/Orders').then(m => ({ default: m.Orders })));
const NewOrder = React.lazy(() => import('./pages/NewOrder').then(m => ({ default: m.NewOrder })));
const ProfileSettings = React.lazy(() => import('./pages/ProfileSettings').then(m => ({ default: m.ProfileSettings })));
const AddFunds = React.lazy(() => import('./pages/AddFunds').then(m => ({ default: m.AddFunds })));
const TermsOfService = React.lazy(() => import('./pages/TermsOfService').then(m => ({ default: m.TermsOfService })));

// Admin pages (separate chunk — not loaded for regular users)
const AdminDashboard = React.lazy(() => import('./pages/AdminDashboard').then(m => ({ default: m.AdminDashboard })));
const AdminOrders = React.lazy(() => import('./pages/AdminOrders').then(m => ({ default: m.AdminOrders })));
const AdminPayments = React.lazy(() => import('./pages/AdminPayments').then(m => ({ default: m.AdminPayments })));
const AdminRefills = React.lazy(() => import('./pages/AdminRefills').then(m => ({ default: m.AdminRefills })));
const ServicesTest = React.lazy(() => import('./pages/ServicesTest').then(m => ({ default: m.ServicesTest })));

function ErrorFallback({ error, resetErrorBoundary }: { error: Error; resetErrorBoundary: () => void }) {
  return (
    <div className="min-h-screen flex items-center justify-center bg-gray-50 dark:bg-dark-900">
      <div className="max-w-md w-full p-8 bg-white dark:bg-dark-800 rounded-2xl shadow-lg text-center">
        <h2 className="text-xl font-semibold text-red-600 mb-4">Something went wrong</h2>
        <p className="text-gray-600 dark:text-gray-400 mb-6 text-sm">{error.message}</p>
        <button
          onClick={resetErrorBoundary}
          className="px-6 py-2.5 bg-primary-600 text-white rounded-xl hover:bg-primary-700 transition-colors"
        >
          Try again
        </button>
      </div>
    </div>
  );
}

function PageLoader() {
  return (
    <div className="flex items-center justify-center py-20">
      <div className="animate-spin rounded-full h-8 w-8 border-b-2 border-primary-600" />
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
        <Router>
          <Suspense fallback={<PageLoader />}>
            <Routes>
            {/* Public routes */}
            <Route path="/login" element={<LoginForm />} />
            <Route path="/register" element={<RegisterForm />} />
            <Route path="/terms" element={<TermsOfService />} />
            <Route path="/services-public" element={<Services />} />

            {/* Protected routes */}
            <Route
              path="/"
              element={
                <ProtectedRoute>
                  <Layout />
                </ProtectedRoute>
              }
            >
              <Route index element={<Navigate to="/dashboard" replace />} />
              <Route path="dashboard" element={<Dashboard />} />
              <Route path="services" element={<Services />} />
              <Route path="orders" element={<Orders />} />
              <Route path="orders/new" element={<NewOrder />} />
              <Route path="add-funds" element={<AddFunds />} />
              <Route path="profile" element={<ProfileSettings />} />

              {/* Admin routes */}
              <Route
                path="admin"
                element={
                  <ProtectedRoute requiredRole="ADMIN">
                    <AdminDashboard />
                  </ProtectedRoute>
                }
              />
              <Route
                path="admin/orders"
                element={
                  <ProtectedRoute requiredRole="ADMIN">
                    <AdminOrders />
                  </ProtectedRoute>
                }
              />
              <Route
                path="admin/payments"
                element={
                  <ProtectedRoute requiredRole="ADMIN">
                    <AdminPayments />
                  </ProtectedRoute>
                }
              />
              <Route
                path="admin/refills"
                element={
                  <ProtectedRoute requiredRole="ADMIN">
                    <AdminRefills />
                  </ProtectedRoute>
                }
              />
              <Route
                path="services-test"
                element={
                  <ProtectedRoute requiredRole="ADMIN">
                    <ServicesTest />
                  </ProtectedRoute>
                }
              />
            </Route>

            {/* Catch all */}
            <Route path="*" element={<Navigate to="/dashboard" replace />} />
            </Routes>
          </Suspense>
        </Router>
      </ErrorBoundary>
    </ThemeProvider>
  );
}

export default App;
