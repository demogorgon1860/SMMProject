import React, { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
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
} from 'lucide-react';

export const Orders: React.FC = () => {
  const [orders, setOrders] = useState<Order[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [selectedOrders, setSelectedOrders] = useState<Set<number>>(new Set());
  const [statusFilter, setStatusFilter] = useState('');
  const [searchTerm, setSearchTerm] = useState('');
  const [startDate, setStartDate] = useState('');
  const [endDate, setEndDate] = useState('');
  const [showFilters, setShowFilters] = useState(false);
  const { user } = useAuthStore();

  useEffect(() => {
    fetchOrders();
  }, [statusFilter, startDate, endDate]);

  const fetchOrders = async () => {
    setLoading(true);
    try {
      const response = await orderAPI.getOrders(
        statusFilter || undefined,
        startDate || undefined,
        endDate || undefined
      );
      if (Array.isArray(response.data)) {
        setOrders(response.data);
      } else if (response.data && Array.isArray(response.data.content)) {
        setOrders(response.data.content);
      } else if (Array.isArray(response.content)) {
        setOrders(response.content);
      } else if (Array.isArray(response)) {
        setOrders(response);
      } else {
        setOrders([]);
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
    if (selectedOrders.size === filteredOrders.length) {
      setSelectedOrders(new Set());
    } else {
      setSelectedOrders(new Set(filteredOrders.map(o => o.id)));
    }
  };

  const getStatusIcon = (status: string) => {
    switch (status?.toUpperCase()) {
      case 'COMPLETED':
        return <CheckCircle size={14} />;
      case 'IN_PROGRESS':
      case 'PROCESSING':
        return <Clock size={14} />;
      case 'PENDING':
        return <Clock size={14} />;
      case 'FAILED':
        return <AlertCircle size={14} />;
      case 'CANCELED':
      case 'CANCELLED':
        return <XCircle size={14} />;
      default:
        return <Clock size={14} />;
    }
  };

  const getStatusColor = (status: string) => {
    switch (status?.toUpperCase()) {
      case 'COMPLETED':
        return 'bg-accent-100 text-accent-700 dark:bg-accent-900/30 dark:text-accent-400';
      case 'IN_PROGRESS':
      case 'PROCESSING':
        return 'bg-blue-100 text-blue-700 dark:bg-blue-900/30 dark:text-blue-400';
      case 'PENDING':
        return 'bg-yellow-100 text-yellow-700 dark:bg-yellow-900/30 dark:text-yellow-400';
      case 'FAILED':
        return 'bg-red-100 text-red-700 dark:bg-red-900/30 dark:text-red-400';
      case 'CANCELED':
      case 'CANCELLED':
        return 'bg-dark-100 text-dark-600 dark:bg-dark-700 dark:text-dark-400';
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

  const formatLink = (link: string) => {
    if (link.length > 35) {
      return link.substring(0, 32) + '...';
    }
    return link;
  };

  const filteredOrders = orders.filter(order => {
    if (searchTerm) {
      const search = searchTerm.toLowerCase();
      return (
        order.id.toString().includes(search) ||
        (order.orderId && order.orderId.toLowerCase().includes(search)) ||
        order.link.toLowerCase().includes(search) ||
        (order.service?.name && order.service.name.toLowerCase().includes(search))
      );
    }
    return true;
  });

  return (
    <div className="space-y-6 animate-fade-in">
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
            className="flex items-center gap-2 px-4 py-2 text-sm font-medium text-dark-600 hover:text-dark-900 dark:text-dark-400 dark:hover:text-white bg-white dark:bg-dark-800 border border-dark-200 dark:border-dark-700 rounded-xl hover:bg-dark-50 dark:hover:bg-dark-700 transition-colors disabled:opacity-50"
          >
            <RefreshCw size={16} className={loading ? 'animate-spin' : ''} />
            Refresh
          </button>
          {user?.role !== 'ADMIN' && (
            <Link
              to="/orders/new"
              className="flex items-center gap-2 px-4 py-2 text-sm font-medium text-white bg-primary-600 hover:bg-primary-700 rounded-xl transition-colors shadow-soft"
            >
              <Plus size={16} />
              New Order
            </Link>
          )}
        </div>
      </div>

      {/* Search and Filters Card */}
      <div className="bg-white dark:bg-dark-800 rounded-2xl border border-dark-100 dark:border-dark-700 shadow-soft dark:shadow-dark-soft overflow-hidden">
        {/* Search Bar */}
        <div className="p-4 border-b border-dark-100 dark:border-dark-700">
          <div className="flex flex-col sm:flex-row gap-3">
            <div className="relative flex-1">
              <Search size={18} className="absolute left-3 top-1/2 -translate-y-1/2 text-dark-400" />
              <input
                type="text"
                value={searchTerm}
                onChange={(e) => setSearchTerm(e.target.value)}
                placeholder="Search by ID, link, or service..."
                className="w-full pl-10 pr-4 py-2.5 border border-dark-200 dark:border-dark-600 rounded-xl bg-white dark:bg-dark-700 text-dark-900 dark:text-white placeholder-dark-400 focus:outline-none focus:ring-2 focus:ring-primary-500/50 focus:border-primary-500 transition-all"
              />
            </div>
            <button
              onClick={() => setShowFilters(!showFilters)}
              className={`flex items-center gap-2 px-4 py-2.5 text-sm font-medium rounded-xl border transition-colors ${
                showFilters || statusFilter || startDate || endDate
                  ? 'bg-primary-50 dark:bg-primary-900/20 text-primary-700 dark:text-primary-300 border-primary-200 dark:border-primary-800'
                  : 'bg-white dark:bg-dark-700 text-dark-600 dark:text-dark-300 border-dark-200 dark:border-dark-600 hover:bg-dark-50 dark:hover:bg-dark-600'
              }`}
            >
              <Filter size={16} />
              Filters
              <ChevronDown size={16} className={`transition-transform ${showFilters ? 'rotate-180' : ''}`} />
            </button>
          </div>
        </div>

        {/* Expandable Filters */}
        {showFilters && (
          <div className="p-4 bg-dark-50 dark:bg-dark-700/50 border-b border-dark-100 dark:border-dark-700 animate-fade-in">
            <div className="grid grid-cols-1 sm:grid-cols-3 gap-4">
              <div>
                <label className="block text-sm font-medium text-dark-700 dark:text-dark-300 mb-2">
                  Status
                </label>
                <select
                  value={statusFilter}
                  onChange={(e) => setStatusFilter(e.target.value)}
                  className="w-full px-3 py-2.5 border border-dark-200 dark:border-dark-600 rounded-xl bg-white dark:bg-dark-700 text-dark-900 dark:text-white focus:outline-none focus:ring-2 focus:ring-primary-500/50 focus:border-primary-500 transition-all"
                >
                  <option value="">All Statuses</option>
                  <option value="PENDING">Pending</option>
                  <option value="IN_PROGRESS">In Progress</option>
                  <option value="COMPLETED">Completed</option>
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
                    onChange={(e) => setStartDate(e.target.value)}
                    className="w-full pl-10 pr-4 py-2.5 border border-dark-200 dark:border-dark-600 rounded-xl bg-white dark:bg-dark-700 text-dark-900 dark:text-white focus:outline-none focus:ring-2 focus:ring-primary-500/50 focus:border-primary-500 transition-all"
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
                    onChange={(e) => setEndDate(e.target.value)}
                    className="w-full pl-10 pr-4 py-2.5 border border-dark-200 dark:border-dark-600 rounded-xl bg-white dark:bg-dark-700 text-dark-900 dark:text-white focus:outline-none focus:ring-2 focus:ring-primary-500/50 focus:border-primary-500 transition-all"
                  />
                </div>
              </div>
            </div>
            {(statusFilter || startDate || endDate) && (
              <button
                onClick={() => {
                  setStatusFilter('');
                  setStartDate('');
                  setEndDate('');
                }}
                className="mt-3 text-sm text-primary-600 dark:text-primary-400 hover:text-primary-700 dark:hover:text-primary-300 font-medium"
              >
                Clear all filters
              </button>
            )}
          </div>
        )}

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
              className="mt-4 px-4 py-2 text-sm font-medium text-primary-600 dark:text-primary-400 hover:text-primary-700"
            >
              Try again
            </button>
          </div>
        )}

        {/* Empty State */}
        {!loading && !error && filteredOrders.length === 0 && (
          <div className="p-12 text-center">
            <div className="inline-flex items-center justify-center w-16 h-16 rounded-full bg-dark-100 dark:bg-dark-700 mb-4">
              <Package size={32} className="text-dark-400" />
            </div>
            <p className="text-dark-600 dark:text-dark-300 font-medium">No orders found</p>
            <p className="text-sm text-dark-400 dark:text-dark-500 mt-1">
              {searchTerm || statusFilter || startDate || endDate
                ? 'Try adjusting your filters'
                : 'Create your first order to get started'}
            </p>
            {user?.role !== 'ADMIN' && !searchTerm && !statusFilter && (
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
        {!loading && !error && filteredOrders.length > 0 && (
          <>
            <div className="overflow-x-auto">
              <table className="w-full">
                <thead>
                  <tr className="bg-dark-50 dark:bg-dark-700/50">
                    <th className="px-4 py-3 text-left">
                      <input
                        type="checkbox"
                        checked={selectedOrders.size === filteredOrders.length && filteredOrders.length > 0}
                        onChange={toggleAllOrders}
                        className="rounded border-dark-300 dark:border-dark-600 text-primary-600 focus:ring-primary-500 bg-white dark:bg-dark-700"
                      />
                    </th>
                    <th className="px-4 py-3 text-left text-xs font-semibold text-dark-600 dark:text-dark-400 uppercase tracking-wider">
                      ID
                    </th>
                    <th className="px-4 py-3 text-left text-xs font-semibold text-dark-600 dark:text-dark-400 uppercase tracking-wider">
                      Service
                    </th>
                    <th className="px-4 py-3 text-left text-xs font-semibold text-dark-600 dark:text-dark-400 uppercase tracking-wider">
                      Link
                    </th>
                    <th className="px-4 py-3 text-center text-xs font-semibold text-dark-600 dark:text-dark-400 uppercase tracking-wider">
                      Quantity
                    </th>
                    <th className="px-4 py-3 text-center text-xs font-semibold text-dark-600 dark:text-dark-400 uppercase tracking-wider">
                      Status
                    </th>
                    <th className="px-4 py-3 text-right text-xs font-semibold text-dark-600 dark:text-dark-400 uppercase tracking-wider">
                      Charge
                    </th>
                    <th className="px-4 py-3 text-right text-xs font-semibold text-dark-600 dark:text-dark-400 uppercase tracking-wider">
                      Created
                    </th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-dark-100 dark:divide-dark-700">
                  {filteredOrders.map((order) => (
                    <tr
                      key={order.id}
                      className="hover:bg-dark-50 dark:hover:bg-dark-700/50 transition-colors"
                    >
                      <td className="px-4 py-4">
                        <input
                          type="checkbox"
                          checked={selectedOrders.has(order.id)}
                          onChange={() => toggleOrderSelection(order.id)}
                          className="rounded border-dark-300 dark:border-dark-600 text-primary-600 focus:ring-primary-500 bg-white dark:bg-dark-700"
                        />
                      </td>
                      <td className="px-4 py-4">
                        <div className="text-sm font-medium text-dark-900 dark:text-white">#{order.id}</div>
                        {order.orderId && (
                          <div className="text-xs text-dark-400">{order.orderId}</div>
                        )}
                      </td>
                      <td className="px-4 py-4">
                        <div className="text-sm text-dark-900 dark:text-white max-w-[200px] truncate" title={order.service?.name}>
                          {order.service?.name || 'N/A'}
                        </div>
                      </td>
                      <td className="px-4 py-4">
                        <a
                          href={order.link}
                          target="_blank"
                          rel="noopener noreferrer"
                          className="inline-flex items-center gap-1 text-sm text-primary-600 dark:text-primary-400 hover:text-primary-700 dark:hover:text-primary-300 max-w-[200px] truncate"
                          title={order.link}
                        >
                          {formatLink(order.link)}
                          <ExternalLink size={12} className="flex-shrink-0" />
                        </a>
                      </td>
                      <td className="px-4 py-4 text-center">
                        <span className="text-sm font-medium text-dark-900 dark:text-white">
                          {order.quantity?.toLocaleString() || 0}
                        </span>
                      </td>
                      <td className="px-4 py-4 text-center">
                        <span className={`inline-flex items-center gap-1.5 px-2.5 py-1 rounded-lg text-xs font-medium ${getStatusColor(order.status)}`}>
                          {getStatusIcon(order.status)}
                          {order.status}
                        </span>
                      </td>
                      <td className="px-4 py-4 text-right">
                        <span className="text-sm font-medium text-dark-900 dark:text-white">
                          ${order.charge ? Number(order.charge).toFixed(4) : '0.0000'}
                        </span>
                      </td>
                      <td className="px-4 py-4 text-right">
                        <span className="text-sm text-dark-500 dark:text-dark-400">
                          {formatDate(order.createdAt || new Date().toISOString())}
                        </span>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>

            {/* Table Footer */}
            <div className="px-4 py-3 bg-dark-50 dark:bg-dark-700/50 border-t border-dark-100 dark:border-dark-700">
              <div className="flex items-center justify-between">
                <div className="text-sm text-dark-500 dark:text-dark-400">
                  Showing <span className="font-medium text-dark-700 dark:text-dark-300">{filteredOrders.length}</span> of{' '}
                  <span className="font-medium text-dark-700 dark:text-dark-300">{orders.length}</span> orders
                </div>
                {selectedOrders.size > 0 && (
                  <div className="text-sm text-primary-600 dark:text-primary-400 font-medium">
                    {selectedOrders.size} selected
                  </div>
                )}
              </div>
            </div>
          </>
        )}
      </div>
    </div>
  );
};
