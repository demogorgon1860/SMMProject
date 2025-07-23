import React from 'react'
import { Navigate, useLocation } from 'react-router-dom'
import { useAuth } from '@/store/auth'
import { LoadingSpinner } from '@/components/ui'
import type { UserRole } from '@/types'

interface ProtectedRouteProps {
  children: React.ReactNode
  requiredRoles?: UserRole[]
  redirectTo?: string
}

export const ProtectedRoute: React.FC<ProtectedRouteProps> = ({
  children,
  requiredRoles = [],
  redirectTo = '/login',
}) => {
  const { user, isLoading, isAuthenticated } = useAuth()
  const location = useLocation()

  // Show loading spinner while checking authentication
  if (isLoading) {
    return (
      <div className="min-h-screen flex items-center justify-center bg-neutral-50 dark:bg-neutral-900">
        <LoadingSpinner size="lg" />
      </div>
    )
  }

  // Redirect to login if not authenticated
  if (!isAuthenticated || !user) {
    return (
      <Navigate
        to={redirectTo}
        state={{ from: location.pathname }}
        replace
      />
    )
  }

  // Check role-based access
  if (requiredRoles.length > 0 && !requiredRoles.includes(user.role)) {
    return (
      <Navigate
        to="/dashboard"
        replace
      />
    )
  }

  // Check if user account is active
  if (!user.isActive) {
    return (
      <div className="min-h-screen flex items-center justify-center bg-neutral-50 dark:bg-neutral-900">
        <div className="max-w-md w-full mx-4">
          <div className="bg-white dark:bg-neutral-800 rounded-lg shadow-lg p-6 text-center">
            <div className="w-16 h-16 mx-auto mb-4 text-warning-500">
              <svg viewBox="0 0 24 24" fill="currentColor">
                <path d="M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm-2 15l-5-5 1.41-1.41L10 14.17l7.59-7.59L19 8l-9 9z"/>
              </svg>
            </div>
            <h1 className="text-xl font-semibold text-neutral-900 dark:text-neutral-100 mb-2">
              Account Suspended
            </h1>
            <p className="text-neutral-600 dark:text-neutral-400 mb-4">
              Your account has been suspended. Please contact support for assistance.
            </p>
            <a
              href="mailto:support@smmpanel.com"
              className="bg-primary-600 hover:bg-primary-700 text-white px-4 py-2 rounded-md font-medium transition-colors inline-block"
            >
              Contact Support
            </a>
          </div>
        </div>
      </div>
    )
  }

  return <>{children}</>
}

export default ProtectedRoute
