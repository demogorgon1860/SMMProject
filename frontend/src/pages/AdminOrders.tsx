import React, { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { adminAPI } from '../services/api';

interface AdminOrder {
  id: number;
  username: string;
  serviceId: number;
  serviceName: string;
  link: string;
  quantity: number;
  charge: number;
  startCount: number;
  remains: number;
  status: string;
  createdAt: string;
  updatedAt: string;
  orderName: string;
  binomOfferId: string;
  youtubeVideoId: string;
}

export const AdminOrders: React.FC = () => {
  const navigate = useNavigate();
  const [orders, setOrders] = useState<AdminOrder[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [statusFilter, setStatusFilter] = useState('');
  const [searchTerm, setSearchTerm] = useState('');
  const [startDate, setStartDate] = useState('');
  const [endDate, setEndDate] = useState('');
  const [selectedOrders, setSelectedOrders] = useState<Set<number>>(new Set());
  const [refillLoading, setRefillLoading] = useState<number | null>(null);

  useEffect(() => {
    fetchOrders();
  }, [statusFilter, searchTerm, startDate, endDate]);

  const fetchOrders = async () => {
    try {
      const response = await adminAPI.getAllOrders(
        statusFilter || undefined,
        searchTerm || undefined,
        startDate || undefined,
        endDate || undefined
      );
      if (response.orders) {
        setOrders(response.orders);
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

  const handleOrderAction = async (orderId: number, action: string, reason?: string) => {
    try {
      await adminAPI.performOrderAction(orderId, { action, reason });
      fetchOrders(); // Refresh the orders list
      alert(`Order ${action} successful`);
    } catch (error: any) {
      alert(error.response?.data?.message || `Failed to ${action} order`);
    }
  };

  const handleBulkAction = async (action: string) => {
    if (selectedOrders.size === 0) {
      alert('Please select at least one order');
      return;
    }

    const confirmMessage = action === 'delete'
      ? `DELETE ${selectedOrders.size} orders? This action CANNOT be undone!`
      : `Apply "${action}" to ${selectedOrders.size} selected orders?`;

    if (!window.confirm(confirmMessage)) {
      return;
    }

    const reason = action === 'partial' ? prompt('Partial reason (optional):') : undefined;

    try {
      // Process all selected orders
      const promises = Array.from(selectedOrders).map(orderId =>
        adminAPI.performOrderAction(orderId, { action, reason: reason || undefined })
      );

      await Promise.all(promises);
      alert(`Successfully applied "${action}" to ${selectedOrders.size} orders`);
      setSelectedOrders(new Set());
      fetchOrders();
    } catch (error: any) {
      alert(error.response?.data?.message || `Failed to apply bulk action`);
    }
  };

  const handleRefill = async (orderId: number) => {
    // Prevent spam clicking
    if (refillLoading !== null) {
      alert('Please wait for the current refill request to complete.');
      return;
    }

    if (!window.confirm('Create a refill for this order? The system will fetch current view count and create a new order for undelivered views.')) {
      return;
    }

    setRefillLoading(orderId);
    try {
      const response = await adminAPI.createRefill(orderId);
      alert(
        `Refill created successfully!\n\n` +
        `Original Order: ${response.originalOrderId}\n` +
        `Refill Order ID: ${response.refillOrderId}\n` +
        `Refill #${response.refillNumber}\n\n` +
        `Original Quantity: ${response.originalQuantity} views\n` +
        `Delivered: ${response.deliveredQuantity} views\n` +
        `Refill Quantity: ${response.refillQuantity} views\n\n` +
        `Current YouTube Views: ${response.currentViewCount}`
      );
      fetchOrders(); // Refresh to show the new refill order
    } catch (error: any) {
      alert(`Refill failed: ${error.response?.data?.message || error.message}`);
    } finally {
      setRefillLoading(null);
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

  const getStatusColor = (status: string) => {
    switch (status) {
      case 'COMPLETED':
      case 'Completed':
        return 'text-green-700 bg-green-50';
      case 'IN_PROGRESS':
      case 'Processing':
        return 'text-blue-700 bg-blue-50';
      case 'PENDING':
      case 'Pending':
        return 'text-yellow-700 bg-yellow-50';
      case 'FAILED':
      case 'Failed':
        return 'text-red-700 bg-red-50';
      case 'CANCELED':
      case 'Canceled':
      case 'CANCELLED':
      case 'Cancelled':
        return 'text-gray-700 bg-gray-50';
      default:
        return 'text-gray-700 bg-gray-50';
    }
  };

  const getServiceBadgeColor = (serviceId?: number) => {
    if (!serviceId) return 'bg-gray-100 text-gray-700';
    const colors = [
      'bg-blue-100 text-blue-700',
      'bg-green-100 text-green-700',
      'bg-purple-100 text-purple-700',
      'bg-pink-100 text-pink-700',
      'bg-indigo-100 text-indigo-700',
      'bg-red-100 text-red-700',
      'bg-yellow-100 text-yellow-700',
      'bg-teal-100 text-teal-700',
    ];
    return colors[serviceId % colors.length];
  };

  const formatDate = (dateString: string) => {
    const date = new Date(dateString);
    return date.toLocaleDateString('en-US', {
      year: 'numeric',
      month: '2-digit',
      day: '2-digit',
    }) + ' ' + date.toLocaleTimeString('en-US', {
      hour: '2-digit',
      minute: '2-digit',
      second: '2-digit',
      hour12: false
    });
  };

  const formatLink = (link: string) => {
    if (link.length > 40) {
      return link.substring(0, 37) + '...';
    }
    return link;
  };

  // Backend now handles all filtering (status, username, dates)
  // No need for client-side filtering
  const filteredOrders = orders;

  // Calculate statistics for selected orders
  const selectedOrdersData = filteredOrders.filter(order => selectedOrders.has(order.id));
  const statistics = {
    charge: selectedOrdersData.reduce((sum, order) => sum + order.charge, 0),
    quantity: selectedOrdersData.reduce((sum, order) => sum + order.quantity, 0),
    remains: selectedOrdersData.reduce((sum, order) => sum + order.remains, 0),
  };

  if (loading) {
    return (
      <div className="flex items-center justify-center min-h-screen">
        <div className="text-center">
          <div className="inline-block animate-spin rounded-full h-8 w-8 border-b-2 border-blue-600"></div>
          <p className="mt-2 text-gray-600">Loading orders...</p>
        </div>
      </div>
    );
  }

  if (error) {
    return (
      <div className="flex items-center justify-center min-h-screen">
        <div className="text-center text-red-600">
          <p className="text-xl font-semibold">Error</p>
          <p>{error}</p>
        </div>
      </div>
    );
  }

  return (
    <div className="w-full">
      {/* Header, Filters, and Statistics with padding */}
      <div className="px-4 py-6">
        <div className="mb-6 flex items-center justify-between">
          <div>
            <h1 className="text-2xl font-bold text-gray-900">All Orders Management</h1>
            <p className="text-gray-600 mt-2">View and manage all user orders</p>
          </div>
          <button
            onClick={() => navigate('/admin/refills')}
            className="bg-purple-500 hover:bg-purple-600 text-white px-6 py-2 rounded-md transition font-medium flex items-center gap-2"
          >
            <svg className="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M4 4v5h.582m15.356 2A8.001 8.001 0 004.582 9m0 0H9m11 11v-5h-.581m0 0a8.003 8.003 0 01-15.357-2m15.357 2H15" />
            </svg>
            View All Refills
          </button>
        </div>

        {/* Filters */}
        <div className="bg-white rounded-lg shadow p-4 mb-6">
          <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-5 gap-4">
            <div>
              <label className="block text-sm font-medium text-gray-700 mb-2">
                Search
              </label>
              <input
                type="text"
                value={searchTerm}
                onChange={(e) => setSearchTerm(e.target.value)}
                placeholder="Search by ID, username, link, or Binom ID..."
                className="w-full px-3 py-2 border border-gray-300 rounded-md focus:outline-none focus:ring-2 focus:ring-blue-500 focus:border-transparent"
              />
            </div>

            <div>
              <label className="block text-sm font-medium text-gray-700 mb-2">
                Status Filter
              </label>
              <select
                value={statusFilter}
                onChange={(e) => setStatusFilter(e.target.value)}
                className="w-full px-3 py-2 border border-gray-300 rounded-md focus:outline-none focus:ring-2 focus:ring-blue-500 focus:border-transparent"
              >
                <option value="">All Statuses</option>
                <option value="PENDING">Pending</option>
                <option value="IN_PROGRESS">In Progress</option>
                <option value="COMPLETED">Completed</option>
                <option value="PARTIAL">Partial</option>
                <option value="CANCELLED">Cancelled</option>
              </select>
            </div>

            <div>
              <label className="block text-sm font-medium text-gray-700 mb-2">
                From Date
              </label>
              <input
                type="date"
                value={startDate}
                onChange={(e) => setStartDate(e.target.value)}
                className="w-full px-3 py-2 border border-gray-300 rounded-md focus:outline-none focus:ring-2 focus:ring-blue-500 focus:border-transparent"
              />
            </div>

            <div>
              <label className="block text-sm font-medium text-gray-700 mb-2">
                To Date
              </label>
              <input
                type="date"
                value={endDate}
                onChange={(e) => setEndDate(e.target.value)}
                className="w-full px-3 py-2 border border-gray-300 rounded-md focus:outline-none focus:ring-2 focus:ring-blue-500 focus:border-transparent"
              />
            </div>

            <div className="flex items-end">
              <button
                onClick={fetchOrders}
                className="bg-blue-500 hover:bg-blue-600 text-white px-6 py-2 rounded-md transition font-medium w-full"
              >
                Refresh
              </button>
            </div>
          </div>
        </div>

        {/* Selection Statistics Bar */}
        {selectedOrders.size > 0 && (
          <div className="bg-blue-50 border border-blue-200 rounded-lg p-4 mb-6">
          <div className="flex items-center justify-between flex-wrap gap-4">
            <div className="flex items-center gap-6">
              <div className="flex items-center">
                <input
                  type="checkbox"
                  checked={true}
                  onChange={() => setSelectedOrders(new Set())}
                  className="rounded border-gray-300 text-blue-600 focus:ring-blue-500 mr-2"
                />
                <span className="font-semibold text-gray-900">{selectedOrders.size} orders selected</span>
              </div>

              <div className="flex items-center gap-4 text-sm">
                <span className="text-gray-700">
                  <span className="font-medium">Charge:</span> {statistics.charge.toFixed(6)}
                </span>
                <span className="text-gray-700">
                  <span className="font-medium">Quantity:</span> {statistics.quantity.toLocaleString()}
                </span>
                <span className="text-gray-700">
                  <span className="font-medium">Remains:</span> {statistics.remains.toLocaleString()}
                </span>
              </div>
            </div>

            <div className="flex items-center gap-2">
              <div className="relative">
                <select
                  onChange={(e) => {
                    if (e.target.value) {
                      handleBulkAction(e.target.value);
                      e.target.value = '';
                    }
                  }}
                  className="px-4 py-2 border border-gray-300 rounded-md bg-white hover:bg-gray-50 focus:outline-none focus:ring-2 focus:ring-blue-500 font-medium"
                >
                  <option value="">Actions</option>
                  <option value="partial">Partial</option>
                  <option value="delete">Delete</option>
                  </select>
                </div>
              </div>
            </div>
          </div>
        )}
      </div>

      {/* Table without padding - full width */}
      {filteredOrders.length === 0 ? (
        <div className="px-4">
          <div className="bg-white rounded-lg shadow p-12 text-center">
            <div className="text-gray-400 mb-4">
              <svg className="mx-auto h-12 w-12" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M9 12h6m-6 4h6m2 5H7a2 2 0 01-2-2V5a2 2 0 012-2h5.586a1 1 0 01.707.293l5.414 5.414a1 1 0 01.293.707V19a2 2 0 01-2 2z" />
              </svg>
            </div>
            <p className="text-gray-500 text-lg">No orders found</p>
            <p className="text-gray-400 text-sm mt-2">
              {searchTerm || statusFilter
                ? 'Try adjusting your filters'
                : 'Orders will appear here once users create them'}
            </p>
          </div>
        </div>
      ) : (
        <div className="w-full bg-white shadow-md overflow-hidden">
          <div className="overflow-x-auto">
            <table className="w-full table-auto divide-y divide-gray-200">
              <thead className="bg-gray-50">
                <tr>
                  <th scope="col" className="px-4 py-3 text-left">
                    <input
                      type="checkbox"
                      checked={selectedOrders.size === filteredOrders.length && filteredOrders.length > 0}
                      onChange={toggleAllOrders}
                      className="rounded border-gray-300 text-blue-600 focus:ring-blue-500"
                    />
                  </th>
                  <th scope="col" className="px-6 py-3 text-left text-xs font-medium text-gray-700 uppercase tracking-wider">
                    ID
                  </th>
                  <th scope="col" className="px-6 py-3 text-left text-xs font-medium text-gray-700 uppercase tracking-wider">
                    User
                  </th>
                  <th scope="col" className="px-6 py-3 text-left text-xs font-medium text-gray-700 uppercase tracking-wider">
                    Charge
                  </th>
                  <th scope="col" className="px-6 py-3 text-left text-xs font-medium text-gray-700 uppercase tracking-wider">
                    Link
                  </th>
                  <th scope="col" className="px-6 py-3 text-center text-xs font-medium text-gray-700 uppercase tracking-wider">
                    Start count
                  </th>
                  <th scope="col" className="px-6 py-3 text-center text-xs font-medium text-gray-700 uppercase tracking-wider">
                    Quantity
                  </th>
                  <th scope="col" className="px-6 py-3 text-left text-xs font-medium text-gray-700 uppercase tracking-wider">
                    Service
                  </th>
                  <th scope="col" className="px-6 py-3 text-center text-xs font-medium text-gray-700 uppercase tracking-wider">
                    Status
                  </th>
                  <th scope="col" className="px-6 py-3 text-center text-xs font-medium text-gray-700 uppercase tracking-wider">
                    Remains
                  </th>
                  <th scope="col" className="px-6 py-3 text-left text-xs font-medium text-gray-700 uppercase tracking-wider">
                    Created
                  </th>
                  <th scope="col" className="px-6 py-3 text-center text-xs font-medium text-gray-700 uppercase tracking-wider">
                    Actions
                  </th>
                </tr>
              </thead>
              <tbody className="bg-white divide-y divide-gray-200">
                {filteredOrders.map((order) => (
                  <tr key={order.id} className="hover:bg-gray-50 transition">
                    <td className="px-4 py-4">
                      <input
                        type="checkbox"
                        checked={selectedOrders.has(order.id)}
                        onChange={() => toggleOrderSelection(order.id)}
                        className="rounded border-gray-300 text-blue-600 focus:ring-blue-500"
                      />
                    </td>
                    <td className="px-6 py-4 whitespace-nowrap">
                      <div className="text-sm font-medium text-gray-900">{order.id}</div>
                      <div className="text-xs text-gray-400">{order.binomOfferId || '-'}</div>
                    </td>
                    <td className="px-6 py-4 whitespace-nowrap">
                      <div className="text-sm text-gray-900">{order.username}</div>
                    </td>
                    <td className="px-6 py-4 whitespace-nowrap">
                      <div className="text-sm text-gray-900">{order.charge?.toFixed(4) || '0.0000'}</div>
                      <div className="text-xs text-gray-400">{(order.charge && order.charge > 0) ? (order.charge * 0.97125).toFixed(5) : '0.00000'}</div>
                    </td>
                    <td className="px-6 py-4">
                      <div className="text-sm text-gray-900 max-w-xs truncate" title={order.link}>
                        <a href={order.link} target="_blank" rel="noopener noreferrer" className="text-blue-600 hover:text-blue-800">
                          {formatLink(order.link)}
                        </a>
                      </div>
                    </td>
                    <td className="px-6 py-4 whitespace-nowrap text-center">
                      <div className="text-sm text-gray-900">{order.startCount || 0}</div>
                    </td>
                    <td className="px-6 py-4 whitespace-nowrap text-center">
                      <span className={`inline-flex items-center px-2.5 py-0.5 rounded-full text-xs font-medium ${getServiceBadgeColor(order.serviceId)}`}>
                        {order.quantity || 0}
                      </span>
                    </td>
                    <td className="px-6 py-4">
                      <div className="text-sm text-gray-900 max-w-xs truncate" title={order.serviceName}>
                        {order.serviceName || 'N/A'}
                      </div>
                    </td>
                    <td className="px-6 py-4 whitespace-nowrap text-center">
                      <span className={`px-3 py-1 inline-flex text-xs leading-5 font-semibold rounded-full ${getStatusColor(order.status)}`}>
                        {order.status}
                      </span>
                    </td>
                    <td className="px-6 py-4 whitespace-nowrap text-center">
                      <div className="text-sm text-gray-900">{order.remains !== null && order.remains !== undefined ? order.remains : '-'}</div>
                    </td>
                    <td className="px-6 py-4 whitespace-nowrap">
                      <div className="text-sm text-gray-900">{formatDate(order.createdAt || new Date().toISOString())}</div>
                    </td>
                    <td className="px-6 py-4 whitespace-nowrap text-center">
                      <div className="relative inline-block text-left">
                        <select
                          onChange={(e) => {
                            const action = e.target.value;
                            if (action === 'partial') {
                              const reason = prompt('Partial reason (optional):');
                              handleOrderAction(order.id, 'partial', reason || undefined);
                            } else if (action === 'delete') {
                              if (window.confirm(`DELETE order ${order.id}? This action CANNOT be undone!`)) {
                                handleOrderAction(order.id, 'delete');
                              }
                            } else if (action === 'refill') {
                              handleRefill(order.id);
                            } else if (action === 'view') {
                              window.open(order.link, '_blank');
                            }
                            e.target.value = '';
                          }}
                          className="px-3 py-1 text-sm border border-gray-300 rounded-md bg-white hover:bg-gray-50 focus:outline-none focus:ring-2 focus:ring-blue-500"
                          disabled={refillLoading === order.id}
                        >
                          <option value="">{refillLoading === order.id ? 'Processing...' : 'Actions'}</option>
                          {(order.status === 'COMPLETED' || order.status === 'IN_PROGRESS' || order.status === 'PARTIAL') && (
                            <option value="refill" disabled={refillLoading !== null}>
                              {refillLoading === order.id ? '⏳ Processing Refill...' : '🔄 Refill'}
                            </option>
                          )}
                          {order.status !== 'CANCELLED' && order.status !== 'PARTIAL' && (
                            <option value="partial">Partial</option>
                          )}
                          <option value="delete">Delete</option>
                          <option value="view">View Link</option>
                        </select>
                      </div>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>

          {/* Table footer with row count */}
          <div className="bg-gray-50 px-6 py-3 border-t border-gray-200">
            <div className="flex items-center justify-between">
              <div className="text-sm text-gray-600">
                Showing <span className="font-medium">{filteredOrders.length}</span> of <span className="font-medium">{orders.length}</span> order{orders.length !== 1 ? 's' : ''}
              </div>
              {selectedOrders.size > 0 && (
                <div className="text-sm text-gray-600">
                  <span className="font-medium">{selectedOrders.size}</span> selected
                  </div>
                )}
              </div>
            </div>
          </div>
        )}
    </div>
  );
};
