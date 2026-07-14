import type { ReactNode } from 'react';
import { Navigate, useLocation } from 'react-router-dom';
import { useAuthStore } from '../../store/authStore';

interface ProtectedRouteProps {
  children: ReactNode;
  /** If set, user must have this role (ADMIN bypass implicit). */
  requiredRole?: 'USER' | 'ADMIN';
  /** Where to send unauthenticated users. */
  loginPath?: string;
}

export function ProtectedRoute({ children, requiredRole, loginPath = '/login' }: ProtectedRouteProps) {
  const isAuthenticated = useAuthStore((s) => s.isAuthenticated);
  const authChecked = useAuthStore((s) => s.authChecked);
  const user = useAuthStore((s) => s.user);
  const location = useLocation();

  // Don't render protected content off a stale persisted token before checkAuth() has verified it
  // with the backend — otherwise a logged-out/expired session briefly flashes the protected shell.
  if (!authChecked) {
    return null;
  }

  if (!isAuthenticated) {
    // Preserve attempted URL so we can bounce back after login.
    return <Navigate to={loginPath} state={{ from: location }} replace />;
  }
  if (requiredRole && user?.role !== requiredRole && user?.role !== 'ADMIN') {
    return <Navigate to="/dashboard" replace />;
  }
  return <>{children}</>;
}
