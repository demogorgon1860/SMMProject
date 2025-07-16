function AddBalance({ onBalanceAdded }: { onBalanceAdded: () => void }) {
    const [amount, setAmount] = useState('');
    const [currency, setCurrency] = useState('USDT');
    const [loading, setLoading] = useState(false);
    const [paymentUrl, setPaymentUrl] = useState('');
  
    const currencies = ['BTC', 'ETH', 'USDT', 'LTC', 'USDC'];
  
    const handleSubmit = async (e: React.FormEvent) => {
      e.preventDefault();
      setLoading(true);
  
      try {
        const response = await api.post('/deposits', {
          amount: parseFloat(amount),
          currency
        });
        
        setPaymentUrl(response.data.paymentUrl);
        
        // Open payment URL in new window
        window.open(response.data.paymentUrl, '_blank');
        
        // Poll for payment completion
        const interval = setInterval(async () => {
          try {
            const status = await api.get(`/deposits/${response.data.orderId}/status`);
            if (status.data.status === 'COMPLETED') {
              clearInterval(interval);
              onBalanceAdded();
              setPaymentUrl('');
              setAmount('');
              alert('Payment completed successfully!');
            }
          } catch (error) {
            console.error('Error checking payment status:', error);
          }
        }, 5000);
        
        // Clear interval after 1 hour
        setTimeout(() => clearInterval(interval), 3600000);
        
      } catch (error) {
        console.error('Error creating deposit:', error);
        alert('Failed to create deposit');
      } finally {
        setLoading(false);
      }
    };
  
    return (
      <div className="bg-white shadow rounded-lg p-6">
        <h2 className="text-lg font-medium text-gray-900 mb-4">Add Balance</h2>
        
        <form onSubmit={handleSubmit} className="space-y-4">
          <div>
            <label className="block text-sm font-medium text-gray-700">Amount (USD)</label>
            <input
              type="number"
              value={amount}
              onChange={(e) => setAmount(e.target.value)}
              min="5"
              step="0.01"
              required
              placeholder="Minimum $5.00"
              className="mt-1 block w-full px-3 py-2 border border-gray-300 rounded-md shadow-sm focus:outline-none focus:ring-blue-500 focus:border-blue-500"
            />
          </div>
  
          <div>
            <label className="block text-sm font-medium text-gray-700">Currency</label>
            <select
              value={currency}
              onChange={(e) => setCurrency(e.target.value)}
              className="mt-1 block w-full px-3 py-2 border border-gray-300 rounded-md shadow-sm focus:outline-none focus:ring-blue-500 focus:border-blue-500"
            >
              {currencies.map(curr => (
                <option key={curr} value={curr}>{curr}</option>
              ))}
            </select>
          </div>
  
          <button
            type="submit"
            disabled={loading}
            className="w-full flex justify-center py-2 px-4 border border-transparent rounded-md shadow-sm text-sm font-medium text-white bg-blue-600 hover:bg-blue-700 focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-blue-500 disabled:opacity-50"
          >
            {loading ? 'Processing...' : 'Continue to Payment'}
          </button>
        </form>
  
        {paymentUrl && (
          <div className="mt-4 p-4 bg-blue-50 rounded-md">
            <p className="text-sm text-blue-600">
              Payment window opened. If it didn't open automatically, 
              <a href={paymentUrl} target="_blank" rel="noopener noreferrer" className="underline ml-1">
                click here
              </a>
            </p>
          </div>
        )}
      </div>
    );
  }
  