import React, { useEffect, useState, useMemo } from 'react';
import { Link } from 'react-router-dom';
import { motion, AnimatePresence } from 'framer-motion';
import { orderAPI } from '../services/api';
import { Order } from '../types';
import { useAuthStore } from '../store/authStore';
import {
  Plus,
  Search,
  Filter,
  RefreshCw,
  Package,
  ExternalLink,
  CheckCircle,
  Clock,
  AlertCircle,
  XCircle,
  Calendar,
  ChevronDown,
  ChevronLeft,
  ChevronRight,
  Loader2,
  TrendingUp,
  ListOrdered,
} from 'lucide-react';
import { formatDateShort } from '../utils/timezone';

// Status config for colors, icons, and pulse behavior
const STATUS_CONFIG: Record<string, { icon: React.ReactNode; colors: string; pulse: boolean; label: string }> = {
  COMPLETED: {
    icon: <CheckCircle size={14} />,
    colors: 'bg-emerald-100 text-emerald-700 dark:bg-emerald-900/30 dark:text-emerald-400',
    pulse: false,
    label: 'Completed',
  },
  IN_PROGRESS: {
    icon: <Loader2 size={14} className="animate-spin" />,
    colors: 'bg-blue-100 text-blue-700 dark:bg-blue-900/30 dark:text-blue-400',
    pulse: true,
    label: 'In Progress',
  },
  PROCESSING: {
    icon: <Loader2 size={14} className="animate-spin" />,
    colors: 'bg-blue-100 text-blue-700 dark:bg-blue-900/30 dark:text-blue-400',
    pulse: true,
    label: 'Processing',
  },
  PENDING: {
    icon: <Clock size={14} />,
    colors: 'bg-amber-100 text-amber-700 dark:bg-amber-900/30 dark:text-amber-400',
    pulse: true,
    label: 'Pending',
  },
  FAILED: {
    icon: <AlertCircle size={14} />,
    colors: 'bg-red-100 text-red-700 dark:bg-red-900/30 dark:text-red-400',
    pulse: false,
    label: 'Failed',
  },
  CANCELED: {
    icon: <XCircle size={14} />,
    colors: 'bg-dark-100 text-dark-600 dark:bg-dark-700 dark:text-dark-400',
    pulse: false,
    label: 'Canceled',
  },
  CANCELLED: {
    icon: <XCircle size={14} />,
    colors: 'bg-dark-100 text-dark-600 dark:bg-dark-700 dark:text-dark-400',
    pulse: false,
    label: 'Cancelled',
  },
  PARTIAL: {
    icon: <TrendingUp size={14} />,
    colors: 'bg-orange-100 text-orange-700 dark:bg-orange-900/30 dark:text-orange-400',
    pulse: false,
    label: 'Partial',
  },
};

const DEFAULT_STATUS = {
  icon: <Clock size={14} />,
  colors: 'bg-dark-100 text-dark-600 dark:bg-dark-700 dark:text-dark-400',
  pulse: false,
  label: 'Unknown',
};

const StatusBadge: React.FC<{ status: string }> = ({ status }) => {
  const config = STATUS_CONFIG[status?.toUpperCase()] || DEFAULT_STATUS;
  return (
    <span className={`inline-flex items-center gap-1.5 px-2.5 py-1 rounded-lg text-xs font-semibold ${config.colors}`}>
      {config.pulse && (
        <span className="relative flex h-2 w-2">
          <span className="absolute inline-flex h-full w-full animate-ping rounded-full bg-current opacity-40" />
          <span className="relative inline-flex h-2 w-2 rounded-full bg-current" />
        </span>
      )}
      {config.icon}
      {config.label}
    </span>
  );
};

// Pagination helper
const getPageNumbers = (current: number, total: number): (number | 'dots')[] => {
  if (total <= 5) return Array.from({ length: total }, (_, i) => i);
  const pages: (number | 'dots')[] = [0];
  if (current > 2) pages.push('dots');
  for (let i = Math.max(1, current - 1); i <= Math.min(total - 2, current + 1); i++) {
    pages.push(i);
  }
  if (current < total - 3) pages.push('dots');
  pages.push(total - 1);
  return pages;
};

// Row animation variants
const rowVariants = {
  hidden: { opacity: 0, y: 8 },
  visible: (i: number) => ({
    opacity: 1,
    y: 0,
    transition: { delay: i * 0.03, duration: 0.3, ease: 'easeOut' },
  }),
};

export const Orders: React.FC = () => {
  const [orders, setOrders] = useState<Order[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [selectedOrders, setSelectedOrders] = useState<Set<number>>(new Set());
  const [statusFilter, setStatusFilter] = useState('');
  const [searchInput, setSearchInput] = useState('');
  const [searchTerm, setSearchTerm] = useState('');
  const [startDate, setStartDate] = useState('');
  const [endDate, setEndDate] = useState('');
  const [showFilters, setShowFilters] = useState(false);
  const [currentPage, setCurrentPage] = useState(0);
  const [totalPages, setTotalPages] = useState(0);
  const [totalElements, setTotalElements] = useState(0);
  const user = useAuthStore((s) => s.user);

  // Debounce search input by 300ms
  useEffect(() => {
    const timer = setTimeout(() => {
      setSearchTerm(searchInput);
      setCurrentPage(0);
    }, 300);
    return () => clearTimeout(timer);
  }, [searchInput]);

  useEffect(() => {
    fetchOrders();
  }, [statusFilter, searchTerm, startDate, endDate, currentPage]);

  const fetchOrders = async () => {
    setLoading(true);
    try {
      const response = await orderAPI.getOrders(
        statusFilter || undefined,
        searchTerm || undefined,
        startDate || undefined,
        endDate || undefined,
        currentPage,
        100
      );

      if (response?.data?.content && Array.isArray(response.data.content)) {
        setOrders(response.data.content);
        setTotalPages(response.data.totalPages || 1);
        setTotalElements(response.data.totalElements || response.data.content.length);
      } else if (response?.content && Array.isArray(response.content)) {
        setOrders(response.content);
        setTotalPages(response.totalPages || 1);
        setTotalElements(response.totalElements || response.content.length);
      } else if (Array.isArray(response?.data)) {
        setOrders(response.data);
        setTotalPages(1);
        setTotalElements(response.data.length);
      } else if (Array.isArray(response)) {
        setOrders(response);
        setTotalPages(1);
        setTotalElements(response.length);
      } else {
        setOrders([]);
        setTotalPages(1);
        setTotalElements(0);
      }
    } catch (error: any) {
      console.error('Error fetching orders:', error);
      setError(error.response?.data?.message || 'Failed to fetch orders');
      setOrders([]);
    } finally {
      setLoading(false);
    }
  };

  const toggleOrderSelection = (orderId: number) => {
    const newSelected = new Set(selectedOrders);
    if (newSelected.has(orderId)) {
      newSelected.delete(orderId);
    } else {
      newSelected.add(orderId);
    }
    setSelectedOrders(newSelected);
  };

  const toggleAllOrders = () => {
    if (selectedOrders.size === orders.length) {
      setSelectedOrders(new Set());
    } else {
      setSelectedOrders(new Set(orders.map(o => o.id)));
    }
  };

  // Stats summary from current orders
  const stats = useMemo(() => {
    const counts = { total: totalElements, active: 0, completed: 0, failed: 0 };
    orders.forEach(o => {
      const s = o.status?.toUpperCase();
      if (s === 'IN_PROGRESS' || s === 'PROCESSING' || s === 'PENDING') counts.active++;
      else if (s === 'COMPLETED' || s === 'PARTIAL') counts.completed++;
      else if (s === 'FAILED' || s === 'CANCELED' || s === 'CANCELLED') counts.failed++;
    });
    return counts;
  }, [orders, totalElements]);

  const formatDate = (dateString: string) => formatDateShort(dateString);

  const truncateLink = (link: string, max = 40) => {
    if (!link) return 'N/A';
    try {
      const url = new URL(link);
      const path = url.pathname + url.search;
      const display = url.host + (path.length > 1 ? path : '');
      return display.length > max ? display.slice(0, max) + '...' : display;
    } catch {
      return link.length > max ? link.slice(0, max) + '...' : link;
    }
  };

  const hasActiveFilters = statusFilter || startDate || endDate;

  return (
    <motion.div
      className="space-y-6"
      initial={{ opacity: 0, y: 12 }}
      animate={{ opacity: 1, y: 0 }}
      transition={{ duration: 0.4, ease: 'easeOut' }}
    >
      {/* Header */}
      <div className="flex flex-col sm:flex-row sm:items-center sm:justify-between gap-4">
        <div>
          <h1 className="text-2xl font-bold text-dark-900 dark:text-white">Orders</h1>
          <p className="text-dark-500 dark:text-dark-400 mt-1">
            Manage and track your service orders
          </p>
        </div>
        <div className="flex items-center gap-3">
          <button
            onClick={fetchOrders}
            disabled={loading}
            className="flex items-center gap-2 px-4 py-2 text-sm font-medium text-dark-600 hover:text-dark-900 dark:text-dark-400 dark:hover:text-white bg-white dark:bg-dark-800 border border-dark-200 dark:border-dark-700 rounded-xl hover:bg-dark-50 dark:hover:bg-dark-700 transition-all duration-200 disabled:opacity-50"
          >
            <RefreshCw size={16} className={`transition-transform duration-500 ${loading ? 'animate-spin' : ''}`} />
            Refresh
          </button>
          {user?.role !== 'ADMIN' && (
            <Link
              to="/orders/new"
              className="flex items-center gap-2 px-4 py-2 text-sm font-medium text-white bg-primary-600 hover:bg-primary-700 rounded-xl transition-all duration-200 shadow-soft hover:shadow-lg hover:-translate-y-0.5"
            >
              <Plus size={16} />
              New Order
            </Link>
          )}
        </div>
      </div>

      {/* Quick Stats */}
      <div className="grid grid-cols-2 sm:grid-cols-4 gap-3">
        {[
          { label: 'Total Orders', value: stats.total, icon: <ListOrdered size={18} />, color: 'text-primary-600 dark:text-primary-400 bg-primary-100 dark:bg-primary-900/30' },
          { label: 'Active', value: stats.active, icon: <Loader2 size={18} />, color: 'text-blue-600 dark:text-blue-400 bg-blue-100 dark:bg-blue-900/30' },
          { label: 'Completed', value: stats.completed, icon: <CheckCircle size={18} />, color: 'text-emerald-600 dark:text-emerald-400 bg-emerald-100 dark:bg-emerald-900/30' },
          { label: 'Failed', value: stats.failed, icon: <AlertCircle size={18} />, color: 'text-red-600 dark:text-red-400 bg-red-100 dark:bg-red-900/30' },
        ].map((stat, i) => (
          <motion.div
            key={stat.label}
            initial={{ opacity: 0, y: 10 }}
            animate={{ opacity: 1, y: 0 }}
            transition={{ delay: i * 0.05, duration: 0.3 }}
            className="bg-white dark:bg-dark-800 rounded-xl border border-dark-100 dark:border-dark-700 p-4 flex items-center gap-3 shadow-soft dark:shadow-dark-soft"
          >
            <div className={`flex items-center justify-center w-9 h-9 rounded-lg ${stat.color}`}>
              {stat.icon}
            </div>
            <div>
              <p className="text-lg font-bold text-dark-900 dark:text-white">{stat.value}</p>
              <p className="text-xs text-dark-500 dark:text-dark-400">{stat.label}</p>
            </div>
          </motion.div>
        ))}
      </div>

      {/* Main Table Card */}
      <div className="bg-white dark:bg-dark-800 rounded-2xl border border-dark-100 dark:border-dark-700 shadow-soft dark:shadow-dark-soft overflow-hidden">
        {/* Search Bar */}
        <div className="p-4 border-b border-dark-100 dark:border-dark-700">
          <div className="flex flex-col sm:flex-row gap-3">
            <div className="relative flex-1">
              <Search size={18} className="absolute left-3 top-1/2 -translate-y-1/2 text-dark-400" />
              <input
                type="text"
                value={searchInput}
                onChange={(e) => setSearchInput(e.target.value)}
                placeholder="Search by ID, link, or service..."
                className="w-full pl-10 pr-4 py-2.5 border border-dark-200 dark:border-dark-600 rounded-xl bg-white dark:bg-dark-700 text-dark-900 dark:text-white placeholder-dark-400 focus:outline-none focus:ring-2 focus:ring-primary-500/30 focus:border-primary-500 transition-all duration-200"
              />
            </div>
            <button
              onClick={() => setShowFilters(!showFilters)}
              className={`flex items-center gap-2 px-4 py-2.5 text-sm font-medium rounded-xl border transition-all duration-200 ${
                showFilters || hasActiveFilters
                  ? 'bg-primary-50 dark:bg-primary-900/20 text-primary-700 dark:text-primary-300 border-primary-200 dark:border-primary-800'
                  : 'bg-white dark:bg-dark-700 text-dark-600 dark:text-dark-300 border-dark-200 dark:border-dark-600 hover:bg-dark-50 dark:hover:bg-dark-600'
              }`}
            >
              <Filter size={16} />
              Filters
              {hasActiveFilters && (
                <span className="flex items-center justify-center w-5 h-5 rounded-full bg-primary-600 text-white text-[10px] font-bold">
                  {[statusFilter, startDate, endDate].filter(Boolean).length}
                </span>
              )}
              <ChevronDown size={16} className={`transition-transform duration-200 ${showFilters ? 'rotate-180' : ''}`} />
            </button>
          </div>
        </div>

        {/* Expandable Filters */}
        <AnimatePresence>
          {showFilters && (
            <motion.div
              initial={{ height: 0, opacity: 0 }}
              animate={{ height: 'auto', opacity: 1 }}
              exit={{ height: 0, opacity: 0 }}
              transition={{ duration: 0.25, ease: 'easeInOut' }}
              className="overflow-hidden"
            >
              <div className="p-4 bg-dark-50/50 dark:bg-dark-700/30 border-b border-dark-100 dark:border-dark-700">
                <div className="grid grid-cols-1 sm:grid-cols-3 gap-4">
                  <div>
                    <label className="block text-sm font-medium text-dark-700 dark:text-dark-300 mb-2">
                      Status
                    </label>
                    <select
                      value={statusFilter}
                      onChange={(e) => { setCurrentPage(0); setStatusFilter(e.target.value); }}
                      className="w-full px-3 py-2.5 border border-dark-200 dark:border-dark-600 rounded-xl bg-white dark:bg-dark-700 text-dark-900 dark:text-white focus:outline-none focus:ring-2 focus:ring-primary-500/30 focus:border-primary-500 transition-all duration-200"
                    >
                      <option value="">All Statuses</option>
                      <option value="PENDING">Pending</option>
                      <option value="IN_PROGRESS">In Progress</option>
                      <option value="COMPLETED">Completed</option>
                      <option value="PARTIAL">Partial</option>
                      <option value="FAILED">Failed</option>
                      <option value="CANCELED">Canceled</option>
                    </select>
                  </div>
                  <div>
                    <label className="block text-sm font-medium text-dark-700 dark:text-dark-300 mb-2">
                      From Date
                    </label>
                    <div className="relative">
                      <Calendar size={16} className="absolute left-3 top-1/2 -translate-y-1/2 text-dark-400" />
                      <input
                        type="date"
                        value={startDate}
                        onChange={(e) => { setCurrentPage(0); setStartDate(e.target.value); }}
                        className="w-full pl-10 pr-4 py-2.5 border border-dark-200 dark:border-dark-600 rounded-xl bg-white dark:bg-dark-700 text-dark-900 dark:text-white focus:outline-none focus:ring-2 focus:ring-primary-500/30 focus:border-primary-500 transition-all duration-200"
                      />
                    </div>
                  </div>
                  <div>
                    <label className="block text-sm font-medium text-dark-700 dark:text-dark-300 mb-2">
                      To Date
                    </label>
                    <div className="relative">
                      <Calendar size={16} className="absolute left-3 top-1/2 -translate-y-1/2 text-dark-400" />
                      <input
                        type="date"
                        value={endDate}
                        onChange={(e) => { setCurrentPage(0); setEndDate(e.target.value); }}
                        className="w-full pl-10 pr-4 py-2.5 border border-dark-200 dark:border-dark-600 rounded-xl bg-white dark:bg-dark-700 text-dark-900 dark:text-white focus:outline-none focus:ring-2 focus:ring-primary-500/30 focus:border-primary-500 transition-all duration-200"
                      />
                    </div>
                  </div>
                </div>
                {hasActiveFilters && (
                  <button
                    onClick={() => {
                      setCurrentPage(0);
                      setStatusFilter('');
                      setStartDate('');
                      setEndDate('');
                    }}
                    className="mt-3 text-sm text-primary-600 dark:text-primary-400 hover:text-primary-700 dark:hover:text-primary-300 font-medium transition-colors"
                  >
                    Clear all filters
                  </button>
                )}
              </div>
            </motion.div>
          )}
        </AnimatePresence>

        {/* Loading State */}
        {loading && (
          <div className="p-12 text-center">
            <div className="inline-flex items-center justify-center w-12 h-12 rounded-full bg-primary-100 dark:bg-primary-900/30 mb-4">
              <RefreshCw size={24} className="text-primary-600 dark:text-primary-400 animate-spin" />
            </div>
            <p className="text-dark-500 dark:text-dark-400">Loading orders...</p>
          </div>
        )}

        {/* Error State */}
        {error && !loading && (
          <div className="p-12 text-center">
            <div className="inline-flex items-center justify-center w-12 h-12 rounded-full bg-red-100 dark:bg-red-900/30 mb-4">
              <AlertCircle size={24} className="text-red-600 dark:text-red-400" />
            </div>
            <p className="text-red-600 dark:text-red-400 font-medium">{error}</p>
            <button
              onClick={fetchOrders}
              className="mt-4 px-4 py-2 text-sm font-medium text-primary-600 dark:text-primary-400 hover:text-primary-700 transition-colors"
            >
              Try again
            </button>
          </div>
        )}

        {/* Empty State */}
        {!loading && !error && orders.length === 0 && (
          <div className="p-12 text-center">
            <div className="inline-flex items-center justify-center w-16 h-16 rounded-2xl bg-dark-100 dark:bg-dark-700 mb-4">
              <Package size={32} className="text-dark-400" />
            </div>
            <p className="text-dark-600 dark:text-dark-300 font-medium">No orders found</p>
            <p className="text-sm text-dark-400 dark:text-dark-500 mt-1">
              {searchTerm || hasActiveFilters
                ? 'Try adjusting your filters'
                : 'Create your first order to get started'}
            </p>
            {user?.role !== 'ADMIN' && !searchTerm && !hasActiveFilters && (
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

        {/* Orders Table */}
        {!loading && !error && orders.length > 0 && (
          <>
            <div className="overflow-x-auto">
              <table className="w-full">
                <thead>
                  <tr className="bg-dark-50/70 dark:bg-dark-700/50">
                    <th className="px-4 py-3 text-left w-10">
                      <input
                        type="checkbox"
                        checked={selectedOrders.size === orders.length && orders.length > 0}
                        onChange={toggleAllOrders}
                        className="rounded border-dark-300 dark:border-dark-600 text-primary-600 focus:ring-primary-500 bg-white dark:bg-dark-700"
                      />
                    </th>
                    {['ID', 'Service', 'Link', 'Quantity', 'Start Count', 'Status', 'Remains', 'Charge', 'Created'].map((header) => (
                      <th
                        key={header}
                        className={`px-4 py-3 text-xs font-semibold text-dark-500 dark:text-dark-400 uppercase tracking-wider ${
                          ['Quantity', 'Start Count', 'Status', 'Remains'].includes(header) ? 'text-center' :
                          ['Charge', 'Created'].includes(header) ? 'text-right' : 'text-left'
                        }`}
                      >
                        {header}
                      </th>
                    ))}
                  </tr>
                </thead>
                <tbody className="divide-y divide-dark-100 dark:divide-dark-700/50">
                  {orders.map((order, index) => (
                    <motion.tr
                      key={order.id}
                      custom={index}
                      variants={rowVariants}
                      initial="hidden"
                      animate="visible"
                      className="group hover:bg-dark-50/50 dark:hover:bg-dark-700/30 transition-colors duration-150"
                    >
                      <td className="px-4 py-3.5">
                        <input
                          type="checkbox"
                          checked={selectedOrders.has(order.id)}
                          onChange={() => toggleOrderSelection(order.id)}
                          className="rounded border-dark-300 dark:border-dark-600 text-primary-600 focus:ring-primary-500 bg-white dark:bg-dark-700"
                        />
                      </td>
                      <td className="px-4 py-3.5">
                        <div className="text-sm font-semibold text-dark-900 dark:text-white">#{order.id}</div>
                        {order.orderId && (
                          <div className="text-xs text-dark-400 mt-0.5">{order.orderId}</div>
                        )}
                      </td>
                      <td className="px-4 py-3.5">
                        <div className="text-sm text-dark-900 dark:text-white max-w-[180px] truncate">
                          {order.serviceName || 'N/A'}
                        </div>
                      </td>
                      <td className="px-4 py-3.5 max-w-[200px]">
                        <a
                          href={order.link}
                          target="_blank"
                          rel="noopener noreferrer"
                          className="inline-flex items-center gap-1 text-sm text-primary-600 dark:text-primary-400 hover:text-primary-700 dark:hover:text-primary-300 transition-colors group/link"
                          title={order.link}
                        >
                          <span className="truncate max-w-[160px]">{truncateLink(order.link)}</span>
                          <ExternalLink size={12} className="flex-shrink-0 opacity-0 group-hover/link:opacity-100 transition-opacity" />
                        </a>
                      </td>
                      <td className="px-4 py-3.5 text-center">
                        <span className="text-sm font-medium text-dark-900 dark:text-white">
                          {order.quantity?.toLocaleString() || 0}
                        </span>
                      </td>
                      <td className="px-4 py-3.5 text-center">
                        <span className="text-sm text-dark-600 dark:text-dark-300">
                          {order.startCount?.toLocaleString() || 0}
                        </span>
                      </td>
                      <td className="px-4 py-3.5 text-center">
                        <StatusBadge status={order.status} />
                      </td>
                      <td className="px-4 py-3.5 text-center">
                        <span className="text-sm text-dark-600 dark:text-dark-300">
                          {order.remains !== null && order.remains !== undefined
                            ? order.remains.toLocaleString()
                            : '-'}
                        </span>
                      </td>
                      <td className="px-4 py-3.5 text-right">
                        <span className="text-sm font-semibold text-dark-900 dark:text-white">
                          ${order.charge ? Number(order.charge).toFixed(4) : '0.0000'}
                        </span>
                      </td>
                      <td className="px-4 py-3.5 text-right">
                        <span className="text-sm text-dark-500 dark:text-dark-400">
                          {formatDate(order.createdAt || new Date().toISOString())}
                        </span>
                      </td>
                    </motion.tr>
                  ))}
                </tbody>
              </table>
            </div>

            {/* Table Footer with Pagination */}
            <div className="px-4 py-3 bg-dark-50/50 dark:bg-dark-700/30 border-t border-dark-100 dark:border-dark-700">
              <div className="flex flex-col sm:flex-row items-center justify-between gap-3">
                <div className="text-sm text-dark-500 dark:text-dark-400">
                  Showing <span className="font-medium text-dark-700 dark:text-dark-300">{orders.length}</span> of{' '}
                  <span className="font-medium text-dark-700 dark:text-dark-300">{totalElements}</span> orders
                  {selectedOrders.size > 0 && (
                    <span className="ml-3 text-primary-600 dark:text-primary-400 font-medium">
                      ({selectedOrders.size} selected)
                    </span>
                  )}
                </div>
                {totalPages > 1 && (
                  <div className="flex items-center gap-1.5">
                    <button
                      onClick={() => setCurrentPage(p => Math.max(0, p - 1))}
                      disabled={currentPage === 0}
                      className="flex items-center justify-center w-9 h-9 rounded-lg border border-dark-200 dark:border-dark-600 bg-white dark:bg-dark-700 text-dark-600 dark:text-dark-300 hover:bg-dark-50 dark:hover:bg-dark-600 disabled:opacity-40 disabled:cursor-not-allowed transition-all duration-200"
                    >
                      <ChevronLeft size={16} />
                    </button>
                    {getPageNumbers(currentPage, totalPages).map((page, idx) =>
                      page === 'dots' ? (
                        <span key={`dots-${idx}`} className="w-9 h-9 flex items-center justify-center text-dark-400 text-sm">...</span>
                      ) : (
                        <button
                          key={page}
                          onClick={() => setCurrentPage(page)}
                          className={`flex items-center justify-center w-9 h-9 rounded-lg text-sm font-medium transition-all duration-200 ${
                            currentPage === page
                              ? 'bg-primary-600 text-white shadow-sm'
                              : 'border border-dark-200 dark:border-dark-600 bg-white dark:bg-dark-700 text-dark-600 dark:text-dark-300 hover:bg-dark-50 dark:hover:bg-dark-600'
                          }`}
                        >
                          {page + 1}
                        </button>
                      )
                    )}
                    <button
                      onClick={() => setCurrentPage(p => Math.min(totalPages - 1, p + 1))}
                      disabled={currentPage >= totalPages - 1}
                      className="flex items-center justify-center w-9 h-9 rounded-lg border border-dark-200 dark:border-dark-600 bg-white dark:bg-dark-700 text-dark-600 dark:text-dark-300 hover:bg-dark-50 dark:hover:bg-dark-600 disabled:opacity-40 disabled:cursor-not-allowed transition-all duration-200"
                    >
                      <ChevronRight size={16} />
                    </button>
                  </div>
                )}
              </div>
            </div>
          </>
        )}
      </div>
    </motion.div>
  );
};
