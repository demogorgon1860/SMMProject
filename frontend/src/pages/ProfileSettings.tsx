import React, { useEffect, useState } from 'react';
import { apiKeyAPI } from '../services/api';

interface ApiKeyStatus {
  hasApiKey: boolean;
  maskedKey?: string;
  lastRotated?: string;
  isActive?: boolean;
}

export const ProfileSettings: React.FC = () => {
  const [activeSection, setActiveSection] = useState<'api-keys' | 'api-docs'>('api-keys');
  const [apiKeyStatus, setApiKeyStatus] = useState<ApiKeyStatus | null>(null);
  const [newApiKey, setNewApiKey] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [actionLoading, setActionLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [successMessage, setSuccessMessage] = useState<string | null>(null);
  const [copiedField, setCopiedField] = useState<string | null>(null);

  useEffect(() => {
    fetchApiKeyStatus();
  }, []);

  const fetchApiKeyStatus = async () => {
    try {
      setLoading(true);
      const status = await apiKeyAPI.getStatus();
      setApiKeyStatus(status);
    } catch (err: any) {
      setError(err.response?.data?.message || 'Failed to fetch API key status');
    } finally {
      setLoading(false);
    }
  };

  const handleGenerateApiKey = async () => {
    try {
      setActionLoading(true);
      setError(null);
      setSuccessMessage(null);
      const response = await apiKeyAPI.generate();
      setNewApiKey(response.apiKey);
      setSuccessMessage('API key generated successfully! Make sure to copy it now - you won\'t see it again.');
      await fetchApiKeyStatus();
    } catch (err: any) {
      setError(err.response?.data?.message || 'Failed to generate API key');
    } finally {
      setActionLoading(false);
    }
  };

  const handleRotateApiKey = async () => {
    if (!confirm('Are you sure you want to rotate your API key? The old key will be immediately deactivated.')) {
      return;
    }
    try {
      setActionLoading(true);
      setError(null);
      setSuccessMessage(null);
      const response = await apiKeyAPI.rotate();
      setNewApiKey(response.apiKey);
      setSuccessMessage('API key rotated successfully! Old key has been deactivated.');
      await fetchApiKeyStatus();
    } catch (err: any) {
      setError(err.response?.data?.message || 'Failed to rotate API key');
    } finally {
      setActionLoading(false);
    }
  };

  const copyToClipboard = (text: string, field: string) => {
    navigator.clipboard.writeText(text);
    setCopiedField(field);
    setTimeout(() => setCopiedField(null), 2000);
  };

  const baseUrl = window.location.origin;

  return (
    <div className="flex h-full bg-gray-50">
      {/* Sidebar Navigation */}
      <div className="w-64 bg-white shadow-sm border-r border-gray-200">
        <div className="p-6">
          <h1 className="text-xl font-bold text-gray-800 mb-6">Profile Settings</h1>
          <nav className="space-y-2">
            <button
              onClick={() => setActiveSection('api-keys')}
              className={`w-full text-left px-4 py-2.5 rounded-md transition-colors flex items-center ${
                activeSection === 'api-keys'
                  ? 'bg-blue-50 text-blue-600 font-medium'
                  : 'text-gray-700 hover:bg-gray-50'
              }`}
            >
              <svg
                className="w-5 h-5 mr-3"
                fill="none"
                stroke="currentColor"
                viewBox="0 0 24 24"
              >
                <path
                  strokeLinecap="round"
                  strokeLinejoin="round"
                  strokeWidth={2}
                  d="M15 7a2 2 0 012 2m4 0a6 6 0 01-7.743 5.743L11 17H9v2H7v2H4a1 1 0 01-1-1v-2.586a1 1 0 01.293-.707l5.964-5.964A6 6 0 1121 9z"
                />
              </svg>
              API Keys
            </button>
            <button
              onClick={() => setActiveSection('api-docs')}
              className={`w-full text-left px-4 py-2.5 rounded-md transition-colors flex items-center ${
                activeSection === 'api-docs'
                  ? 'bg-blue-50 text-blue-600 font-medium'
                  : 'text-gray-700 hover:bg-gray-50'
              }`}
            >
              <svg
                className="w-5 h-5 mr-3"
                fill="none"
                stroke="currentColor"
                viewBox="0 0 24 24"
              >
                <path
                  strokeLinecap="round"
                  strokeLinejoin="round"
                  strokeWidth={2}
                  d="M9 12h6m-6 4h6m2 5H7a2 2 0 01-2-2V5a2 2 0 012-2h5.586a1 1 0 01.707.293l5.414 5.414a1 1 0 01.293.707V19a2 2 0 01-2 2z"
                />
              </svg>
              API Documentation
            </button>
          </nav>
        </div>
      </div>

      {/* Main Content Area */}
      <div className="flex-1 overflow-y-auto">
        <div className="p-8">
          {/* Error/Success Messages */}
          {error && (
            <div className="mb-6 bg-red-50 border border-red-200 text-red-700 px-4 py-3 rounded-lg">
              {error}
            </div>
          )}
          {successMessage && (
            <div className="mb-6 bg-green-50 border border-green-200 text-green-700 px-4 py-3 rounded-lg">
              {successMessage}
            </div>
          )}

          {/* API Keys Section */}
          {activeSection === 'api-keys' && (
            <div className="bg-white rounded-lg shadow">
              <div className="border-b border-gray-200 px-6 py-4">
                <h2 className="text-xl font-semibold text-gray-900">API Key Generation</h2>
                <p className="text-sm text-gray-600 mt-1">
                  Generate and manage your API key for external integrations
                </p>
              </div>

              <div className="p-6">
                {loading ? (
                  <div className="text-center py-8">
                    <div className="text-gray-500">Loading...</div>
                  </div>
                ) : (
                  <div className="space-y-6">
                    {/* Current API Key Status */}
                    <div>
                      <label className="block text-sm font-medium text-gray-700 mb-2">
                        API Key Status
                      </label>
                      <div className="flex items-center space-x-2">
                        <div
                          className={`h-3 w-3 rounded-full ${
                            apiKeyStatus?.isActive ? 'bg-green-500' :
                            apiKeyStatus?.hasApiKey ? 'bg-yellow-500' : 'bg-red-500'
                          }`}
                        />
                        <span className="text-sm text-gray-600">
                          {apiKeyStatus?.isActive ? 'Active' :
                           apiKeyStatus?.hasApiKey ? 'Inactive' : 'No API key generated'}
                        </span>
                      </div>
                    </div>

                    {/* Display existing API key preview */}
                    {apiKeyStatus?.hasApiKey && apiKeyStatus.maskedKey && (
                      <div>
                        <label className="block text-sm font-medium text-gray-700 mb-2">
                          Current API Key Preview
                        </label>
                        <div className="flex items-center space-x-2">
                          <input
                            type="text"
                            value={apiKeyStatus.maskedKey}
                            readOnly
                            className="flex-1 px-3 py-2 border border-gray-300 rounded bg-gray-50 text-gray-600 font-mono text-sm"
                          />
                        </div>
                        <p className="text-xs text-gray-500 mt-1">
                          For security, only a preview is shown
                        </p>
                      </div>
                    )}

                    {/* Display newly generated API key */}
                    {newApiKey && (
                      <div className="bg-yellow-50 border border-yellow-200 rounded-lg p-4">
                        <label className="block text-sm font-medium text-yellow-900 mb-2">
                          Your New API Key (Copy it now!)
                        </label>
                        <div className="flex items-center space-x-2">
                          <input
                            type="text"
                            value={newApiKey}
                            readOnly
                            className="flex-1 px-3 py-2 border border-yellow-300 rounded bg-white font-mono text-sm"
                          />
                          <button
                            onClick={() => copyToClipboard(newApiKey, 'apiKey')}
                            className="px-4 py-2 bg-yellow-600 text-white rounded hover:bg-yellow-700 transition text-sm"
                          >
                            {copiedField === 'apiKey' ? 'Copied!' : 'Copy'}
                          </button>
                        </div>
                        <p className="text-xs text-yellow-800 mt-2">
                          Save this key securely. You won't be able to see it again!
                        </p>
                      </div>
                    )}

                    {/* Action Buttons */}
                    <div className="space-y-3">
                      {!apiKeyStatus?.hasApiKey ? (
                        <button
                          onClick={handleGenerateApiKey}
                          disabled={actionLoading}
                          className="w-full bg-blue-500 hover:bg-blue-600 text-white px-4 py-3 rounded font-medium transition disabled:opacity-50 disabled:cursor-not-allowed"
                        >
                          {actionLoading ? 'Generating...' : 'Generate API Key'}
                        </button>
                      ) : (
                        <button
                          onClick={handleRotateApiKey}
                          disabled={actionLoading}
                          className="w-full bg-green-500 hover:bg-green-600 text-white px-4 py-3 rounded font-medium transition disabled:opacity-50 disabled:cursor-not-allowed"
                        >
                          {actionLoading ? 'Rotating...' : 'Rotate API Key'}
                        </button>
                      )}
                    </div>

                    {/* Info Box */}
                    <div className="bg-blue-50 border border-blue-200 rounded-lg p-4">
                      <h3 className="text-sm font-medium text-blue-900 mb-2">API Key Information</h3>
                      <ul className="text-xs text-blue-800 space-y-1">
                        <li>• Use API keys to authenticate external scripts and applications</li>
                        <li>• Rotating a key deactivates the old key and creates a new active one</li>
                        <li>• Never share your API key publicly or commit it to version control</li>
                        <li>• Only active API keys can be used for authentication</li>
                      </ul>
                    </div>
                  </div>
                )}
              </div>
            </div>
          )}

          {/* API Documentation Section */}
          {activeSection === 'api-docs' && (
            <div className="bg-white rounded-lg shadow">
              <div className="border-b border-gray-200 px-6 py-4">
                <h2 className="text-xl font-semibold text-gray-900">API Documentation</h2>
                <p className="text-sm text-gray-600 mt-1">
                  Complete guide to integrate with our API
                </p>
              </div>

              <div className="p-6 space-y-6">
                {/* Base URL */}
                <div>
                  <h3 className="text-sm font-semibold text-gray-900 mb-2">Base URL</h3>
                  <div className="flex items-center space-x-2">
                    <code className="flex-1 bg-gray-100 px-3 py-2 rounded text-sm font-mono">
                      {baseUrl}/api/v2
                    </code>
                    <button
                      onClick={() => copyToClipboard(`${baseUrl}/api/v2`, 'baseUrl')}
                      className="px-3 py-2 bg-gray-200 hover:bg-gray-300 rounded text-sm transition"
                    >
                      {copiedField === 'baseUrl' ? 'Copied!' : 'Copy'}
                    </button>
                  </div>
                </div>

                {/* Authentication */}
                <div>
                  <h3 className="text-sm font-semibold text-gray-900 mb-2">Authentication</h3>
                  <p className="text-sm text-gray-600 mb-2">
                    Include your API key in all requests using the <code className="bg-gray-100 px-1 rounded">key</code> parameter:
                  </p>
                  <code className="block bg-gray-100 px-3 py-2 rounded text-xs font-mono">
                    POST {baseUrl}/api/v2?key=YOUR_API_KEY&action=...
                  </code>
                </div>

                {/* Endpoints */}
                <div className="space-y-4">
                  <h3 className="text-sm font-semibold text-gray-900">Available Endpoints</h3>

                  {/* Create Order */}
                  <div className="border border-gray-200 rounded-lg p-4">
                    <h4 className="font-medium text-gray-900 mb-2">1. Create Order</h4>
                    <div className="space-y-2 text-sm">
                      <div>
                        <span className="text-gray-600">Action:</span>
                        <code className="ml-2 bg-gray-100 px-2 py-1 rounded">add</code>
                      </div>
                      <div>
                        <span className="text-gray-600">Parameters:</span>
                        <ul className="ml-4 mt-1 text-xs space-y-1">
                          <li>• <code className="bg-gray-100 px-1 rounded">service</code> - Service ID (integer)</li>
                          <li>• <code className="bg-gray-100 px-1 rounded">link</code> - YouTube video URL</li>
                          <li>• <code className="bg-gray-100 px-1 rounded">quantity</code> - Number of views (integer)</li>
                        </ul>
                      </div>
                      <div>
                        <span className="text-gray-600">Example:</span>
                        <code className="block bg-gray-900 text-green-400 px-3 py-2 rounded mt-1 text-xs overflow-x-auto">
                          curl -X POST "{baseUrl}/api/v2?key=YOUR_KEY&action=add&service=1&link=https://youtube.com/watch?v=VIDEO_ID&quantity=1000"
                        </code>
                      </div>
                      <div>
                        <span className="text-gray-600">Response:</span>
                        <pre className="bg-gray-900 text-green-400 px-3 py-2 rounded mt-1 text-xs overflow-x-auto">
{`{
  "status": "Success",
  "order": 12345,
  "charge": "4.00",
  "start_count": 1523,
  "created_at": "2025-11-06T10:30:45",
  "remaining_balance": "95.50",
  "currency": "USD"
}`}
                        </pre>
                      </div>
                    </div>
                  </div>

                  {/* Check Order Status */}
                  <div className="border border-gray-200 rounded-lg p-4">
                    <h4 className="font-medium text-gray-900 mb-2">2. Check Order Status</h4>
                    <div className="space-y-2 text-sm">
                      <div>
                        <span className="text-gray-600">Action:</span>
                        <code className="ml-2 bg-gray-100 px-2 py-1 rounded">status</code>
                      </div>
                      <div>
                        <span className="text-gray-600">Parameters:</span>
                        <ul className="ml-4 mt-1 text-xs space-y-1">
                          <li>• <code className="bg-gray-100 px-1 rounded">order</code> - Order ID (integer)</li>
                        </ul>
                      </div>
                      <div>
                        <span className="text-gray-600">Example:</span>
                        <code className="block bg-gray-900 text-green-400 px-3 py-2 rounded mt-1 text-xs overflow-x-auto">
                          curl -X POST "{baseUrl}/api/v2?key=YOUR_KEY&action=status&order=12345"
                        </code>
                      </div>
                      <div>
                        <span className="text-gray-600">Response:</span>
                        <pre className="bg-gray-900 text-green-400 px-3 py-2 rounded mt-1 text-xs overflow-x-auto">
{`{
  "charge": "4.00",
  "start_count": 1523,
  "status": "In progress",
  "remains": 450,
  "currency": "USDT"
}`}
                        </pre>
                      </div>
                    </div>
                  </div>

                  {/* Get Services */}
                  <div className="border border-gray-200 rounded-lg p-4">
                    <h4 className="font-medium text-gray-900 mb-2">3. Get Available Services</h4>
                    <div className="space-y-2 text-sm">
                      <div>
                        <span className="text-gray-600">Action:</span>
                        <code className="ml-2 bg-gray-100 px-2 py-1 rounded">services</code>
                      </div>
                      <div>
                        <span className="text-gray-600">Example:</span>
                        <code className="block bg-gray-900 text-green-400 px-3 py-2 rounded mt-1 text-xs overflow-x-auto">
                          curl -X POST "{baseUrl}/api/v2?key=YOUR_KEY&action=services"
                        </code>
                      </div>
                      <div>
                        <span className="text-gray-600">Response:</span>
                        <pre className="bg-gray-900 text-green-400 px-3 py-2 rounded mt-1 text-xs overflow-x-auto">
{`[
  {
    "service": 1,
    "name": "YouTube Views - USA",
    "category": "YouTube",
    "rate": "4.00",
    "min": 100,
    "max": 100000
  }
]`}
                        </pre>
                      </div>
                    </div>
                  </div>

                  {/* Check Balance */}
                  <div className="border border-gray-200 rounded-lg p-4">
                    <h4 className="font-medium text-gray-900 mb-2">4. Check Balance</h4>
                    <div className="space-y-2 text-sm">
                      <div>
                        <span className="text-gray-600">Action:</span>
                        <code className="ml-2 bg-gray-100 px-2 py-1 rounded">balance</code>
                      </div>
                      <div>
                        <span className="text-gray-600">Example:</span>
                        <code className="block bg-gray-900 text-green-400 px-3 py-2 rounded mt-1 text-xs overflow-x-auto">
                          curl -X POST "{baseUrl}/api/v2?key=YOUR_KEY&action=balance"
                        </code>
                      </div>
                      <div>
                        <span className="text-gray-600">Response:</span>
                        <pre className="bg-gray-900 text-green-400 px-3 py-2 rounded mt-1 text-xs overflow-x-auto">
{`{
  "balance": "95.50",
  "currency": "USDT"
}`}
                        </pre>
                      </div>
                    </div>
                  </div>

                  {/* Batch Status Check */}
                  <div className="border border-gray-200 rounded-lg p-4">
                    <h4 className="font-medium text-gray-900 mb-2">5. Batch Order Status</h4>
                    <div className="space-y-2 text-sm">
                      <div>
                        <span className="text-gray-600">Action:</span>
                        <code className="ml-2 bg-gray-100 px-2 py-1 rounded">statuses</code>
                      </div>
                      <div>
                        <span className="text-gray-600">Parameters:</span>
                        <ul className="ml-4 mt-1 text-xs space-y-1">
                          <li>• <code className="bg-gray-100 px-1 rounded">orders</code> - Comma-separated order IDs</li>
                        </ul>
                      </div>
                      <div>
                        <span className="text-gray-600">Example:</span>
                        <code className="block bg-gray-900 text-green-400 px-3 py-2 rounded mt-1 text-xs overflow-x-auto">
                          curl -X POST "{baseUrl}/api/v2?key=YOUR_KEY&action=statuses&orders=123,456,789"
                        </code>
                      </div>
                    </div>
                  </div>

                  {/* Cancel Order */}
                  <div className="border border-gray-200 rounded-lg p-4">
                    <h4 className="font-medium text-gray-900 mb-2">6. Cancel Order</h4>
                    <div className="space-y-2 text-sm">
                      <div>
                        <span className="text-gray-600">Action:</span>
                        <code className="ml-2 bg-gray-100 px-2 py-1 rounded">cancel</code>
                      </div>
                      <div>
                        <span className="text-gray-600">Parameters:</span>
                        <ul className="ml-4 mt-1 text-xs space-y-1">
                          <li>• <code className="bg-gray-100 px-1 rounded">order</code> - Order ID to cancel (integer)</li>
                        </ul>
                      </div>
                      <div>
                        <span className="text-gray-600">Example:</span>
                        <code className="block bg-gray-900 text-green-400 px-3 py-2 rounded mt-1 text-xs overflow-x-auto">
                          curl -X POST "{baseUrl}/api/v2?key=YOUR_KEY&action=cancel&order=12345"
                        </code>
                      </div>
                    </div>
                  </div>

                  {/* Mass Order Creation */}
                  <div className="border border-gray-200 rounded-lg p-4">
                    <h4 className="font-medium text-gray-900 mb-2">7. Mass Order (Bulk Creation)</h4>
                    <div className="space-y-2 text-sm">
                      <div>
                        <span className="text-gray-600">Action:</span>
                        <code className="ml-2 bg-gray-100 px-2 py-1 rounded">mass</code>
                      </div>
                      <div>
                        <span className="text-gray-600">Description:</span>
                        <p className="text-xs text-gray-600 mt-1">Create multiple orders in a single API call (max 100 orders). Requires JSON request body.</p>
                      </div>
                      <div>
                        <span className="text-gray-600">Example:</span>
                        <code className="block bg-gray-900 text-green-400 px-3 py-2 rounded mt-1 text-xs overflow-x-auto">
{`curl -X POST "{baseUrl}/api/v2?key=YOUR_KEY&action=mass" \\
  -H "Content-Type: application/json" \\
  -d '{
    "orders": [
      {
        "service": 1,
        "link": "https://youtube.com/watch?v=VIDEO1",
        "quantity": 1000
      },
      {
        "service": 2,
        "link": "https://youtube.com/watch?v=VIDEO2",
        "quantity": 500
      }
    ]
  }'`}
                        </code>
                      </div>
                      <div>
                        <span className="text-gray-600">Response:</span>
                        <pre className="bg-gray-900 text-green-400 px-3 py-2 rounded mt-1 text-xs overflow-x-auto">
{`{
  "status": "Success",
  "orders": [
    {
      "order": 12345,
      "charge": "4.00",
      "start_count": 1523,
      "created_at": "2025-11-06T10:30:45",
      "status": "Success"
    },
    {
      "order": 12346,
      "charge": "2.00",
      "start_count": 892,
      "created_at": "2025-11-06T10:30:46",
      "status": "Success"
    }
  ],
  "total_orders": 2,
  "total_charge": "6.00",
  "remaining_balance": "93.50",
  "currency": "USD"
}`}
                        </pre>
                      </div>
                    </div>
                  </div>
                </div>

                {/* Error Handling */}
                <div>
                  <h3 className="text-sm font-semibold text-gray-900 mb-2">Error Handling</h3>
                  <p className="text-sm text-gray-600 mb-2">
                    All errors return a JSON response with an error field:
                  </p>
                  <pre className="bg-gray-900 text-red-400 px-3 py-2 rounded text-xs">
{`{
  "error": "Invalid API key"
}`}
                  </pre>
                </div>

                {/* Rate Limits */}
                <div className="bg-yellow-50 border border-yellow-200 rounded-lg p-4">
                  <h3 className="text-sm font-semibold text-yellow-900 mb-2">Rate Limits</h3>
                  <ul className="text-xs text-yellow-800 space-y-1">
                    <li>• Order creation: Limited per user</li>
                    <li>• Status checks: Reasonable usage expected</li>
                    <li>• Exceeding limits returns HTTP 429</li>
                  </ul>
                </div>
              </div>
            </div>
          )}
        </div>
      </div>
    </div>
  );
};
