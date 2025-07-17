// frontend/src/components/ApiDocs.tsx

import React, { useState } from 'react';
import { Service } from '../types';

interface ApiDocsProps {
  apiKey?: string;
  services?: Service[];
}

export const ApiDocs: React.FC<ApiDocsProps> = ({ apiKey, services = [] }) => {
  const [showKey, setShowKey] = useState(false);
  const [selectedEndpoint, setSelectedEndpoint] = useState<string>('services');
  const [copiedSection, setCopiedSection] = useState<string | null>(null);

  const copyToClipboard = (text: string, section: string) => {
    navigator.clipboard.writeText(text);
    setCopiedSection(section);
    setTimeout(() => setCopiedSection(null), 2000);
  };

  const apiBaseUrl = window.location.origin + '/api/v2';

  const endpoints = [
    {
      id: 'services',
      title: 'Get Services',
      method: 'GET',
      url: '/services',
      description: 'Retrieve all available services with pricing',
      headers: {
        'Authorization': 'Bearer YOUR_API_KEY'
      },
      response: {
        "services": [
          {
            "id": 1,
            "name": "YouTube Views (Standard)",
            "category": "YouTube",
            "minOrder": 100,
            "maxOrder": 1000000,
            "pricePer1000": "1.0000",
            "description": "High quality YouTube views"
          }
        ]
      }
    },
    {
      id: 'create-order',
      title: 'Create Order',
      method: 'POST',
      url: '/orders',
      description: 'Create a new order for YouTube views',
      headers: {
        'Authorization': 'Bearer YOUR_API_KEY',
        'Content-Type': 'application/json'
      },
      body: {
        "service": 1,
        "link": "https://youtube.com/watch?v=dQw4w9WgXcQ",
        "quantity": 1000
      },
      response: {
        "order": {
          "id": 12345,
          "service": 1,
          "link": "https://youtube.com/watch?v=dQw4w9WgXcQ",
          "quantity": 1000,
          "startCount": 1234,
          "remains": 1000,
          "status": "Pending",
          "charge": "1.00"
        }
      }
    },
    {
      id: 'get-orders',
      title: 'Get Orders',
      method: 'GET',
      url: '/orders',
      description: 'Retrieve your orders with optional filtering',
      headers: {
        'Authorization': 'Bearer YOUR_API_KEY'
      },
      params: {
        status: 'Pending (optional)',
        page: '0 (optional)',
        size: '20 (optional)'
      },
      response: {
        "orders": {
          "content": [
            {
              "id": 12345,
              "service": 1,
              "link": "https://youtube.com/watch?v=dQw4w9WgXcQ",
              "quantity": 1000,
              "startCount": 1234,
              "remains": 500,
              "status": "In progress",
              "charge": "1.00"
            }
          ],
          "totalElements": 1,
          "totalPages": 1,
          "size": 20,
          "number": 0
        }
      }
    },
    {
      id: 'get-order',
      title: 'Get Order Status',
      method: 'GET',
      url: '/orders/{id}',
      description: 'Get detailed information about a specific order',
      headers: {
        'Authorization': 'Bearer YOUR_API_KEY'
      },
      response: {
        "order": {
          "id": 12345,
          "service": 1,
          "link": "https://youtube.com/watch?v=dQw4w9WgXcQ",
          "quantity": 1000,
          "startCount": 1234,
          "remains": 0,
          "status": "Completed",
          "charge": "1.00"
        }
      }
    },
    {
      id: 'get-balance',
      title: 'Get Balance',
      method: 'GET',
      url: '/balance',
      description: 'Check your current account balance',
      headers: {
        'Authorization': 'Bearer YOUR_API_KEY'
      },
      response: {
        "balance": "25.50"
      }
    }
  ];

  const statusMeanings = [
    { status: 'Pending', description: 'Order created, waiting for processing' },
    { status: 'In progress', description: 'Order is being processed' },
    { status: 'Processing', description: 'Creating clips and setting up campaigns' },
    { status: 'Partial', description: 'Order partially completed' },
    { status: 'Completed', description: 'Order successfully completed' },
    { status: 'Canceled', description: 'Order was cancelled' },
    { status: 'Paused', description: 'Order processing paused' },
    { status: 'Refill', description: 'Order needs refill/top-up' }
  ];

  const selectedEndpointData = endpoints.find(ep => ep.id === selectedEndpoint);

  const generateCurlCommand = (endpoint: any) => {
    let curl = `curl -X ${endpoint.method} "${apiBaseUrl}${endpoint.url}"`;
    
    if (endpoint.headers) {
      Object.entries(endpoint.headers).forEach(([key, value]) => {
        const headerValue = key === 'Authorization' && apiKey 
          ? `Bearer ${apiKey}` 
          : value;
        curl += ` \\\n  -H "${key}: ${headerValue}"`;
      });
    }
    
    if (endpoint.body) {
      curl += ` \\\n  -d '${JSON.stringify(endpoint.body, null, 2)}'`;
    }
    
    if (endpoint.params) {
      const params = new URLSearchParams();
      Object.entries(endpoint.params).forEach(([key, value]) => {
        if (!value.includes('optional')) {
          params.append(key, value.split(' ')[0]);
        }
      });
      if (params.toString()) {
        curl = curl.replace(endpoint.url, `${endpoint.url}?${params.toString()}`);
      }
    }
    
    return curl;
  };

  return (
    <div className="bg-white shadow rounded-lg p-6">
      <h2 className="text-lg font-medium text-gray-900 mb-6">API Documentation</h2>
      
      {/* API Key Section */}
      <div className="mb-8">
        <h3 className="text-md font-medium text-gray-900 mb-3">Your API Key</h3>
        <div className="flex items-center space-x-2 mb-4">
          <code className="bg-gray-100 px-3 py-2 rounded text-sm flex-1 font-mono">
            {showKey && apiKey ? apiKey : '••••••••••••••••••••••••••••••••••••••••'}
          </code>
          <button
            onClick={() => setShowKey(!showKey)}
            className="px-3 py-2 bg-gray-200 rounded text-sm hover:bg-gray-300 transition-colors"
          >
            {showKey ? '👁️ Hide' : '👁️ Show'}
          </button>
          {apiKey && (
            <button
              onClick={() => copyToClipboard(apiKey, 'api-key')}
              className="px-3 py-2 bg-blue-600 text-white rounded text-sm hover:bg-blue-700 transition-colors"
            >
              {copiedSection === 'api-key' ? '✓ Copied!' : '📋 Copy'}
            </button>
          )}
        </div>
        <p className="text-sm text-gray-600">
          Include this key in the Authorization header: <code className="bg-gray-100 px-1 rounded">Bearer YOUR_API_KEY</code>
        </p>
      </div>

      {/* Base URL */}
      <div className="mb-8">
        <h3 className="text-md font-medium text-gray-900 mb-3">Base URL</h3>
        <div className="bg-gray-100 p-3 rounded font-mono text-sm">
          {apiBaseUrl}
        </div>
      </div>

      {/* Endpoints Navigation */}
      <div className="mb-6">
        <h3 className="text-md font-medium text-gray-900 mb-3">Endpoints</h3>
        <div className="flex flex-wrap gap-2">
          {endpoints.map(endpoint => (
            <button
              key={endpoint.id}
              onClick={() => setSelectedEndpoint(endpoint.id)}
              className={`px-3 py-2 rounded text-sm font-medium transition-colors ${
                selectedEndpoint === endpoint.id
                  ? 'bg-blue-600 text-white'
                  : 'bg-gray-100 text-gray-700 hover:bg-gray-200'
              }`}
            >
              <span className={`inline-block w-12 text-xs font-bold ${
                endpoint.method === 'GET' ? 'text-green-600' : 'text-blue-600'
              }`}>
                {endpoint.method}
              </span>
              {endpoint.title}
            </button>
          ))}
        </div>
      </div>

      {/* Selected Endpoint Details */}
      {selectedEndpointData && (
        <div className="mb-8">
          <div className="border rounded-lg p-6">
            <div className="flex items-center mb-4">
              <span className={`inline-block px-2 py-1 rounded text-xs font-bold mr-3 ${
                selectedEndpointData.method === 'GET' 
                  ? 'bg-green-100 text-green-800' 
                  : 'bg-blue-100 text-blue-800'
              }`}>
                {selectedEndpointData.method}
              </span>
              <code className="text-lg font-mono text-gray-900">
                {selectedEndpointData.url}
              </code>
            </div>
            
            <p className="text-gray-600 mb-6">{selectedEndpointData.description}</p>

            {/* Headers */}
            <div className="mb-6">
              <h4 className="font-medium text-gray-900 mb-2">Headers</h4>
              <div className="bg-gray-50 p-3 rounded">
                <pre className="text-sm">
{Object.entries(selectedEndpointData.headers).map(([key, value]) => (
`${key}: ${key === 'Authorization' && apiKey ? `Bearer ${apiKey}` : value}`
)).join('\n')}
                </pre>
              </div>
            </div>

            {/* URL Parameters */}
            {selectedEndpointData.params && (
              <div className="mb-6">
                <h4 className="font-medium text-gray-900 mb-2">URL Parameters</h4>
                <div className="bg-gray-50 p-3 rounded">
                  {Object.entries(selectedEndpointData.params).map(([key, value]) => (
                    <div key={key} className="flex items-center justify-between py-1">
                      <code className="text-sm font-mono text-blue-600">{key}</code>
                      <span className="text-sm text-gray-600">{value}</span>
                    </div>
                  ))}
                </div>
              </div>
            )}

            {/* Request Body */}
            {selectedEndpointData.body && (
              <div className="mb-6">
                <div className="flex items-center justify-between mb-2">
                  <h4 className="font-medium text-gray-900">Request Body</h4>
                  <button
                    onClick={() => copyToClipboard(JSON.stringify(selectedEndpointData.body, null, 2), 'request-body')}
                    className="text-xs text-blue-600 hover:text-blue-800"
                  >
                    {copiedSection === 'request-body' ? '✓ Copied!' : '📋 Copy'}
                  </button>
                </div>
                <div className="bg-gray-50 p-3 rounded">
                  <pre className="text-sm">
                    <code>{JSON.stringify(selectedEndpointData.body, null, 2)}</code>
                  </pre>
                </div>
              </div>
            )}

            {/* Response */}
            <div className="mb-6">
              <div className="flex items-center justify-between mb-2">
                <h4 className="font-medium text-gray-900">Response</h4>
                <button
                  onClick={() => copyToClipboard(JSON.stringify(selectedEndpointData.response, null, 2), 'response')}
                  className="text-xs text-blue-600 hover:text-blue-800"
                >
                  {copiedSection === 'response' ? '✓ Copied!' : '📋 Copy'}
                </button>
              </div>
              <div className="bg-gray-50 p-3 rounded">
                <pre className="text-sm">
                  <code>{JSON.stringify(selectedEndpointData.response, null, 2)}</code>
                </pre>
              </div>
            </div>

            {/* cURL Example */}
            <div>
              <div className="flex items-center justify-between mb-2">
                <h4 className="font-medium text-gray-900">cURL Example</h4>
                <button
                  onClick={() => copyToClipboard(generateCurlCommand(selectedEndpointData), 'curl')}
                  className="text-xs text-blue-600 hover:text-blue-800"
                >
                  {copiedSection === 'curl' ? '✓ Copied!' : '📋 Copy'}
                </button>
              </div>
              <div className="bg-gray-900 text-gray-100 p-3 rounded">
                <pre className="text-sm">
                  <code>{generateCurlCommand(selectedEndpointData)}</code>
                </pre>
              </div>
            </div>
          </div>
        </div>
      )}

      {/* Status Codes */}
      <div className="mb-8">
        <h3 className="text-md font-medium text-gray-900 mb-3">Order Status Meanings</h3>
        <div className="grid grid-cols-1 md:grid-cols-2 gap-3">
          {statusMeanings.map(({ status, description }) => (
            <div key={status} className="flex items-center p-3 border rounded">
              <span className="inline-block w-20 text-xs font-medium text-gray-600 mr-3">
                {status}
              </span>
              <span className="text-sm text-gray-700">{description}</span>
            </div>
          ))}
        </div>
      </div>

      {/* Error Codes */}
      <div className="mb-8">
        <h3 className="text-md font-medium text-gray-900 mb-3">HTTP Status Codes</h3>
        <div className="space-y-2">
          <div className="flex items-center justify-between p-2 bg-green-50 rounded">
            <code className="font-mono text-green-700">200 OK</code>
            <span className="text-sm text-green-700">Request successful</span>
          </div>
          <div className="flex items-center justify-between p-2 bg-red-50 rounded">
            <code className="font-mono text-red-700">400 Bad Request</code>
            <span className="text-sm text-red-700">Invalid request parameters</span>
          </div>
          <div className="flex items-center justify-between p-2 bg-red-50 rounded">
            <code className="font-mono text-red-700">401 Unauthorized</code>
            <span className="text-sm text-red-700">Invalid or missing API key</span>
          </div>
          <div className="flex items-center justify-between p-2 bg-red-50 rounded">
            <code className="font-mono text-red-700">402 Payment Required</code>
            <span className="text-sm text-red-700">Insufficient balance</span>
          </div>
          <div className="flex items-center justify-between p-2 bg-red-50 rounded">
            <code className="font-mono text-red-700">404 Not Found</code>
            <span className="text-sm text-red-700">Order or resource not found</span>
          </div>
          <div className="flex items-center justify-between p-2 bg-yellow-50 rounded">
            <code className="font-mono text-yellow-700">429 Too Many Requests</code>
            <span className="text-sm text-yellow-700">Rate limit exceeded</span>
          </div>
          <div className="flex items-center justify-between p-2 bg-red-50 rounded">
            <code className="font-mono text-red-700">500 Internal Server Error</code>
            <span className="text-sm text-red-700">Server error</span>
          </div>
        </div>
      </div>

      {/* Rate Limits */}
      <div className="mb-8">
        <h3 className="text-md font-medium text-gray-900 mb-3">Rate Limits</h3>
        <div className="bg-yellow-50 border border-yellow-200 p-4 rounded">
          <ul className="text-sm text-yellow-800 space-y-1">
            <li>• Maximum 1000 requests per hour per API key</li>
            <li>• Maximum 100 orders per day per account</li>
            <li>• Bulk operations limited to 50 orders at once</li>
          </ul>
        </div>
      </div>

      {/* Available Services */}
      {services.length > 0 && (
        <div className="mb-8">
          <h3 className="text-md font-medium text-gray-900 mb-3">Available Services</h3>
          <div className="overflow-x-auto">
            <table className="min-w-full border border-gray-200 rounded">
              <thead className="bg-gray-50">
                <tr>
                  <th className="px-4 py-2 text-left text-xs font-medium text-gray-500 uppercase">ID</th>
                  <th className="px-4 py-2 text-left text-xs font-medium text-gray-500 uppercase">Name</th>
                  <th className="px-4 py-2 text-left text-xs font-medium text-gray-500 uppercase">Category</th>
                  <th className="px-4 py-2 text-left text-xs font-medium text-gray-500 uppercase">Min Order</th>
                  <th className="px-4 py-2 text-left text-xs font-medium text-gray-500 uppercase">Max Order</th>
                  <th className="px-4 py-2 text-left text-xs font-medium text-gray-500 uppercase">Price/1000</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-gray-200">
                {services.map(service => (
                  <tr key={service.id}>
                    <td className="px-4 py-2 text-sm font-mono">{service.id}</td>
                    <td className="px-4 py-2 text-sm">{service.name}</td>
                    <td className="px-4 py-2 text-sm">{service.category}</td>
                    <td className="px-4 py-2 text-sm">{service.minOrder.toLocaleString()}</td>
                    <td className="px-4 py-2 text-sm">{service.maxOrder.toLocaleString()}</td>
                    <td className="px-4 py-2 text-sm font-medium">${service.pricePer1000}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      )}

      {/* Support */}
      <div>
        <h3 className="text-md font-medium text-gray-900 mb-3">Support</h3>
        <div className="bg-blue-50 border border-blue-200 p-4 rounded">
          <p className="text-sm text-blue-800 mb-2">
            Need help with the API? Contact our support team:
          </p>
          <ul className="text-sm text-blue-700 space-y-1">
            <li>• Email: api-support@smmpanel.com</li>
            <li>• Telegram: @smmpanel_support</li>
            <li>• Documentation: Check our GitHub repository</li>
          </ul>
        </div>
      </div>
    </div>
  );
};