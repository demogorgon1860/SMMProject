import React, { useEffect } from 'react';
import { BrowserRouter as Router, Routes, Route, Navigate } from 'react-router-dom';
import { useAuthStore } from './store/authStore';

// Components
import { Layout } from './components/Layout';
import { LoginForm } from './components/auth/LoginForm';
import { RegisterForm } from './components/auth/RegisterForm';
import { ProtectedRoute } from './components/auth/ProtectedRoute';

// Pages
import { Dashboard } from './pages/Dashboard';
import { Services } from './pages/Services';
import { Orders } from './pages/Orders';
import { NewOrder } from './pages/NewOrder';
import { ProfileSettings } from './pages/ProfileSettings';
import { AdminDashboard } from './pages/AdminDashboard';
import { AdminOrders } from './pages/AdminOrders';
import { AdminPayments } from './pages/AdminPayments';
import { AdminRefills } from './pages/AdminRefills';
import { ServicesTest } from './pages/ServicesTest';
import { TermsOfService } from './pages/TermsOfService';
import { AddFunds } from './pages/AddFunds';

function App() {
  const { checkAuth } = useAuthStore();
  
  useEffect(() => {
    checkAuth();
  }, [checkAuth]);
  
  return (
    <Router>
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
    </Router>
  );
}

export default App;