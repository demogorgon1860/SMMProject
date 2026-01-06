import React, { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { adminAPI } from '../services/api';
import { DashboardStats } from '../types';
import {
  Package,
  DollarSign,
  Users,
  Clock,
  CheckCircle,
  XCircle,
  TrendingUp,
  CreditCard,
  TestTube,
  Database,
  Server,
  Activity,
  RefreshCw,
  ArrowRight,
  Shield,
  BarChart3,
  AlertTriangle,
} from 'lucide-react';

interface StatCardProps {
  title: string;
  value: string | number;
  icon: React.ReactNode;
  iconBg: string;
  trend?: { value: string; positive: boolean };
}

const StatCard: React.FC<StatCardProps> = ({ title, value, icon, iconBg, trend }) => (
  <div className="bg-white dark:bg-dark-800 rounded-2xl border border-dark-100 dark:border-dark-700 p-6 shadow-soft dark:shadow-dark-soft">
    <div className="flex items-start justify-between">
      <div>
        <p className="text-sm font-medium text-dark-500 dark:text-dark-400">{title}</p>
        <p className="text-2xl font-bold text-dark-900 dark:text-white mt-1">{value}</p>
        {trend && (
          <p className={`text-xs font-medium mt-2 ${trend.positive ? 'text-accent-600' : 'text-red-600'}`}>
            {trend.positive ? '+' : ''}{trend.value} from last period
          </p>
        )}
      </div>
      <div className={`w-12 h-12 rounded-xl ${iconBg} flex items-center justify-center`}>
        {icon}
      </div>
    </div>
  </div>
);

interface StatusItemProps {
  label: string;
  status: 'online' | 'warning' | 'offline';
  icon: React.ReactNode;
}

const StatusItem: React.FC<StatusItemProps> = ({ label, status, icon }) => {
  const statusConfig = {
    online: { color: 'text-accent-500', bg: 'bg-accent-100 dark:bg-accent-900/30', label: 'Online' },
    warning: { color: 'text-yellow-500', bg: 'bg-yellow-100 dark:bg-yellow-900/30', label: 'Warning' },
    offline: { color: 'text-red-500', bg: 'bg-red-100 dark:bg-red-900/30', label: 'Offline' },
  };

  const config = statusConfig[status];

  return (
    <div className="flex items-center justify-between py-3">
      <div className="flex items-center gap-3">
        <div className={`w-8 h-8 rounded-lg ${config.bg} flex items-center justify-center`}>
          <span className={config.color}>{icon}</span>
        </div>
        <span className="text-sm font-medium text-dark-700 dark:text-dark-300">{label}</span>
      </div>
      <div className="flex items-center gap-2">
        <div className={`w-2 h-2 rounded-full ${config.color.replace('text-', 'bg-')}`} />
        <span className={`text-sm font-medium ${config.color}`}>{config.label}</span>
      </div>
    </div>
  );
};

export const AdminDashboard: React.FC = () => {
  const [stats, setStats] = useState<DashboardStats | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    fetchDashboardStats();
  }, []);

  const fetchDashboardStats = async () => {
    setLoading(true);
    try {
      const response = await adminAPI.getDashboard();
      setStats(response);
    } catch (error) {
      console.error('Failed to fetch dashboard stats:', error);
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="space-y-6 animate-fade-in">
      {/* Header */}
      <div className="flex flex-col sm:flex-row sm:items-center sm:justify-between gap-4">
        <div>
          <div className="flex items-center gap-3">
            <div className="w-10 h-10 rounded-xl bg-primary-100 dark:bg-primary-900/30 flex items-center justify-center">
              <Shield size={20} className="text-primary-600 dark:text-primary-400" />
            </div>
            <div>
              <h1 className="text-2xl font-bold text-dark-900 dark:text-white">Admin Dashboard</h1>
              <p className="text-sm text-dark-500 dark:text-dark-400">System overview and management</p>
            </div>
          </div>
        </div>
        <button
          onClick={fetchDashboardStats}
          disabled={loading}
          className="flex items-center gap-2 px-4 py-2 text-sm font-medium text-dark-600 hover:text-dark-900 dark:text-dark-400 dark:hover:text-white bg-white dark:bg-dark-800 border border-dark-200 dark:border-dark-700 rounded-xl hover:bg-dark-50 dark:hover:bg-dark-700 transition-colors disabled:opacity-50"
        >
          <RefreshCw size={16} className={loading ? 'animate-spin' : ''} />
          Refresh
        </button>
      </div>

      {/* Loading State */}
      {loading && !stats && (
        <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 xl:grid-cols-6 gap-4">
          {[...Array(6)].map((_, i) => (
            <div key={i} className="bg-white dark:bg-dark-800 rounded-2xl border border-dark-100 dark:border-dark-700 p-6 animate-pulse">
              <div className="h-4 bg-dark-200 dark:bg-dark-600 rounded w-1/2 mb-3" />
              <div className="h-8 bg-dark-200 dark:bg-dark-600 rounded w-2/3" />
            </div>
          ))}
        </div>
      )}

      {/* Stats Grid */}
      {stats && (
        <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 xl:grid-cols-6 gap-4">
          <StatCard
            title="Total Orders"
            value={stats.totalOrders?.toLocaleString() || 0}
            icon={<Package size={24} className="text-primary-600 dark:text-primary-400" />}
            iconBg="bg-primary-100 dark:bg-primary-900/30"
          />
          <StatCard
            title="Total Revenue"
            value={`$${stats.totalRevenue?.toFixed(2) || '0.00'}`}
            icon={<DollarSign size={24} className="text-accent-600 dark:text-accent-400" />}
            iconBg="bg-accent-100 dark:bg-accent-900/30"
          />
          <StatCard
            title="Active Users"
            value={stats.activeUsers?.toLocaleString() || 0}
            icon={<Users size={24} className="text-blue-600 dark:text-blue-400" />}
            iconBg="bg-blue-100 dark:bg-blue-900/30"
          />
          <StatCard
            title="Pending"
            value={stats.pendingOrders?.toLocaleString() || 0}
            icon={<Clock size={24} className="text-yellow-600 dark:text-yellow-400" />}
            iconBg="bg-yellow-100 dark:bg-yellow-900/30"
          />
          <StatCard
            title="Completed"
            value={stats.completedOrders?.toLocaleString() || 0}
            icon={<CheckCircle size={24} className="text-accent-600 dark:text-accent-400" />}
            iconBg="bg-accent-100 dark:bg-accent-900/30"
          />
          <StatCard
            title="Failed"
            value={stats.failedOrders?.toLocaleString() || 0}
            icon={<XCircle size={24} className="text-red-600 dark:text-red-400" />}
            iconBg="bg-red-100 dark:bg-red-900/30"
          />
        </div>
      )}

      {/* Main Content Grid */}
      <div className="grid grid-cols-1 lg:grid-cols-3 gap-6">
        {/* Quick Actions */}
        <div className="bg-white dark:bg-dark-800 rounded-2xl border border-dark-100 dark:border-dark-700 p-6 shadow-soft dark:shadow-dark-soft">
          <div className="flex items-center gap-3 mb-6">
            <div className="w-10 h-10 rounded-xl bg-primary-100 dark:bg-primary-900/30 flex items-center justify-center">
              <BarChart3 size={20} className="text-primary-600 dark:text-primary-400" />
            </div>
            <h2 className="text-lg font-semibold text-dark-900 dark:text-white">Quick Actions</h2>
          </div>

          <div className="space-y-3">
            <Link
              to="/admin/orders"
              className="flex items-center justify-between p-4 rounded-xl bg-dark-50 dark:bg-dark-700/50 hover:bg-dark-100 dark:hover:bg-dark-700 transition-colors group"
            >
              <div className="flex items-center gap-3">
                <div className="w-10 h-10 rounded-lg bg-blue-100 dark:bg-blue-900/30 flex items-center justify-center">
                  <Package size={18} className="text-blue-600 dark:text-blue-400" />
                </div>
                <div>
                  <p className="font-medium text-dark-900 dark:text-white">All Orders</p>
                  <p className="text-xs text-dark-500 dark:text-dark-400">View and manage orders</p>
                </div>
              </div>
              <ArrowRight size={18} className="text-dark-400 group-hover:text-dark-600 dark:group-hover:text-dark-200 transition-colors" />
            </Link>

            <Link
              to="/admin/payments"
              className="flex items-center justify-between p-4 rounded-xl bg-dark-50 dark:bg-dark-700/50 hover:bg-dark-100 dark:hover:bg-dark-700 transition-colors group"
            >
              <div className="flex items-center gap-3">
                <div className="w-10 h-10 rounded-lg bg-accent-100 dark:bg-accent-900/30 flex items-center justify-center">
                  <CreditCard size={18} className="text-accent-600 dark:text-accent-400" />
                </div>
                <div>
                  <p className="font-medium text-dark-900 dark:text-white">Payments</p>
                  <p className="text-xs text-dark-500 dark:text-dark-400">Review payment history</p>
                </div>
              </div>
              <ArrowRight size={18} className="text-dark-400 group-hover:text-dark-600 dark:group-hover:text-dark-200 transition-colors" />
            </Link>

            <Link
              to="/admin/refills"
              className="flex items-center justify-between p-4 rounded-xl bg-dark-50 dark:bg-dark-700/50 hover:bg-dark-100 dark:hover:bg-dark-700 transition-colors group"
            >
              <div className="flex items-center gap-3">
                <div className="w-10 h-10 rounded-lg bg-purple-100 dark:bg-purple-900/30 flex items-center justify-center">
                  <TrendingUp size={18} className="text-purple-600 dark:text-purple-400" />
                </div>
                <div>
                  <p className="font-medium text-dark-900 dark:text-white">Refills</p>
                  <p className="text-xs text-dark-500 dark:text-dark-400">Manage refill requests</p>
                </div>
              </div>
              <ArrowRight size={18} className="text-dark-400 group-hover:text-dark-600 dark:group-hover:text-dark-200 transition-colors" />
            </Link>

            <Link
              to="/services-test"
              className="flex items-center justify-between p-4 rounded-xl bg-dark-50 dark:bg-dark-700/50 hover:bg-dark-100 dark:hover:bg-dark-700 transition-colors group"
            >
              <div className="flex items-center gap-3">
                <div className="w-10 h-10 rounded-lg bg-orange-100 dark:bg-orange-900/30 flex items-center justify-center">
                  <TestTube size={18} className="text-orange-600 dark:text-orange-400" />
                </div>
                <div>
                  <p className="font-medium text-dark-900 dark:text-white">Test Services</p>
                  <p className="text-xs text-dark-500 dark:text-dark-400">Debug and test services</p>
                </div>
              </div>
              <ArrowRight size={18} className="text-dark-400 group-hover:text-dark-600 dark:group-hover:text-dark-200 transition-colors" />
            </Link>
          </div>
        </div>

        {/* System Status */}
        <div className="lg:col-span-2 bg-white dark:bg-dark-800 rounded-2xl border border-dark-100 dark:border-dark-700 p-6 shadow-soft dark:shadow-dark-soft">
          <div className="flex items-center gap-3 mb-6">
            <div className="w-10 h-10 rounded-xl bg-accent-100 dark:bg-accent-900/30 flex items-center justify-center">
              <Activity size={20} className="text-accent-600 dark:text-accent-400" />
            </div>
            <h2 className="text-lg font-semibold text-dark-900 dark:text-white">System Status</h2>
          </div>

          <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
            <div className="space-y-1 divide-y divide-dark-100 dark:divide-dark-700">
              <StatusItem
                label="PostgreSQL Database"
                status="online"
                icon={<Database size={16} />}
              />
              <StatusItem
                label="Redis Cache"
                status="online"
                icon={<Server size={16} />}
              />
              <StatusItem
                label="Kafka Queue"
                status="online"
                icon={<Activity size={16} />}
              />
            </div>
            <div className="space-y-1 divide-y divide-dark-100 dark:divide-dark-700">
              <StatusItem
                label="Binom API"
                status="warning"
                icon={<AlertTriangle size={16} />}
              />
              <StatusItem
                label="YouTube API"
                status="warning"
                icon={<AlertTriangle size={16} />}
              />
              <StatusItem
                label="Instagram Bot"
                status="online"
                icon={<Server size={16} />}
              />
            </div>
          </div>

          {/* Order Status Distribution */}
          {stats && (
            <div className="mt-6 pt-6 border-t border-dark-100 dark:border-dark-700">
              <h3 className="text-sm font-medium text-dark-700 dark:text-dark-300 mb-4">Order Distribution</h3>
              <div className="flex gap-2">
                {stats.completedOrders > 0 && (
                  <div
                    className="h-3 bg-accent-500 rounded-full transition-all"
                    style={{ width: `${(stats.completedOrders / stats.totalOrders) * 100}%` }}
                    title={`Completed: ${stats.completedOrders}`}
                  />
                )}
                {stats.pendingOrders > 0 && (
                  <div
                    className="h-3 bg-yellow-500 rounded-full transition-all"
                    style={{ width: `${(stats.pendingOrders / stats.totalOrders) * 100}%` }}
                    title={`Pending: ${stats.pendingOrders}`}
                  />
                )}
                {stats.failedOrders > 0 && (
                  <div
                    className="h-3 bg-red-500 rounded-full transition-all"
                    style={{ width: `${(stats.failedOrders / stats.totalOrders) * 100}%` }}
                    title={`Failed: ${stats.failedOrders}`}
                  />
                )}
              </div>
              <div className="flex items-center gap-4 mt-3 text-xs">
                <div className="flex items-center gap-1.5">
                  <div className="w-2.5 h-2.5 rounded-full bg-accent-500" />
                  <span className="text-dark-600 dark:text-dark-400">Completed</span>
                </div>
                <div className="flex items-center gap-1.5">
                  <div className="w-2.5 h-2.5 rounded-full bg-yellow-500" />
                  <span className="text-dark-600 dark:text-dark-400">Pending</span>
                </div>
                <div className="flex items-center gap-1.5">
                  <div className="w-2.5 h-2.5 rounded-full bg-red-500" />
                  <span className="text-dark-600 dark:text-dark-400">Failed</span>
                </div>
              </div>
            </div>
          )}
        </div>
      </div>
    </div>
  );
};
