import React, { Suspense, useEffect } from 'react'
import { BrowserRouter as Router, Routes, Route, Navigate } from 'react-router-dom'
import { QueryClient, QueryClientProvider } from 'react-query'
import { ReactQueryDevtools } from 'react-query/devtools'
import { Toaster } from 'react-hot-toast'
import { HelmetProvider } from 'react-helmet-async'
import { ErrorBoundary } from 'react-error-boundary'

// Store
import { useAuthStore } from '@/store/auth'
import { useWebSocketStore } from '@/store/websocket'

// Components
import { LoadingSpinner } from '@/components/ui/LoadingSpinner'
import { ErrorFallback } from '@/components/ui/ErrorFallback'
import { ProtectedRoute } from '@/components/auth/ProtectedRoute'

// Pages - Lazy loaded for better performance
const LoginPage = React.lazy(() => import('@/pages/auth/LoginPage'))
const RegisterPage = React.lazy(() => import('@/pages/auth/RegisterPage'))
const ForgotPasswordPage = React.lazy(() => import('@/pages/auth/ForgotPasswordPage'))
const DashboardPage = React.lazy(() => import('@/pages/DashboardPage'))
const OrdersPage = React.lazy(() => import('@/pages/OrdersPage'))
const NewOrderPage = React.lazy(() => import('@/pages/NewOrderPage'))
const BalancePage = React.lazy(() => import('@/pages/BalancePage'))
const ApiDocsPage = React.lazy(() => import('@/pages/ApiDocsPage'))
const SettingsPage = React.lazy(() => import('@/pages/SettingsPage'))
const AdminDashboardPage = React.lazy(() => import('@/pages/admin/AdminDashboardPage'))
const AdminOrdersPage = React.lazy(() => import('@/pages/admin/AdminOrdersPage'))
const AdminUsersPage = React.lazy(() => import('@/pages/admin/AdminUsersPage'))
const AdminSettingsPage = React.lazy(() => import('@/pages/admin/AdminSettingsPage'))
const AdminTrafficSourcesPage = React.lazy(() => import('@/pages/admin/AdminTrafficSourcesPage'))
const AdminReportsPage = React.lazy(() => import('@/pages/admin/AdminReportsPage'))
const NotFoundPage = React.lazy(() => import('@/pages/NotFoundPage'))

// React Query Client Configuration
const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      retry: (failureCount, error: any) => {
        // Don't retry on 4xx errors
        if (error?.response?.status >= 400 && error?.response?.status < 500) {
          return false
        }
        return failureCount < 2
      },
      staleTime: 5 * 60 * 1000, // 5 minutes
      cacheTime: 10 * 60 * 1000, // 10 minutes
      refetchOnWindowFocus: false,
      refetchOnReconnect: true,
    },
    mutations: {
      retry: false,
    },
  },
})

// Loading component for Suspense
const PageLoader: React.FC = () => (
  <div className="min-h-screen flex items-center justify-center bg-neutral-50 dark:bg-neutral-900">
    <LoadingSpinner size="lg" />
  </div>
)

// Main App Component
const App: React.FC = () => {
  const { user, initializeAuth, isLoading } = useAuthStore()
  const { connect, disconnect } = useWebSocketStore()

  // Initialize authentication on app start
  useEffect(() => {
    initializeAuth()
  }, [initializeAuth])

  // Connect/disconnect WebSocket based on auth status
  useEffect(() => {
    if (user) {
      connect()
    } else {
      disconnect()
    }

    return () => {
      disconnect()
    }
  }, [user, connect, disconnect])

  // Show loading spinner while checking authentication
  if (isLoading) {
    return <PageLoader />
  }

  return (
    <ErrorBoundary
      FallbackComponent={ErrorFallback}
      onError={(error, errorInfo) => {
        console.error('Application Error:', error, errorInfo)
        // Send to error tracking service (Sentry, etc.)
      }}
    >
      <HelmetProvider>
        <QueryClientProvider client={queryClient}>
          <Router>
            <div className="min-h-screen bg-neutral-50 dark:bg-neutral-900">
              <Suspense fallback={<PageLoader />}>
                <Routes>
                  {/* Public Routes */}
                  <Route
                    path="/login"
                    element={
                      user ? <Navigate to="/dashboard" replace /> : <LoginPage />
                    }
                  />
                  <Route
                    path="/register"
                    element={
                      user ? <Navigate to="/dashboard" replace /> : <RegisterPage />
                    }
                  />
                  <Route
                    path="/forgot-password"
                    element={
                      user ? <Navigate to="/dashboard" replace /> : <ForgotPasswordPage />
                    }
                  />

                  {/* Protected User Routes */}
                  <Route
                    path="/dashboard"
                    element={
                      <ProtectedRoute>
                        <DashboardPage />
                      </ProtectedRoute>
                    }
                  />
                  <Route
                    path="/orders"
                    element={
                      <ProtectedRoute>
                        <OrdersPage />
                      </ProtectedRoute>
                    }
                  />
                  <Route
                    path="/orders/new"
                    element={
                      <ProtectedRoute>
                        <NewOrderPage />
                      </ProtectedRoute>
                    }
                  />
                  <Route
                    path="/balance"
                    element={
                      <ProtectedRoute>
                        <BalancePage />
                      </ProtectedRoute>
                    }
                  />
                  <Route
                    path="/api-docs"
                    element={
                      <ProtectedRoute>
                        <ApiDocsPage />
                      </ProtectedRoute>
                    }
                  />
                  <Route
                    path="/settings"
                    element={
                      <ProtectedRoute>
                        <SettingsPage />
                      </ProtectedRoute>
                    }
                  />

                  {/* Admin Routes */}
                  <Route
                    path="/admin"
                    element={
                      <ProtectedRoute requiredRoles={['ADMIN', 'OPERATOR']}>
                        <AdminDashboardPage />
                      </ProtectedRoute>
                    }
                  />
                  <Route
                    path="/admin/orders"
                    element={
                      <ProtectedRoute requiredRoles={['ADMIN', 'OPERATOR']}>
                        <AdminOrdersPage />
                      </ProtectedRoute>
                    }
                  />
                  <Route
                    path="/admin/users"
                    element={
                      <ProtectedRoute requiredRoles={['ADMIN']}>
                        <AdminUsersPage />
                      </ProtectedRoute>
                    }
                  />
                  <Route
                    path="/admin/settings"
                    element={
                      <ProtectedRoute requiredRoles={['ADMIN']}>
                        <AdminSettingsPage />
                      </ProtectedRoute>
                    }
                  />
                  <Route
                    path="/admin/traffic-sources"
                    element={
                      <ProtectedRoute requiredRoles={['ADMIN']}>
                        <AdminTrafficSourcesPage />
                      </ProtectedRoute>
                    }
                  />
                  <Route
                    path="/admin/reports"
                    element={
                      <ProtectedRoute requiredRoles={['ADMIN', 'OPERATOR']}>
                        <AdminReportsPage />
                      </ProtectedRoute>
                    }
                  />

                  {/* Default Routes */}
                  <Route
                    path="/"
                    element={
                      <Navigate
                        to={user ? "/dashboard" : "/login"}
                        replace
                      />
                    }
                  />

                  {/* 404 Route */}
                  <Route path="*" element={<NotFoundPage />} />
                </Routes>
              </Suspense>

              {/* Global Toast Notifications */}
              <Toaster
                position="top-right"
                gutter={8}
                containerClassName=""
                containerStyle={{}}
                toastOptions={{
                  className: '',
                  duration: 4000,
                  style: {
                    background: '#363636',
                    color: '#fff',
                  },
                  success: {
                    duration: 3000,
                    iconTheme: {
                      primary: '#22c55e',
                      secondary: '#fff',
                    },
                  },
                  error: {
                    duration: 5000,
                    iconTheme: {
                      primary: '#ef4444',
                      secondary: '#fff',
                    },
                  },
                  loading: {
                    duration: Infinity,
                  },
                }}
              />

              {/* React Query DevTools (development only) */}
              {import.meta.env.DEV && (
                <ReactQueryDevtools
                  initialIsOpen={false}
                  position="bottom-right"
                />
              )}
            </div>
          </Router>
        </QueryClientProvider>
      </HelmetProvider>
    </ErrorBoundary>
  )
}

export default App