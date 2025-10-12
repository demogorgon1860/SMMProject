import React, { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { orderAPI, serviceAPI } from '../services/api';
import { Service } from '../types';
import { useAuthStore } from '../store/authStore';
import { MassOrderModal } from '../components/MassOrderModal';

export const NewOrder: React.FC = () => {
  const navigate = useNavigate();
  const { user } = useAuthStore();
  const [services, setServices] = useState<Service[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [showMassOrderModal, setShowMassOrderModal] = useState(false);
  const [orderType, setOrderType] = useState<'single' | 'mass'>('single');

  const [formData, setFormData] = useState({
    service: '',
    link: '',
    quantity: '',
  });

  const [selectedService, setSelectedService] = useState<Service | null>(null);

  useEffect(() => {
    // Redirect admin users to admin orders page
    if (user?.role === 'ADMIN') {
      navigate('/admin/orders');
      return;
    }
    fetchServices();
  }, [user, navigate]);
  
  const fetchServices = async () => {
    try {
      const response = await serviceAPI.getServices();
      console.log('Services response:', response); // Debug log
      // Handle PerfectPanelResponse structure
      const servicesList = response?.data || response || [];
      setServices(Array.isArray(servicesList) ? servicesList : []);
    } catch (error) {
      console.error('Failed to fetch services:', error);
      setServices([]);
    }
  };
  
  const handleServiceChange = (e: React.ChangeEvent<HTMLSelectElement>) => {
    const serviceId = e.target.value;
    setFormData(prev => ({ ...prev, service: serviceId }));
    
    const service = services.find(s => s.id.toString() === serviceId);
    setSelectedService(service || null);
  };
  
  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setError(null);
    setLoading(true);
    
    try {
      await orderAPI.createOrder({
        service: parseInt(formData.service),
        link: formData.link,
        quantity: parseInt(formData.quantity),
      });
      navigate('/orders');
    } catch (error: any) {
      setError(error.response?.data?.message || 'Failed to create order');
    } finally {
      setLoading(false);
    }
  };
  
  const calculateCharge = () => {
    if (!selectedService || !formData.quantity) return '0.00';
    const price = selectedService.pricePer1000 || selectedService.rate || 0;
    const charge = (price * parseInt(formData.quantity)) / 1000;
    return charge.toFixed(2);
  };
  
  return (
    <div className="px-4 py-6">
      <div className="max-w-2xl mx-auto">
        <h1 className="text-2xl font-bold text-gray-900 mb-6">Create New Order</h1>

        {/* Order Type Selection */}
        <div className="bg-white shadow rounded-lg p-6 mb-6">
          <h2 className="text-lg font-semibold mb-4">Choose Order Type</h2>
          <div className="grid grid-cols-2 gap-4">
            <button
              type="button"
              onClick={() => setOrderType('single')}
              className={`p-4 border-2 rounded-lg transition ${
                orderType === 'single'
                  ? 'border-blue-500 bg-blue-50'
                  : 'border-gray-300 hover:border-gray-400'
              }`}
            >
              <div className="font-semibold mb-1">New Order</div>
              <div className="text-sm text-gray-600">Create a single order</div>
            </button>
            <button
              type="button"
              onClick={() => {
                setOrderType('mass');
                setShowMassOrderModal(true);
              }}
              className={`p-4 border-2 rounded-lg transition ${
                orderType === 'mass'
                  ? 'border-purple-500 bg-purple-50'
                  : 'border-gray-300 hover:border-gray-400'
              }`}
            >
              <div className="font-semibold mb-1">Mass Order</div>
              <div className="text-sm text-gray-600">Create up to 100 orders at once</div>
            </button>
          </div>
        </div>

        {/* Regular Order Form */}
        {orderType === 'single' && (
        <form onSubmit={handleSubmit} className="bg-white shadow rounded-lg p-6">
          {error && (
            <div className="mb-4 p-3 bg-red-50 border border-red-200 text-red-700 rounded">
              {error}
            </div>
          )}
          
          <div className="mb-4">
            <label className="block text-sm font-medium text-gray-700 mb-2">
              Service
            </label>
            <select
              value={formData.service}
              onChange={handleServiceChange}
              required
              className="w-full px-3 py-2 border border-gray-300 rounded-md focus:outline-none focus:ring-2 focus:ring-blue-500"
            >
              <option value="">Select a service</option>
              {services.map(service => (
                <option key={service.id} value={service.id}>
                  {service.name} - ${service.pricePer1000 || service.rate || '0'}/1000
                </option>
              ))}
            </select>
            {selectedService && (
              <div className="mt-2 text-sm text-gray-600">
                <p>Min: {selectedService.minOrder || selectedService.min || 1} | Max: {selectedService.maxOrder || selectedService.max || 100000}</p>
                {selectedService.description && (
                  <p className="mt-1">{selectedService.description}</p>
                )}
              </div>
            )}
          </div>
          
          <div className="mb-4">
            <label className="block text-sm font-medium text-gray-700 mb-2">
              Link
            </label>
            <input
              type="url"
              value={formData.link}
              onChange={(e) => setFormData(prev => ({ ...prev, link: e.target.value }))}
              required
              placeholder="https://youtube.com/watch?v=..."
              className="w-full px-3 py-2 border border-gray-300 rounded-md focus:outline-none focus:ring-2 focus:ring-blue-500"
            />
          </div>
          
          <div className="mb-4">
            <label className="block text-sm font-medium text-gray-700 mb-2">
              Quantity
            </label>
            <input
              type="number"
              value={formData.quantity}
              onChange={(e) => setFormData(prev => ({ ...prev, quantity: e.target.value }))}
              required
              min={selectedService?.minOrder || selectedService?.min || 1}
              max={selectedService?.maxOrder || selectedService?.max || 100000}
              className="w-full px-3 py-2 border border-gray-300 rounded-md focus:outline-none focus:ring-2 focus:ring-blue-500"
            />
          </div>
          
          <div className="mb-6 p-4 bg-gray-50 rounded">
            <div className="flex justify-between items-center">
              <span className="text-sm font-medium text-gray-700">Total Charge:</span>
              <span className="text-lg font-bold text-gray-900">${calculateCharge()}</span>
            </div>
          </div>
          
          <div className="flex gap-4">
            <button
              type="submit"
              disabled={loading}
              className="flex-1 bg-blue-500 hover:bg-blue-600 text-white py-2 px-4 rounded-md transition disabled:opacity-50"
            >
              {loading ? 'Creating...' : 'Create Order'}
            </button>
            <button
              type="button"
              onClick={() => navigate('/orders')}
              className="flex-1 bg-gray-300 hover:bg-gray-400 text-gray-800 py-2 px-4 rounded-md transition"
            >
              Cancel
            </button>
          </div>
        </form>
        )}

        {/* Mass Order Modal */}
        <MassOrderModal
          isOpen={showMassOrderModal}
          onClose={() => {
            setShowMassOrderModal(false);
            setOrderType('single');
          }}
          onSuccess={() => {
            setShowMassOrderModal(false);
            navigate('/orders');
          }}
        />
      </div>
    </div>
  );
};