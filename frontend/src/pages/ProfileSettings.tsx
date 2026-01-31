import React, { useEffect, useState } from 'react';
import { apiKeyAPI } from '../services/api';
import {
  Key,
  FileText,
  Copy,
  Check,
  RefreshCw,
  AlertTriangle,
  AlertCircle,
  CheckCircle,
  XCircle,
  Loader2,
  Shield,
  Code,
  Terminal,
  Info,
} from 'lucide-react';

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

  const navItems = [
    { id: 'api-keys', label: 'API Keys', icon: Key },
    { id: 'api-docs', label: 'API Documentation', icon: FileText },
  ];

  return (
    <div className="flex flex-col lg:flex-row gap-6 animate-fade-in">
      {/* Sidebar Navigation */}
      <div className="lg:w-64 flex-shrink-0">
        <div className="bg-white dark:bg-dark-800 rounded-2xl border border-dark-100 dark:border-dark-700 shadow-soft dark:shadow-dark-soft overflow-hidden">
          <div className="p-6 border-b border-dark-100 dark:border-dark-700">
            <div className="flex items-center gap-3">
              <div className="w-10 h-10 rounded-xl bg-primary-100 dark:bg-primary-900/30 flex items-center justify-center">
                <Shield size={20} className="text-primary-600 dark:text-primary-400" />
              </div>
              <div>
                <h1 className="text-lg font-bold text-dark-900 dark:text-white">Settings</h1>
                <p className="text-xs text-dark-500 dark:text-dark-400">API & Integration</p>
              </div>
            </div>
          </div>
          <nav className="p-3">
            {navItems.map((item) => {
              const Icon = item.icon;
              return (
                <button
                  key={item.id}
                  onClick={() => setActiveSection(item.id as 'api-keys' | 'api-docs')}
                  className={`w-full flex items-center gap-3 px-4 py-3 rounded-xl text-sm font-medium transition-all ${
                    activeSection === item.id
                      ? 'bg-primary-100 text-primary-700 dark:bg-primary-900/50 dark:text-primary-300'
                      : 'text-dark-600 hover:text-dark-900 hover:bg-dark-100 dark:text-dark-400 dark:hover:text-white dark:hover:bg-dark-700'
                  }`}
                >
                  <Icon size={18} />
                  {item.label}
                </button>
              );
            })}
          </nav>
        </div>
      </div>

      {/* Main Content Area */}
      <div className="flex-1 min-w-0">
        {/* Error/Success Messages */}
        {error && (
          <div className="mb-6 bg-red-50 dark:bg-red-900/20 border border-red-200 dark:border-red-800/50 rounded-xl p-4 flex items-start gap-3">
            <XCircle size={18} className="text-red-600 dark:text-red-400 mt-0.5 flex-shrink-0" />
            <p className="text-sm text-red-700 dark:text-red-300">{error}</p>
          </div>
        )}
        {successMessage && (
          <div className="mb-6 bg-accent-50 dark:bg-accent-900/20 border border-accent-200 dark:border-accent-800/50 rounded-xl p-4 flex items-start gap-3">
            <CheckCircle size={18} className="text-accent-600 dark:text-accent-400 mt-0.5 flex-shrink-0" />
            <p className="text-sm text-accent-700 dark:text-accent-300">{successMessage}</p>
          </div>
        )}

        {/* API Keys Section */}
        {activeSection === 'api-keys' && (
          <div className="bg-white dark:bg-dark-800 rounded-2xl border border-dark-100 dark:border-dark-700 shadow-soft dark:shadow-dark-soft overflow-hidden">
            <div className="p-6 border-b border-dark-100 dark:border-dark-700">
              <div className="flex items-center gap-3">
                <div className="w-10 h-10 rounded-xl bg-primary-100 dark:bg-primary-900/30 flex items-center justify-center">
                  <Key size={20} className="text-primary-600 dark:text-primary-400" />
                </div>
                <div>
                  <h2 className="text-lg font-semibold text-dark-900 dark:text-white">API Key Management</h2>
                  <p className="text-sm text-dark-500 dark:text-dark-400">
                    Generate and manage your API key for external integrations
                  </p>
                </div>
              </div>
            </div>

            <div className="p-6">
              {loading ? (
                <div className="text-center py-12">
                  <Loader2 size={24} className="animate-spin text-primary-500 mx-auto mb-3" />
                  <p className="text-sm text-dark-500 dark:text-dark-400">Loading API key status...</p>
                </div>
              ) : (
                <div className="space-y-6">
                  {/* Current API Key Status */}
                  <div>
                    <label className="block text-sm font-medium text-dark-700 dark:text-dark-300 mb-3">
                      API Key Status
                    </label>
                    <div className="flex items-center gap-3 p-4 rounded-xl bg-dark-50 dark:bg-dark-700/50">
                      <div
                        className={`w-3 h-3 rounded-full ${
                          apiKeyStatus?.isActive ? 'bg-accent-500' :
                          apiKeyStatus?.hasApiKey ? 'bg-yellow-500' : 'bg-red-500'
                        }`}
                      />
                      <span className="text-sm font-medium text-dark-700 dark:text-dark-300">
                        {apiKeyStatus?.isActive ? 'Active' :
                         apiKeyStatus?.hasApiKey ? 'Inactive' : 'No API key generated'}
                      </span>
                    </div>
                  </div>

                  {/* Display existing API key preview */}
                  {apiKeyStatus?.hasApiKey && apiKeyStatus.maskedKey && (
                    <div>
                      <label className="block text-sm font-medium text-dark-700 dark:text-dark-300 mb-2">
                        Current API Key Preview
                      </label>
                      <div className="flex items-center gap-2">
                        <input
                          type="text"
                          value={apiKeyStatus.maskedKey}
                          readOnly
                          className="flex-1 px-4 py-3 bg-dark-100 dark:bg-dark-700 border border-dark-200 dark:border-dark-600 rounded-xl text-dark-600 dark:text-dark-400 font-mono text-sm"
                        />
                      </div>
                      <p className="mt-2 text-xs text-dark-500 dark:text-dark-400 flex items-center gap-1">
                        <Info size={12} />
                        For security, only a preview is shown
                      </p>
                    </div>
                  )}

                  {/* Display newly generated API key */}
                  {newApiKey && (
                    <div className="bg-yellow-50 dark:bg-yellow-900/20 border border-yellow-200 dark:border-yellow-800/50 rounded-xl p-4">
                      <div className="flex items-center gap-2 mb-3">
                        <AlertTriangle size={18} className="text-yellow-600 dark:text-yellow-400" />
                        <label className="text-sm font-medium text-yellow-800 dark:text-yellow-300">
                          Your New API Key (Copy it now!)
                        </label>
                      </div>
                      <div className="flex items-center gap-2">
                        <input
                          type="text"
                          value={newApiKey}
                          readOnly
                          className="flex-1 px-4 py-3 bg-white dark:bg-dark-800 border border-yellow-300 dark:border-yellow-700 rounded-xl font-mono text-sm text-dark-900 dark:text-white"
                        />
                        <button
                          onClick={() => copyToClipboard(newApiKey, 'apiKey')}
                          className="px-4 py-3 bg-yellow-600 hover:bg-yellow-700 text-white rounded-xl transition-colors flex items-center gap-2"
                        >
                          {copiedField === 'apiKey' ? <Check size={16} /> : <Copy size={16} />}
                          {copiedField === 'apiKey' ? 'Copied!' : 'Copy'}
                        </button>
                      </div>
                      <p className="mt-3 text-xs text-yellow-700 dark:text-yellow-400">
                        Save this key securely. You won't be able to see it again!
                      </p>
                    </div>
                  )}

                  {/* Action Buttons */}
                  <div className="pt-2">
                    {!apiKeyStatus?.hasApiKey ? (
                      <button
                        onClick={handleGenerateApiKey}
                        disabled={actionLoading}
                        className="w-full bg-gradient-to-r from-primary-600 to-primary-700 hover:from-primary-700 hover:to-primary-800 text-white px-6 py-3.5 rounded-xl font-medium transition-all disabled:opacity-50 disabled:cursor-not-allowed flex items-center justify-center gap-2"
                      >
                        {actionLoading ? (
                          <>
                            <Loader2 size={18} className="animate-spin" />
                            Generating...
                          </>
                        ) : (
                          <>
                            <Key size={18} />
                            Generate API Key
                          </>
                        )}
                      </button>
                    ) : (
                      <button
                        onClick={handleRotateApiKey}
                        disabled={actionLoading}
                        className="w-full bg-gradient-to-r from-accent-600 to-accent-700 hover:from-accent-700 hover:to-accent-800 text-white px-6 py-3.5 rounded-xl font-medium transition-all disabled:opacity-50 disabled:cursor-not-allowed flex items-center justify-center gap-2"
                      >
                        {actionLoading ? (
                          <>
                            <Loader2 size={18} className="animate-spin" />
                            Rotating...
                          </>
                        ) : (
                          <>
                            <RefreshCw size={18} />
                            Rotate API Key
                          </>
                        )}
                      </button>
                    )}
                  </div>

                  {/* Info Box */}
                  <div className="bg-blue-50 dark:bg-blue-900/20 border border-blue-200 dark:border-blue-800/50 rounded-xl p-4">
                    <div className="flex items-start gap-3">
                      <Info size={18} className="text-blue-600 dark:text-blue-400 mt-0.5 flex-shrink-0" />
                      <div>
                        <h3 className="text-sm font-medium text-blue-800 dark:text-blue-300 mb-2">API Key Information</h3>
                        <ul className="text-xs text-blue-700 dark:text-blue-400 space-y-1.5">
                          <li className="flex items-start gap-2">
                            <span className="text-blue-500">•</span>
                            Use API keys to authenticate external scripts and applications
                          </li>
                          <li className="flex items-start gap-2">
                            <span className="text-blue-500">•</span>
                            Rotating a key deactivates the old key and creates a new active one
                          </li>
                          <li className="flex items-start gap-2">
                            <span className="text-blue-500">•</span>
                            Never share your API key publicly or commit it to version control
                          </li>
                          <li className="flex items-start gap-2">
                            <span className="text-blue-500">•</span>
                            Only active API keys can be used for authentication
                          </li>
                        </ul>
                      </div>
                    </div>
                  </div>
                </div>
              )}
            </div>
          </div>
        )}

        {/* API Documentation Section */}
        {activeSection === 'api-docs' && (
          <div className="space-y-6">
            {/* Header Card */}
            <div className="bg-white dark:bg-dark-800 rounded-2xl border border-dark-100 dark:border-dark-700 shadow-soft dark:shadow-dark-soft overflow-hidden">
              <div className="p-6 border-b border-dark-100 dark:border-dark-700">
                <div className="flex items-center gap-3">
                  <div className="w-10 h-10 rounded-xl bg-blue-100 dark:bg-blue-900/30 flex items-center justify-center">
                    <Code size={20} className="text-blue-600 dark:text-blue-400" />
                  </div>
                  <div>
                    <h2 className="text-lg font-semibold text-dark-900 dark:text-white">API Documentation</h2>
                    <p className="text-sm text-dark-500 dark:text-dark-400">
                      Complete guide to integrate with our API
                    </p>
                  </div>
                </div>
              </div>

              <div className="p-6 space-y-6">
                {/* Base URL */}
                <div>
                  <h3 className="text-sm font-semibold text-dark-900 dark:text-white mb-2">Base URL</h3>
                  <div className="flex items-center gap-2">
                    <code className="flex-1 bg-dark-100 dark:bg-dark-700 px-4 py-3 rounded-xl text-sm font-mono text-dark-700 dark:text-dark-300">
                      {baseUrl}/api/v2
                    </code>
                    <button
                      onClick={() => copyToClipboard(`${baseUrl}/api/v2`, 'baseUrl')}
                      className="px-4 py-3 bg-dark-200 dark:bg-dark-600 hover:bg-dark-300 dark:hover:bg-dark-500 rounded-xl transition-colors flex items-center gap-2 text-dark-700 dark:text-dark-300"
                    >
                      {copiedField === 'baseUrl' ? <Check size={16} /> : <Copy size={16} />}
                    </button>
                  </div>
                </div>

                {/* Authentication */}
                <div>
                  <h3 className="text-sm font-semibold text-dark-900 dark:text-white mb-2">Authentication</h3>
                  <p className="text-sm text-dark-600 dark:text-dark-400 mb-3">
                    Include your API key in all requests using the <code className="bg-dark-100 dark:bg-dark-700 px-1.5 py-0.5 rounded text-xs">key</code> parameter:
                  </p>
                  <code className="block bg-dark-100 dark:bg-dark-700 px-4 py-3 rounded-xl text-xs font-mono text-dark-700 dark:text-dark-300 overflow-x-auto">
                    POST {baseUrl}/api/v2?key=YOUR_API_KEY&action=...
                  </code>
                </div>
              </div>
            </div>

            {/* Endpoints */}
            <div className="bg-white dark:bg-dark-800 rounded-2xl border border-dark-100 dark:border-dark-700 shadow-soft dark:shadow-dark-soft overflow-hidden">
              <div className="p-6 border-b border-dark-100 dark:border-dark-700">
                <div className="flex items-center gap-3">
                  <div className="w-10 h-10 rounded-xl bg-purple-100 dark:bg-purple-900/30 flex items-center justify-center">
                    <Terminal size={20} className="text-purple-600 dark:text-purple-400" />
                  </div>
                  <h3 className="text-lg font-semibold text-dark-900 dark:text-white">Available Endpoints</h3>
                </div>
              </div>

              <div className="p-6 space-y-4">
                {/* Create Order */}
                <EndpointCard
                  title="1. Create Order"
                  action="add"
                  parameters={[
                    { name: 'service', desc: 'Service ID (see Available Services below)' },
                    { name: 'link', desc: 'Target URL (YouTube or Instagram)' },
                    { name: 'quantity', desc: 'Order quantity (integer)' },
                    { name: 'customComments', desc: '(Optional) For services 5 and 9 only' },
                  ]}
                  example={`curl -X POST "${baseUrl}/api/v2?key=YOUR_KEY&action=add&service=1&link=https://youtube.com/watch?v=VIDEO_ID&quantity=1000"`}
                  response={`{
  "status": "Success",
  "order": 12345,
  "charge": "4.00",
  "start_count": 1523,
  "created_at": "2025-11-06T10:30:45",
  "remaining_balance": "95.50",
  "currency": "USD"
}`}
                  copyToClipboard={copyToClipboard}
                  copiedField={copiedField}
                />

                {/* Check Order Status */}
                <EndpointCard
                  title="2. Check Order Status"
                  action="status"
                  parameters={[
                    { name: 'order', desc: 'Order ID (integer)' },
                  ]}
                  example={`curl -X POST "${baseUrl}/api/v2?key=YOUR_KEY&action=status&order=12345"`}
                  response={`{
  "charge": "4.00",
  "start_count": 1523,
  "status": "In progress",
  "remains": 450,
  "currency": "USDT"
}`}
                  copyToClipboard={copyToClipboard}
                  copiedField={copiedField}
                />

                {/* Get Services */}
                <EndpointCard
                  title="3. Get Available Services"
                  action="services"
                  example={`curl -X POST "${baseUrl}/api/v2?key=YOUR_KEY&action=services"`}
                  response={`[
  {
    "service": 1,
    "name": "YouTube Views - USA",
    "category": "YouTube",
    "rate": "4.00",
    "min": 100,
    "max": 100000
  }
]`}
                  copyToClipboard={copyToClipboard}
                  copiedField={copiedField}
                />

                {/* Check Balance */}
                <EndpointCard
                  title="4. Check Balance"
                  action="balance"
                  example={`curl -X POST "${baseUrl}/api/v2?key=YOUR_KEY&action=balance"`}
                  response={`{
  "balance": "95.50",
  "currency": "USDT"
}`}
                  copyToClipboard={copyToClipboard}
                  copiedField={copiedField}
                />

                {/* Batch Status Check */}
                <EndpointCard
                  title="5. Batch Order Status"
                  action="statuses"
                  parameters={[
                    { name: 'orders', desc: 'Comma-separated order IDs' },
                  ]}
                  example={`curl -X POST "${baseUrl}/api/v2?key=YOUR_KEY&action=statuses&orders=123,456,789"`}
                  copyToClipboard={copyToClipboard}
                  copiedField={copiedField}
                />

                {/* Cancel Order */}
                <EndpointCard
                  title="6. Cancel Order"
                  action="cancel"
                  parameters={[
                    { name: 'order', desc: 'Order ID to cancel (integer)' },
                  ]}
                  example={`curl -X POST "${baseUrl}/api/v2?key=YOUR_KEY&action=cancel&order=12345"`}
                  copyToClipboard={copyToClipboard}
                  copiedField={copiedField}
                />

                {/* Emoji Comments */}
                <EndpointCard
                  title="Service 5: Emoji Comments"
                  action="add"
                  description="For emoji comments, pass emoji type via customComments parameter."
                  parameters={[
                    { name: 'customComments', desc: 'EMOJI:POSITIVE or EMOJI:NEGATIVE' },
                  ]}
                  example={`curl -X POST "${baseUrl}/api/v2?key=YOUR_KEY&action=add&service=5&link=https://instagram.com/p/ABC123/&quantity=20&customComments=EMOJI:POSITIVE"`}
                  response={`EMOJI:POSITIVE → 😄😊😍🥰😘🔥💯✨⭐️🌟❤️🧡💛💖💕💞👍👏🙌🤍😎🤩😆🎉
EMOJI:NEGATIVE → 😒😑😐🙄😤😠😡👎❌🚫⛔️🤨🤔😕😟😬`}
                  copyToClipboard={copyToClipboard}
                  copiedField={copiedField}
                />

                {/* Custom Comments */}
                <EndpointCard
                  title="Service 9: Custom Comments"
                  action="add"
                  description="For custom comments, pass your comments separated by newlines. Number of comments must equal quantity."
                  parameters={[
                    { name: 'customComments', desc: 'Comments separated by newlines (\\n)' },
                  ]}
                  example={`curl -X POST "${baseUrl}/api/v2" \\
  -d "key=YOUR_KEY" \\
  -d "action=add" \\
  -d "service=9" \\
  -d "link=https://instagram.com/p/ABC123/" \\
  -d "quantity=5" \\
  --data-urlencode "customComments=Great post!
Love this!
Amazing!
Beautiful!
Perfect!"`}
                  copyToClipboard={copyToClipboard}
                  copiedField={copiedField}
                />

                {/* Mass Order */}
                <EndpointCard
                  title="7. Mass Order (Bulk Creation)"
                  action="mass"
                  description="Create multiple orders in a single API call (max 100 orders). Requires JSON request body."
                  example={`curl -X POST "${baseUrl}/api/v2?key=YOUR_KEY&action=mass" \\
  -H "Content-Type: application/json" \\
  -d '{
    "orders": [
      {"service": 1, "link": "https://youtube.com/watch?v=VIDEO1", "quantity": 1000},
      {"service": 2, "link": "https://youtube.com/watch?v=VIDEO2", "quantity": 500}
    ]
  }'`}
                  response={`{
  "status": "Success",
  "orders": [
    {"order": 12345, "charge": "4.00", "status": "Success"},
    {"order": 12346, "charge": "2.00", "status": "Success"}
  ],
  "total_orders": 2,
  "total_charge": "6.00",
  "remaining_balance": "93.50",
  "currency": "USD"
}`}
                  copyToClipboard={copyToClipboard}
                  copiedField={copiedField}
                />
              </div>
            </div>

            {/* Error Handling & Rate Limits */}
            <div className="grid grid-cols-1 md:grid-cols-2 gap-6">
              <div className="bg-white dark:bg-dark-800 rounded-2xl border border-dark-100 dark:border-dark-700 p-6 shadow-soft dark:shadow-dark-soft">
                <div className="flex items-center gap-2 mb-4">
                  <AlertCircle size={18} className="text-red-500" />
                  <h3 className="text-sm font-semibold text-dark-900 dark:text-white">Error Handling</h3>
                </div>
                <p className="text-sm text-dark-600 dark:text-dark-400 mb-3">
                  All errors return a JSON response with an error field:
                </p>
                <pre className="bg-dark-900 text-red-400 px-4 py-3 rounded-xl text-xs font-mono overflow-x-auto">
{`{
  "error": "Invalid API key"
}`}
                </pre>
              </div>

              <div className="bg-yellow-50 dark:bg-yellow-900/20 rounded-2xl border border-yellow-200 dark:border-yellow-800/50 p-6">
                <div className="flex items-center gap-2 mb-4">
                  <AlertTriangle size={18} className="text-yellow-600 dark:text-yellow-400" />
                  <h3 className="text-sm font-semibold text-yellow-800 dark:text-yellow-300">Rate Limits</h3>
                </div>
                <ul className="text-xs text-yellow-700 dark:text-yellow-400 space-y-2">
                  <li className="flex items-start gap-2">
                    <span className="text-yellow-500">•</span>
                    Order creation: Limited per user
                  </li>
                  <li className="flex items-start gap-2">
                    <span className="text-yellow-500">•</span>
                    Status checks: Reasonable usage expected
                  </li>
                  <li className="flex items-start gap-2">
                    <span className="text-yellow-500">•</span>
                    Exceeding limits returns HTTP 429
                  </li>
                </ul>
              </div>
            </div>
          </div>
        )}
      </div>
    </div>
  );
};

// Endpoint Card Component
interface EndpointCardProps {
  title: string;
  action: string;
  description?: string;
  parameters?: { name: string; desc: string }[];
  example: string;
  response?: string;
  copyToClipboard: (text: string, field: string) => void;
  copiedField: string | null;
}

const EndpointCard: React.FC<EndpointCardProps> = ({
  title,
  action,
  description,
  parameters,
  example,
  response,
  copyToClipboard,
  copiedField,
}) => {
  const fieldId = `example-${action}`;

  return (
    <div className="border border-dark-200 dark:border-dark-700 rounded-xl overflow-hidden">
      <div className="px-4 py-3 bg-dark-50 dark:bg-dark-700/50 border-b border-dark-200 dark:border-dark-700">
        <h4 className="font-medium text-dark-900 dark:text-white">{title}</h4>
      </div>
      <div className="p-4 space-y-3">
        <div className="flex items-center gap-2">
          <span className="text-sm text-dark-600 dark:text-dark-400">Action:</span>
          <code className="bg-primary-100 dark:bg-primary-900/30 text-primary-700 dark:text-primary-300 px-2 py-1 rounded-lg text-xs font-medium">
            {action}
          </code>
        </div>

        {description && (
          <p className="text-xs text-dark-600 dark:text-dark-400">{description}</p>
        )}

        {parameters && parameters.length > 0 && (
          <div>
            <span className="text-sm text-dark-600 dark:text-dark-400">Parameters:</span>
            <ul className="mt-1 space-y-1">
              {parameters.map((param) => (
                <li key={param.name} className="text-xs text-dark-500 dark:text-dark-400 flex items-center gap-2">
                  <span className="text-dark-400">•</span>
                  <code className="bg-dark-100 dark:bg-dark-700 px-1.5 py-0.5 rounded text-dark-700 dark:text-dark-300">
                    {param.name}
                  </code>
                  <span>- {param.desc}</span>
                </li>
              ))}
            </ul>
          </div>
        )}

        <div>
          <div className="flex items-center justify-between mb-1">
            <span className="text-sm text-dark-600 dark:text-dark-400">Example:</span>
            <button
              onClick={() => copyToClipboard(example, fieldId)}
              className="text-xs text-primary-600 hover:text-primary-700 dark:text-primary-400 dark:hover:text-primary-300 flex items-center gap-1"
            >
              {copiedField === fieldId ? <Check size={12} /> : <Copy size={12} />}
              {copiedField === fieldId ? 'Copied!' : 'Copy'}
            </button>
          </div>
          <pre className="bg-dark-900 text-accent-400 px-4 py-3 rounded-xl text-xs font-mono overflow-x-auto whitespace-pre-wrap break-all">
            {example}
          </pre>
        </div>

        {response && (
          <div>
            <span className="text-sm text-dark-600 dark:text-dark-400">Response:</span>
            <pre className="mt-1 bg-dark-900 text-accent-400 px-4 py-3 rounded-xl text-xs font-mono overflow-x-auto">
              {response}
            </pre>
          </div>
        )}
      </div>
    </div>
  );
};
