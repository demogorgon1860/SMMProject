import React, { useEffect, useState } from 'react';
import { orderAPI } from '../services/api';
import { Order } from '../types';
import { useAuthStore } from '../store/authStore';

export const Orders: React.FC = () => {
  const [orders, setOrders] = useState<Order[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const { user } = useAuthStore();
  
  useEffect(() => {
    fetchOrders();
  }, []);
  
  const fetchOrders = async () => {
    try {
      const response = await orderAPI.getOrders();
      // Ensure we always have an array
      if (Array.isArray(response.data)) {
        setOrders(response.data);
      } else if (response.data && Array.isArray(response.data.content)) {
        // Handle paginated response
        setOrders(response.data.content);
      } else {
        setOrders([]);
      }
    } catch (error: any) {
      console.error('Error fetching orders:', error);
      setError(error.response?.data?.message || 'Failed to fetch orders');
      setOrders([]); // Set empty array on error
    } finally {
      setLoading(false);
    }
  };
  
  const handleDelete = async (orderId: number) => {
    if (!confirm('Are you sure you want to delete this order?')) return;
    
    try {
      await orderAPI.deleteOrder(orderId);
      setOrders(orders.filter(order => order.id !== orderId));
    } catch (error: any) {
      alert(error.response?.data?.message || 'Failed to delete order');
    }
  };
  
  const getStatusColor = (status: string) => {
    switch (status) {
      case 'COMPLETED': return 'text-green-600 bg-green-100';
      case 'IN_PROGRESS': return 'text-blue-600 bg-blue-100';
      case 'PENDING': return 'text-yellow-600 bg-yellow-100';
      case 'FAILED': return 'text-red-600 bg-red-100';
      case 'CANCELED': return 'text-gray-600 bg-gray-100';
      default: return 'text-gray-600 bg-gray-100';
    }
  };
  
  if (loading) return <div className="text-center py-8">Loading orders...</div>;
  if (error) return <div className="text-red-600 text-center py-8">{error}</div>;
  
  return (
    <div className="px-4 py-6">
      <div className="flex justify-between items-center mb-6">
        <h1 className="text-2xl font-bold text-gray-900">My Orders</h1>
        {user?.role !== 'ADMIN' && (
          <a
            href="/orders/new"
            className="bg-blue-500 hover:bg-blue-600 text-white px-4 py-2 rounded-md transition"
          >
            Create New Order
          </a>
        )}
      </div>
      
      {orders.length === 0 ? (
        <div className="bg-white rounded-lg shadow p-8 text-center">
          <p className="text-gray-500">No orders found</p>
        </div>
      ) : (
        <div className="bg-white shadow overflow-hidden sm:rounded-md">
          <ul className="divide-y divide-gray-200">
            {orders.map(order => (
              <li key={order.id} className="px-6 py-4 hover:bg-gray-50">
                <div className="flex items-center justify-between">
                  <div className="flex-1">
                    <div className="flex items-center">
                      <p className="text-sm font-medium text-gray-900">
                        Order #{order.orderId || order.id}
                      </p>
                      <span className={`ml-2 px-2 py-1 text-xs rounded-full ${getStatusColor(order.status)}`}>
                        {order.status}
                      </span>
                    </div>
                    <p className="mt-1 text-sm text-gray-600">
                      Service: {order.service?.name || 'N/A'}
                    </p>
                    <p className="mt-1 text-sm text-gray-500">
                      Link: {order.link}
                    </p>
                    <div className="mt-2 flex items-center text-sm text-gray-500">
                      <span>Quantity: {order.quantity}</span>
                      <span className="mx-2">•</span>
                      <span>Charge: ${order.charge}</span>
                      {order.remains !== null && (
                        <>
                          <span className="mx-2">•</span>
                          <span>Remains: {order.remains}</span>
                        </>
                      )}
                    </div>
                  </div>
                  <div className="flex items-center space-x-2">
                    {order.status === 'PENDING' && (
                      <button
                        onClick={() => handleDelete(order.id)}
                        className="text-red-600 hover:text-red-900"
                      >
                        Delete
                      </button>
                    )}
                  </div>
                </div>
              </li>
            ))}
          </ul>
        </div>
      )}
    </div>
  );
};