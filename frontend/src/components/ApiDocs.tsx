function ApiDocs({ apiKey }: { apiKey?: string }) {
    const [showKey, setShowKey] = useState(false);
  
    return (
      <div className="bg-white shadow rounded-lg p-6">
        <h2 className="text-lg font-medium text-gray-900 mb-4">API Documentation</h2>
        
        <div className="space-y-6">
          <div>
            <h3 className="text-md font-medium text-gray-900 mb-2">Your API Key</h3>
            <div className="flex items-center space-x-2">
              <code className="bg-gray-100 px-3 py-2 rounded text-sm flex-1">
                {showKey && apiKey ? apiKey : '••••••••••••••••••••••••'}
              </code>
              <button
                onClick={() => setShowKey(!showKey)}
                className="px-3 py-2 bg-gray-200 rounded text-sm hover:bg-gray-300"
              >
                {showKey ? 'Hide' : 'Show'}
              </button>
            </div>
          </div>
  
          <div>
            <h3 className="text-md font-medium text-gray-900 mb-2">Endpoints</h3>
            <div className="space-y-4">
              <div className="border rounded-lg p-4">
                <h4 className="font-medium text-gray-900">Create Order</h4>
                <p className="text-sm text-gray-600 mt-1">POST /api/v2/orders</p>
                <pre className="mt-2 bg-gray-100 p-3 rounded text-xs overflow-x-auto">
  {`{
    "service": 1,
    "link": "https://youtube.com/watch?v=...",
    "quantity": 1000
  }`}
                </pre>
              </div>
  
              <div className="border rounded-lg p-4">
                <h4 className="font-medium text-gray-900">Get Order Status</h4>
                <p className="text-sm text-gray-600 mt-1">GET /api/v2/orders/{`{id}`}</p>
              </div>
  
              <div className="border rounded-lg p-4">
                <h4 className="font-medium text-gray-900">Get Balance</h4>
                <p className="text-sm text-gray-600 mt-1">GET /api/v2/balance</p>
              </div>
            </div>
          </div>
        </div>
      </div>
    );
  }
                <option key={
  