import React, { useState, useEffect } from 'react';
import { motion } from 'framer-motion';
import { depositsAPI } from '../services/api';
import { toast } from 'react-hot-toast';
import {
  Wallet,
  CreditCard,
  Clock,
  CheckCircle,
  XCircle,
  ExternalLink,
  DollarSign,
  History,
  AlertCircle,
  Loader2,
  Bitcoin,
  Shield,
  Zap,
} from 'lucide-react';
import { formatDateOnly, formatDate } from '../utils/timezone';

interface Deposit {
  id: number;
  orderId: string;
  amount: number;
  status: string;
  paymentMethod: string;
  paymentUrl?: string;
  createdAt: string;
  confirmedAt?: string;
}

const QUICK_AMOUNTS = [10, 25, 50, 100, 250, 500];

const rowVariants = {
  hidden: { opacity: 0, y: 6 },
  visible: (i: number) => ({
    opacity: 1,
    y: 0,
    transition: { delay: i * 0.04, duration: 0.25, ease: 'easeOut' },
  }),
};

export const AddFunds: React.FC = () => {
  const [amount, setAmount] = useState('');
  const [paymentMethod, setPaymentMethod] = useState('cryptomus');
  const [loading, setLoading] = useState(false);
  const [deposits, setDeposits] = useState<Deposit[]>([]);
  const [loadingDeposits, setLoadingDeposits] = useState(true);

  useEffect(() => {
    loadDeposits();
  }, []);

  const loadDeposits = async () => {
    try {
      setLoadingDeposits(true);
      const response = await depositsAPI.getDeposits(0, 100);
      setDeposits(response.content || []);
    } catch (error: any) {
      console.error('Failed to load deposits:', error);
    } finally {
      setLoadingDeposits(false);
    }
  };

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();

    const amountNum = parseFloat(amount);
    if (isNaN(amountNum) || amountNum < 5) {
      toast.error('Minimum deposit amount is $5.00');
      return;
    }

    setLoading(true);
    try {
      const response = await depositsAPI.createDeposit(amountNum);

      if (response.paymentUrl) {
        window.location.href = response.paymentUrl;
      } else {
        toast.success('Deposit created successfully');
        setAmount('');
        loadDeposits();
      }
    } catch (error: any) {
      toast.error(error.response?.data?.message || 'Failed to create deposit');
    } finally {
      setLoading(false);
    }
  };

  const getStatusConfig = (status: string) => {
    switch (status.toUpperCase()) {
      case 'COMPLETED':
        return {
          icon: <CheckCircle size={14} />,
          colors: 'bg-emerald-100 text-emerald-700 dark:bg-emerald-900/30 dark:text-emerald-400',
          label: 'Completed',
        };
      case 'PENDING':
        return {
          icon: <Clock size={14} />,
          colors: 'bg-amber-100 text-amber-700 dark:bg-amber-900/30 dark:text-amber-400',
          label: 'Pending',
          pulse: true,
        };
      case 'FAILED':
        return {
          icon: <XCircle size={14} />,
          colors: 'bg-red-100 text-red-700 dark:bg-red-900/30 dark:text-red-400',
          label: 'Failed',
        };
      default:
        return {
          icon: <AlertCircle size={14} />,
          colors: 'bg-dark-100 text-dark-600 dark:bg-dark-700 dark:text-dark-400',
          label: status,
        };
    }
  };

  return (
    <motion.div
      className="max-w-4xl mx-auto space-y-6"
      initial={{ opacity: 0, y: 12 }}
      animate={{ opacity: 1, y: 0 }}
      transition={{ duration: 0.4, ease: 'easeOut' }}
    >
      {/* Header */}
      <div className="flex items-center gap-3">
        <div className="w-10 h-10 rounded-xl bg-accent-100 dark:bg-accent-900/30 flex items-center justify-center">
          <Wallet size={20} className="text-accent-600 dark:text-accent-400" />
        </div>
        <div>
          <h1 className="text-2xl font-bold text-dark-900 dark:text-white">Add Funds</h1>
          <p className="text-sm text-dark-500 dark:text-dark-400">Top up your balance to place orders</p>
        </div>
      </div>

      <div className="grid grid-cols-1 lg:grid-cols-3 gap-6">
        {/* Payment Form */}
        <motion.div
          initial={{ opacity: 0, y: 8 }}
          animate={{ opacity: 1, y: 0 }}
          transition={{ delay: 0.1, duration: 0.3 }}
          className="lg:col-span-2 bg-white dark:bg-dark-800 rounded-2xl border border-dark-100 dark:border-dark-700 shadow-soft dark:shadow-dark-soft overflow-hidden"
        >
          <div className="p-6 border-b border-dark-100 dark:border-dark-700">
            <div className="flex items-center gap-3">
              <div className="w-10 h-10 rounded-xl bg-primary-100 dark:bg-primary-900/30 flex items-center justify-center">
                <CreditCard size={20} className="text-primary-600 dark:text-primary-400" />
              </div>
              <div>
                <h2 className="text-lg font-semibold text-dark-900 dark:text-white">Top Up Balance</h2>
                <p className="text-sm text-dark-500 dark:text-dark-400">Choose amount and payment method</p>
              </div>
            </div>
          </div>

          <form onSubmit={handleSubmit} className="p-6 space-y-6">
            {/* Payment Method */}
            <div>
              <label className="block text-sm font-medium text-dark-700 dark:text-dark-300 mb-3">
                Payment Method
              </label>
              <div className="grid grid-cols-1 gap-3">
                <button
                  type="button"
                  onClick={() => setPaymentMethod('cryptomus')}
                  className={`flex items-center gap-4 p-4 rounded-xl border-2 transition-all duration-200 ${
                    paymentMethod === 'cryptomus'
                      ? 'border-primary-500 bg-primary-50 dark:bg-primary-900/20 shadow-sm shadow-primary-500/10'
                      : 'border-dark-200 dark:border-dark-600 hover:border-dark-300 dark:hover:border-dark-500 hover:shadow-soft'
                  }`}
                >
                  <div className={`w-12 h-12 rounded-xl flex items-center justify-center transition-colors duration-200 ${
                    paymentMethod === 'cryptomus'
                      ? 'bg-primary-100 dark:bg-primary-900/30'
                      : 'bg-dark-100 dark:bg-dark-700'
                  }`}>
                    <Bitcoin size={24} className={
                      paymentMethod === 'cryptomus'
                        ? 'text-primary-600 dark:text-primary-400'
                        : 'text-dark-500 dark:text-dark-400'
                    } />
                  </div>
                  <div className="text-left flex-1">
                    <p className={`font-medium ${
                      paymentMethod === 'cryptomus'
                        ? 'text-primary-700 dark:text-primary-300'
                        : 'text-dark-900 dark:text-white'
                    }`}>
                      Cryptomus
                    </p>
                    <p className="text-sm text-dark-500 dark:text-dark-400">
                      Pay with Bitcoin, USDT, ETH, and more
                    </p>
                  </div>
                  {paymentMethod === 'cryptomus' && (
                    <CheckCircle size={20} className="text-primary-600 dark:text-primary-400" />
                  )}
                </button>
              </div>
            </div>

            {/* Quick Amount Selection */}
            <div>
              <label className="block text-sm font-medium text-dark-700 dark:text-dark-300 mb-3">
                Quick Select
              </label>
              <div className="grid grid-cols-3 sm:grid-cols-6 gap-2">
                {QUICK_AMOUNTS.map((quickAmount) => (
                  <button
                    key={quickAmount}
                    type="button"
                    onClick={() => setAmount(quickAmount.toString())}
                    className={`py-2.5 px-3 rounded-xl text-sm font-medium transition-all duration-200 ${
                      amount === quickAmount.toString()
                        ? 'bg-primary-600 text-white shadow-sm shadow-primary-600/30 scale-[1.02]'
                        : 'bg-dark-100 dark:bg-dark-700 text-dark-700 dark:text-dark-300 hover:bg-dark-200 dark:hover:bg-dark-600 hover:scale-[1.02]'
                    }`}
                  >
                    ${quickAmount}
                  </button>
                ))}
              </div>
            </div>

            {/* Custom Amount Input */}
            <div>
              <label className="block text-sm font-medium text-dark-700 dark:text-dark-300 mb-2">
                Amount
              </label>
              <div className="relative">
                <div className="absolute inset-y-0 left-0 pl-4 flex items-center pointer-events-none">
                  <DollarSign size={18} className="text-dark-400" />
                </div>
                <input
                  type="number"
                  step="0.01"
                  min="5"
                  value={amount}
                  onChange={(e) => setAmount(e.target.value)}
                  className="w-full pl-10 pr-4 py-3 bg-dark-50 dark:bg-dark-700 border border-dark-200 dark:border-dark-600 rounded-xl text-dark-900 dark:text-white placeholder-dark-400 focus:outline-none focus:ring-2 focus:ring-primary-500/30 focus:border-primary-500 transition-all duration-200"
                  placeholder="Enter amount (min $5.00)"
                  required
                />
              </div>
              <p className="mt-2 text-xs text-dark-500 dark:text-dark-400 flex items-center gap-1">
                <AlertCircle size={12} />
                Minimum deposit: $5.00
              </p>
            </div>

            {/* Submit Button */}
            <button
              type="submit"
              disabled={loading || !amount || parseFloat(amount) < 5}
              className="w-full bg-gradient-to-r from-primary-600 to-primary-700 hover:from-primary-700 hover:to-primary-800 text-white py-3.5 rounded-xl font-medium transition-all duration-200 disabled:opacity-50 disabled:cursor-not-allowed shadow-soft hover:shadow-lg hover:-translate-y-0.5 disabled:hover:translate-y-0 disabled:hover:shadow-soft flex items-center justify-center gap-2"
            >
              {loading ? (
                <>
                  <Loader2 size={18} className="animate-spin" />
                  Processing...
                </>
              ) : (
                <>
                  <CreditCard size={18} />
                  Pay ${amount || '0.00'}
                </>
              )}
            </button>
          </form>
        </motion.div>

        {/* Info Cards */}
        <motion.div
          initial={{ opacity: 0, y: 8 }}
          animate={{ opacity: 1, y: 0 }}
          transition={{ delay: 0.2, duration: 0.3 }}
          className="space-y-4"
        >
          <div className="bg-white dark:bg-dark-800 rounded-2xl border border-dark-100 dark:border-dark-700 p-6 shadow-soft dark:shadow-dark-soft">
            <h3 className="text-sm font-semibold text-dark-900 dark:text-white mb-4">Payment Info</h3>
            <ul className="space-y-3 text-sm text-dark-600 dark:text-dark-400">
              {[
                { icon: <Zap size={16} />, text: 'Instant balance top-up after confirmation' },
                { icon: <Bitcoin size={16} />, text: 'Multiple cryptocurrencies supported' },
                { icon: <Shield size={16} />, text: 'Secure processing via Cryptomus' },
                { icon: <CheckCircle size={16} />, text: 'No hidden fees or charges' },
              ].map((item, i) => (
                <li key={i} className="flex items-start gap-2.5">
                  <span className="text-accent-500 mt-0.5 flex-shrink-0">{item.icon}</span>
                  <span>{item.text}</span>
                </li>
              ))}
            </ul>
          </div>

          <div className="bg-amber-50 dark:bg-amber-900/10 rounded-2xl border border-amber-200 dark:border-amber-800/30 p-4">
            <div className="flex items-start gap-3">
              <AlertCircle size={18} className="text-amber-600 dark:text-amber-400 mt-0.5 flex-shrink-0" />
              <div>
                <h4 className="text-sm font-medium text-amber-800 dark:text-amber-300">Important</h4>
                <p className="text-xs text-amber-700 dark:text-amber-400 mt-1 leading-relaxed">
                  Crypto payments may take up to 30 minutes to confirm depending on network congestion.
                </p>
              </div>
            </div>
          </div>
        </motion.div>
      </div>

      {/* Deposits History */}
      <motion.div
        initial={{ opacity: 0, y: 8 }}
        animate={{ opacity: 1, y: 0 }}
        transition={{ delay: 0.25, duration: 0.3 }}
        className="bg-white dark:bg-dark-800 rounded-2xl border border-dark-100 dark:border-dark-700 shadow-soft dark:shadow-dark-soft overflow-hidden"
      >
        <div className="p-6 border-b border-dark-100 dark:border-dark-700">
          <div className="flex items-center gap-3">
            <div className="w-10 h-10 rounded-xl bg-blue-100 dark:bg-blue-900/30 flex items-center justify-center">
              <History size={20} className="text-blue-600 dark:text-blue-400" />
            </div>
            <div>
              <h2 className="text-lg font-semibold text-dark-900 dark:text-white">Deposit History</h2>
              <p className="text-sm text-dark-500 dark:text-dark-400">Your recent payment transactions</p>
            </div>
          </div>
        </div>

        {loadingDeposits ? (
          <div className="p-12 text-center">
            <Loader2 size={24} className="animate-spin text-primary-500 mx-auto mb-3" />
            <p className="text-sm text-dark-500 dark:text-dark-400">Loading deposits...</p>
          </div>
        ) : deposits.length === 0 ? (
          <div className="p-12 text-center">
            <div className="w-16 h-16 rounded-2xl bg-dark-100 dark:bg-dark-700 flex items-center justify-center mx-auto mb-4">
              <Wallet size={32} className="text-dark-400" />
            </div>
            <h3 className="text-lg font-medium text-dark-900 dark:text-white mb-1">No deposits yet</h3>
            <p className="text-sm text-dark-500 dark:text-dark-400">
              Make your first deposit to start placing orders
            </p>
          </div>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full">
              <thead>
                <tr className="bg-dark-50/70 dark:bg-dark-700/50">
                  {['ID', 'Date', 'Method', 'Amount', 'Status', 'Action'].map((header) => (
                    <th key={header} className="px-6 py-3 text-left text-xs font-semibold text-dark-500 dark:text-dark-400 uppercase tracking-wider">
                      {header}
                    </th>
                  ))}
                </tr>
              </thead>
              <tbody className="divide-y divide-dark-100 dark:divide-dark-700/50">
                {deposits.map((deposit, index) => {
                  const statusConfig = getStatusConfig(deposit.status);
                  return (
                    <motion.tr
                      key={deposit.id}
                      custom={index}
                      variants={rowVariants}
                      initial="hidden"
                      animate="visible"
                      className="group hover:bg-dark-50/50 dark:hover:bg-dark-700/30 transition-colors duration-150"
                    >
                      <td className="px-6 py-3.5 whitespace-nowrap">
                        <span className="text-sm font-semibold text-dark-900 dark:text-white">
                          #{deposit.id}
                        </span>
                      </td>
                      <td className="px-6 py-3.5 whitespace-nowrap">
                        <span className="text-sm text-dark-600 dark:text-dark-300">
                          {formatDateOnly(deposit.createdAt)}
                        </span>
                        <span className="block text-xs text-dark-400 dark:text-dark-500 mt-0.5">
                          {formatDate(deposit.createdAt, { hour: '2-digit', minute: '2-digit', second: '2-digit' })}
                        </span>
                      </td>
                      <td className="px-6 py-3.5 whitespace-nowrap">
                        <div className="flex items-center gap-2">
                          <Bitcoin size={16} className="text-dark-400" />
                          <span className="text-sm text-dark-600 dark:text-dark-400">
                            {deposit.paymentMethod}
                          </span>
                        </div>
                      </td>
                      <td className="px-6 py-3.5 whitespace-nowrap">
                        <span className="text-sm font-semibold text-dark-900 dark:text-white">
                          ${deposit.amount.toFixed(2)}
                        </span>
                      </td>
                      <td className="px-6 py-3.5 whitespace-nowrap">
                        <span className={`inline-flex items-center gap-1.5 px-2.5 py-1 text-xs font-semibold rounded-lg ${statusConfig.colors}`}>
                          {'pulse' in statusConfig && statusConfig.pulse && (
                            <span className="relative flex h-2 w-2">
                              <span className="absolute inline-flex h-full w-full animate-ping rounded-full bg-current opacity-40" />
                              <span className="relative inline-flex h-2 w-2 rounded-full bg-current" />
                            </span>
                          )}
                          {statusConfig.icon}
                          {statusConfig.label}
                        </span>
                      </td>
                      <td className="px-6 py-3.5 whitespace-nowrap">
                        {deposit.status === 'PENDING' && deposit.paymentUrl && (
                          <a
                            href={deposit.paymentUrl}
                            target="_blank"
                            rel="noopener noreferrer"
                            className="inline-flex items-center gap-1.5 text-sm font-medium text-primary-600 hover:text-primary-700 dark:text-primary-400 dark:hover:text-primary-300 transition-colors"
                          >
                            Complete Payment
                            <ExternalLink size={14} />
                          </a>
                        )}
                      </td>
                    </motion.tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        )}
      </motion.div>
    </motion.div>
  );
};
