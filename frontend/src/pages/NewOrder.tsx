import React, { useEffect, useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { motion, AnimatePresence } from 'framer-motion';
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
  Loader2,
} from 'lucide-react';

export const NewOrder: React.FC = () => {
  const navigate = useNavigate();
  const user = useAuthStore((s) => s.user);
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
    genderType: '' as 'MALE' | 'FEMALE' | '',
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

  const getBaseName = (name: string): string =>
    name.replace(/\s*\[(male|female)\]\s*/gi, ' ').replace(/\s+/g, ' ').trim();

  const isGenderPaired = (service: Service): boolean => {
    const name = service.name;
    if (!/\[male\]|\[female\]/i.test(name) || /mix/i.test(name)) return false;
    const base = getBaseName(name).toLowerCase();
    const hasMale = services.some(s => /\[male\]/i.test(s.name) && !(/mix/i.test(s.name)) && getBaseName(s.name).toLowerCase() === base);
    const hasFemale = services.some(s => /\[female\]/i.test(s.name) && !(/mix/i.test(s.name)) && getBaseName(s.name).toLowerCase() === base);
    return hasMale && hasFemale;
  };

  const getGenderPartner = (service: Service, gender: 'MALE' | 'FEMALE'): Service | null => {
    const base = getBaseName(service.name).toLowerCase();
    const pattern = gender === 'MALE' ? /\[male\]/i : /\[female\]/i;
    return services.find(s =>
      pattern.test(s.name) && !(/mix/i.test(s.name)) &&
      getBaseName(s.name).toLowerCase() === base
    ) || null;
  };

  const displayServices = useMemo((): Service[] => {
    const seen = new Set<string>();
    const result: Service[] = [];
    services.forEach(service => {
      if (!isGenderPaired(service)) {
        result.push(service);
        return;
      }
      const base = getBaseName(service.name).toLowerCase();
      if (seen.has(base)) return;
      seen.add(base);
      if (/\[male\]/i.test(service.name)) {
        result.push(service);
      } else {
        const maleService = getGenderPartner(service, 'MALE');
        if (maleService) result.push(maleService);
      }
    });
    return result;
  }, [services]);

  const handleServiceChange = (e: React.ChangeEvent<HTMLSelectElement>) => {
    const serviceId = e.target.value;
    const service = services.find(s => s.id.toString() === serviceId);
    if (service && isGenderPaired(service)) {
      const maleService = getGenderPartner(service, 'MALE') || service;
      setSelectedService(maleService);
      setFormData(prev => ({ ...prev, service: maleService.id.toString(), customComments: '', genderType: 'MALE' }));
    } else {
      setSelectedService(service || null);
      setFormData(prev => ({ ...prev, service: serviceId, customComments: '', genderType: '' }));
    }
  };

  const handleGenderToggle = (gender: 'MALE' | 'FEMALE') => {
    if (!selectedService) return;
    const partner = getGenderPartner(selectedService, gender);
    if (partner) {
      setSelectedService(partner);
      setFormData(prev => ({ ...prev, genderType: gender }));
    }
  };

  const requiresCustomComments = (service: Service | null): boolean => {
    if (!service) return false;
    const name = service.name?.toLowerCase() || '';
    return name.includes('custom') && name.includes('comment');
  };

  const requiresGenderType = (service: Service | null): boolean => {
    if (!service) return false;
    return isGenderPaired(service);
  };

  const isGenderTypeValid = (): boolean => {
    if (!requiresGenderType(selectedService)) return true;
    return formData.genderType === 'MALE' || formData.genderType === 'FEMALE';
  };

  const MAX_COMMENT_LENGTH = 2200;

  const commentsInfo = useMemo(() => {
    const minOrder = selectedService?.minOrder || selectedService?.min || 1;
    const maxOrder = selectedService?.maxOrder || selectedService?.max || 100000;

    if (!formData.customComments.trim()) {
      return { lines: [] as { text: string; lineNumber: number }[], count: 0, invalidLines: [] as { text: string; lineNumber: number }[], isValid: false, minOrder, maxOrder };
    }

    const lines = formData.customComments.split('\n');
    const nonEmptyLines = lines
      .map((line: string, index: number) => ({ text: line.trim(), lineNumber: index + 1 }))
      .filter((line: { text: string }) => line.text.length > 0);

    const invalidLines = nonEmptyLines.filter((line: { text: string }) => line.text.length > MAX_COMMENT_LENGTH);

    const count = nonEmptyLines.length;
    const isValid = count >= minOrder && count <= maxOrder && invalidLines.length === 0;

    return { lines: nonEmptyLines, count, invalidLines, isValid, minOrder, maxOrder };
  }, [formData.customComments, selectedService]);

  const getEffectiveQuantity = (): number => {
    if (requiresCustomComments(selectedService)) {
      return commentsInfo.count;
    }
    return parseInt(formData.quantity) || 0;
  };

  const isCustomCommentsValid = (): boolean => {
    if (!requiresCustomComments(selectedService)) return true;
    return commentsInfo.isValid;
  };

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setError(null);
    setLoading(true);

    try {
      const quantity = requiresCustomComments(selectedService)
        ? commentsInfo.count
        : parseInt(formData.quantity);

      const orderData: any = {
        service: selectedService!.id,
        link: formData.link,
        quantity,
      };

      if (formData.customComments.trim() && formData.genderType) {
        orderData.customComments = `GENDER:${formData.genderType}\n${formData.customComments.trim()}`;
      } else if (formData.customComments.trim()) {
        orderData.customComments = formData.customComments.trim();
      } else if (formData.genderType) {
        orderData.customComments = `GENDER:${formData.genderType}`;
      }

      const response = await orderAPI.createOrder(orderData);
      const orderId = response?.data?.order || response?.order || response?.data?.id || response?.id;
      const charge = calculateCharge();

      setSuccess({ orderId, charge });

      if (selectedService && isGenderPaired(selectedService)) {
        const maleRep = getGenderPartner(selectedService, 'MALE') || selectedService;
        setSelectedService(maleRep);
      }
      setFormData(prev => ({
        ...prev,
        link: '',
        quantity: '',
        customComments: '',
        genderType: '',
      }));

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

  // Check if form is ready for submission
  const isFormValid = formData.service && formData.link &&
    (requiresCustomComments(selectedService) ? isCustomCommentsValid() : !!formData.quantity) &&
    isGenderTypeValid();

  return (
    <motion.div
      className="max-w-2xl mx-auto space-y-6"
      initial={{ opacity: 0, y: 12 }}
      animate={{ opacity: 1, y: 0 }}
      transition={{ duration: 0.4, ease: 'easeOut' }}
    >
      {/* Header */}
      <div className="flex items-center gap-4">
        <button
          onClick={() => navigate('/orders')}
          className="w-10 h-10 rounded-xl bg-white dark:bg-dark-800 border border-dark-200 dark:border-dark-700 flex items-center justify-center text-dark-500 hover:text-dark-700 dark:text-dark-400 dark:hover:text-white transition-all duration-200 hover:shadow-soft"
        >
          <ArrowLeft size={18} />
        </button>
        <div>
          <h1 className="text-2xl font-bold text-dark-900 dark:text-white">New Order</h1>
          <p className="text-dark-500 dark:text-dark-400">Create a new service order</p>
        </div>
      </div>

      {/* Order Type Selection */}
      <motion.div
        initial={{ opacity: 0, y: 8 }}
        animate={{ opacity: 1, y: 0 }}
        transition={{ delay: 0.1, duration: 0.3 }}
        className="bg-white dark:bg-dark-800 rounded-2xl border border-dark-100 dark:border-dark-700 p-4 sm:p-6 shadow-soft dark:shadow-dark-soft"
      >
        <h2 className="text-sm font-medium text-dark-700 dark:text-dark-300 mb-4">Order Type</h2>
        <div className="grid grid-cols-1 sm:grid-cols-2 gap-3 sm:gap-4">
          <button
            type="button"
            onClick={() => setOrderType('single')}
            className={`p-4 rounded-xl border-2 transition-all duration-200 ${
              orderType === 'single'
                ? 'border-primary-500 bg-primary-50 dark:bg-primary-900/20 shadow-sm shadow-primary-500/10'
                : 'border-dark-200 dark:border-dark-600 hover:border-dark-300 dark:hover:border-dark-500 hover:shadow-soft'
            }`}
          >
            <div className="flex items-center gap-3">
              <div className={`w-10 h-10 rounded-lg flex items-center justify-center transition-colors duration-200 ${
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
            className={`p-4 rounded-xl border-2 transition-all duration-200 ${
              orderType === 'mass'
                ? 'border-purple-500 bg-purple-50 dark:bg-purple-900/20 shadow-sm shadow-purple-500/10'
                : 'border-dark-200 dark:border-dark-600 hover:border-dark-300 dark:hover:border-dark-500 hover:shadow-soft'
            }`}
          >
            <div className="flex items-center gap-3">
              <div className={`w-10 h-10 rounded-lg flex items-center justify-center transition-colors duration-200 ${
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
      </motion.div>

      {/* Order Form */}
      <AnimatePresence mode="wait">
        {orderType === 'single' && (
          <motion.form
            key="single-form"
            onSubmit={handleSubmit}
            initial={{ opacity: 0, y: 8 }}
            animate={{ opacity: 1, y: 0 }}
            exit={{ opacity: 0, y: -8 }}
            transition={{ delay: 0.15, duration: 0.3 }}
            className="bg-white dark:bg-dark-800 rounded-2xl border border-dark-100 dark:border-dark-700 shadow-soft dark:shadow-dark-soft overflow-hidden"
          >
            <div className="p-6 space-y-5">
              {/* Error Message */}
              <AnimatePresence>
                {error && (
                  <motion.div
                    initial={{ opacity: 0, height: 0 }}
                    animate={{ opacity: 1, height: 'auto' }}
                    exit={{ opacity: 0, height: 0 }}
                    transition={{ duration: 0.2 }}
                    className="overflow-hidden"
                  >
                    <div className="flex items-center gap-3 p-4 rounded-xl bg-red-50 dark:bg-red-900/20 border border-red-200 dark:border-red-800 text-red-700 dark:text-red-400">
                      <AlertCircle size={20} className="flex-shrink-0" />
                      <span className="text-sm">{error}</span>
                    </div>
                  </motion.div>
                )}
              </AnimatePresence>

              {/* Success Message */}
              <AnimatePresence>
                {success && (
                  <motion.div
                    initial={{ opacity: 0, scale: 0.95 }}
                    animate={{ opacity: 1, scale: 1 }}
                    exit={{ opacity: 0, scale: 0.95 }}
                    transition={{ duration: 0.25, ease: 'easeOut' }}
                  >
                    <div className="flex items-center justify-between p-4 rounded-xl bg-emerald-50 dark:bg-emerald-900/20 border border-emerald-200 dark:border-emerald-800 text-emerald-700 dark:text-emerald-400">
                      <div className="flex items-center gap-3">
                        <div className="flex items-center justify-center w-8 h-8 rounded-full bg-emerald-100 dark:bg-emerald-900/50">
                          <CheckCircle2 size={18} />
                        </div>
                        <div>
                          <p className="text-sm font-medium">Order #{success.orderId} created!</p>
                          <p className="text-xs opacity-80">Charge: ${success.charge}</p>
                        </div>
                      </div>
                      <button
                        type="button"
                        onClick={() => setSuccess(null)}
                        className="text-emerald-500 hover:text-emerald-700 dark:hover:text-emerald-300 transition-colors p-1"
                      >
                        <span className="sr-only">Dismiss</span>
                        &times;
                      </button>
                    </div>
                  </motion.div>
                )}
              </AnimatePresence>

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
                    className="block w-full pl-10 pr-10 py-3 border border-dark-200 dark:border-dark-600 rounded-xl bg-white dark:bg-dark-700 text-dark-900 dark:text-white focus:outline-none focus:ring-2 focus:ring-primary-500/30 focus:border-primary-500 transition-all duration-200 appearance-none"
                  >
                    <option value="">Select a service</option>
                    {displayServices.map(service => (
                      <option key={service.id} value={service.id}>
                        {isGenderPaired(service) ? getBaseName(service.name) : service.name} - ${service.pricePer1000 || service.rate || '0'}/1000
                      </option>
                    ))}
                  </select>
                  <div className="absolute inset-y-0 right-0 pr-3 flex items-center pointer-events-none">
                    <ChevronDown size={18} className="text-dark-400" />
                  </div>
                </div>
                <AnimatePresence>
                  {selectedService && (
                    <motion.div
                      initial={{ opacity: 0, height: 0 }}
                      animate={{ opacity: 1, height: 'auto' }}
                      exit={{ opacity: 0, height: 0 }}
                      transition={{ duration: 0.2 }}
                      className="overflow-hidden"
                    >
                      <div className="flex items-start gap-2 mt-2 p-3 rounded-lg bg-dark-50 dark:bg-dark-700/50">
                        <Info size={16} className="text-primary-500 flex-shrink-0 mt-0.5" />
                        <div className="text-sm text-dark-600 dark:text-dark-400">
                          <p>Min: {selectedService.minOrder || selectedService.min || 1} | Max: {(selectedService.maxOrder || selectedService.max || 100000).toLocaleString()}</p>
                          {selectedService.description && (
                            <p className="mt-1 text-dark-500">{selectedService.description}</p>
                          )}
                        </div>
                      </div>
                    </motion.div>
                  )}
                </AnimatePresence>
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
                    placeholder="https://instagram.com/p/..."
                    className="block w-full pl-10 pr-4 py-3 border border-dark-200 dark:border-dark-600 rounded-xl bg-white dark:bg-dark-700 text-dark-900 dark:text-white placeholder-dark-400 focus:outline-none focus:ring-2 focus:ring-primary-500/30 focus:border-primary-500 transition-all duration-200"
                  />
                </div>
              </div>

              {/* Quantity */}
              {!requiresCustomComments(selectedService) && (
                <motion.div
                  initial={{ opacity: 0 }}
                  animate={{ opacity: 1 }}
                  className="space-y-2"
                >
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
                      className="block w-full pl-10 pr-4 py-3 border border-dark-200 dark:border-dark-600 rounded-xl bg-white dark:bg-dark-700 text-dark-900 dark:text-white placeholder-dark-400 focus:outline-none focus:ring-2 focus:ring-primary-500/30 focus:border-primary-500 transition-all duration-200"
                    />
                  </div>
                </motion.div>
              )}

              {/* Gender Type Selection */}
              <AnimatePresence>
                {requiresGenderType(selectedService) && (
                  <motion.div
                    initial={{ opacity: 0, height: 0 }}
                    animate={{ opacity: 1, height: 'auto' }}
                    exit={{ opacity: 0, height: 0 }}
                    transition={{ duration: 0.25 }}
                    className="overflow-hidden"
                  >
                    <div className="space-y-3">
                      <label className="block text-sm font-medium text-dark-700 dark:text-dark-300">
                        Gender Type
                      </label>
                      <div className="grid grid-cols-1 sm:grid-cols-2 gap-3 sm:gap-4">
                        <button
                          type="button"
                          onClick={() => handleGenderToggle('MALE')}
                          className={`p-4 rounded-xl border-2 transition-all duration-200 ${
                            formData.genderType === 'MALE'
                              ? 'border-blue-500 bg-blue-50 dark:bg-blue-900/20 shadow-sm shadow-blue-500/10'
                              : 'border-dark-200 dark:border-dark-600 hover:border-blue-300 dark:hover:border-blue-700 hover:shadow-soft'
                          }`}
                        >
                          <div className="text-center">
                            <div className="text-2xl mb-2">👨 🧔 👦</div>
                            <p className={`font-medium ${
                              formData.genderType === 'MALE'
                                ? 'text-blue-700 dark:text-blue-300'
                                : 'text-dark-700 dark:text-dark-300'
                            }`}>
                              Male
                            </p>
                            <p className="text-xs text-dark-500 dark:text-dark-400 mt-1">
                              Male profiles
                            </p>
                          </div>
                        </button>
                        <button
                          type="button"
                          onClick={() => handleGenderToggle('FEMALE')}
                          className={`p-4 rounded-xl border-2 transition-all duration-200 ${
                            formData.genderType === 'FEMALE'
                              ? 'border-pink-500 bg-pink-50 dark:bg-pink-900/20 shadow-sm shadow-pink-500/10'
                              : 'border-dark-200 dark:border-dark-600 hover:border-pink-300 dark:hover:border-pink-700 hover:shadow-soft'
                          }`}
                        >
                          <div className="text-center">
                            <div className="text-2xl mb-2">👩 💁 👧</div>
                            <p className={`font-medium ${
                              formData.genderType === 'FEMALE'
                                ? 'text-pink-700 dark:text-pink-300'
                                : 'text-dark-700 dark:text-dark-300'
                            }`}>
                              Female
                            </p>
                            <p className="text-xs text-dark-500 dark:text-dark-400 mt-1">
                              Female profiles
                            </p>
                          </div>
                        </button>
                      </div>
                    </div>
                  </motion.div>
                )}
              </AnimatePresence>

              {/* Custom Comments */}
              {requiresCustomComments(selectedService) && (() => {
                const isCountValid = commentsInfo.count >= commentsInfo.minOrder && commentsInfo.count <= commentsInfo.maxOrder;
                const hasInvalidLines = commentsInfo.invalidLines.length > 0;

                return (
                  <motion.div
                    initial={{ opacity: 0, height: 0 }}
                    animate={{ opacity: 1, height: 'auto' }}
                    className="space-y-3 overflow-hidden"
                  >
                    {/* Header with counter */}
                    <div className="flex items-center justify-between">
                      <label className="flex items-center gap-2 text-sm font-medium text-dark-700 dark:text-dark-300">
                        <MessageSquare size={16} />
                        Comments (1 per line)
                      </label>
                      <div className="flex items-center gap-2">
                        {commentsInfo.count > 0 && (
                          <span className={`inline-flex items-center gap-1.5 px-3 py-1 rounded-full text-sm font-medium transition-colors duration-200 ${
                            commentsInfo.isValid
                              ? 'bg-emerald-100 text-emerald-700 dark:bg-emerald-900/30 dark:text-emerald-400'
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
                        className={`block w-full px-4 py-3 border-2 rounded-xl bg-white dark:bg-dark-700 text-dark-900 dark:text-white placeholder-dark-400 focus:outline-none transition-all duration-200 resize-none font-mono text-sm leading-relaxed ${
                          commentsInfo.count === 0
                            ? 'border-dark-200 dark:border-dark-600 focus:border-primary-500'
                            : commentsInfo.isValid
                              ? 'border-emerald-300 dark:border-emerald-600 focus:border-emerald-500'
                              : 'border-amber-300 dark:border-amber-600 focus:border-amber-500'
                        }`}
                      />
                    </div>

                    {/* Validation messages */}
                    <div className="space-y-2">
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

                      <div className="flex items-start gap-2 p-3 rounded-xl bg-dark-50 dark:bg-dark-700/50 border border-dark-200 dark:border-dark-600">
                        <Info size={16} className="flex-shrink-0 mt-0.5 text-dark-400" />
                        <div className="text-sm text-dark-600 dark:text-dark-400">
                          <p>Each line = 1 comment | Min: {commentsInfo.minOrder} | Max: {commentsInfo.maxOrder} | Limit: {MAX_COMMENT_LENGTH} chars per comment</p>
                        </div>
                      </div>
                    </div>
                  </motion.div>
                );
              })()}
            </div>

            {/* Total & Actions */}
            <div className="px-6 py-4 bg-dark-50/50 dark:bg-dark-700/30 border-t border-dark-100 dark:border-dark-700">
              <div className="flex items-center justify-between mb-4">
                <div className="flex items-center gap-2 text-dark-600 dark:text-dark-400">
                  <DollarSign size={18} />
                  <span className="text-sm font-medium">Total Charge</span>
                </div>
                <motion.span
                  key={calculateCharge()}
                  initial={{ scale: 1.1, opacity: 0.7 }}
                  animate={{ scale: 1, opacity: 1 }}
                  transition={{ duration: 0.2 }}
                  className="text-2xl font-bold text-dark-900 dark:text-white"
                >
                  ${calculateCharge()}
                </motion.span>
              </div>

              <div className="flex gap-3">
                <button
                  type="button"
                  onClick={() => navigate('/orders')}
                  className="flex-1 py-3 px-4 rounded-xl text-dark-700 dark:text-dark-300 font-medium bg-white dark:bg-dark-600 border border-dark-200 dark:border-dark-500 hover:bg-dark-50 dark:hover:bg-dark-500 transition-all duration-200"
                >
                  Cancel
                </button>
                <button
                  type="submit"
                  disabled={loading || !isFormValid}
                  className="flex-1 flex items-center justify-center gap-2 py-3 px-4 rounded-xl text-white font-medium bg-primary-600 hover:bg-primary-700 disabled:opacity-50 disabled:cursor-not-allowed transition-all duration-200 shadow-soft hover:shadow-lg hover:-translate-y-0.5 disabled:hover:translate-y-0 disabled:hover:shadow-soft"
                >
                  {loading ? (
                    <>
                      <Loader2 size={18} className="animate-spin" />
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
          </motion.form>
        )}
      </AnimatePresence>

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
    </motion.div>
  );
};
