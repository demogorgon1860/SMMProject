// frontend/src/components/AddBalance.tsx

import React, { useState, useEffect } from 'react';
import { CreateDepositRequest, DepositResponse, DepositStatus } from '../types';
import { apiService } from '../services/api';

interface AddBalanceProps {
  onBalanceAdded: () => void;
}

export const AddBalance: React.FC<AddBalanceProps> = ({ onBalanceAdded }) => {
  const [amount, setAmount] = useState('');
  const [currency, setCurrency] = useState<'BTC' | 'ETH' | 'USDT' | 'LTC' | 'USDC'>('USDT');
  const [loading, setLoading] = useState(false);
  const [paymentData, setPaymentData] = useState<DepositResponse | null>(null);
  const [paymentStatus, setPaymentStatus] = useState<DepositStatus | null>(null);
  const [error, setError] = useState('');
  const [statusCheckInterval, setStatusCheckInterval] = useState<NodeJS.Timeout | null>(null);

  const currencies = [
    { value: 'BTC', label: 'Bitcoin (BTC)', icon: '₿' },
    { value: 'ETH', label: 'Ethereum (ETH)', icon: 'Ξ' },
    { value: 'USDT', label: 'Tether (USDT)', icon: '₮' },
    { value: 'LTC', label: 'Litecoin (LTC)', icon: 'Ł' },
    { value: 'USDC', label: 'USD Coin (USDC)', icon: '$' }
  ] as const;

  useEffect(() => {
    return () => {
      if (statusCheckInterval) {
        clearInterval(statusCheckInterval);
      }
    };
  }, [statusCheckInterval]);

  const startStatusChecking = (orderId: string) => {
    const interval = setInterval(async () => {
      try {
        const status = await apiService.balance.getDepositStatus(orderId);
        setPaymentStatus(status);
        
        if (status.status === 'COMPLETED') {
          clearInterval(interval);
          setStatusCheckInterval(null);
          onBalanceAdded();
          setPaymentData(null);
          setAmount('');
          alert('✅ Payment completed successfully! Your balance has been updated.');
        } else if (status.status === 'FAILED' || status.status === 'EXPIRED') {
          clearInterval(interval);
          setStatusCheckInterval(null);
          setError(`Payment ${status.status.toLowerCase()}. Please try again.`);
          setPaymentData(null);
        }
      } catch (error) {
        console.error('Error checking payment status:', error);
      }
    }, 5000); // Проверяем каждые 5 секунд

    setStatusCheckInterval(interval);

    // Останавливаем проверку через 1 час
    setTimeout(() => {
      clearInterval(interval);
      setStatusCheckInterval(null);
    }, 3600000);
  };

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setError('');
    setLoading(true);

    const amountValue = parseFloat(amount);
    if (amountValue < 5) {
      setError('Minimum deposit amount is $5.00');
      setLoading(false);
      return;
    }

    try {
      const depositData: CreateDepositRequest = {
        amount: amountValue,
        currency
      };

      const response = await apiService.balance.createDeposit(depositData);
      setPaymentData(response);
      
      // Открываем платежную ссылку в новом окне
      window.open(response.paymentUrl, '_blank', 'width=800,height=600');
      
      // Начинаем проверку статуса
      startStatusChecking(response.orderId);
      
    } catch (error: any) {
      setError(error.response?.data?.error || error.message || 'Failed to create deposit');
      setPaymentData(null);
    } finally {
      setLoading(false);
    }
  };

  const handleCancelPayment = () => {
    if (statusCheckInterval) {
      clearInterval(statusCheckInterval);
      setStatusCheckInterval(null);
    }
    setPaymentData(null);
    setPaymentStatus(null);
    setError('');
  };

  const copyToClipboard = (text: string) => {
    navigator.clipboard.writeText(text);
    alert('Copied to clipboard!');
  };

  const formatExpiryTime = (expiresAt: string): string => {
    const expiry = new Date(expiresAt);
    const now = new Date();
    const diffMs = expiry.getTime() - now.getTime();
    
    if (diffMs <= 0) return 'Expired';
    
    const diffMins = Math.floor(diffMs / 60000);
    const hours = Math.floor(diffMins / 60);
    const minutes = diffMins % 60;
    
    return `${hours}h ${minutes}m`;
  };

  if (paymentData) {
    return (
      <div className="bg-white shadow rounded-lg p-6">
        <div className="flex justify-between items-start mb-4">
          <h2 className="text-lg font-medium text-gray-900">Payment Details</h2>
          <button
            onClick={handleCancelPayment}
            className="text-gray-400 hover:text-gray-600"
          >
            ✕
          </button>
        </div>

        <div className="space-y-4">
          <div className="bg-blue-50 border border-blue-200 p-4 rounded-lg">
            <div className="flex items-center mb-2">
              <div className="w-3 h-3 bg-blue-600 rounded-full animate-pulse mr-2"></div>
              <span className="font-medium text-blue-900">
                Waiting for payment...
              </span>
            </div>
            <p className="text-sm text-blue-700">
              Payment window opened. Complete the payment to add funds to your balance.
            </p>
          </div>

          <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
            <div>
              <label className="block text-sm font-medium text-gray-700 mb-1">
                Amount (USD)
              </label>
              <div className="text-lg font-bold text-gray-900">
                ${paymentData.amount.toFixed(2)}
              </div>
            </div>

            <div>
              <label className="block text-sm font-medium text-gray-700 mb-1">
                Currency
              </label>
              <div className="text-lg font-bold text-gray-900">
                {currencies.find(c => c.value === currency)?.icon} {currency}
              </div>
            </div>

            <div>
              <label className="block text-sm font-medium text-gray-700 mb-1">
                Crypto Amount
              </label>
              <div className="flex items-center">
                <span className="text-lg font-mono text-gray-900 mr-2">
                  {paymentData.cryptoAmount}
                </span>
                <button
                  onClick={() => copyToClipboard(paymentData.cryptoAmount)}
                  className="text-blue-600 hover:text-blue-800 text-sm"
                >
                  📋 Copy
                </button>
              </div>
            </div>

            <div>
              <label className="block text-sm font-medium text-gray-700 mb-1">
                Expires in
              </label>
              <div className="text-lg font-bold text-red-600">
                {formatExpiryTime(paymentData.expiresAt)}
              </div>
            </div>
          </div>

          <div>
            <label className="block text-sm font-medium text-gray-700 mb-1">
              Order ID
            </label>
            <div className="flex items-center">
              <span className="text-sm font-mono text-gray-900 mr-2">
                {paymentData.orderId}
              </span>
              <button
                onClick={() => copyToClipboard(paymentData.orderId)}
                className="text-blue-600 hover:text-blue-800 text-sm"
              >
                📋 Copy
              </button>
            </div>
          </div>

          {paymentStatus && (
            <div className="mt-4">
              <div className={`p-3 rounded-lg ${
                paymentStatus.status === 'PENDING' ? 'bg-yellow-50 border border-yellow-200' :
                paymentStatus.status === 'PROCESSING' ? 'bg-blue-50 border border-blue-200' :
                paymentStatus.status === 'COMPLETED' ? 'bg-green-50 border border-green-200' :
                'bg-red-50 border border-red-200'
              }`}>
                <div className="text-sm font-medium">
                  Status: {paymentStatus.status}
                </div>
                {paymentStatus.confirmedAt && (
                  <div className="text-xs text-gray-600 mt-1">
                    Confirmed: {new Date(paymentStatus.confirmedAt).toLocaleString()}
                  </div>
                )}
              </div>
            </div>
          )}

          <div className="flex space-x-3">
            <button
              onClick={() => window.open(paymentData.paymentUrl, '_blank')}
              className="flex-1 bg-blue-600 text-white px-4 py-2 rounded-md hover:bg-blue-700 transition-colors"
            >
              🔗 Open Payment Page
            </button>
            <button
              onClick={handleCancelPayment}
              className="px-4 py-2 border border-gray-300 text-gray-700 rounded-md hover:bg-gray-50 transition-colors"
            >
              Cancel
            </button>
          </div>
        </div>
      </div>
    );
  }

  return (
    <div className="bg-white shadow rounded-lg p-6">
      <h2 className="text-lg font-medium text-gray-900 mb-4">Add Balance</h2>
      
      <form onSubmit={handleSubmit} className="space-y-4">
        <div>
          <label className="block text-sm font-medium text-gray-700 mb-1">
            Amount (USD)
          </label>
          <input
            type="number"
            value={amount}
            onChange={(e) => setAmount(e.target.value)}
            min="5"
            step="0.01"
            required
            placeholder="Minimum $5.00"
            className="block w-full px-3 py-2 border border-gray-300 rounded-md shadow-sm focus:outline-none focus:ring-blue-500 focus:border-blue-500"
          />
          <p className="mt-1 text-xs text-gray-500">
            Minimum deposit: $5.00 • No maximum limit
          </p>
        </div>

        <div>
          <label className="block text-sm font-medium text-gray-700 mb-1">
            Payment Currency
          </label>
          <div className="grid grid-cols-2 md:grid-cols-3 gap-2">
            {currencies.map((curr) => (
              <label
                key={curr.value}
                className={`flex items-center p-3 border rounded-lg cursor-pointer transition-colors ${
                  currency === curr.value
                    ? 'border-blue-500 bg-blue-50 text-blue-700'
                    : 'border-gray-300 hover:border-gray-400'
                }`}
              >
                <input
                  type="radio"
                  name="currency"
                  value={curr.value}
                  checked={currency === curr.value}
                  onChange={(e) => setCurrency(e.target.value as any)}
                  className="sr-only"
                />
                <span className="text-lg mr-2">{curr.icon}</span>
                <span className="text-sm font-medium">{curr.value}</span>
              </label>
            ))}
          </div>
        </div>

        <div className="bg-gray-50 p-4 rounded-lg">
          <div className="text-sm text-gray-600 space-y-1">
            <p>• Payment is processed securely through Cryptomus</p>
            <p>• Funds are typically confirmed within 10-30 minutes</p>
            <p>• You will receive email confirmation when payment is complete</p>
            <p>• Payment link expires in 1 hour</p>
          </div>
        </div>

        {error && (
          <div className="bg-red-50 border border-red-200 text-red-600 px-4 py-3 rounded">
            {error}
          </div>
        )}

        <button
          type="submit"
          disabled={loading || !amount || parseFloat(amount) < 5}
          className="w-full flex justify-center py-3 px-4 border border-transparent rounded-md shadow-sm text-sm font-medium text-white bg-blue-600 hover:bg-blue-700 focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-blue-500 disabled:opacity-50 disabled:cursor-not-allowed"
        >
          {loading ? (
            <div className="flex items-center">
              <div className="animate-spin rounded-full h-4 w-4 border-b-2 border-white mr-2"></div>
              Creating Payment...
            </div>
          ) : (
            <>
              🚀 Continue to Payment
              {amount && parseFloat(amount) >= 5 && (
                <span className="ml-2 font-bold">${parseFloat(amount).toFixed(2)}</span>
              )}
            </>
          )}
        </button>
      </form>
    </div>
  );
};