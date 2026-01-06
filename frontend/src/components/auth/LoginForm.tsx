import React, { useState } from 'react';
import { useNavigate, Link } from 'react-router-dom';
import { useAuthStore } from '../../store/authStore';
import { ThemeToggle } from '../ui/ThemeToggle';
import { Eye, EyeOff, LogIn, User, Lock, AlertCircle, Mail, FileText, MessageCircle } from 'lucide-react';

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
    <div className="min-h-screen flex items-center justify-center bg-gradient-to-br from-dark-50 via-dark-100 to-primary-50 dark:from-dark-950 dark:via-dark-900 dark:to-dark-950 transition-colors duration-300 px-4">
      {/* Theme Toggle - Floating */}
      <div className="fixed top-4 right-4">
        <ThemeToggle />
      </div>

      <div className="w-full max-w-md">
        {/* Logo and Title */}
        <div className="text-center mb-8">
          <div className="inline-flex items-center justify-center w-16 h-16 rounded-2xl bg-gradient-to-br from-primary-500 to-primary-700 shadow-glow mb-4">
            <span className="text-white text-2xl font-bold">S</span>
          </div>
          <h1 className="text-2xl font-bold text-dark-900 dark:text-white">
            Welcome back
          </h1>
          <p className="text-dark-500 dark:text-dark-400 mt-2">
            Sign in to your SMM Panel account
          </p>
        </div>

        {/* Login Card */}
        <div className="bg-white dark:bg-dark-800 rounded-2xl shadow-soft-lg dark:shadow-dark-lg border border-dark-100 dark:border-dark-700 p-8 animate-fade-in-up">
          <form onSubmit={handleSubmit} className="space-y-6">
            {/* Error Alert */}
            {error && (
              <div className="flex items-center gap-3 p-4 rounded-xl bg-red-50 dark:bg-red-900/20 border border-red-200 dark:border-red-800 text-red-700 dark:text-red-400 animate-fade-in">
                <AlertCircle size={20} className="flex-shrink-0" />
                <span className="text-sm">{error}</span>
              </div>
            )}

            {/* Username Field */}
            <div className="space-y-2">
              <label htmlFor="username" className="block text-sm font-medium text-dark-700 dark:text-dark-300">
                Username
              </label>
              <div className="relative">
                <div className="absolute inset-y-0 left-0 pl-3 flex items-center pointer-events-none">
                  <User size={18} className="text-dark-400" />
                </div>
                <input
                  id="username"
                  name="username"
                  type="text"
                  required
                  value={formData.username}
                  onChange={handleChange}
                  className="block w-full pl-10 pr-4 py-3 border border-dark-200 dark:border-dark-600 rounded-xl bg-white dark:bg-dark-700 text-dark-900 dark:text-white placeholder-dark-400 focus:outline-none focus:ring-2 focus:ring-primary-500/50 focus:border-primary-500 transition-all duration-200"
                  placeholder="Enter your username"
                />
              </div>
            </div>

            {/* Password Field */}
            <div className="space-y-2">
              <label htmlFor="password" className="block text-sm font-medium text-dark-700 dark:text-dark-300">
                Password
              </label>
              <div className="relative">
                <div className="absolute inset-y-0 left-0 pl-3 flex items-center pointer-events-none">
                  <Lock size={18} className="text-dark-400" />
                </div>
                <input
                  id="password"
                  name="password"
                  type={showPassword ? 'text' : 'password'}
                  required
                  value={formData.password}
                  onChange={handleChange}
                  className="block w-full pl-10 pr-12 py-3 border border-dark-200 dark:border-dark-600 rounded-xl bg-white dark:bg-dark-700 text-dark-900 dark:text-white placeholder-dark-400 focus:outline-none focus:ring-2 focus:ring-primary-500/50 focus:border-primary-500 transition-all duration-200"
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
            </div>

            {/* Submit Button */}
            <button
              type="submit"
              disabled={isLoading}
              className="w-full flex items-center justify-center gap-2 py-3 px-4 rounded-xl text-white font-medium bg-gradient-to-r from-primary-600 to-primary-700 hover:from-primary-700 hover:to-primary-800 focus:outline-none focus:ring-2 focus:ring-primary-500/50 focus:ring-offset-2 dark:focus:ring-offset-dark-800 disabled:opacity-50 disabled:cursor-not-allowed transition-all duration-200 shadow-soft dark:shadow-dark-soft"
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
            </button>
          </form>

          {/* Register Link */}
          <div className="mt-6 text-center">
            <span className="text-dark-500 dark:text-dark-400">
              Don't have an account?{' '}
              <Link
                to="/register"
                className="font-medium text-primary-600 hover:text-primary-700 dark:text-primary-400 dark:hover:text-primary-300 transition-colors"
              >
                Register
              </Link>
            </span>
          </div>
        </div>

        {/* Footer Links */}
        <div className="mt-8 flex flex-wrap justify-center gap-6 text-sm">
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
        </div>
      </div>
    </div>
  );
};
