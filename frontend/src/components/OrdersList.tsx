// frontend/src/components/OrdersList.tsx

import React, { useState, useEffect } from 'react';
import { Order, OrderStatus } from '../types';
import { apiService } from '../services/api';

interface OrdersListProps {
  orders: Order[];
  loading?: boolean;
  onRefresh?: () => void;
}

export const OrdersList: React.FC<OrdersListProps> = ({ 
  orders: initialOrders, 
  loading = false, 
  onRefresh 
}) => {
  const [orders, setOrders] = useState<Order[]>(initialOrders);
  const [filter, setFilter] = useState<OrderStatus | 'all'>('all');
  const [sortField, setSortField] = useState<keyof Order>('id');
  const [sortDirection, setSortDirection] = useState<'asc' | 'desc'>('desc');

  useEffect(() => {
    setOrders(initialOrders);
  }, [initialOrders]);

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

  const getStatusIcon = (status: OrderStatus): string => {
    const icons: Record<OrderStatus, string> = {
      'Pending': '⏳',
      'In progress': '🔄',
      'Processing': '⚙️',
      'Partial': '📊',
      'Completed': '✅',
      'Canceled': '❌',
      'Paused': '⏸️',
      'Refill': '🔁'
    };
    return icons[status] || '❓';
  };

  const filteredOrders = orders.filter(order => 
    filter === 'all' || order.status === filter
  );

  const sortedOrders = [...filteredOrders].sort((a, b) => {
    const aValue = a[sortField];
    const bValue = b[sortField];
    
    if (typeof aValue === 'string' && typeof bValue === 'string') {
      return sortDirection === 'asc' 
        ? aValue.localeCompare(bValue)
        : bValue.localeCompare(aValue);
    }
    
    if (typeof aValue === 'number' && typeof bValue === 'number') {
      return sortDirection === 'asc' ? aValue - bValue : bValue - aValue;
    }
    
    return 0;
  });

  const handleSort = (field: keyof Order) => {
    if (sortField === field) {
      setSortDirection(sortDirection === 'asc' ? 'desc' : 'asc');
    } else {
      setSortField(field);
      setSortDirection('desc');
    }
  };

  const formatDate = (dateString?: string): string => {
    if (!dateString) return 'N/A';
    return new Date(dateString).toLocaleString();
  };

  const truncateUrl = (url: string, maxLength: number = 50): string => {
    return url.length > maxLength ? url.substring(0, maxLength) + '...' : url;
  };

  const getProgress = (order: Order): number => {
    if (order.quantity === 0) return 0;
    const delivered = order.quantity - order.remains;
    return Math.min(100, Math.max(0, (delivered / order.quantity) * 100));
  };

  return (
    <div className="bg-white shadow rounded-lg">
      <div className="px-6 py-4 border-b border-gray-200">
        <div className="flex justify-between items-center">
          <h2 className="text-lg font-medium text-gray-900">Orders</h2>
          <div className="flex items-center space-x-4">
            <select
              value={filter}
              onChange={(e) => setFilter(e.target.value as OrderStatus | 'all')}
              className="px-3 py-1 border border-gray-300 rounded-md text-sm focus:outline-none focus:ring-1 focus:ring-blue-500"
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
            {onRefresh && (
              <button
                onClick={onRefresh}
                disabled={loading}
                className="px-3 py-1 bg-blue-600 text-white rounded-md text-sm hover:bg-blue-700 disabled:opacity-50"
              >
                {loading ? '🔄' : '↻'} Refresh
              </button>
            )}
          </div>
        </div>
        <div className="mt-2 text-sm text-gray-600">
          Showing {sortedOrders.length} of {orders.length} orders
        </div>
      </div>
      
      <div className="overflow-x-auto">
        <table className="min-w-full divide-y divide-gray-200">
          <thead className="bg-gray-50">
            <tr>
              <th 
                onClick={() => handleSort('id')}
                className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider cursor-pointer hover:bg-gray-100"
              >
                <div className="flex items-center">
                  ID
                  {sortField === 'id' && (
                    <span className="ml-1">{sortDirection === 'asc' ? '↑' : '↓'}</span>
                  )}
                </div>
              </th>
              <th className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">
                Service
              </th>
              <th className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">
                Link
              </th>
              <th 
                onClick={() => handleSort('quantity')}
                className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider cursor-pointer hover:bg-gray-100"
              >
                <div className="flex items-center">
                  Quantity
                  {sortField === 'quantity' && (
                    <span className="ml-1">{sortDirection === 'asc' ? '↑' : '↓'}</span>
                  )}
                </div>
              </th>
              <th className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">
                Progress
              </th>
              <th 
                onClick={() => handleSort('startCount')}
                className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider cursor-pointer hover:bg-gray-100"
              >
                <div className="flex items-center">
                  Start Count
                  {sortField === 'startCount' && (
                    <span className="ml-1">{sortDirection === 'asc' ? '↑' : '↓'}</span>
                  )}
                </div>
              </th>
              <th className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">
                Remains
              </th>
              <th 
                onClick={() => handleSort('status')}
                className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider cursor-pointer hover:bg-gray-100"
              >
                <div className="flex items-center">
                  Status
                  {sortField === 'status' && (
                    <span className="ml-1">{sortDirection === 'asc' ? '↑' : '↓'}</span>
                  )}
                </div>
              </th>
              <th 
                onClick={() => handleSort('charge')}
                className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider cursor-pointer hover:bg-gray-100"
              >
                <div className="flex items-center">
                  Charge
                  {sortField === 'charge' && (
                    <span className="ml-1">{sortDirection === 'asc' ? '↑' : '↓'}</span>
                  )}
                </div>
              </th>
              <th className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">
                Created
              </th>
            </tr>
          </thead>
          <tbody className="bg-white divide-y divide-gray-200">
            {loading ? (
              <tr>
                <td colSpan={9} className="px-6 py-12 text-center">
                  <div className="flex justify-center items-center">
                    <div className="animate-spin rounded-full h-8 w-8 border-b-2 border-blue-600 mr-3"></div>
                    Loading orders...
                  </div>
                </td>
              </tr>
            ) : sortedOrders.length === 0 ? (
              <tr>
                <td colSpan={9} className="px-6 py-12 text-center text-gray-500">
                  {filter === 'all' ? 'No orders yet' : `No orders with status "${filter}"`}
                  <div className="mt-2 text-sm">
                    {filter !== 'all' && (
                      <button
                        onClick={() => setFilter('all')}
                        className="text-blue-600 hover:text-blue-800"
                      >
                        Show all orders
                      </button>
                    )}
                  </div>
                </td>
              </tr>
            ) : (
              sortedOrders.map((order) => {
                const progress = getProgress(order);
                return (
                  <tr key={order.id} className="hover:bg-gray-50">
                    <td className="px-6 py-4 whitespace-nowrap text-sm font-medium text-gray-900">
                      #{order.id}
                    </td>
                    <td className="px-6 py-4 whitespace-nowrap text-sm text-gray-900">
                      Service {order.service}
                    </td>
                    <td className="px-6 py-4 text-sm text-gray-900 max-w-xs">
                      <a 
                        href={order.link} 
                        target="_blank" 
                        rel="noopener noreferrer" 
                        className="text-blue-600 hover:text-blue-800 truncate block"
                        title={order.link}
                      >
                        {truncateUrl(order.link)}
                      </a>
                    </td>
                    <td className="px-6 py-4 whitespace-nowrap text-sm text-gray-900">
                      {order.quantity.toLocaleString()}
                    </td>
                    <td className="px-6 py-4 whitespace-nowrap text-sm text-gray-900">
                      <div className="w-full bg-gray-200 rounded-full h-2">
                        <div 
                          className={`h-2 rounded-full transition-all duration-300 ${
                            progress === 100 ? 'bg-green-600' : 
                            progress > 50 ? 'bg-blue-600' : 
                            progress > 0 ? 'bg-yellow-600' : 'bg-gray-400'
                          }`}
                          style={{ width: `${progress}%` }}
                        ></div>
                      </div>
                      <div className="text-xs text-gray-600 mt-1">
                        {(order.quantity - order.remains).toLocaleString()} / {order.quantity.toLocaleString()} ({progress.toFixed(1)}%)
                      </div>
                    </td>
                    <td className="px-6 py-4 whitespace-nowrap text-sm text-gray-900">
                      {order.startCount.toLocaleString()}
                    </td>
                    <td className="px-6 py-4 whitespace-nowrap text-sm text-gray-900">
                      {order.remains.toLocaleString()}
                    </td>
                    <td className="px-6 py-4 whitespace-nowrap">
                      <span className={`inline-flex items-center px-2.5 py-0.5 rounded-full text-xs font-medium ${getStatusColor(order.status)}`}>
                        <span className="mr-1">{getStatusIcon(order.status)}</span>
                        {order.status}
                      </span>
                    </td>
                    <td className="px-6 py-4 whitespace-nowrap text-sm text-gray-900 font-medium">
                      ${order.charge}
                    </td>
                    <td className="px-6 py-4 whitespace-nowrap text-sm text-gray-500">
                      {formatDate(order.createdAt)}
                    </td>
                  </tr>
                );
              })
            )}
          </tbody>
        </table>
      </div>
      
      {!loading && sortedOrders.length > 0 && (
        <div className="px-6 py-3 bg-gray-50 border-t border-gray-200">
          <div className="flex justify-between items-center text-sm text-gray-600">
            <div>
              Total: {sortedOrders.length} orders
            </div>
            <div>
              Total charge: ${sortedOrders.reduce((sum, order) => sum + parseFloat(order.charge), 0).toFixed(2)}
            </div>
          </div>
        </div>
      )}
    </div>
  );
};