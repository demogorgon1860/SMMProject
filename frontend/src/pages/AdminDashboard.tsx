// frontend/src/components/AdminDashboard.tsx

import React, { useState, useEffect } from 'react';
import {
  User,
  AdminOrderDto,
  DashboardStats,
  BulkActionRequest,
  ConversionCoefficient,
  TrafficSource,
  OrderStatus
} from '../types';
import { apiService } from '../services/api';

interface AdminDashboardProps {
  user: User;
  onLogout: () => void;
}

export const AdminDashboard: React.FC<AdminDashboardProps> = ({ user, onLogout }) => {
  const [activeTab, setActiveTab] = useState<'overview' | 'orders' | 'settings' | 'traffic-sources'>('overview');
  const [stats, setStats] = useState<DashboardStats | null>(null);
  const [orders, setOrders] = useState<AdminOrderDto[]>([]);
  const [coefficients, setCoefficients] = useState<ConversionCoefficient[]>([]);
  const [trafficSources, setTrafficSources] = useState<TrafficSource[]>([]);
  const [loading, setLoading] = useState(false);
  const [selectedOrders, setSelectedOrders] = useState<Set<number>>(new Set());
  const [bulkActionLoading, setBulkActionLoading] = useState(false);
  const [filters, setFilters] = useState({
    status: 'all' as OrderStatus | 'all',
    username: '',
    page: 0,
    size: 50
  });

  useEffect(() => {
    if (activeTab === 'overview') {
      fetchStats();
    } else if (activeTab === 'orders') {
      fetchOrders();
    } else if (activeTab === 'settings') {
      fetchCoefficients();
    } else if (activeTab === 'traffic-sources') {
      fetchTrafficSources();
    }
  }, [activeTab, filters]);

  const fetchStats = async () => {
    try {
      setLoading(true);
      const data = await apiService.admin.getDashboardStats();
      setStats(data);
    } catch (error) {
      console.error('Error fetching stats:', error);
    } finally {
      setLoading(false);
    }
  };

  const fetchOrders = async () => {
    try {
      setLoading(true);
      const response = await apiService.admin.getAllOrders({
        status: filters.status === 'all' ? undefined : filters.status,
        username: filters.username || undefined,
        page: filters.page,
        size: filters.size
      });
      setOrders(response.content);
    } catch (error) {
      console.error('Error fetching orders:', error);
    } finally {
      setLoading(false);
    }
  };

  const fetchCoefficients = async () => {
    try {
      setLoading(true);
      const data = await apiService.admin.getConversionCoefficients();
      setCoefficients(data);
    } catch (error) {
      console.error('Error fetching coefficients:', error);
    } finally {
      setLoading(false);
    }
  };

  const fetchTrafficSources = async () => {
    try {
      setLoading(true);
      const data = await apiService.admin.getTrafficSources();
      setTrafficSources(data);
    } catch (error) {
      console.error('Error fetching traffic sources:', error);
    } finally {
      setLoading(false);
    }
  };

  const handleBulkAction = async (action: BulkActionRequest['action'], params?: any) => {
    if (selectedOrders.size === 0) {
      alert('Please select orders first');
      return;
    }

    const confirmed = window.confirm(
      `Are you sure you want to ${action.toLowerCase().replace('_', ' ')} ${selectedOrders.size} orders?`
    );

    if (!confirmed) return;

    try {
      setBulkActionLoading(true);
      await apiService.admin.bulkAction({
        orderIds: Array.from(selectedOrders),
        action,
        params
      });
      
      setSelectedOrders(new Set());
      fetchOrders();
      alert(`Successfully applied ${action} to ${selectedOrders.size} orders`);
    } catch (error: any) {
      alert(`Error: ${error.response?.data?.error || error.message}`);
    } finally {
      setBulkActionLoading(false);
    }
  };

  const handleOrderAction = async (orderId: number, action: string, params?: any) => {
    try {
      if (action === 'SET_START_COUNT') {
        const startCount = prompt('Enter new start count:');
        if (!startCount || isNaN(Number(startCount))) return;
        await apiService.admin.setStartCount(orderId, Number(startCount));
      } else if (action === 'REFRESH_START_COUNT') {
        await apiService.admin.refreshStartCount(orderId);
      } else if (action === 'REFILL') {
        await apiService.admin.refillOrder(orderId);
      } else {
        await apiService.admin.updateOrderStatus(orderId, action, params?.reason);
      }
      
      fetchOrders();
      alert(`Successfully applied ${action} to order #${orderId}`);
    } catch (error: any) {
      alert(`Error: ${error.response?.data?.error || error.message}`);
    }
  };

  const updateCoefficient = async (id: number, withClip: number, withoutClip: number) => {
    try {
      await apiService.admin.updateConversionCoefficient(id, { withClip, withoutClip });
      fetchCoefficients();
      alert('Coefficient updated successfully');
    } catch (error: any) {
      alert(`Error: ${error.response?.data?.error || error.message}`);
    }
  };

  const getStatusColor = (status: OrderStatus): string => {
    const colors: Record<OrderStatus, string> = {
      'Pending': 'bg-yellow-100 text-yellow-800',
      'In progress': 'bg-blue-100 text-blue-800',
      'Processing': 'bg-purple-100 text-purple-800',
      'Partial': 'bg-orange-100 text-orange-800',
      'Completed': 'bg-green-100 text-green-800',
      'Canceled': 'bg-red-100 text-red-800',
      'Paused': 'bg-gray-100 text-gray-800',
      'Refill': 'bg-indigo-100 text-indigo-800'
    };
    return colors[status] || 'bg-gray-100 text-gray-800';
  };

  const formatDate = (dateString: string): string => {
    return new Date(dateString).toLocaleString();
  };

  return (
    <div className="min-h-screen bg-gray-100">
      {/* Header */}
      <header className="bg-white shadow">
        <div className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8">
          <div className="flex justify-between items-center py-6">
            <h1 className="text-2xl font-bold text-gray-900">
              {user.role === 'ADMIN' ? 'Admin Panel' : 'Operator Panel'}
            </h1>
            <div className="flex items-center space-x-4">
              <span className="text-sm text-gray-600">{user.username}</span>
              <span className="px-2 py-1 bg-blue-100 text-blue-800 text-xs font-medium rounded">
                {user.role}
              </span>
              <button
                onClick={onLogout}
                className="text-sm text-gray-600 hover:text-gray-900"
              >
                Logout
              </button>
            </div>
          </div>
        </div>
      </header>

      {/* Navigation */}
      <div className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 mt-6">
        <nav className="flex space-x-4">
          <button
            onClick={() => setActiveTab('overview')}
            className={`px-3 py-2 rounded-md text-sm font-medium ${
              activeTab === 'overview'
                ? 'bg-blue-600 text-white'
                : 'text-gray-600 hover:text-gray-900'
            }`}
          >
            📊 Overview
          </button>
          <button
            onClick={() => setActiveTab('orders')}
            className={`px-3 py-2 rounded-md text-sm font-medium ${
              activeTab === 'orders'
                ? 'bg-blue-600 text-white'
                : 'text-gray-600 hover:text-gray-900'
            }`}
          >
            📋 Orders Management
          </button>
          {user.role === 'ADMIN' && (
            <>
              <button
                onClick={() => setActiveTab('settings')}
                className={`px-3 py-2 rounded-md text-sm font-medium ${
                  activeTab === 'settings'
                    ? 'bg-blue-600 text-white'
                    : 'text-gray-600 hover:text-gray-900'
                }`}
              >
                ⚙️ Settings
              </button>
              <button
                onClick={() => setActiveTab('traffic-sources')}
                className={`px-3 py-2 rounded-md text-sm font-medium ${
                  activeTab === 'traffic-sources'
                    ? 'bg-blue-600 text-white'
                    : 'text-gray-600 hover:text-gray-900'
                }`}
              >
                🌐 Traffic Sources
              </button>
            </>
          )}
        </nav>
      </div>

      {/* Content */}
      <div className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 mt-6">
        {/* Overview Tab */}
        {activeTab === 'overview' && (
          <div className="space-y-6">
            {loading ? (
              <div className="text-center py-12">
                <div className="animate-spin rounded-full h-12 w-12 border-b-2 border-blue-600 mx-auto"></div>
                <p className="mt-4 text-gray-600">Loading statistics...</p>
              </div>
            ) : stats ? (
              <>
                {/* Stats Cards */}
                <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-4 gap-6">
                  <div className="bg-white p-6 rounded-lg shadow">
                    <div className="flex items-center">
                      <div className="p-2 bg-blue-100 rounded-lg">
                        <span className="text-2xl">📊</span>
                      </div>
                      <div className="ml-4">
                        <p className="text-sm font-medium text-gray-600">Total Orders</p>
                        <p className="text-2xl font-bold text-gray-900">{stats.totalOrders.toLocaleString()}</p>
                      </div>
                    </div>
                  </div>

                  <div className="bg-white p-6 rounded-lg shadow">
                    <div className="flex items-center">
                      <div className="p-2 bg-yellow-100 rounded-lg">
                        <span className="text-2xl">🔄</span>
                      </div>
                      <div className="ml-4">
                        <p className="text-sm font-medium text-gray-600">Active Orders</p>
                        <p className="text-2xl font-bold text-gray-900">{stats.activeOrders.toLocaleString()}</p>
                      </div>
                    </div>
                  </div>

                  <div className="bg-white p-6 rounded-lg shadow">
                    <div className="flex items-center">
                      <div className="p-2 bg-green-100 rounded-lg">
                        <span className="text-2xl">✅</span>
                      </div>
                      <div className="ml-4">
                        <p className="text-sm font-medium text-gray-600">Completed</p>
                        <p className="text-2xl font-bold text-gray-900">{stats.completedOrders.toLocaleString()}</p>
                      </div>
                    </div>
                  </div>

                  <div className="bg-white p-6 rounded-lg shadow">
                    <div className="flex items-center">
                      <div className="p-2 bg-purple-100 rounded-lg">
                        <span className="text-2xl">💰</span>
                      </div>
                      <div className="ml-4">
                        <p className="text-sm font-medium text-gray-600">Total Revenue</p>
                        <p className="text-2xl font-bold text-gray-900">${stats.totalRevenue}</p>
                      </div>
                    </div>
                  </div>
                </div>

                {/* Today's Stats */}
                <div className="grid grid-cols-1 md:grid-cols-3 gap-6">
                  <div className="bg-white p-6 rounded-lg shadow">
                    <h3 className="text-lg font-medium text-gray-900 mb-4">Today's Performance</h3>
                    <div className="space-y-3">
                      <div className="flex justify-between">
                        <span className="text-gray-600">Orders Today</span>
                        <span className="font-medium">{stats.todayOrders}</span>
                      </div>
                      <div className="flex justify-between">
                        <span className="text-gray-600">Revenue Today</span>
                        <span className="font-medium">${stats.todayRevenue}</span>
                      </div>
                      <div className="flex justify-between">
                        <span className="text-gray-600">Avg Order Value</span>
                        <span className="font-medium">${stats.averageOrderValue}</span>
                      </div>
                    </div>
                  </div>

                  <div className="bg-white p-6 rounded-lg shadow">
                    <h3 className="text-lg font-medium text-gray-900 mb-4">System Status</h3>
                    <div className="space-y-3">
                      <div className="flex justify-between items-center">
                        <span className="text-gray-600">YouTube Integration</span>
                        <span className={`px-2 py-1 rounded text-xs font-medium ${
                          stats.systemStatus.youtube === 'ONLINE' 
                            ? 'bg-green-100 text-green-800'
                            : stats.systemStatus.youtube === 'LIMITED'
                            ? 'bg-yellow-100 text-yellow-800'
                            : 'bg-red-100 text-red-800'
                        }`}>
                          {stats.systemStatus.youtube}
                        </span>
                      </div>
                      <div className="flex justify-between items-center">
                        <span className="text-gray-600">Binom Integration</span>
                        <span className={`px-2 py-1 rounded text-xs font-medium ${
                          stats.systemStatus.binom === 'ONLINE' 
                            ? 'bg-green-100 text-green-800'
                            : 'bg-red-100 text-red-800'
                        }`}>
                          {stats.systemStatus.binom}
                        </span>
                      </div>
                      <div className="flex justify-between items-center">
                        <span className="text-gray-600">Cryptomus Payments</span>
                        <span className={`px-2 py-1 rounded text-xs font-medium ${
                          stats.systemStatus.cryptomus === 'ONLINE' 
                            ? 'bg-green-100 text-green-800'
                            : 'bg-red-100 text-red-800'
                        }`}>
                          {stats.systemStatus.cryptomus}
                        </span>
                      </div>
                    </div>
                  </div>

                  <div className="bg-white p-6 rounded-lg shadow">
                    <h3 className="text-lg font-medium text-gray-900 mb-4">Quick Actions</h3>
                    <div className="space-y-2">
                      <button 
                        onClick={() => setActiveTab('orders')}
                        className="w-full text-left px-3 py-2 text-sm text-blue-600 hover:bg-blue-50 rounded"
                      >
                        📋 Manage Orders
                      </button>
                      {user.role === 'ADMIN' && (
                        <>
                          <button 
                            onClick={() => setActiveTab('settings')}
                            className="w-full text-left px-3 py-2 text-sm text-blue-600 hover:bg-blue-50 rounded"
                          >
                            ⚙️ System Settings
                          </button>
                          <button 
                            onClick={() => setActiveTab('traffic-sources')}
                            className="w-full text-left px-3 py-2 text-sm text-blue-600 hover:bg-blue-50 rounded"
                          >
                            🌐 Traffic Sources
                          </button>
                        </>
                      )}
                    </div>
                  </div>
                </div>
              </>
            ) : (
              <div className="text-center py-12">
                <p className="text-gray-600">Failed to load statistics</p>
                <button
                  onClick={fetchStats}
                  className="mt-4 px-4 py-2 bg-blue-600 text-white rounded hover:bg-blue-700"
                >
                  Retry
                </button>
              </div>
            )}
          </div>
        )}

        {/* Orders Management Tab */}
        {activeTab === 'orders' && (
          <div className="space-y-6">
            {/* Filters */}
            <div className="bg-white p-4 rounded-lg shadow">
              <div className="flex flex-wrap gap-4 items-center">
                <div>
                  <label className="block text-sm font-medium text-gray-700 mb-1">Status</label>
                  <select
                    value={filters.status}
                    onChange={(e) => setFilters({...filters, status: e.target.value as any, page: 0})}
                    className="px-3 py-2 border border-gray-300 rounded-md text-sm focus:ring-blue-500 focus:border-blue-500"
                  >
                    <option value="all">All Statuses</option>
                    <option value="Pending">Pending</option>
                    <option value="In progress">In Progress</option>
                    <option value="Processing">Processing</option>
                    <option value="Partial">Partial</option>
                    <option value="Completed">Completed</option>
                    <option value="Canceled">Canceled</option>
                    <option value="Paused">Paused</option>
                    <option value="Refill">Refill</option>
                  </select>
                </div>
                
                <div>
                  <label className="block text-sm font-medium text-gray-700 mb-1">Username</label>
                  <input
                    type="text"
                    value={filters.username}
                    onChange={(e) => setFilters({...filters, username: e.target.value, page: 0})}
                    placeholder="Filter by username"
                    className="px-3 py-2 border border-gray-300 rounded-md text-sm focus:ring-blue-500 focus:border-blue-500"
                  />
                </div>

                <div>
                  <label className="block text-sm font-medium text-gray-700 mb-1">Page Size</label>
                  <select
                    value={filters.size}
                    onChange={(e) => setFilters({...filters, size: Number(e.target.value), page: 0})}
                    className="px-3 py-2 border border-gray-300 rounded-md text-sm focus:ring-blue-500 focus:border-blue-500"
                  >
                    <option value={25}>25</option>
                    <option value={50}>50</option>
                    <option value={100}>100</option>
                  </select>
                </div>

                <div className="ml-auto">
                  <button
                    onClick={fetchOrders}
                    disabled={loading}
                    className="px-4 py-2 bg-blue-600 text-white rounded-md hover:bg-blue-700 disabled:opacity-50"
                  >
                    {loading ? 'Loading...' : 'Refresh'}
                  </button>
                </div>
              </div>
            </div>

            {/* Bulk Actions */}
            {selectedOrders.size > 0 && (
              <div className="bg-yellow-50 border border-yellow-200 p-4 rounded-lg">
                <div className="flex items-center justify-between">
                  <span className="text-sm font-medium text-yellow-800">
                    {selectedOrders.size} orders selected
                  </span>
                  <div className="flex space-x-2">
                    <button
                      onClick={() => handleBulkAction('START')}
                      disabled={bulkActionLoading}
                      className="px-3 py-1 bg-green-600 text-white text-sm rounded hover:bg-green-700 disabled:opacity-50"
                    >
                      ▶️ Start
                    </button>
                    <button
                      onClick={() => handleBulkAction('STOP')}
                      disabled={bulkActionLoading}
                      className="px-3 py-1 bg-red-600 text-white text-sm rounded hover:bg-red-700 disabled:opacity-50"
                    >
                      ⏹️ Stop
                    </button>
                    <button
                      onClick={() => handleBulkAction('RESTART')}
                      disabled={bulkActionLoading}
                      className="px-3 py-1 bg-blue-600 text-white text-sm rounded hover:bg-blue-700 disabled:opacity-50"
                    >
                      🔄 Restart
                    </button>
                    <button
                      onClick={() => handleBulkAction('PARTIAL_CANCEL')}
                      disabled={bulkActionLoading}
                      className="px-3 py-1 bg-orange-600 text-white text-sm rounded hover:bg-orange-700 disabled:opacity-50"
                    >
                      ✂️ Partial Cancel
                    </button>
                    <button
                      onClick={() => setSelectedOrders(new Set())}
                      className="px-3 py-1 bg-gray-600 text-white text-sm rounded hover:bg-gray-700"
                    >
                      Clear Selection
                    </button>
                  </div>
                </div>
              </div>
            )}

            {/* Orders Table */}
            <div className="bg-white shadow rounded-lg overflow-hidden">
              <div className="overflow-x-auto">
                <table className="min-w-full divide-y divide-gray-200">
                  <thead className="bg-gray-50">
                    <tr>
                      <th className="px-6 py-3 text-left">
                        <input
                          type="checkbox"
                          checked={selectedOrders.size === orders.length && orders.length > 0}
                          onChange={(e) => {
                            if (e.target.checked) {
                              setSelectedOrders(new Set(orders.map(o => o.id)));
                            } else {
                              setSelectedOrders(new Set());
                            }
                          }}
                          className="h-4 w-4 text-blue-600 focus:ring-blue-500 border-gray-300 rounded"
                        />
                      </th>
                      <th className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">
                        Order Info
                      </th>
                      <th className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">
                        User
                      </th>
                      <th className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">
                        Progress
                      </th>
                      <th className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">
                        Status
                      </th>
                      <th className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">
                        Processing
                      </th>
                      <th className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">
                        Actions
                      </th>
                    </tr>
                  </thead>
                  <tbody className="bg-white divide-y divide-gray-200">
                    {loading ? (
                      <tr>
                        <td colSpan={7} className="px-6 py-12 text-center">
                          <div className="flex justify-center items-center">
                            <div className="animate-spin rounded-full h-8 w-8 border-b-2 border-blue-600 mr-3"></div>
                            Loading orders...
                          </div>
                        </td>
                      </tr>
                    ) : orders.length === 0 ? (
                      <tr>
                        <td colSpan={7} className="px-6 py-12 text-center text-gray-500">
                          No orders found with current filters
                        </td>
                      </tr>
                    ) : (
                      orders.map((order) => {
                        const progress = order.quantity > 0 ? ((order.quantity - order.remains) / order.quantity) * 100 : 0;
                        return (
                          <tr key={order.id} className="hover:bg-gray-50">
                            <td className="px-6 py-4">
                              <input
                                type="checkbox"
                                checked={selectedOrders.has(order.id)}
                                onChange={(e) => {
                                  const newSelected = new Set(selectedOrders);
                                  if (e.target.checked) {
                                    newSelected.add(order.id);
                                  } else {
                                    newSelected.delete(order.id);
                                  }
                                  setSelectedOrders(newSelected);
                                }}
                                className="h-4 w-4 text-blue-600 focus:ring-blue-500 border-gray-300 rounded"
                              />
                            </td>
                            <td className="px-6 py-4">
                              <div className="text-sm">
                                <div className="font-medium text-gray-900">#{order.id}</div>
                                <div className="text-gray-500">Service {order.service.id}</div>
                                <div className="text-gray-500">{order.quantity.toLocaleString()} views</div>
                                <div className="text-gray-500">${order.charge}</div>
                                <div className="text-xs text-gray-400 mt-1">
                                  {formatDate(order.createdAt)}
                                </div>
                              </div>
                            </td>
                            <td className="px-6 py-4">
                              <div className="text-sm">
                                <div className="font-medium text-gray-900">{order.user.username}</div>
                                <div className="text-gray-500">ID: {order.user.id}</div>
                              </div>
                            </td>
                            <td className="px-6 py-4">
                              <div className="text-sm">
                                <div className="w-full bg-gray-200 rounded-full h-2 mb-2">
                                  <div 
                                    className={`h-2 rounded-full ${
                                      progress === 100 ? 'bg-green-600' : 
                                      progress > 50 ? 'bg-blue-600' : 
                                      progress > 0 ? 'bg-yellow-600' : 'bg-gray-400'
                                    }`}
                                    style={{ width: `${progress}%` }}
                                  ></div>
                                </div>
                                <div className="text-xs text-gray-600">
                                  {(order.quantity - order.remains).toLocaleString()} / {order.quantity.toLocaleString()} ({progress.toFixed(1)}%)
                                </div>
                                <div className="text-xs text-gray-500">
                                  Start: {order.startCount.toLocaleString()} | Remains: {order.remains.toLocaleString()}
                                </div>
                              </div>
                            </td>
                            <td className="px-6 py-4">
                              <span className={`inline-flex px-2 py-1 text-xs font-semibold rounded-full ${getStatusColor(order.status)}`}>
                                {order.status}
                              </span>
                            </td>
                            <td className="px-6 py-4">
                              <div className="text-xs space-y-1">
                                {order.videoProcessing && (
                                  <div className="flex items-center">
                                    <span className={`w-2 h-2 rounded-full mr-2 ${
                                      order.videoProcessing.clipCreated ? 'bg-green-500' : 'bg-gray-400'
                                    }`}></span>
                                    <span>Clip: {order.videoProcessing.clipCreated ? 'Created' : 'No'}</span>
                                  </div>
                                )}
                                {order.binomCampaign && (
                                  <div className="flex items-center">
                                    <span className={`w-2 h-2 rounded-full mr-2 ${
                                      order.binomCampaign.status === 'ACTIVE' ? 'bg-green-500' : 'bg-gray-400'
                                    }`}></span>
                                    <span>Campaign: {order.binomCampaign.status}</span>
                                  </div>
                                )}
                                {order.binomCampaign && (
                                  <div className="text-gray-500">
                                    Clicks: {order.binomCampaign.clicksDelivered}/{order.binomCampaign.clicksRequired}
                                  </div>
                                )}
                              </div>
                            </td>
                            <td className="px-6 py-4">
                              <div className="flex flex-wrap gap-1">
                                <button
                                  onClick={() => handleOrderAction(order.id, 'START')}
                                  className="px-2 py-1 bg-green-600 text-white text-xs rounded hover:bg-green-700"
                                  title="Start"
                                >
                                  ▶️
                                </button>
                                <button
                                  onClick={() => handleOrderAction(order.id, 'STOP')}
                                  className="px-2 py-1 bg-red-600 text-white text-xs rounded hover:bg-red-700"
                                  title="Stop"
                                >
                                  ⏹️
                                </button>
                                <button
                                  onClick={() => handleOrderAction(order.id, 'RESTART')}
                                  className="px-2 py-1 bg-blue-600 text-white text-xs rounded hover:bg-blue-700"
                                  title="Restart"
                                >
                                  🔄
                                </button>
                                <button
                                  onClick={() => handleOrderAction(order.id, 'SET_START_COUNT')}
                                  className="px-2 py-1 bg-purple-600 text-white text-xs rounded hover:bg-purple-700"
                                  title="Set Start Count"
                                >
                                  📊
                                </button>
                                <button
                                  onClick={() => handleOrderAction(order.id, 'REFRESH_START_COUNT')}
                                  className="px-2 py-1 bg-indigo-600 text-white text-xs rounded hover:bg-indigo-700"
                                  title="Refresh Start Count"
                                >
                                  🔄📊
                                </button>
                                <button
                                  onClick={() => handleOrderAction(order.id, 'REFILL')}
                                  className="px-2 py-1 bg-orange-600 text-white text-xs rounded hover:bg-orange-700"
                                  title="Refill"
                                >
                                  🔁
                                </button>
                              </div>
                            </td>
                          </tr>
                        );
                      })
                    )}
                  </tbody>
                </table>
              </div>
            </div>
          </div>
        )}

        {/* Settings Tab */}
        {activeTab === 'settings' && user.role === 'ADMIN' && (
          <div className="space-y-6">
            <div className="bg-white shadow rounded-lg p-6">
              <h3 className="text-lg font-medium text-gray-900 mb-4">Conversion Coefficients</h3>
              <div className="space-y-4">
                {coefficients.map((coeff) => (
                  <div key={coeff.id} className="border border-gray-200 rounded-lg p-4">
                    <div className="flex items-center justify-between mb-3">
                      <h4 className="font-medium text-gray-900">Service ID: {coeff.serviceId}</h4>
                      <span className="text-sm text-gray-500">
                        Last updated: {formatDate(coeff.updatedAt)}
                      </span>
                    </div>
                    <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
                      <div>
                        <label className="block text-sm font-medium text-gray-700 mb-1">
                          With Clip Coefficient
                        </label>
                        <input
                          type="number"
                          step="0.1"
                          min="1.0"
                          max="10.0"
                          defaultValue={coeff.withClip}
                          onBlur={(e) => {
                            const value = parseFloat(e.target.value);
                            if (value !== parseFloat(coeff.withClip)) {
                              updateCoefficient(coeff.id, value, parseFloat(coeff.withoutClip));
                            }
                          }}
                          className="block w-full px-3 py-2 border border-gray-300 rounded-md shadow-sm focus:ring-blue-500 focus:border-blue-500"
                        />
                      </div>
                      <div>
                        <label className="block text-sm font-medium text-gray-700 mb-1">
                          Without Clip Coefficient
                        </label>
                        <input
                          type="number"
                          step="0.1"
                          min="1.0"
                          max="10.0"
                          defaultValue={coeff.withoutClip}
                          onBlur={(e) => {
                            const value = parseFloat(e.target.value);
                            if (value !== parseFloat(coeff.withoutClip)) {
                              updateCoefficient(coeff.id, parseFloat(coeff.withClip), value);
                            }
                          }}
                          className="block w-full px-3 py-2 border border-gray-300 rounded-md shadow-sm focus:ring-blue-500 focus:border-blue-500"
                        />
                      </div>
                    </div>
                    <div className="mt-3 text-sm text-gray-600">
                      <p>• With Clip: Required clicks = Target views × {coeff.withClip}</p>
                      <p>• Without Clip: Required clicks = Target views × {coeff.withoutClip}</p>
                    </div>
                  </div>
                ))}
              </div>
            </div>
          </div>
        )}

        {/* Traffic Sources Tab */}
        {activeTab === 'traffic-sources' && user.role === 'ADMIN' && (
          <div className="space-y-6">
            <div className="bg-white shadow rounded-lg p-6">
              <div className="flex justify-between items-center mb-4">
                <h3 className="text-lg font-medium text-gray-900">Traffic Sources</h3>
                <button className="px-4 py-2 bg-blue-600 text-white rounded-md hover:bg-blue-700">
                  Add Source
                </button>
              </div>
              <div className="overflow-x-auto">
                <table className="min-w-full divide-y divide-gray-200">
                  <thead className="bg-gray-50">
                    <tr>
                      <th className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">
                        Name
                      </th>
                      <th className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">
                        Source ID
                      </th>
                      <th className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">
                        Weight
                      </th>
                      <th className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">
                        Daily Usage
                      </th>
                      <th className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">
                        Performance
                      </th>
                      <th className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">
                        Status
                      </th>
                      <th className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">
                        Actions
                      </th>
                    </tr>
                  </thead>
                  <tbody className="bg-white divide-y divide-gray-200">
                    {trafficSources.map((source) => (
                      <tr key={source.id}>
                        <td className="px-6 py-4 whitespace-nowrap">
                          <div className="text-sm font-medium text-gray-900">{source.name}</div>
                          {source.geoTargeting && (
                            <div className="text-sm text-gray-500">GEO: {source.geoTargeting}</div>
                          )}
                        </td>
                        <td className="px-6 py-4 whitespace-nowrap text-sm font-mono text-gray-900">
                          {source.sourceId}
                        </td>
                        <td className="px-6 py-4 whitespace-nowrap text-sm text-gray-900">
                          {source.weight}
                        </td>
                        <td className="px-6 py-4 whitespace-nowrap">
                          <div className="text-sm text-gray-900">
                            {source.clicksUsedToday.toLocaleString()} / {source.dailyLimit ? source.dailyLimit.toLocaleString() : '∞'}
                          </div>
                          {source.dailyLimit && (
                            <div className="w-full bg-gray-200 rounded-full h-1 mt-1">
                              <div 
                                className="bg-blue-600 h-1 rounded-full"
                                style={{ width: `${Math.min(100, (source.clicksUsedToday / source.dailyLimit) * 100)}%` }}
                              ></div>
                            </div>
                          )}
                        </td>
                        <td className="px-6 py-4 whitespace-nowrap">
                          <div className="text-sm text-gray-900">{source.performanceScore}%</div>
                          <div className={`w-full h-1 rounded-full ${
                            parseFloat(source.performanceScore) >= 80 ? 'bg-green-500' :
                            parseFloat(source.performanceScore) >= 60 ? 'bg-yellow-500' :
                            'bg-red-500'
                          }`}></div>
                        </td>
                        <td className="px-6 py-4 whitespace-nowrap">
                          <span className={`inline-flex px-2 py-1 text-xs font-semibold rounded-full ${
                            source.active ? 'bg-green-100 text-green-800' : 'bg-red-100 text-red-800'
                          }`}>
                            {source.active ? 'Active' : 'Inactive'}
                          </span>
                        </td>
                        <td className="px-6 py-4 whitespace-nowrap text-sm font-medium">
                          <button className="text-blue-600 hover:text-blue-900 mr-3">
                            Edit
                          </button>
                          <button className={`${
                            source.active ? 'text-red-600 hover:text-red-900' : 'text-green-600 hover:text-green-900'
                          }`}>
                            {source.active ? 'Disable' : 'Enable'}
                          </button>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </div>
          </div>
        )}
      </div>
    </div>
  );
};