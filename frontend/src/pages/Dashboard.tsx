import React, { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { useAuthStore } from '../store/authStore';
import { userAPI, orderAPI } from '../services/api';
import {
  Wallet,
  Plus,
  Package,
  CreditCard,
  Settings,
  TrendingUp,
  Clock,
  CheckCircle,
  AlertCircle,
  ArrowRight,
  RefreshCw,
} from 'lucide-react';

interface Order {
  id: number;
  service: { name: string };
  status: string;
  quantity: number;
  charge: number;
  createdAt: string;
}

export const Dashboard: React.FC = () => {
  const { user } = useAuthStore();
  const [balance, setBalance] = useState<number | null>(null);
  const [loading, setLoading] = useState(true);
  const [recentOrders, setRecentOrders] = useState<Order[]>([]);
  const [ordersLoading, setOrdersLoading] = useState(true);

  useEffect(() => {
    fetchBalance();
    fetchRecentOrders();
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

  const fetchRecentOrders = async () => {
    try {
      const response = await orderAPI.getOrders(undefined, undefined, undefined, 0, 5);
      setRecentOrders(response.content || []);
    } catch (error) {
      console.error('Failed to fetch orders:', error);
    } finally {
      setOrdersLoading(false);
    }
  };

  const getStatusIcon = (status: string) => {
    switch (status?.toLowerCase()) {
      case 'completed':
        return <CheckCircle size={16} className="text-accent-500" />;
      case 'pending':
      case 'processing':
      case 'in_progress':
        return <Clock size={16} className="text-yellow-500" />;
      case 'failed':
      case 'canceled':
      case 'cancelled':
        return <AlertCircle size={16} className="text-red-500" />;
      default:
        return <Clock size={16} className="text-dark-400" />;
    }
  };

  const getStatusColor = (status: string) => {
    switch (status?.toLowerCase()) {
      case 'completed':
        return 'bg-accent-100 text-accent-700 dark:bg-accent-900/30 dark:text-accent-400';
      case 'pending':
      case 'processing':
      case 'in_progress':
        return 'bg-yellow-100 text-yellow-700 dark:bg-yellow-900/30 dark:text-yellow-400';
      case 'failed':
      case 'canceled':
      case 'cancelled':
        return 'bg-red-100 text-red-700 dark:bg-red-900/30 dark:text-red-400';
      default:
        return 'bg-dark-100 text-dark-600 dark:bg-dark-700 dark:text-dark-400';
    }
  };

  const formatDate = (dateString: string) => {
    const date = new Date(dateString);
    return date.toLocaleDateString('en-US', {
      month: 'short',
      day: 'numeric',
      hour: '2-digit',
      minute: '2-digit',
    });
  };

  return (
    <div className="space-y-6 animate-fade-in">
      {/* Welcome Header */}
      <div className="flex flex-col sm:flex-row sm:items-center sm:justify-between gap-4">
        <div>
          <h1 className="text-2xl font-bold text-dark-900 dark:text-white">
            Welcome back, {user?.username}
          </h1>
          <p className="text-dark-500 dark:text-dark-400 mt-1">
            Here's what's happening with your account
          </p>
        </div>
        <button
          onClick={() => {
            fetchBalance();
            fetchRecentOrders();
          }}
          className="flex items-center gap-2 px-4 py-2 text-sm font-medium text-dark-600 hover:text-dark-900 dark:text-dark-400 dark:hover:text-white bg-white dark:bg-dark-800 border border-dark-200 dark:border-dark-700 rounded-xl hover:bg-dark-50 dark:hover:bg-dark-700 transition-colors"
        >
          <RefreshCw size={16} />
          Refresh
        </button>
      </div>

      {/* Stats Cards */}
      <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-4 gap-4">
        {/* Balance Card */}
        <div className="bg-gradient-to-br from-accent-500 to-accent-600 rounded-2xl p-6 text-white shadow-soft dark:shadow-dark-soft">
          <div className="flex items-center justify-between">
            <div>
              <p className="text-accent-100 text-sm font-medium">Current Balance</p>
              <p className="text-3xl font-bold mt-1">
                {loading ? (
                  <span className="animate-pulse">$---.--</span>
                ) : (
                  `$${balance?.toFixed(2) || '0.00'}`
                )}
              </p>
            </div>
            <div className="w-12 h-12 rounded-xl bg-white/20 flex items-center justify-center">
              <Wallet size={24} />
            </div>
          </div>
          <Link
            to="/add-funds"
            className="mt-4 flex items-center gap-1 text-sm font-medium text-accent-100 hover:text-white transition-colors"
          >
            Add funds <ArrowRight size={16} />
          </Link>
        </div>

        {/* Total Spent Card */}
        <div className="bg-white dark:bg-dark-800 rounded-2xl p-6 border border-dark-100 dark:border-dark-700 shadow-soft dark:shadow-dark-soft">
          <div className="flex items-center justify-between">
            <div>
              <p className="text-dark-500 dark:text-dark-400 text-sm font-medium">Total Spent</p>
              <p className="text-2xl font-bold text-dark-900 dark:text-white mt-1">
                ${(user as any)?.totalSpent?.toFixed(2) || '0.00'}
              </p>
            </div>
            <div className="w-12 h-12 rounded-xl bg-primary-100 dark:bg-primary-900/30 flex items-center justify-center">
              <TrendingUp size={24} className="text-primary-600 dark:text-primary-400" />
            </div>
          </div>
        </div>

        {/* Orders Card */}
        <div className="bg-white dark:bg-dark-800 rounded-2xl p-6 border border-dark-100 dark:border-dark-700 shadow-soft dark:shadow-dark-soft">
          <div className="flex items-center justify-between">
            <div>
              <p className="text-dark-500 dark:text-dark-400 text-sm font-medium">Total Orders</p>
              <p className="text-2xl font-bold text-dark-900 dark:text-white mt-1">
                {recentOrders.length > 0 ? `${recentOrders.length}+` : '0'}
              </p>
            </div>
            <div className="w-12 h-12 rounded-xl bg-blue-100 dark:bg-blue-900/30 flex items-center justify-center">
              <Package size={24} className="text-blue-600 dark:text-blue-400" />
            </div>
          </div>
        </div>

        {/* Role Card */}
        <div className="bg-white dark:bg-dark-800 rounded-2xl p-6 border border-dark-100 dark:border-dark-700 shadow-soft dark:shadow-dark-soft">
          <div className="flex items-center justify-between">
            <div>
              <p className="text-dark-500 dark:text-dark-400 text-sm font-medium">Account Type</p>
              <p className="text-2xl font-bold text-dark-900 dark:text-white mt-1">{user?.role || 'USER'}</p>
            </div>
            <div className="w-12 h-12 rounded-xl bg-purple-100 dark:bg-purple-900/30 flex items-center justify-center">
              <Settings size={24} className="text-purple-600 dark:text-purple-400" />
            </div>
          </div>
        </div>
      </div>

      {/* Quick Actions & Recent Orders */}
      <div className="grid grid-cols-1 lg:grid-cols-3 gap-6">
        {/* Quick Actions */}
        <div className="bg-white dark:bg-dark-800 rounded-2xl p-6 border border-dark-100 dark:border-dark-700 shadow-soft dark:shadow-dark-soft">
          <h2 className="text-lg font-semibold text-dark-900 dark:text-white mb-4">Quick Actions</h2>
          <div className="space-y-3">
            {user?.role !== 'ADMIN' && (
              <Link
                to="/orders/new"
                className="flex items-center gap-3 p-4 rounded-xl bg-primary-50 dark:bg-primary-900/20 border border-primary-100 dark:border-primary-900/30 text-primary-700 dark:text-primary-300 hover:bg-primary-100 dark:hover:bg-primary-900/30 transition-colors group"
              >
                <div className="w-10 h-10 rounded-lg bg-primary-100 dark:bg-primary-900/50 flex items-center justify-center group-hover:scale-110 transition-transform">
                  <Plus size={20} className="text-primary-600 dark:text-primary-400" />
                </div>
                <div>
                  <p className="font-medium">New Order</p>
                  <p className="text-sm text-primary-600/70 dark:text-primary-400/70">Create a new service order</p>
                </div>
              </Link>
            )}

            <Link
              to="/orders"
              className="flex items-center gap-3 p-4 rounded-xl bg-dark-50 dark:bg-dark-700/50 border border-dark-100 dark:border-dark-600 text-dark-700 dark:text-dark-300 hover:bg-dark-100 dark:hover:bg-dark-700 transition-colors group"
            >
              <div className="w-10 h-10 rounded-lg bg-dark-100 dark:bg-dark-600 flex items-center justify-center group-hover:scale-110 transition-transform">
                <Package size={20} className="text-dark-600 dark:text-dark-400" />
              </div>
              <div>
                <p className="font-medium">View Orders</p>
                <p className="text-sm text-dark-500 dark:text-dark-400">Check your order history</p>
              </div>
            </Link>

            <Link
              to="/add-funds"
              className="flex items-center gap-3 p-4 rounded-xl bg-accent-50 dark:bg-accent-900/20 border border-accent-100 dark:border-accent-900/30 text-accent-700 dark:text-accent-300 hover:bg-accent-100 dark:hover:bg-accent-900/30 transition-colors group"
            >
              <div className="w-10 h-10 rounded-lg bg-accent-100 dark:bg-accent-900/50 flex items-center justify-center group-hover:scale-110 transition-transform">
                <CreditCard size={20} className="text-accent-600 dark:text-accent-400" />
              </div>
              <div>
                <p className="font-medium">Add Funds</p>
                <p className="text-sm text-accent-600/70 dark:text-accent-400/70">Top up your balance</p>
              </div>
            </Link>

            <Link
              to="/profile"
              className="flex items-center gap-3 p-4 rounded-xl bg-dark-50 dark:bg-dark-700/50 border border-dark-100 dark:border-dark-600 text-dark-700 dark:text-dark-300 hover:bg-dark-100 dark:hover:bg-dark-700 transition-colors group"
            >
              <div className="w-10 h-10 rounded-lg bg-dark-100 dark:bg-dark-600 flex items-center justify-center group-hover:scale-110 transition-transform">
                <Settings size={20} className="text-dark-600 dark:text-dark-400" />
              </div>
              <div>
                <p className="font-medium">Settings</p>
                <p className="text-sm text-dark-500 dark:text-dark-400">Manage your account</p>
              </div>
            </Link>
          </div>
        </div>

        {/* Recent Orders */}
        <div className="lg:col-span-2 bg-white dark:bg-dark-800 rounded-2xl p-6 border border-dark-100 dark:border-dark-700 shadow-soft dark:shadow-dark-soft">
          <div className="flex items-center justify-between mb-4">
            <h2 className="text-lg font-semibold text-dark-900 dark:text-white">Recent Orders</h2>
            <Link
              to="/orders"
              className="text-sm font-medium text-primary-600 hover:text-primary-700 dark:text-primary-400 dark:hover:text-primary-300 flex items-center gap-1 transition-colors"
            >
              View all <ArrowRight size={16} />
            </Link>
          </div>

          {ordersLoading ? (
            <div className="space-y-3">
              {[1, 2, 3].map((i) => (
                <div key={i} className="animate-pulse flex items-center gap-4 p-4 rounded-xl bg-dark-50 dark:bg-dark-700/50">
                  <div className="w-10 h-10 rounded-lg bg-dark-200 dark:bg-dark-600" />
                  <div className="flex-1 space-y-2">
                    <div className="h-4 bg-dark-200 dark:bg-dark-600 rounded w-1/3" />
                    <div className="h-3 bg-dark-200 dark:bg-dark-600 rounded w-1/2" />
                  </div>
                </div>
              ))}
            </div>
          ) : recentOrders.length > 0 ? (
            <div className="space-y-3">
              {recentOrders.map((order) => (
                <div
                  key={order.id}
                  className="flex items-center gap-4 p-4 rounded-xl bg-dark-50 dark:bg-dark-700/50 hover:bg-dark-100 dark:hover:bg-dark-700 transition-colors"
                >
                  <div className="w-10 h-10 rounded-lg bg-primary-100 dark:bg-primary-900/30 flex items-center justify-center">
                    <Package size={20} className="text-primary-600 dark:text-primary-400" />
                  </div>
                  <div className="flex-1 min-w-0">
                    <p className="font-medium text-dark-900 dark:text-white truncate">
                      {order.service?.name || `Order #${order.id}`}
                    </p>
                    <p className="text-sm text-dark-500 dark:text-dark-400">
                      {order.quantity} units - ${order.charge?.toFixed(2)}
                    </p>
                  </div>
                  <div className="text-right">
                    <span className={`inline-flex items-center gap-1.5 px-2.5 py-1 rounded-lg text-xs font-medium ${getStatusColor(order.status)}`}>
                      {getStatusIcon(order.status)}
                      {order.status}
                    </span>
                    <p className="text-xs text-dark-400 dark:text-dark-500 mt-1">
                      {formatDate(order.createdAt)}
                    </p>
                  </div>
                </div>
              ))}
            </div>
          ) : (
            <div className="text-center py-12">
              <div className="w-16 h-16 rounded-full bg-dark-100 dark:bg-dark-700 flex items-center justify-center mx-auto mb-4">
                <Package size={32} className="text-dark-400" />
              </div>
              <p className="text-dark-500 dark:text-dark-400 font-medium">No orders yet</p>
              <p className="text-sm text-dark-400 dark:text-dark-500 mt-1">Create your first order to get started</p>
              {user?.role !== 'ADMIN' && (
                <Link
                  to="/orders/new"
                  className="inline-flex items-center gap-2 mt-4 px-4 py-2 rounded-xl bg-primary-600 text-white font-medium hover:bg-primary-700 transition-colors"
                >
                  <Plus size={18} />
                  Create Order
                </Link>
              )}
            </div>
          )}
        </div>
      </div>
    </div>
  );
};
