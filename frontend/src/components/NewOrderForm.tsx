import React, { useState } from 'react';
import { Service } from '../types';
import { api } from '../services/api';

interface NewOrderFormProps {
  services: Service[];
  onOrderCreated: () => void;
}

export const NewOrderForm: React.FC<NewOrderFormProps> = ({ services, onOrderCreated }) => {
  const [selectedService, setSelectedService] = useState<number | null>(null);
  const [link, setLink] = useState('');
  const [quantity, setQuantity] = useState('');
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const [success, setSuccess] = useState(false);

  const service = services.find(s => s.id === selectedService);
  const charge = service && quantity ? 
    ((parseFloat(service.pricePer1000) * parseInt(quantity)) / 1000).toFixed(2) : '0.00';

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setError('');
    setSuccess(false);
    setLoading(true);

    try {
      await api.createOrder({
        service: selectedService!,
        link,
        quantity: parseInt(quantity)
      });
      
      setSuccess(true);
      setLink('');
      setQuantity('');
      onOrderCreated();
      
      setTimeout(() => setSuccess(false), 3000);
    } catch (err: any) {
      setError(err.response?.data?.error || 'Failed to create order');
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="bg-white shadow rounded-lg p-6">
      <h2 className="text-lg font-medium text-gray-900 mb-4">New Order</h2>
      
      <form onSubmit={handleSubmit} className="space-y-4">
        <div>
          <label className="block text-sm font-medium text-gray-700">Service</label>
          <select
            value={selectedService || ''}
            onChange={(e) => setSelectedService(parseInt(e.target.value))}
            required
            className="mt-1 block w-full px-3 py-2 border border-gray-300 rounded-md shadow-sm focus:outline-none focus:ring-blue-500 focus:border-blue-500"
          >
            <option value="">Select a service</option>
            {services.map(service => (
              <option key={service.id} value={service.id}>
                {service.name} - ${service.pricePer1000}/1000
              </option>
            ))}
          </select>
        </div>

        {service && (
          <div className="text-sm text-gray-600">
            Min: {service.minOrder} - Max: {service.maxOrder}
          </div>
        )}

        <div>
          <label className="block text-sm font-medium text-gray-700">Link</label>
          <input
            type="url"
            value={link}
            onChange={(e) => setLink(e.target.value)}
            placeholder="https://youtube.com/watch?v=..."
            required
            className="mt-1 block w-full px-3 py-2 border border-gray-300 rounded-md shadow-sm focus:outline-none focus:ring-blue-500 focus:border-blue-500"
          />
        </div>

        <div>
          <label className="block text-sm font-medium text-gray-700">Quantity</label>
          <input
            type="number"
            value={quantity}
            onChange={(e) => setQuantity(e.target.value)}
            min={service?.minOrder || 100}
            max={service?.maxOrder || 1000000}
            required
            className="mt-1 block w-full px-3 py-2 border border-gray-300 rounded-md shadow-sm focus:outline-none focus:ring-blue-500 focus:border-blue-500"
          />
        </div>

        <div className="bg-gray-50 p-4 rounded-md">
          <div className="flex justify-between">
            <span className="text-sm font-medium text-gray-700">Charge:</span>
            <span className="text-sm font-bold text-gray-900">${charge}</span>
          </div>
        </div>

        {error && (
          <div className="bg-red-50 border border-red-200 text-red-600 px-4 py-3 rounded">
            {error}
          </div>
        )}

        {success && (
          <div className="bg-green-50 border border-green-200 text-green-600 px-4 py-3 rounded">
            Order created successfully!
          </div>
        )}

        <button
          type="submit"
          disabled={loading}
          className="w-full flex justify-center py-2 px-4 border border-transparent rounded-md shadow-sm text-sm font-medium text-white bg-blue-600 hover:bg-blue-700 focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-blue-500 disabled:opacity-50"
        >
          {loading ? 'Creating...' : 'Create Order'}
        </button>
      </form>
    </div>
  );
};
