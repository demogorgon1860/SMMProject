import React, { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { useAuthStore } from '../store/authStore';
import { userAPI } from '../services/api';

export const Dashboard: React.FC = () => {
  const { user } = useAuthStore();
  const [balance, setBalance] = useState<number | null>(null);
  const [loading, setLoading] = useState(true);
  const [userLoading, setUserLoading] = useState(true);

  useEffect(() => {
    // Wait a moment for auth store to initialize
    const timeout = setTimeout(() => {
      setUserLoading(false);
    }, 100);
    fetchBalance();
    return () => clearTimeout(timeout);
  }, []);
  
  const fetchBalance = async () => {
    try {
      const response = await userAPI.getBalance();
      setBalance(response.balance);
    } catch (error) {
      console.error('Failed to fetch balance:', error);
    } finally {
      setLoading(false);
    }
  };
  
  if (userLoading) {
    return (
      <div className="min-h-screen flex items-center justify-center" style={{ backgroundColor: '#f3f4f6' }}>
        <div className="text-gray-500">Loading...</div>
      </div>
    );
  }

  return (
    <div className="min-h-screen" style={{ backgroundColor: '#f3f4f6', color: '#111827' }}>
      <div className="px-4 py-6">
        <h1 className="text-2xl font-bold text-gray-900 mb-6" style={{ color: '#111827' }}>User Dashboard</h1>
        {!user && !userLoading && <p style={{ color: 'red' }}>No user data available!</p>}
        
        <div className="grid grid-cols-1 md:grid-cols-3 gap-6">
        <div className="bg-white rounded-lg shadow p-6">
          <h2 className="text-sm font-medium text-gray-500 mb-2">Username</h2>
          <p className="text-2xl font-bold text-gray-900">{user?.username}</p>
        </div>
        
        <div className="bg-white rounded-lg shadow p-6">
          <h2 className="text-sm font-medium text-gray-500 mb-2">Role</h2>
          <p className="text-2xl font-bold text-gray-900">{user?.role}</p>
        </div>
        
        <div className="bg-white rounded-lg shadow p-6">
          <h2 className="text-sm font-medium text-gray-500 mb-2">Balance</h2>
          {loading ? (
            <p className="text-2xl font-bold text-gray-900">Loading...</p>
          ) : (
            <p className="text-2xl font-bold text-green-600">
              ${balance?.toFixed(2) || '0.00'}
            </p>
          )}
        </div>
      </div>
      
      <div className="mt-8">
        <h2 className="text-xl font-semibold text-gray-900 mb-4">Quick Actions</h2>
        <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-4 gap-4">
          {user?.role !== 'ADMIN' && (
            <Link
              to="/orders/new"
              className="bg-blue-500 hover:bg-blue-600 text-white rounded-lg p-4 text-center transition"
            >
              Create New Order
            </Link>
          )}
          <Link
            to="/orders"
            className="bg-gray-500 hover:bg-gray-600 text-white rounded-lg p-4 text-center transition"
          >
            View Orders
          </Link>
          <Link
            to="/services-test"
            className="bg-purple-500 hover:bg-purple-600 text-white rounded-lg p-4 text-center transition"
          >
            Test Services
          </Link>
          <Link
            to="/profile"
            className="bg-green-500 hover:bg-green-600 text-white rounded-lg p-4 text-center transition"
          >
            Profile Settings
          </Link>
        </div>
      </div>
      </div>
    </div>
  );
};