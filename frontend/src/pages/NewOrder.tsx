import React, { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { orderAPI, serviceAPI } from '../services/api';
import { Service } from '../types';
import { useAuthStore } from '../store/authStore';
import { MassOrderModal } from '../components/MassOrderModal';
import {
  Package,
  Link as LinkIcon,
  Hash,
  DollarSign,
  AlertCircle,
  ChevronDown,
  Layers,
  Send,
  ArrowLeft,
  Info,
  MessageSquare,
  AlertTriangle,
  CheckCircle2,
} from 'lucide-react';

export const NewOrder: React.FC = () => {
  const navigate = useNavigate();
  const { user } = useAuthStore();
  const [services, setServices] = useState<Service[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [success, setSuccess] = useState<{ orderId: number; charge: string } | null>(null);
  const [showMassOrderModal, setShowMassOrderModal] = useState(false);
  const [orderType, setOrderType] = useState<'single' | 'mass'>('single');

  const [formData, setFormData] = useState({
    service: '',
    link: '',
    quantity: '',
    customComments: '',
    emojiType: '' as 'POSITIVE' | 'NEGATIVE' | '',
  });

  const [selectedService, setSelectedService] = useState<Service | null>(null);

  useEffect(() => {
    if (user?.role === 'ADMIN') {
      navigate('/admin/orders');
      return;
    }
    fetchServices();
  }, [user, navigate]);

  const fetchServices = async () => {
    try {
      const response = await serviceAPI.getServices();
      const servicesList = response?.data || response || [];
      setServices(Array.isArray(servicesList) ? servicesList : []);
    } catch (error) {
      console.error('Failed to fetch services:', error);
      setServices([]);
    }
  };

  const handleServiceChange = (e: React.ChangeEvent<HTMLSelectElement>) => {
    const serviceId = e.target.value;
    setFormData(prev => ({ ...prev, service: serviceId, customComments: '', emojiType: '' }));
    const service = services.find(s => s.id.toString() === serviceId);
    setSelectedService(service || null);
  };

  // Check if service requires custom comments (e.g., "Instagram Comments Custom")
  const requiresCustomComments = (service: Service | null): boolean => {
    if (!service) return false;
    const name = service.name?.toLowerCase() || '';
    return name.includes('custom') && name.includes('comment');
  };

  // Check if service requires emoji type selection (e.g., "Instagram Emoji Comments")
  const requiresEmojiType = (service: Service | null): boolean => {
    if (!service) return false;
    const name = service.name?.toLowerCase() || '';
    return name.includes('emoji') && name.includes('comment');
  };

  // Check if emoji type is selected
  const isEmojiTypeValid = (): boolean => {
    if (!requiresEmojiType(selectedService)) return true;
    return formData.emojiType === 'POSITIVE' || formData.emojiType === 'NEGATIVE';
  };

  const MAX_COMMENT_LENGTH = 2200; // Instagram comment limit

  // Parse comments and get validation info
  const getCommentsInfo = (): {
    lines: { text: string; lineNumber: number }[];
    count: number;
    invalidLines: { text: string; lineNumber: number }[];
    isValid: boolean;
    minOrder: number;
    maxOrder: number;
  } => {
    const minOrder = selectedService?.minOrder || selectedService?.min || 1;
    const maxOrder = selectedService?.maxOrder || selectedService?.max || 100000;

    if (!formData.customComments.trim()) {
      return { lines: [], count: 0, invalidLines: [], isValid: false, minOrder, maxOrder };
    }

    const lines = formData.customComments.split('\n');
    const nonEmptyLines = lines
      .map((line, index) => ({ text: line.trim(), lineNumber: index + 1 }))
      .filter(line => line.text.length > 0);

    const invalidLines = nonEmptyLines.filter(line => line.text.length > MAX_COMMENT_LENGTH);

    const count = nonEmptyLines.length;
    const isValid = count >= minOrder && count <= maxOrder && invalidLines.length === 0;

    return {
      lines: nonEmptyLines,
      count,
      invalidLines,
      isValid,
      minOrder,
      maxOrder
    };
  };

  // Get auto-calculated quantity for custom comments
  const getEffectiveQuantity = (): number => {
    if (requiresCustomComments(selectedService)) {
      return getCommentsInfo().count;
    }
    return parseInt(formData.quantity) || 0;
  };

  // Check if custom comments are valid
  const isCustomCommentsValid = (): boolean => {
    if (!requiresCustomComments(selectedService)) return true;
    return getCommentsInfo().isValid;
  };

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setError(null);
    setLoading(true);

    try {
      // For custom comments, use auto-calculated quantity from line count
      const quantity = requiresCustomComments(selectedService)
        ? getCommentsInfo().count
        : parseInt(formData.quantity);

      const orderData: any = {
        service: parseInt(formData.service),
        link: formData.link,
        quantity,
      };

      // Include custom comments or emoji type
      if (formData.customComments.trim()) {
        orderData.customComments = formData.customComments.trim();
      } else if (formData.emojiType) {
        orderData.customComments = `EMOJI:${formData.emojiType}`;
      }

      const response = await orderAPI.createOrder(orderData);
      const orderId = response?.data?.order || response?.order || response?.data?.id || response?.id;
      const charge = calculateCharge();

      // Show success message
      setSuccess({ orderId, charge });

      // Reset form but keep service selected for quick re-ordering
      setFormData(prev => ({
        ...prev,
        link: '',
        quantity: '',
        customComments: '',
        emojiType: '',
      }));

      // Auto-hide success after 5 seconds
      setTimeout(() => setSuccess(null), 5000);
    } catch (error: any) {
      setError(error.response?.data?.message || 'Failed to create order');
    } finally {
      setLoading(false);
    }
  };

  const calculateCharge = () => {
    const quantity = getEffectiveQuantity();
    if (!selectedService || quantity === 0) return '0.00';
    const price = selectedService.pricePer1000 || selectedService.rate || 0;
    const charge = (price * quantity) / 1000;
    return charge.toFixed(4);
  };

  return (
    <div className="max-w-2xl mx-auto space-y-6 animate-fade-in">
      {/* Header */}
      <div className="flex items-center gap-4">
        <button
          onClick={() => navigate('/orders')}
          className="w-10 h-10 rounded-xl bg-white dark:bg-dark-800 border border-dark-200 dark:border-dark-700 flex items-center justify-center text-dark-500 hover:text-dark-700 dark:text-dark-400 dark:hover:text-white transition-colors"
        >
          <ArrowLeft size={18} />
        </button>
        <div>
          <h1 className="text-2xl font-bold text-dark-900 dark:text-white">New Order</h1>
          <p className="text-dark-500 dark:text-dark-400">Create a new service order</p>
        </div>
      </div>

      {/* Order Type Selection */}
      <div className="bg-white dark:bg-dark-800 rounded-2xl border border-dark-100 dark:border-dark-700 p-6 shadow-soft dark:shadow-dark-soft">
        <h2 className="text-sm font-medium text-dark-700 dark:text-dark-300 mb-4">Order Type</h2>
        <div className="grid grid-cols-2 gap-4">
          <button
            type="button"
            onClick={() => setOrderType('single')}
            className={`p-4 rounded-xl border-2 transition-all ${
              orderType === 'single'
                ? 'border-primary-500 bg-primary-50 dark:bg-primary-900/20'
                : 'border-dark-200 dark:border-dark-600 hover:border-dark-300 dark:hover:border-dark-500'
            }`}
          >
            <div className="flex items-center gap-3">
              <div className={`w-10 h-10 rounded-lg flex items-center justify-center ${
                orderType === 'single'
                  ? 'bg-primary-100 dark:bg-primary-900/50'
                  : 'bg-dark-100 dark:bg-dark-700'
              }`}>
                <Package size={20} className={orderType === 'single' ? 'text-primary-600 dark:text-primary-400' : 'text-dark-500'} />
              </div>
              <div className="text-left">
                <p className={`font-medium ${orderType === 'single' ? 'text-primary-700 dark:text-primary-300' : 'text-dark-700 dark:text-dark-300'}`}>
                  Single Order
                </p>
                <p className="text-xs text-dark-500 dark:text-dark-400">Create one order</p>
              </div>
            </div>
          </button>
          <button
            type="button"
            onClick={() => {
              setOrderType('mass');
              setShowMassOrderModal(true);
            }}
            className={`p-4 rounded-xl border-2 transition-all ${
              orderType === 'mass'
                ? 'border-purple-500 bg-purple-50 dark:bg-purple-900/20'
                : 'border-dark-200 dark:border-dark-600 hover:border-dark-300 dark:hover:border-dark-500'
            }`}
          >
            <div className="flex items-center gap-3">
              <div className={`w-10 h-10 rounded-lg flex items-center justify-center ${
                orderType === 'mass'
                  ? 'bg-purple-100 dark:bg-purple-900/50'
                  : 'bg-dark-100 dark:bg-dark-700'
              }`}>
                <Layers size={20} className={orderType === 'mass' ? 'text-purple-600 dark:text-purple-400' : 'text-dark-500'} />
              </div>
              <div className="text-left">
                <p className={`font-medium ${orderType === 'mass' ? 'text-purple-700 dark:text-purple-300' : 'text-dark-700 dark:text-dark-300'}`}>
                  Mass Order
                </p>
                <p className="text-xs text-dark-500 dark:text-dark-400">Up to 100 at once</p>
              </div>
            </div>
          </button>
        </div>
      </div>

      {/* Order Form */}
      {orderType === 'single' && (
        <form onSubmit={handleSubmit} className="bg-white dark:bg-dark-800 rounded-2xl border border-dark-100 dark:border-dark-700 shadow-soft dark:shadow-dark-soft overflow-hidden">
          <div className="p-6 space-y-5">
            {/* Error */}
            {/* Error Message */}
            {error && (
              <div className="flex items-center gap-3 p-4 rounded-xl bg-red-50 dark:bg-red-900/20 border border-red-200 dark:border-red-800 text-red-700 dark:text-red-400">
                <AlertCircle size={20} className="flex-shrink-0" />
                <span className="text-sm">{error}</span>
              </div>
            )}

            {/* Success Message */}
            {success && (
              <div className="flex items-center justify-between p-4 rounded-xl bg-green-50 dark:bg-green-900/20 border border-green-200 dark:border-green-800 text-green-700 dark:text-green-400 animate-fade-in">
                <div className="flex items-center gap-3">
                  <CheckCircle2 size={20} className="flex-shrink-0" />
                  <div>
                    <p className="text-sm font-medium">Order #{success.orderId} created successfully!</p>
                    <p className="text-xs opacity-80">Charge: ${success.charge}</p>
                  </div>
                </div>
                <button
                  type="button"
                  onClick={() => setSuccess(null)}
                  className="text-green-500 hover:text-green-700 dark:hover:text-green-300 transition-colors"
                >
                  ✕
                </button>
              </div>
            )}

            {/* Service Selection */}
            <div className="space-y-2">
              <label className="block text-sm font-medium text-dark-700 dark:text-dark-300">
                Service
              </label>
              <div className="relative">
                <div className="absolute inset-y-0 left-0 pl-3 flex items-center pointer-events-none">
                  <Package size={18} className="text-dark-400" />
                </div>
                <select
                  value={formData.service}
                  onChange={handleServiceChange}
                  required
                  className="block w-full pl-10 pr-10 py-3 border border-dark-200 dark:border-dark-600 rounded-xl bg-white dark:bg-dark-700 text-dark-900 dark:text-white focus:outline-none focus:ring-2 focus:ring-primary-500/50 focus:border-primary-500 transition-all appearance-none"
                >
                  <option value="">Select a service</option>
                  {services.map(service => (
                    <option key={service.id} value={service.id}>
                      {service.name} - ${service.pricePer1000 || service.rate || '0'}/1000
                    </option>
                  ))}
                </select>
                <div className="absolute inset-y-0 right-0 pr-3 flex items-center pointer-events-none">
                  <ChevronDown size={18} className="text-dark-400" />
                </div>
              </div>
              {selectedService && (
                <div className="flex items-start gap-2 mt-2 p-3 rounded-lg bg-dark-50 dark:bg-dark-700/50">
                  <Info size={16} className="text-primary-500 flex-shrink-0 mt-0.5" />
                  <div className="text-sm text-dark-600 dark:text-dark-400">
                    <p>Min: {selectedService.minOrder || selectedService.min || 1} | Max: {(selectedService.maxOrder || selectedService.max || 100000).toLocaleString()}</p>
                    {selectedService.description && (
                      <p className="mt-1 text-dark-500">{selectedService.description}</p>
                    )}
                  </div>
                </div>
              )}
            </div>

            {/* Link */}
            <div className="space-y-2">
              <label className="block text-sm font-medium text-dark-700 dark:text-dark-300">
                Link
              </label>
              <div className="relative">
                <div className="absolute inset-y-0 left-0 pl-3 flex items-center pointer-events-none">
                  <LinkIcon size={18} className="text-dark-400" />
                </div>
                <input
                  type="url"
                  value={formData.link}
                  onChange={(e) => setFormData(prev => ({ ...prev, link: e.target.value }))}
                  required
                  placeholder="https://youtube.com/watch?v=... or https://instagram.com/p/..."
                  className="block w-full pl-10 pr-4 py-3 border border-dark-200 dark:border-dark-600 rounded-xl bg-white dark:bg-dark-700 text-dark-900 dark:text-white placeholder-dark-400 focus:outline-none focus:ring-2 focus:ring-primary-500/50 focus:border-primary-500 transition-all"
                />
              </div>
            </div>

            {/* Quantity - hidden for custom comments services */}
            {!requiresCustomComments(selectedService) && (
              <div className="space-y-2">
                <label className="block text-sm font-medium text-dark-700 dark:text-dark-300">
                  Quantity
                </label>
                <div className="relative">
                  <div className="absolute inset-y-0 left-0 pl-3 flex items-center pointer-events-none">
                    <Hash size={18} className="text-dark-400" />
                  </div>
                  <input
                    type="number"
                    value={formData.quantity}
                    onChange={(e) => setFormData(prev => ({ ...prev, quantity: e.target.value }))}
                    required
                    min={selectedService?.minOrder || selectedService?.min || 1}
                    max={selectedService?.maxOrder || selectedService?.max || 100000}
                    placeholder="Enter quantity"
                    className="block w-full pl-10 pr-4 py-3 border border-dark-200 dark:border-dark-600 rounded-xl bg-white dark:bg-dark-700 text-dark-900 dark:text-white placeholder-dark-400 focus:outline-none focus:ring-2 focus:ring-primary-500/50 focus:border-primary-500 transition-all"
                  />
                </div>
              </div>
            )}

            {/* Emoji Type Selection (for emoji comment services) */}
            {requiresEmojiType(selectedService) && (
              <div className="space-y-3">
                <label className="block text-sm font-medium text-dark-700 dark:text-dark-300">
                  Emoji Type
                </label>
                <div className="grid grid-cols-2 gap-4">
                  <button
                    type="button"
                    onClick={() => setFormData(prev => ({ ...prev, emojiType: 'POSITIVE' }))}
                    className={`p-4 rounded-xl border-2 transition-all ${
                      formData.emojiType === 'POSITIVE'
                        ? 'border-green-500 bg-green-50 dark:bg-green-900/20'
                        : 'border-dark-200 dark:border-dark-600 hover:border-green-300 dark:hover:border-green-700'
                    }`}
                  >
                    <div className="text-center">
                      <div className="text-2xl mb-2">😊 🔥 ❤️ 👍</div>
                      <p className={`font-medium ${
                        formData.emojiType === 'POSITIVE'
                          ? 'text-green-700 dark:text-green-300'
                          : 'text-dark-700 dark:text-dark-300'
                      }`}>
                        Positive
                      </p>
                      <p className="text-xs text-dark-500 dark:text-dark-400 mt-1">
                        Happy, love, fire
                      </p>
                    </div>
                  </button>
                  <button
                    type="button"
                    onClick={() => setFormData(prev => ({ ...prev, emojiType: 'NEGATIVE' }))}
                    className={`p-4 rounded-xl border-2 transition-all ${
                      formData.emojiType === 'NEGATIVE'
                        ? 'border-red-500 bg-red-50 dark:bg-red-900/20'
                        : 'border-dark-200 dark:border-dark-600 hover:border-red-300 dark:hover:border-red-700'
                    }`}
                  >
                    <div className="text-center">
                      <div className="text-2xl mb-2">😒 🙄 😡 👎</div>
                      <p className={`font-medium ${
                        formData.emojiType === 'NEGATIVE'
                          ? 'text-red-700 dark:text-red-300'
                          : 'text-dark-700 dark:text-dark-300'
                      }`}>
                        Negative
                      </p>
                      <p className="text-xs text-dark-500 dark:text-dark-400 mt-1">
                        Angry, annoyed, dislike
                      </p>
                    </div>
                  </button>
                </div>
              </div>
            )}

            {/* Custom Comments (for custom comment services) */}
            {requiresCustomComments(selectedService) && (() => {
              const commentsInfo = getCommentsInfo();
              const isCountValid = commentsInfo.count >= commentsInfo.minOrder && commentsInfo.count <= commentsInfo.maxOrder;
              const hasInvalidLines = commentsInfo.invalidLines.length > 0;

              return (
                <div className="space-y-3">
                  {/* Header with counter */}
                  <div className="flex items-center justify-between">
                    <label className="flex items-center gap-2 text-sm font-medium text-dark-700 dark:text-dark-300">
                      <MessageSquare size={16} />
                      Comments (1 per line)
                    </label>
                    <div className="flex items-center gap-2">
                      {commentsInfo.count > 0 && (
                        <span className={`inline-flex items-center gap-1.5 px-3 py-1 rounded-full text-sm font-medium ${
                          commentsInfo.isValid
                            ? 'bg-green-100 text-green-700 dark:bg-green-900/30 dark:text-green-400'
                            : 'bg-amber-100 text-amber-700 dark:bg-amber-900/30 dark:text-amber-400'
                        }`}>
                          {commentsInfo.isValid ? (
                            <CheckCircle2 size={14} />
                          ) : (
                            <AlertTriangle size={14} />
                          )}
                          {commentsInfo.count} comment{commentsInfo.count !== 1 ? 's' : ''}
                        </span>
                      )}
                    </div>
                  </div>

                  {/* Textarea */}
                  <div className="relative">
                    <textarea
                      value={formData.customComments}
                      onChange={(e) => setFormData(prev => ({ ...prev, customComments: e.target.value }))}
                      required
                      rows={10}
                      placeholder={`Enter your comments here, one per line:\n\nGreat content! 🔥\nLove this post! ❤️\nAmazing work!\nKeep it up! 👍\nSo inspiring! ✨`}
                      className={`block w-full px-4 py-3 border-2 rounded-xl bg-white dark:bg-dark-700 text-dark-900 dark:text-white placeholder-dark-400 focus:outline-none transition-all resize-none font-mono text-sm leading-relaxed ${
                        commentsInfo.count === 0
                          ? 'border-dark-200 dark:border-dark-600 focus:border-primary-500'
                          : commentsInfo.isValid
                            ? 'border-green-300 dark:border-green-600 focus:border-green-500'
                            : 'border-amber-300 dark:border-amber-600 focus:border-amber-500'
                      }`}
                    />
                  </div>

                  {/* Validation messages */}
                  <div className="space-y-2">
                    {/* Character limit warning */}
                    {hasInvalidLines && (
                      <div className="flex items-start gap-2 p-3 rounded-xl bg-red-50 dark:bg-red-900/20 border border-red-200 dark:border-red-800">
                        <AlertCircle size={16} className="flex-shrink-0 mt-0.5 text-red-500" />
                        <div className="text-sm text-red-700 dark:text-red-400">
                          <p className="font-medium">Comments too long (max {MAX_COMMENT_LENGTH} characters):</p>
                          <ul className="mt-1 space-y-0.5">
                            {commentsInfo.invalidLines.slice(0, 3).map(line => (
                              <li key={line.lineNumber}>
                                Line {line.lineNumber}: {line.text.length} characters
                              </li>
                            ))}
                            {commentsInfo.invalidLines.length > 3 && (
                              <li>...and {commentsInfo.invalidLines.length - 3} more</li>
                            )}
                          </ul>
                        </div>
                      </div>
                    )}

                    {/* Count validation */}
                    {commentsInfo.count > 0 && !isCountValid && (
                      <div className="flex items-start gap-2 p-3 rounded-xl bg-amber-50 dark:bg-amber-900/20 border border-amber-200 dark:border-amber-800">
                        <AlertTriangle size={16} className="flex-shrink-0 mt-0.5 text-amber-500" />
                        <p className="text-sm text-amber-700 dark:text-amber-400">
                          {commentsInfo.count < commentsInfo.minOrder
                            ? `Minimum ${commentsInfo.minOrder} comments required. Add ${commentsInfo.minOrder - commentsInfo.count} more.`
                            : `Maximum ${commentsInfo.maxOrder} comments allowed. Remove ${commentsInfo.count - commentsInfo.maxOrder}.`
                          }
                        </p>
                      </div>
                    )}

                    {/* Info box */}
                    <div className="flex items-start gap-2 p-3 rounded-xl bg-dark-50 dark:bg-dark-700/50 border border-dark-200 dark:border-dark-600">
                      <Info size={16} className="flex-shrink-0 mt-0.5 text-dark-400" />
                      <div className="text-sm text-dark-600 dark:text-dark-400">
                        <p>Each line = 1 comment • Min: {commentsInfo.minOrder} • Max: {commentsInfo.maxOrder} • Limit: {MAX_COMMENT_LENGTH} chars per comment</p>
                      </div>
                    </div>
                  </div>
                </div>
              );
            })()}
          </div>

          {/* Total & Actions */}
          <div className="px-6 py-4 bg-dark-50 dark:bg-dark-700/50 border-t border-dark-100 dark:border-dark-700">
            <div className="flex items-center justify-between mb-4">
              <div className="flex items-center gap-2 text-dark-600 dark:text-dark-400">
                <DollarSign size={18} />
                <span className="text-sm font-medium">Total Charge</span>
              </div>
              <span className="text-2xl font-bold text-dark-900 dark:text-white">${calculateCharge()}</span>
            </div>

            <div className="flex gap-3">
              <button
                type="button"
                onClick={() => navigate('/orders')}
                className="flex-1 py-3 px-4 rounded-xl text-dark-700 dark:text-dark-300 font-medium bg-white dark:bg-dark-600 border border-dark-200 dark:border-dark-500 hover:bg-dark-50 dark:hover:bg-dark-500 transition-colors"
              >
                Cancel
              </button>
              <button
                type="submit"
                disabled={
                  loading ||
                  !formData.service ||
                  !formData.link ||
                  (!requiresCustomComments(selectedService) && !formData.quantity) ||
                  !isCustomCommentsValid() ||
                  !isEmojiTypeValid()
                }
                className="flex-1 flex items-center justify-center gap-2 py-3 px-4 rounded-xl text-white font-medium bg-primary-600 hover:bg-primary-700 disabled:opacity-50 disabled:cursor-not-allowed transition-colors shadow-soft"
              >
                {loading ? (
                  <>
                    <svg className="animate-spin h-5 w-5" viewBox="0 0 24 24">
                      <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4" fill="none" />
                      <path className="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4zm2 5.291A7.962 7.962 0 014 12H0c0 3.042 1.135 5.824 3 7.938l3-2.647z" />
                    </svg>
                    <span>Creating...</span>
                  </>
                ) : (
                  <>
                    <Send size={18} />
                    <span>Create Order</span>
                  </>
                )}
              </button>
            </div>
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
  );
};
