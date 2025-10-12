import React, { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { adminAPI } from '../services/api';
import { DashboardStats } from '../types';

export const AdminDashboard: React.FC = () => {
  const [stats, setStats] = useState<DashboardStats | null>(null);
  const [loading, setLoading] = useState(true);
  
  useEffect(() => {
    fetchDashboardStats();
  }, []);
  
  const fetchDashboardStats = async () => {
    try {
      const response = await adminAPI.getDashboard();
      setStats(response);
    } catch (error) {
      console.error('Failed to fetch dashboard stats:', error);
    } finally {
      setLoading(false);
    }
  };
  
  if (loading) return <div className="text-center py-8">Loading dashboard...</div>;
  
  return (
    <div className="px-4 py-6">
      <h1 className="text-2xl font-bold text-gray-900 mb-6">Admin Dashboard</h1>
      
      <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-6 mb-8">
        <div className="bg-white rounded-lg shadow p-6">
          <h3 className="text-sm font-medium text-gray-500 mb-2">Total Orders</h3>
          <p className="text-3xl font-bold text-gray-900">{stats?.totalOrders || 0}</p>
        </div>
        
        <div className="bg-white rounded-lg shadow p-6">
          <h3 className="text-sm font-medium text-gray-500 mb-2">Total Revenue</h3>
          <p className="text-3xl font-bold text-green-600">
            ${stats?.totalRevenue?.toFixed(2) || '0.00'}
          </p>
        </div>
        
        <div className="bg-white rounded-lg shadow p-6">
          <h3 className="text-sm font-medium text-gray-500 mb-2">Active Users</h3>
          <p className="text-3xl font-bold text-blue-600">{stats?.activeUsers || 0}</p>
        </div>
        
        <div className="bg-white rounded-lg shadow p-6">
          <h3 className="text-sm font-medium text-gray-500 mb-2">Pending Orders</h3>
          <p className="text-3xl font-bold text-yellow-600">{stats?.pendingOrders || 0}</p>
        </div>
        
        <div className="bg-white rounded-lg shadow p-6">
          <h3 className="text-sm font-medium text-gray-500 mb-2">Completed Orders</h3>
          <p className="text-3xl font-bold text-green-600">{stats?.completedOrders || 0}</p>
        </div>
        
        <div className="bg-white rounded-lg shadow p-6">
          <h3 className="text-sm font-medium text-gray-500 mb-2">Failed Orders</h3>
          <p className="text-3xl font-bold text-red-600">{stats?.failedOrders || 0}</p>
        </div>
      </div>
      
      <div className="grid grid-cols-1 md:grid-cols-2 gap-6">
        <div className="bg-white rounded-lg shadow p-6">
          <h2 className="text-lg font-semibold text-gray-900 mb-4">Quick Actions</h2>
          <div className="space-y-2">
            <Link
              to="/admin/orders"
              className="block w-full bg-blue-500 hover:bg-blue-600 text-white py-2 px-4 rounded text-center transition"
            >
              View All Orders
            </Link>
            <Link
              to="/admin/users"
              className="block w-full bg-green-500 hover:bg-green-600 text-white py-2 px-4 rounded text-center transition"
            >
              Manage Users
            </Link>
            <Link
              to="/services-test"
              className="block w-full bg-purple-500 hover:bg-purple-600 text-white py-2 px-4 rounded text-center transition"
            >
              Test Services
            </Link>
          </div>
        </div>
        
        <div className="bg-white rounded-lg shadow p-6">
          <h2 className="text-lg font-semibold text-gray-900 mb-4">System Status</h2>
          <div className="space-y-3">
            <div className="flex justify-between items-center">
              <span className="text-sm text-gray-600">Database</span>
              <span className="text-sm font-medium text-green-600">Connected</span>
            </div>
            <div className="flex justify-between items-center">
              <span className="text-sm text-gray-600">Redis Cache</span>
              <span className="text-sm font-medium text-green-600">Active</span>
            </div>
            <div className="flex justify-between items-center">
              <span className="text-sm text-gray-600">Kafka</span>
              <span className="text-sm font-medium text-green-600">Running</span>
            </div>
            <div className="flex justify-between items-center">
              <span className="text-sm text-gray-600">Binom API</span>
              <span className="text-sm font-medium text-yellow-600">Check Required</span>
            </div>
            <div className="flex justify-between items-center">
              <span className="text-sm text-gray-600">YouTube API</span>
              <span className="text-sm font-medium text-yellow-600">Check Required</span>
            </div>
          </div>
        </div>
      </div>
    </div>
  );
};