import React, { useState } from 'react';
import { Link, useNavigate, Outlet, useLocation } from 'react-router-dom';
import { useAuthStore } from '../store/authStore';
import { ThemeToggle } from './ui/ThemeToggle';
import {
  LayoutDashboard,
  ShoppingBag,
  Package,
  Settings,
  Shield,
  Wallet,
  LogOut,
  Menu,
  X,
  ChevronDown,
  User,
  FileText,
  Mail,
  Plus,
} from 'lucide-react';

export const Layout: React.FC = () => {
  const navigate = useNavigate();
  const location = useLocation();
  const { user, logout } = useAuthStore();
  const [isMobileMenuOpen, setIsMobileMenuOpen] = useState(false);
  const [isProfileDropdownOpen, setIsProfileDropdownOpen] = useState(false);

  const handleLogout = () => {
    logout();
    navigate('/login');
  };

  const toggleMobileMenu = () => {
    setIsMobileMenuOpen(!isMobileMenuOpen);
  };

  const closeMobileMenu = () => {
    setIsMobileMenuOpen(false);
  };

  const isActive = (path: string) => {
    if (path === '/admin') {
      return location.pathname.startsWith('/admin');
    }
    return location.pathname === path;
  };

  const navLinkClass = (path: string) => `
    flex items-center gap-2 px-3 py-2 rounded-xl text-sm font-medium transition-all duration-200
    ${isActive(path)
      ? 'bg-primary-100 text-primary-700 dark:bg-primary-900/50 dark:text-primary-300'
      : 'text-dark-600 hover:text-dark-900 hover:bg-dark-100 dark:text-dark-300 dark:hover:text-white dark:hover:bg-dark-700'
    }
  `;

  const mobileNavLinkClass = (path: string) => `
    flex items-center gap-3 px-4 py-3 rounded-xl text-base font-medium transition-all duration-200
    ${isActive(path)
      ? 'bg-primary-100 text-primary-700 dark:bg-primary-900/50 dark:text-primary-300'
      : 'text-dark-600 hover:text-dark-900 hover:bg-dark-100 dark:text-dark-300 dark:hover:text-white dark:hover:bg-dark-700'
    }
  `;

  return (
    <div className="min-h-screen bg-dark-50 dark:bg-dark-950 transition-colors duration-300">
      {/* Navigation */}
      <nav className="sticky top-0 z-40 bg-white/80 dark:bg-dark-900/80 backdrop-blur-lg border-b border-dark-100 dark:border-dark-800">
        <div className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8">
          <div className="flex justify-between h-16">
            {/* Logo and Desktop Nav */}
            <div className="flex items-center gap-8">
              <Link
                to="/dashboard"
                className="flex items-center"
              >
                <img
                  src="/logo-v2.png"
                  alt="SMM World"
                  className="h-10 w-auto"
                />
              </Link>

              {/* Desktop Navigation */}
              <div className="hidden md:flex items-center gap-1">
                <Link to="/dashboard" className={navLinkClass('/dashboard')}>
                  <LayoutDashboard size={18} />
                  Dashboard
                </Link>

                <Link to="/services" className={navLinkClass('/services')}>
                  <ShoppingBag size={18} />
                  Services
                </Link>

                <Link to="/orders" className={navLinkClass('/orders')}>
                  <Package size={18} />
                  Orders
                </Link>

                <Link to="/orders/new" className={navLinkClass('/orders/new')}>
                  <Plus size={18} />
                  New Order
                </Link>

                {user?.role === 'ADMIN' && (
                  <Link to="/admin" className={navLinkClass('/admin')}>
                    <Shield size={18} />
                    Admin
                  </Link>
                )}
              </div>
            </div>

            {/* Desktop Right Section */}
            <div className="hidden md:flex items-center gap-3">
              {/* Balance */}
              <Link
                to="/add-funds"
                className="flex items-center gap-2 px-3 py-1.5 rounded-xl bg-accent-100 text-accent-700 dark:bg-accent-900/50 dark:text-accent-300 hover:bg-accent-200 dark:hover:bg-accent-900/70 transition-colors"
              >
                <Wallet size={16} />
                <span className="font-semibold">${Number(user?.balance || 0).toFixed(2)}</span>
              </Link>

              {/* Theme Toggle */}
              <ThemeToggle />

              {/* Profile Dropdown */}
              <div className="relative">
                <button
                  onClick={() => setIsProfileDropdownOpen(!isProfileDropdownOpen)}
                  className="flex items-center gap-2 px-3 py-2 rounded-xl text-dark-600 hover:text-dark-900 hover:bg-dark-100 dark:text-dark-300 dark:hover:text-white dark:hover:bg-dark-700 transition-colors"
                >
                  <div className="w-8 h-8 rounded-full bg-primary-100 dark:bg-primary-900/50 flex items-center justify-center">
                    <User size={16} className="text-primary-600 dark:text-primary-400" />
                  </div>
                  <span className="text-sm font-medium max-w-24 truncate">{user?.username}</span>
                  <ChevronDown
                    size={16}
                    className={`transition-transform duration-200 ${isProfileDropdownOpen ? 'rotate-180' : ''}`}
                  />
                </button>

                {/* Dropdown Menu */}
                {isProfileDropdownOpen && (
                  <>
                    <div
                      className="fixed inset-0 z-10"
                      onClick={() => setIsProfileDropdownOpen(false)}
                    />
                    <div className="absolute right-0 mt-2 w-56 rounded-xl bg-white dark:bg-dark-800 border border-dark-100 dark:border-dark-700 shadow-soft-lg dark:shadow-dark-lg z-20 animate-fade-in-down overflow-hidden">
                      <div className="px-4 py-3 border-b border-dark-100 dark:border-dark-700">
                        <p className="text-sm font-medium text-dark-900 dark:text-white truncate">
                          {user?.username}
                        </p>
                        <p className="text-xs text-dark-500 dark:text-dark-400">{user?.email}</p>
                        <span className="inline-block mt-1 px-2 py-0.5 text-2xs font-medium rounded-md bg-primary-100 text-primary-700 dark:bg-primary-900/50 dark:text-primary-300">
                          {user?.role}
                        </span>
                      </div>

                      <div className="py-1">
                        <Link
                          to="/profile"
                          onClick={() => setIsProfileDropdownOpen(false)}
                          className="flex items-center gap-2 px-4 py-2 text-sm text-dark-600 hover:text-dark-900 hover:bg-dark-50 dark:text-dark-300 dark:hover:text-white dark:hover:bg-dark-700 transition-colors"
                        >
                          <Settings size={16} />
                          Settings
                        </Link>

                        <Link
                          to="/terms"
                          onClick={() => setIsProfileDropdownOpen(false)}
                          className="flex items-center gap-2 px-4 py-2 text-sm text-dark-600 hover:text-dark-900 hover:bg-dark-50 dark:text-dark-300 dark:hover:text-white dark:hover:bg-dark-700 transition-colors"
                        >
                          <FileText size={16} />
                          Terms of Service
                        </Link>

                        <a
                          href="mailto:smmdata.top@gmail.com"
                          className="flex items-center gap-2 px-4 py-2 text-sm text-dark-600 hover:text-dark-900 hover:bg-dark-50 dark:text-dark-300 dark:hover:text-white dark:hover:bg-dark-700 transition-colors"
                        >
                          <Mail size={16} />
                          Contact Support
                        </a>
                      </div>

                      <div className="border-t border-dark-100 dark:border-dark-700 py-1">
                        <button
                          onClick={() => {
                            setIsProfileDropdownOpen(false);
                            handleLogout();
                          }}
                          className="flex items-center gap-2 w-full px-4 py-2 text-sm text-red-600 hover:text-red-700 hover:bg-red-50 dark:text-red-400 dark:hover:text-red-300 dark:hover:bg-red-900/20 transition-colors"
                        >
                          <LogOut size={16} />
                          Logout
                        </button>
                      </div>
                    </div>
                  </>
                )}
              </div>
            </div>

            {/* Mobile Menu Button */}
            <div className="md:hidden flex items-center gap-2">
              <ThemeToggle size="sm" />
              <button
                onClick={toggleMobileMenu}
                className="p-2 rounded-xl text-dark-600 hover:text-dark-900 hover:bg-dark-100 dark:text-dark-300 dark:hover:text-white dark:hover:bg-dark-700 transition-colors"
              >
                {isMobileMenuOpen ? <X size={24} /> : <Menu size={24} />}
              </button>
            </div>
          </div>
        </div>

        {/* Mobile Menu */}
        {isMobileMenuOpen && (
          <div className="md:hidden border-t border-dark-100 dark:border-dark-800 bg-white dark:bg-dark-900 animate-fade-in-down">
            <div className="p-4 space-y-2">
              {/* Balance */}
              <Link
                to="/add-funds"
                onClick={closeMobileMenu}
                className="flex items-center justify-between px-4 py-3 rounded-xl bg-accent-50 dark:bg-accent-900/30 text-accent-700 dark:text-accent-300"
              >
                <span className="flex items-center gap-2">
                  <Wallet size={18} />
                  Balance
                </span>
                <span className="font-bold">${user?.balance?.toFixed(2) || '0.00'}</span>
              </Link>

              {/* Navigation Links */}
              <Link to="/dashboard" onClick={closeMobileMenu} className={mobileNavLinkClass('/dashboard')}>
                <LayoutDashboard size={20} />
                Dashboard
              </Link>

              <Link to="/services" onClick={closeMobileMenu} className={mobileNavLinkClass('/services')}>
                <ShoppingBag size={20} />
                Services
              </Link>

              <Link to="/orders" onClick={closeMobileMenu} className={mobileNavLinkClass('/orders')}>
                <Package size={20} />
                Orders
              </Link>

              <Link to="/orders/new" onClick={closeMobileMenu} className={mobileNavLinkClass('/orders/new')}>
                <Plus size={20} />
                New Order
              </Link>

              {user?.role === 'ADMIN' && (
                <Link to="/admin" onClick={closeMobileMenu} className={mobileNavLinkClass('/admin')}>
                  <Shield size={20} />
                  Admin Panel
                </Link>
              )}

              {/* Divider */}
              <div className="border-t border-dark-100 dark:border-dark-700 my-2" />

              {/* User Info */}
              <div className="px-4 py-2">
                <div className="flex items-center gap-3">
                  <div className="w-10 h-10 rounded-full bg-primary-100 dark:bg-primary-900/50 flex items-center justify-center">
                    <User size={20} className="text-primary-600 dark:text-primary-400" />
                  </div>
                  <div>
                    <p className="font-medium text-dark-900 dark:text-white">{user?.username}</p>
                    <p className="text-sm text-dark-500 dark:text-dark-400">{user?.email}</p>
                  </div>
                </div>
              </div>

              <Link to="/profile" onClick={closeMobileMenu} className={mobileNavLinkClass('/profile')}>
                <Settings size={20} />
                Settings
              </Link>

              <Link to="/terms" onClick={closeMobileMenu} className={mobileNavLinkClass('/terms')}>
                <FileText size={20} />
                Terms of Service
              </Link>

              <a
                href="mailto:smmdata.top@gmail.com"
                className="flex items-center gap-3 px-4 py-3 rounded-xl text-base font-medium text-dark-600 hover:text-dark-900 hover:bg-dark-100 dark:text-dark-300 dark:hover:text-white dark:hover:bg-dark-700 transition-colors"
              >
                <Mail size={20} />
                Contact Support
              </a>

              {/* Logout */}
              <button
                onClick={() => {
                  closeMobileMenu();
                  handleLogout();
                }}
                className="flex items-center gap-3 w-full px-4 py-3 rounded-xl text-base font-medium text-red-600 hover:bg-red-50 dark:text-red-400 dark:hover:bg-red-900/20 transition-colors"
              >
                <LogOut size={20} />
                Logout
              </button>
            </div>
          </div>
        )}
      </nav>

      {/* Main Content */}
      <main
        className={`
          ${location.pathname === '/orders' || location.pathname.startsWith('/admin')
            ? 'w-full px-4 sm:px-6 lg:px-8'
            : 'max-w-7xl mx-auto px-4 sm:px-6 lg:px-8'
          }
          py-6 sm:py-8
        `}
      >
        <Outlet />
      </main>
    </div>
  );
};
