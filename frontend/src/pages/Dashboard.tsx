import React, { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { useAuthStore } from '../store/authStore';
import { userAPI, orderAPI, adminAPI } from '../services/api';
import { motion } from 'framer-motion';
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
  TestTube,
} from 'lucide-react';
import { formatDateShort } from '../utils/timezone';

interface Order {
  id: number;
  service?: { name: string };
  serviceName?: string;
  status: string;
  quantity: number;
  charge: number;
  createdAt: string;
  username?: string;
}

const containerVariants = {
  hidden: { opacity: 0 },
  visible: {
    opacity: 1,
    transition: { staggerChildren: 0.08, delayChildren: 0.05 },
  },
};

const itemVariants = {
  hidden: { opacity: 0, y: 16 },
  visible: { opacity: 1, y: 0, transition: { duration: 0.4, ease: [0.16, 1, 0.3, 1] } },
};

const cardHover = {
  y: -2,
  transition: { duration: 0.2, ease: 'easeOut' },
};

export const Dashboard: React.FC = () => {
  const user = useAuthStore((s) => s.user);
  const updateBalance = useAuthStore((s) => s.updateBalance);
  const [balance, setBalance] = useState<number | null>(null);
  const [loading, setLoading] = useState(true);
  const [recentOrders, setRecentOrders] = useState<Order[]>([]);
  const [ordersLoading, setOrdersLoading] = useState(true);
  const [totalOrders, setTotalOrders] = useState<number>(0);
  const [totalSpent, setTotalSpent] = useState<number>(0);
  const [refreshing, setRefreshing] = useState(false);

  useEffect(() => {
    fetchBalance();
    fetchRecentOrders();
  }, []);

  const fetchBalance = async () => {
    setLoading(true);
    try {
      const response = await userAPI.getBalance();
      setBalance(response.balance);
      updateBalance(Number(response.balance));
      if (response.totalSpent !== undefined && response.totalSpent !== null) {
        setTotalSpent(Number(response.totalSpent));
      }
      if (response.totalOrders !== undefined && response.totalOrders !== null) {
        setTotalOrders(Number(response.totalOrders));
      }
    } catch (error) {
      console.error('Failed to fetch balance:', error);
    } finally {
      setLoading(false);
    }
  };

  const fetchRecentOrders = async () => {
    setOrdersLoading(true);
    try {
      let orders: Order[] = [];
      if (user?.role === 'ADMIN') {
        const response = await adminAPI.getAllOrders(undefined, undefined, undefined, undefined, 0, 5);
        if (response?.orders && Array.isArray(response.orders)) {
          orders = response.orders;
        }
      } else {
        const response = await orderAPI.getOrders(undefined, undefined, undefined, undefined, 0, 5);
        if (response?.data?.content && Array.isArray(response.data.content)) {
          orders = response.data.content;
        } else if (response?.content && Array.isArray(response.content)) {
          orders = response.content;
        } else if (response?.data && Array.isArray(response.data)) {
          orders = response.data;
        } else if (Array.isArray(response)) {
          orders = response;
        }
      }
      setRecentOrders(orders.slice(0, 5));
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
    return formatDateShort(dateString);
  };

  return (
    <motion.div
      className="space-y-6"
      variants={containerVariants}
      initial="hidden"
      animate="visible"
    >
      {/* Welcome Header */}
      <motion.div
        className="flex flex-col sm:flex-row sm:items-center sm:justify-between gap-4"
        variants={itemVariants}
      >
        <div>
          <h1 className="text-2xl font-bold text-dark-900 dark:text-white">
            Welcome back, {user?.username}
          </h1>
          <p className="text-dark-500 dark:text-dark-400 mt-1">
            Here's what's happening with your account
          </p>
        </div>
        <motion.button
          onClick={async () => {
            setRefreshing(true);
            await Promise.all([fetchBalance(), fetchRecentOrders()]);
            setRefreshing(false);
          }}
          disabled={refreshing}
          className="flex items-center gap-2 px-4 py-2 text-sm font-medium text-dark-600 hover:text-dark-900 dark:text-dark-400 dark:hover:text-white bg-white dark:bg-dark-800 border border-dark-200 dark:border-dark-700 rounded-xl hover:bg-dark-50 dark:hover:bg-dark-700 transition-colors disabled:opacity-50"
          whileTap={{ scale: 0.97 }}
        >
          <motion.div animate={{ rotate: refreshing ? 360 : 0 }} transition={{ duration: 0.8, repeat: refreshing ? Infinity : 0, ease: 'linear' }}>
            <RefreshCw size={16} />
          </motion.div>
          Refresh
        </motion.button>
      </motion.div>

      {/* Stats Cards */}
      <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-4 gap-4">
        {/* Balance Card */}
        <motion.div variants={itemVariants} whileHover={cardHover}>
          <div className="bg-gradient-to-br from-accent-500 to-accent-600 rounded-2xl p-6 text-white shadow-lg shadow-accent-500/20 dark:shadow-accent-500/10">
            <div className="flex items-center justify-between">
              <div>
                <p className="text-accent-100 text-sm font-medium">Current Balance</p>
                <p className="text-3xl font-bold mt-1">
                  {loading ? (
                    <span className="inline-block w-28 h-9 bg-white/20 rounded-lg animate-pulse" />
                  ) : (
                    `$${balance?.toFixed(2) || '0.00'}`
                  )}
                </p>
              </div>
              <div className="w-12 h-12 rounded-xl bg-white/20 flex items-center justify-center backdrop-blur-sm">
                <Wallet size={24} />
              </div>
            </div>
            <Link
              to="/add-funds"
              className="mt-4 flex items-center gap-1 text-sm font-medium text-accent-100 hover:text-white transition-colors group"
            >
              Add funds <ArrowRight size={16} className="group-hover:translate-x-1 transition-transform" />
            </Link>
          </div>
        </motion.div>

        {/* Total Spent Card */}
        <motion.div variants={itemVariants} whileHover={cardHover}>
          <div className="bg-white dark:bg-dark-800 rounded-2xl p-6 border border-dark-100 dark:border-dark-700 shadow-soft dark:shadow-dark-soft h-full">
            <div className="flex items-center justify-between">
              <div>
                <p className="text-dark-500 dark:text-dark-400 text-sm font-medium">Total Spent</p>
                <p className="text-2xl font-bold text-dark-900 dark:text-white mt-1">
                  ${totalSpent.toFixed(2)}
                </p>
              </div>
              <div className="w-12 h-12 rounded-xl bg-primary-100 dark:bg-primary-900/30 flex items-center justify-center">
                <TrendingUp size={24} className="text-primary-600 dark:text-primary-400" />
              </div>
            </div>
          </div>
        </motion.div>

        {/* Orders Card */}
        <motion.div variants={itemVariants} whileHover={cardHover}>
          <div className="bg-white dark:bg-dark-800 rounded-2xl p-6 border border-dark-100 dark:border-dark-700 shadow-soft dark:shadow-dark-soft h-full">
            <div className="flex items-center justify-between">
              <div>
                <p className="text-dark-500 dark:text-dark-400 text-sm font-medium">Total Orders</p>
                <p className="text-2xl font-bold text-dark-900 dark:text-white mt-1">
                  {totalOrders}
                </p>
              </div>
              <div className="w-12 h-12 rounded-xl bg-blue-100 dark:bg-blue-900/30 flex items-center justify-center">
                <Package size={24} className="text-blue-600 dark:text-blue-400" />
              </div>
            </div>
          </div>
        </motion.div>

        {/* Role Card */}
        <motion.div variants={itemVariants} whileHover={cardHover}>
          <div className="bg-white dark:bg-dark-800 rounded-2xl p-6 border border-dark-100 dark:border-dark-700 shadow-soft dark:shadow-dark-soft h-full">
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
        </motion.div>
      </div>

      {/* Quick Actions & Recent Orders */}
      <div className="grid grid-cols-1 lg:grid-cols-3 gap-6">
        {/* Quick Actions */}
        <motion.div
          className="bg-white dark:bg-dark-800 rounded-2xl p-6 border border-dark-100 dark:border-dark-700 shadow-soft dark:shadow-dark-soft"
          variants={itemVariants}
        >
          <h2 className="text-lg font-semibold text-dark-900 dark:text-white mb-4">Quick Actions</h2>
          <div className="space-y-3">
            {user?.role === 'ADMIN' ? (
              <>
                <QuickAction to="/admin/orders" icon={Package} label="All Orders" desc="View and manage orders" color="primary" />
                <QuickAction to="/admin/payments" icon={CreditCard} label="Payments" desc="Review payment history" color="accent" />
                <QuickAction to="/admin/refills" icon={TrendingUp} label="Refills" desc="Manage refill requests" color="neutral" />
                <QuickAction to="/services-test" icon={TestTube} label="Test Services" desc="Debug and test services" color="neutral" />
              </>
            ) : (
              <>
                <QuickAction to="/orders/new" icon={Plus} label="New Order" desc="Create a new service order" color="primary" />
                <QuickAction to="/orders" icon={Package} label="View Orders" desc="Check your order history" color="neutral" />
                <QuickAction to="/add-funds" icon={CreditCard} label="Add Funds" desc="Top up your balance" color="accent" />
                <QuickAction to="/profile" icon={Settings} label="Settings" desc="Manage your account" color="neutral" />
              </>
            )}
          </div>
        </motion.div>

        {/* Recent Orders */}
        <motion.div
          className="lg:col-span-2 bg-white dark:bg-dark-800 rounded-2xl p-6 border border-dark-100 dark:border-dark-700 shadow-soft dark:shadow-dark-soft"
          variants={itemVariants}
        >
          <div className="flex items-center justify-between mb-4">
            <h2 className="text-lg font-semibold text-dark-900 dark:text-white">Recent Orders</h2>
            <Link
              to={user?.role === 'ADMIN' ? '/admin/orders' : '/orders'}
              className="text-sm font-medium text-primary-600 hover:text-primary-700 dark:text-primary-400 dark:hover:text-primary-300 flex items-center gap-1 transition-colors group"
            >
              View all <ArrowRight size={16} className="group-hover:translate-x-1 transition-transform" />
            </Link>
          </div>

          {ordersLoading ? (
            <div className="space-y-3">
              {[1, 2, 3].map((i) => (
                <div key={i} className="flex items-center gap-4 p-4 rounded-xl bg-dark-50 dark:bg-dark-700/50">
                  <div className="w-10 h-10 rounded-lg bg-dark-200 dark:bg-dark-600 animate-pulse" />
                  <div className="flex-1 space-y-2">
                    <div className="h-4 bg-dark-200 dark:bg-dark-600 rounded w-1/3 animate-pulse" />
                    <div className="h-3 bg-dark-200 dark:bg-dark-600 rounded w-1/2 animate-pulse" />
                  </div>
                </div>
              ))}
            </div>
          ) : recentOrders.length > 0 ? (
            <motion.div
              className="space-y-3"
              variants={{ visible: { transition: { staggerChildren: 0.05 } } }}
              initial="hidden"
              animate="visible"
            >
              {recentOrders.map((order) => (
                <motion.div
                  key={order.id}
                  className="flex items-center gap-4 p-4 rounded-xl bg-dark-50 dark:bg-dark-700/50 hover:bg-dark-100 dark:hover:bg-dark-700 transition-colors"
                  variants={itemVariants}
                >
                  <div className="w-10 h-10 rounded-lg bg-primary-100 dark:bg-primary-900/30 flex items-center justify-center">
                    <Package size={20} className="text-primary-600 dark:text-primary-400" />
                  </div>
                  <div className="flex-1 min-w-0">
                    <p className="font-medium text-dark-900 dark:text-white truncate">
                      {user?.role === 'ADMIN' && order.username && (
                        <span className="text-primary-600 dark:text-primary-400">{order.username}: </span>
                      )}
                      {order.serviceName || order.service?.name || `Order #${order.id}`}
                    </p>
                    <p className="text-sm text-dark-500 dark:text-dark-400">
                      {order.quantity} units - ${Number(order.charge || 0).toFixed(2)}
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
                </motion.div>
              ))}
            </motion.div>
          ) : (
            <div className="text-center py-12">
              <div className="w-16 h-16 rounded-full bg-dark-100 dark:bg-dark-700 flex items-center justify-center mx-auto mb-4">
                <Package size={32} className="text-dark-400" />
              </div>
              <p className="text-dark-500 dark:text-dark-400 font-medium">No orders yet</p>
              <p className="text-sm text-dark-400 dark:text-dark-500 mt-1">{user?.role === 'ADMIN' ? 'No orders have been placed yet' : 'Create your first order to get started'}</p>
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
        </motion.div>
      </div>
    </motion.div>
  );
};

// Quick Action component
function QuickAction({ to, icon: Icon, label, desc, color }: {
  to: string;
  icon: React.ElementType;
  label: string;
  desc: string;
  color: 'primary' | 'accent' | 'neutral';
}) {
  const colors = {
    primary: 'bg-primary-50 dark:bg-primary-900/20 border-primary-100 dark:border-primary-900/30 text-primary-700 dark:text-primary-300 hover:bg-primary-100 dark:hover:bg-primary-900/30',
    accent: 'bg-accent-50 dark:bg-accent-900/20 border-accent-100 dark:border-accent-900/30 text-accent-700 dark:text-accent-300 hover:bg-accent-100 dark:hover:bg-accent-900/30',
    neutral: 'bg-dark-50 dark:bg-dark-700/50 border-dark-100 dark:border-dark-600 text-dark-700 dark:text-dark-300 hover:bg-dark-100 dark:hover:bg-dark-700',
  };

  const iconColors = {
    primary: 'bg-primary-100 dark:bg-primary-900/50 text-primary-600 dark:text-primary-400',
    accent: 'bg-accent-100 dark:bg-accent-900/50 text-accent-600 dark:text-accent-400',
    neutral: 'bg-dark-100 dark:bg-dark-600 text-dark-600 dark:text-dark-400',
  };

  const descColors = {
    primary: 'text-primary-600/70 dark:text-primary-400/70',
    accent: 'text-accent-600/70 dark:text-accent-400/70',
    neutral: 'text-dark-500 dark:text-dark-400',
  };

  return (
    <Link
      to={to}
      className={`flex items-center gap-3 p-4 rounded-xl border transition-colors group ${colors[color]}`}
    >
      <div className={`w-10 h-10 rounded-lg flex items-center justify-center group-hover:scale-110 transition-transform ${iconColors[color]}`}>
        <Icon size={20} />
      </div>
      <div>
        <p className="font-medium">{label}</p>
        <p className={`text-sm ${descColors[color]}`}>{desc}</p>
      </div>
    </Link>
  );
}
