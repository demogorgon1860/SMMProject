import React, { useState } from 'react';
import { useNavigate, Link } from 'react-router-dom';
import { useAuthStore } from '../../store/authStore';
import { ThemeToggle } from '../ui/ThemeToggle';
import { motion, easeOut } from 'framer-motion';
import { Eye, EyeOff, LogIn, User, Lock, AlertCircle, Mail, FileText, MessageCircle } from 'lucide-react';

const containerVariants = {
  hidden: { opacity: 0 },
  visible: {
    opacity: 1,
    transition: {
      duration: 0.6,
      staggerChildren: 0.08,
    },
  },
};

const itemVariants = {
  hidden: { opacity: 0, y: 20 },
  visible: {
    opacity: 1,
    y: 0,
    transition: { duration: 0.5, ease: easeOut },
  },
};

const buttonVariants = {
  hover: { scale: 1.01, transition: { duration: 0.2 } },
  tap: { scale: 0.98 },
};

export const LoginForm: React.FC = () => {
  const navigate = useNavigate();
  const { login, isLoading, error, clearError } = useAuthStore();

  const [formData, setFormData] = useState({
    username: '',
    password: '',
  });
  const [showPassword, setShowPassword] = useState(false);

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    try {
      await login(formData.username, formData.password);
      navigate('/dashboard');
    } catch (error) {
      console.error('Login error:', error);
    }
  };

  const handleChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    setFormData(prev => ({
      ...prev,
      [e.target.name]: e.target.value,
    }));
    if (error) clearError();
  };

  return (
    <div className="min-h-screen flex items-center justify-center bg-gradient-to-br from-dark-50 via-dark-100 to-primary-50 dark:from-dark-950 dark:via-dark-900 dark:to-primary-950/50 transition-colors duration-500 px-4 relative overflow-hidden">
      {/* Animated background orbs */}
      <div className="absolute inset-0 overflow-hidden pointer-events-none">
        <div className="absolute -top-40 -right-40 w-80 h-80 bg-primary-400/20 dark:bg-primary-500/10 rounded-full blur-3xl animate-pulse-soft" />
        <div className="absolute -bottom-40 -left-40 w-80 h-80 bg-accent-400/20 dark:bg-accent-500/10 rounded-full blur-3xl animate-pulse-soft" style={{ animationDelay: '1s' }} />
        <div className="absolute top-1/2 left-1/2 -translate-x-1/2 -translate-y-1/2 w-96 h-96 bg-primary-300/10 dark:bg-primary-600/5 rounded-full blur-3xl animate-pulse-soft" style={{ animationDelay: '0.5s' }} />
      </div>

      {/* Theme Toggle - Floating */}
      <motion.div
        className="fixed top-4 right-4 z-50"
        initial={{ opacity: 0, y: -10 }}
        animate={{ opacity: 1, y: 0 }}
        transition={{ delay: 0.8, duration: 0.4 }}
      >
        <ThemeToggle />
      </motion.div>

      <motion.div
        className="w-full max-w-md relative z-10"
        variants={containerVariants}
        initial="hidden"
        animate="visible"
      >
        {/* Logo and Title */}
        <motion.div className="text-center mb-8" variants={itemVariants}>
          <motion.img
            src="/logo-v2.png"
            alt="SMM World"
            className="h-16 w-auto mx-auto mb-4"
            initial={{ opacity: 0, scale: 0.8 }}
            animate={{ opacity: 1, scale: 1 }}
            transition={{ duration: 0.5, ease: easeOut }}
          />
          <h1 className="text-2xl font-bold text-dark-900 dark:text-white">
            Welcome back
          </h1>
          <p className="text-dark-500 dark:text-dark-400 mt-2">
            Sign in to your SMM World account
          </p>
        </motion.div>

        {/* Login Card - Glassmorphism */}
        <motion.div
          className="bg-white/70 dark:bg-dark-800/70 backdrop-blur-xl rounded-2xl shadow-soft-lg dark:shadow-dark-lg border border-white/50 dark:border-dark-700/50 p-8"
          variants={itemVariants}
        >
          <form onSubmit={handleSubmit} className="space-y-6">
            {/* Error Alert */}
            {error && (
              <motion.div
                className="flex items-center gap-3 p-4 rounded-xl bg-red-50/80 dark:bg-red-900/20 border border-red-200/50 dark:border-red-800/50 text-red-700 dark:text-red-400 backdrop-blur-sm"
                initial={{ opacity: 0, y: -10, height: 0 }}
                animate={{ opacity: 1, y: 0, height: 'auto' }}
                transition={{ duration: 0.3 }}
              >
                <AlertCircle size={20} className="flex-shrink-0" />
                <span className="text-sm">{error}</span>
              </motion.div>
            )}

            {/* Username Field */}
            <motion.div className="space-y-2" variants={itemVariants}>
              <label htmlFor="username" className="block text-sm font-medium text-dark-700 dark:text-dark-300">
                Username
              </label>
              <div className="relative group">
                <div className="absolute inset-y-0 left-0 pl-3 flex items-center pointer-events-none">
                  <User size={18} className="text-dark-400 group-focus-within:text-primary-500 transition-colors duration-200" />
                </div>
                <input
                  id="username"
                  name="username"
                  type="text"
                  autoComplete="username"
                  required
                  value={formData.username}
                  onChange={handleChange}
                  className="block w-full pl-10 pr-4 py-3 border border-dark-200/70 dark:border-dark-600/70 rounded-xl bg-white/50 dark:bg-dark-700/50 text-dark-900 dark:text-white placeholder-dark-400 focus:outline-none focus:ring-2 focus:ring-primary-500/30 focus:border-primary-500 dark:focus:border-primary-400 transition-all duration-200 backdrop-blur-sm"
                  placeholder="Enter your username"
                />
              </div>
            </motion.div>

            {/* Password Field */}
            <motion.div className="space-y-2" variants={itemVariants}>
              <label htmlFor="password" className="block text-sm font-medium text-dark-700 dark:text-dark-300">
                Password
              </label>
              <div className="relative group">
                <div className="absolute inset-y-0 left-0 pl-3 flex items-center pointer-events-none">
                  <Lock size={18} className="text-dark-400 group-focus-within:text-primary-500 transition-colors duration-200" />
                </div>
                <input
                  id="password"
                  name="password"
                  type={showPassword ? 'text' : 'password'}
                  autoComplete="current-password"
                  required
                  value={formData.password}
                  onChange={handleChange}
                  className="block w-full pl-10 pr-12 py-3 border border-dark-200/70 dark:border-dark-600/70 rounded-xl bg-white/50 dark:bg-dark-700/50 text-dark-900 dark:text-white placeholder-dark-400 focus:outline-none focus:ring-2 focus:ring-primary-500/30 focus:border-primary-500 dark:focus:border-primary-400 transition-all duration-200 backdrop-blur-sm"
                  placeholder="Enter your password"
                />
                <button
                  type="button"
                  onClick={() => setShowPassword(!showPassword)}
                  className="absolute inset-y-0 right-0 pr-3 flex items-center text-dark-400 hover:text-dark-600 dark:hover:text-dark-300 transition-colors"
                >
                  {showPassword ? <EyeOff size={18} /> : <Eye size={18} />}
                </button>
              </div>
            </motion.div>

            {/* Submit Button */}
            <motion.div variants={itemVariants}>
              <motion.button
                type="submit"
                disabled={isLoading}
                variants={buttonVariants}
                whileHover={!isLoading ? 'hover' : undefined}
                whileTap={!isLoading ? 'tap' : undefined}
                className="w-full flex items-center justify-center gap-2 py-3 px-4 rounded-xl text-white font-medium bg-gradient-to-r from-primary-600 to-primary-700 hover:from-primary-700 hover:to-primary-800 focus:outline-none focus:ring-2 focus:ring-primary-500/50 focus:ring-offset-2 dark:focus:ring-offset-dark-800 disabled:opacity-50 disabled:cursor-not-allowed transition-all duration-200 shadow-lg shadow-primary-500/25 dark:shadow-primary-500/15 hover:shadow-xl hover:shadow-primary-500/30"
              >
                {isLoading ? (
                  <>
                    <svg className="animate-spin h-5 w-5" viewBox="0 0 24 24">
                      <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4" fill="none" />
                      <path className="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4zm2 5.291A7.962 7.962 0 014 12H0c0 3.042 1.135 5.824 3 7.938l3-2.647z" />
                    </svg>
                    <span>Signing in...</span>
                  </>
                ) : (
                  <>
                    <LogIn size={18} />
                    <span>Sign in</span>
                  </>
                )}
              </motion.button>
            </motion.div>
          </form>

          {/* Register Link */}
          <motion.div className="mt-6 text-center" variants={itemVariants}>
            <span className="text-dark-500 dark:text-dark-400">
              Don't have an account?{' '}
              <Link
                to="/register"
                className="font-medium text-primary-600 hover:text-primary-700 dark:text-primary-400 dark:hover:text-primary-300 transition-colors"
              >
                Register
              </Link>
            </span>
          </motion.div>
        </motion.div>

        {/* Footer Links */}
        <motion.div
          className="mt-8 flex flex-wrap justify-center gap-6 text-sm"
          variants={itemVariants}
        >
          <Link
            to="/services-public"
            className="flex items-center gap-1.5 text-dark-500 hover:text-primary-600 dark:text-dark-400 dark:hover:text-primary-400 transition-colors"
          >
            <Mail size={16} />
            Services
          </Link>
          <Link
            to="/terms"
            className="flex items-center gap-1.5 text-dark-500 hover:text-primary-600 dark:text-dark-400 dark:hover:text-primary-400 transition-colors"
          >
            <FileText size={16} />
            Terms of Service
          </Link>
          <a
            href="mailto:smmdata.top@gmail.com"
            className="flex items-center gap-1.5 text-dark-500 hover:text-primary-600 dark:text-dark-400 dark:hover:text-primary-400 transition-colors"
          >
            <MessageCircle size={16} />
            Contact Us
          </a>
        </motion.div>
      </motion.div>
    </div>
  );
};
