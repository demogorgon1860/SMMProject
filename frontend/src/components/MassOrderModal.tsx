import React, { useState } from 'react';
import { orderAPI } from '../services/api';

interface MassOrderModalProps {
  isOpen: boolean;
  onClose: () => void;
  onSuccess: () => void;
}

interface MassOrderResult {
  totalOrders: number;
  successfulOrders: number;
  failedOrders: number;
  successful: Array<{
    orderId: number;
    serviceId: number;
    link: string;
    quantity: number;
    cost: number;
    lineNumber: number;
  }>;
  failed: Array<{
    serviceId?: number;
    link?: string;
    quantity?: number;
    lineNumber: number;
    originalLine: string;
    errorMessage: string;
  }>;
  parseErrors: Array<{
    lineNumber: number;
    originalLine: string;
    errorMessage: string;
  }>;
  totalCost: number;
}

export const MassOrderModal: React.FC<MassOrderModalProps> = ({
  isOpen,
  onClose,
  onSuccess,
}) => {
  const [ordersText, setOrdersText] = useState('');
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [previewData, setPreviewData] = useState<MassOrderResult | null>(null);
  const [showPreview, setShowPreview] = useState(false);

  if (!isOpen) return null;

  const handlePreview = async () => {
    setError(null);
    setPreviewData(null);

    if (!ordersText.trim()) {
      setError('Please enter at least one order');
      return;
    }

    setLoading(true);
    try {
      const response = await orderAPI.previewMassOrder({
        ordersText,
        delimiter: '|',
        maxOrders: 100,
      });

      setPreviewData(response.data);
      setShowPreview(true);
    } catch (error: any) {
      setError(error.response?.data?.message || 'Failed to preview orders');
    } finally {
      setLoading(false);
    }
  };

  const handleSubmit = async () => {
    setError(null);

    if (!ordersText.trim()) {
      setError('Please enter at least one order');
      return;
    }

    setLoading(true);
    try {
      const response = await orderAPI.createMassOrder({
        ordersText,
        delimiter: '|',
        maxOrders: 100,
      });

      const result = response.data;

      // Show success message with results
      if (result.successfulOrders > 0) {
        alert(
          `Successfully created ${result.successfulOrders} order(s)!\n` +
          `Failed: ${result.failedOrders}\n` +
          `Total Cost: $${result.totalCost.toFixed(2)}`
        );
        onSuccess();
        handleClose();
      } else {
        setError('All orders failed. Please check the format and try again.');
        setPreviewData(result);
        setShowPreview(true);
      }
    } catch (error: any) {
      setError(error.response?.data?.message || 'Failed to create orders');
    } finally {
      setLoading(false);
    }
  };

  const handleClose = () => {
    setOrdersText('');
    setError(null);
    setPreviewData(null);
    setShowPreview(false);
    onClose();
  };

  const exampleText = `1001 | https://youtube.com/watch?v=example1 | 1000
1002 | https://instagram.com/p/example2 | 500
1003 | https://twitter.com/user/status/example3 | 2000`;

  return (
    <div className="fixed inset-0 z-50 overflow-y-auto">
      <div className="flex items-center justify-center min-h-screen px-4">
        {/* Overlay */}
        <div
          className="fixed inset-0 bg-black bg-opacity-50 transition-opacity"
          onClick={handleClose}
        ></div>

        {/* Modal */}
        <div className="relative bg-white rounded-lg max-w-4xl w-full max-h-[90vh] overflow-y-auto">
          <div className="p-6">
            <div className="flex justify-between items-center mb-4">
              <h2 className="text-2xl font-bold text-gray-900">Mass Order</h2>
              <button
                onClick={handleClose}
                className="text-gray-400 hover:text-gray-600"
              >
                <svg className="w-6 h-6" fill="none" stroke="currentColor">
                  <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M6 18L18 6M6 6l12 12" />
                </svg>
              </button>
            </div>

            {/* Instructions */}
            <div className="mb-4 p-4 bg-purple-50 border border-purple-200 rounded-lg">
              <p className="text-sm font-medium text-purple-800 mb-2">
                One order per line in format:
              </p>
              <p className="text-sm text-purple-700 font-mono">
                service_id | link | quantity
              </p>
              <p className="text-xs text-purple-600 mt-2">
                Maximum 100 orders per submission
              </p>
            </div>

            {/* Error message */}
            {error && (
              <div className="mb-4 p-3 bg-red-50 border border-red-200 text-red-700 rounded">
                {error}
              </div>
            )}

            {/* Text area */}
            <div className="mb-4">
              <textarea
                value={ordersText}
                onChange={(e) => setOrdersText(e.target.value)}
                placeholder={exampleText}
                className="w-full h-64 px-3 py-2 border border-gray-300 rounded-md focus:outline-none focus:ring-2 focus:ring-purple-500 font-mono text-sm"
                disabled={loading}
              />
            </div>

            {/* Preview section */}
            {showPreview && previewData && (
              <div className="mb-4 p-4 bg-gray-50 rounded-lg max-h-64 overflow-y-auto">
                <h3 className="font-semibold mb-2">Preview Results</h3>

                {/* Summary */}
                <div className="mb-3 text-sm">
                  <p>Total Orders: {previewData.totalOrders}</p>
                  <p className="text-green-600">Valid: {previewData.successfulOrders}</p>
                  <p className="text-red-600">Errors: {previewData.failedOrders}</p>
                  <p className="font-semibold">Total Cost: ${previewData.totalCost.toFixed(2)}</p>
                </div>

                {/* Parse errors */}
                {previewData.parseErrors.length > 0 && (
                  <div className="mb-3">
                    <h4 className="font-medium text-red-700 text-sm mb-1">Parse Errors:</h4>
                    {previewData.parseErrors.map((error, idx) => (
                      <div key={idx} className="text-xs text-red-600 mb-1">
                        Line {error.lineNumber}: {error.errorMessage}
                      </div>
                    ))}
                  </div>
                )}

                {/* Valid orders */}
                {previewData.successful.length > 0 && (
                  <div>
                    <h4 className="font-medium text-green-700 text-sm mb-1">Valid Orders:</h4>
                    {previewData.successful.map((order, idx) => (
                      <div key={idx} className="text-xs text-green-600 mb-1">
                        Line {order.lineNumber}: Service {order.serviceId}, {order.quantity} units - ${order.cost.toFixed(2)}
                      </div>
                    ))}
                  </div>
                )}
              </div>
            )}

            {/* Buttons */}
            <div className="flex gap-3">
              <button
                onClick={handlePreview}
                disabled={loading || !ordersText.trim()}
                className="flex-1 bg-purple-100 hover:bg-purple-200 text-purple-700 py-2 px-4 rounded-md transition disabled:opacity-50"
              >
                {loading && !showPreview ? 'Validating...' : 'Preview'}
              </button>
              <button
                onClick={handleSubmit}
                disabled={loading || !ordersText.trim()}
                className="flex-1 bg-purple-600 hover:bg-purple-700 text-white py-2 px-4 rounded-md transition disabled:opacity-50"
              >
                {loading && showPreview ? 'Creating Orders...' : 'Submit'}
              </button>
              <button
                onClick={handleClose}
                disabled={loading}
                className="flex-1 bg-gray-300 hover:bg-gray-400 text-gray-800 py-2 px-4 rounded-md transition disabled:opacity-50"
              >
                Cancel
              </button>
            </div>
          </div>
        </div>
      </div>
    </div>
  );
};