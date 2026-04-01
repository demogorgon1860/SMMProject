import React, { Suspense, useEffect } from 'react';
import { BrowserRouter as Router, Routes, Route, Navigate, useLocation } from 'react-router-dom';
import { ErrorBoundary } from 'react-error-boundary';
import { motion, AnimatePresence } from 'framer-motion';
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

// Admin pages (separate chunk)
const AdminDashboard = React.lazy(() => import('./pages/AdminDashboard').then(m => ({ default: m.AdminDashboard })));
const AdminOrders = React.lazy(() => import('./pages/AdminOrders').then(m => ({ default: m.AdminOrders })));
const AdminPayments = React.lazy(() => import('./pages/AdminPayments').then(m => ({ default: m.AdminPayments })));
const AdminRefills = React.lazy(() => import('./pages/AdminRefills').then(m => ({ default: m.AdminRefills })));
const ServicesTest = React.lazy(() => import('./pages/ServicesTest').then(m => ({ default: m.ServicesTest })));

function ErrorFallback({ error, resetErrorBoundary }: { error: Error; resetErrorBoundary: () => void }) {
  return (
    <div className="min-h-screen flex items-center justify-center bg-dark-50 dark:bg-dark-900">
      <motion.div
        className="max-w-md w-full p-8 bg-white dark:bg-dark-800 rounded-2xl shadow-soft-lg dark:shadow-dark-lg border border-dark-100 dark:border-dark-700 text-center"
        initial={{ opacity: 0, y: 20 }}
        animate={{ opacity: 1, y: 0 }}
        transition={{ duration: 0.4 }}
      >
        <div className="w-16 h-16 rounded-full bg-red-100 dark:bg-red-900/30 flex items-center justify-center mx-auto mb-4">
          <svg className="w-8 h-8 text-red-600 dark:text-red-400" fill="none" viewBox="0 0 24 24" stroke="currentColor">
            <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M12 9v2m0 4h.01m-6.938 4h13.856c1.54 0 2.502-1.667 1.732-2.5L13.732 4c-.77-.833-1.964-.833-2.732 0L3.732 16.5c-.77.833.192 2.5 1.732 2.5z" />
          </svg>
        </div>
        <h2 className="text-xl font-semibold text-dark-900 dark:text-white mb-2">Something went wrong</h2>
        <p className="text-dark-500 dark:text-dark-400 mb-6 text-sm">{error.message}</p>
        <button
          onClick={resetErrorBoundary}
          className="px-6 py-2.5 bg-primary-600 text-white rounded-xl hover:bg-primary-700 transition-colors font-medium"
        >
          Try again
        </button>
      </motion.div>
    </div>
  );
}

function PageLoader() {
  return (
    <div className="flex flex-col items-center justify-center py-20 gap-3">
      <div className="relative">
        <div className="w-10 h-10 rounded-full border-2 border-dark-200 dark:border-dark-700" />
        <div className="absolute inset-0 w-10 h-10 rounded-full border-2 border-primary-600 border-t-transparent animate-spin" />
      </div>
      <p className="text-sm text-dark-400 dark:text-dark-500 animate-pulse">Loading...</p>
    </div>
  );
}

// Animated page wrapper
function AnimatedOutlet() {
  const location = useLocation();
  return (
    <AnimatePresence mode="wait">
      <motion.div
        key={location.pathname}
        initial={{ opacity: 0, y: 8 }}
        animate={{ opacity: 1, y: 0 }}
        exit={{ opacity: 0, y: -8 }}
        transition={{ duration: 0.2, ease: [0.16, 1, 0.3, 1] }}
      >
        <Suspense fallback={<PageLoader />}>
          <Routes location={location}>
            <Route index element={<Navigate to="/dashboard" replace />} />
            <Route path="dashboard" element={<Dashboard />} />
            <Route path="services" element={<Services />} />
            <Route path="orders" element={<Orders />} />
            <Route path="orders/new" element={<NewOrder />} />
            <Route path="add-funds" element={<AddFunds />} />
            <Route path="profile" element={<ProfileSettings />} />

            {/* Admin routes */}
            <Route path="admin" element={<ProtectedRoute requiredRole="ADMIN"><AdminDashboard /></ProtectedRoute>} />
            <Route path="admin/orders" element={<ProtectedRoute requiredRole="ADMIN"><AdminOrders /></ProtectedRoute>} />
            <Route path="admin/payments" element={<ProtectedRoute requiredRole="ADMIN"><AdminPayments /></ProtectedRoute>} />
            <Route path="admin/refills" element={<ProtectedRoute requiredRole="ADMIN"><AdminRefills /></ProtectedRoute>} />
            <Route path="services-test" element={<ProtectedRoute requiredRole="ADMIN"><ServicesTest /></ProtectedRoute>} />
          </Routes>
        </Suspense>
      </motion.div>
    </AnimatePresence>
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

              {/* Protected routes with animated transitions */}
              <Route
                path="/*"
                element={
                  <ProtectedRoute>
                    <Layout />
                  </ProtectedRoute>
                }
              >
                <Route path="*" element={<AnimatedOutlet />} />
              </Route>
            </Routes>
          </Suspense>
        </Router>
      </ErrorBoundary>
    </ThemeProvider>
  );
}

export default App;
