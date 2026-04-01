import React, { useState } from 'react';
import { Link, useNavigate, Outlet, useLocation } from 'react-router-dom';
import { useAuthStore } from '../store/authStore';
import { ThemeToggle } from './ui/ThemeToggle';
import { motion, AnimatePresence } from 'framer-motion';
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

const dropdownVariants = {
  initial: { opacity: 0, scale: 0.95, y: -4 },
  animate: { opacity: 1, scale: 1, y: 0, transition: { duration: 0.15, ease: 'easeOut' } },
  exit: { opacity: 0, scale: 0.95, y: -4, transition: { duration: 0.1, ease: 'easeIn' } },
};

const mobileMenuVariants = {
  initial: { opacity: 0, height: 0 },
  animate: { opacity: 1, height: 'auto', transition: { duration: 0.25, ease: 'easeOut' } },
  exit: { opacity: 0, height: 0, transition: { duration: 0.2, ease: 'easeIn' } },
};

const mobileItemVariants = {
  initial: { opacity: 0, x: -12 },
  animate: (i: number) => ({
    opacity: 1,
    x: 0,
    transition: { delay: i * 0.04, duration: 0.25, ease: 'easeOut' },
  }),
  exit: { opacity: 0, x: -12, transition: { duration: 0.1 } },
};

export const Layout: React.FC = () => {
  const navigate = useNavigate();
  const location = useLocation();
  const user = useAuthStore((s) => s.user);
  const logout = useAuthStore((s) => s.logout);
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

  const mobileLinks = [
    { to: '/dashboard', icon: LayoutDashboard, label: 'Dashboard' },
    { to: '/services', icon: ShoppingBag, label: 'Services' },
    { to: '/orders', icon: Package, label: 'Orders' },
    { to: '/orders/new', icon: Plus, label: 'New Order' },
    ...(user?.role === 'ADMIN' ? [{ to: '/admin', icon: Shield, label: 'Admin Panel' }] : []),
  ];

  return (
    <div className="min-h-screen bg-dark-50 dark:bg-dark-950 transition-colors duration-300">
      {/* Navigation */}
      <nav className="sticky top-0 z-40 bg-white/80 dark:bg-dark-900/80 backdrop-blur-xl border-b border-dark-100/80 dark:border-dark-800/80">
        <div className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8">
          <div className="flex justify-between h-16">
            {/* Logo and Desktop Nav */}
            <div className="flex items-center gap-8">
              <Link to="/dashboard" className="flex items-center">
                <img src="/logo-v2.png" alt="SMM World" className="h-10 w-auto" />
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
                  <motion.div
                    animate={{ rotate: isProfileDropdownOpen ? 180 : 0 }}
                    transition={{ duration: 0.2 }}
                  >
                    <ChevronDown size={16} />
                  </motion.div>
                </button>

                {/* Dropdown Menu with AnimatePresence */}
                <AnimatePresence>
                  {isProfileDropdownOpen && (
                    <>
                      <div
                        className="fixed inset-0 z-10"
                        onClick={() => setIsProfileDropdownOpen(false)}
                      />
                      <motion.div
                        className="absolute right-0 mt-2 w-56 rounded-xl bg-white/90 dark:bg-dark-800/90 backdrop-blur-xl border border-dark-100/50 dark:border-dark-700/50 shadow-soft-lg dark:shadow-dark-lg z-20 overflow-hidden"
                        variants={dropdownVariants}
                        initial="initial"
                        animate="animate"
                        exit="exit"
                      >
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
                      </motion.div>
                    </>
                  )}
                </AnimatePresence>
              </div>
            </div>

            {/* Mobile Menu Button */}
            <div className="md:hidden flex items-center gap-2">
              <ThemeToggle size="sm" />
              <motion.button
                onClick={toggleMobileMenu}
                className="p-2 rounded-xl text-dark-600 hover:text-dark-900 hover:bg-dark-100 dark:text-dark-300 dark:hover:text-white dark:hover:bg-dark-700 transition-colors"
                whileTap={{ scale: 0.9 }}
              >
                {isMobileMenuOpen ? <X size={24} /> : <Menu size={24} />}
              </motion.button>
            </div>
          </div>
        </div>

        {/* Mobile Menu with AnimatePresence */}
        <AnimatePresence>
          {isMobileMenuOpen && (
            <motion.div
              className="md:hidden border-t border-dark-100 dark:border-dark-800 bg-white/95 dark:bg-dark-900/95 backdrop-blur-xl overflow-hidden"
              variants={mobileMenuVariants}
              initial="initial"
              animate="animate"
              exit="exit"
            >
              <div className="p-4 space-y-2">
                {/* Balance */}
                <motion.div variants={mobileItemVariants} custom={0} initial="initial" animate="animate" exit="exit">
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
                </motion.div>

                {/* Navigation Links */}
                {mobileLinks.map((link, i) => (
                  <motion.div key={link.to} variants={mobileItemVariants} custom={i + 1} initial="initial" animate="animate" exit="exit">
                    <Link to={link.to} onClick={closeMobileMenu} className={mobileNavLinkClass(link.to)}>
                      <link.icon size={20} />
                      {link.label}
                    </Link>
                  </motion.div>
                ))}

                {/* Divider */}
                <motion.div
                  className="border-t border-dark-100 dark:border-dark-700 my-2"
                  variants={mobileItemVariants}
                  custom={mobileLinks.length + 1}
                  initial="initial"
                  animate="animate"
                  exit="exit"
                />

                {/* User Info */}
                <motion.div
                  className="px-4 py-2"
                  variants={mobileItemVariants}
                  custom={mobileLinks.length + 2}
                  initial="initial"
                  animate="animate"
                  exit="exit"
                >
                  <div className="flex items-center gap-3">
                    <div className="w-10 h-10 rounded-full bg-primary-100 dark:bg-primary-900/50 flex items-center justify-center">
                      <User size={20} className="text-primary-600 dark:text-primary-400" />
                    </div>
                    <div>
                      <p className="font-medium text-dark-900 dark:text-white">{user?.username}</p>
                      <p className="text-sm text-dark-500 dark:text-dark-400">{user?.email}</p>
                    </div>
                  </div>
                </motion.div>

                <motion.div variants={mobileItemVariants} custom={mobileLinks.length + 3} initial="initial" animate="animate" exit="exit">
                  <Link to="/profile" onClick={closeMobileMenu} className={mobileNavLinkClass('/profile')}>
                    <Settings size={20} />
                    Settings
                  </Link>
                </motion.div>

                <motion.div variants={mobileItemVariants} custom={mobileLinks.length + 4} initial="initial" animate="animate" exit="exit">
                  <Link to="/terms" onClick={closeMobileMenu} className={mobileNavLinkClass('/terms')}>
                    <FileText size={20} />
                    Terms of Service
                  </Link>
                </motion.div>

                <motion.div variants={mobileItemVariants} custom={mobileLinks.length + 5} initial="initial" animate="animate" exit="exit">
                  <a
                    href="mailto:smmdata.top@gmail.com"
                    className="flex items-center gap-3 px-4 py-3 rounded-xl text-base font-medium text-dark-600 hover:text-dark-900 hover:bg-dark-100 dark:text-dark-300 dark:hover:text-white dark:hover:bg-dark-700 transition-colors"
                  >
                    <Mail size={20} />
                    Contact Support
                  </a>
                </motion.div>

                {/* Logout */}
                <motion.div variants={mobileItemVariants} custom={mobileLinks.length + 6} initial="initial" animate="animate" exit="exit">
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
                </motion.div>
              </div>
            </motion.div>
          )}
        </AnimatePresence>
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
