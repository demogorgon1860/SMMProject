import React, { useState, useEffect } from 'react';
import { Link, useLocation, useNavigate } from 'react-router-dom';
import { motion, AnimatePresence } from 'framer-motion';
import { HomeIcon, ShoppingCartIcon, PlusIcon, CreditCardIcon, DocumentTextIcon, CogIcon, UserIcon, BellIcon, MenuIcon, XIcon, LogOutIcon, UsersIcon, BarChart3Icon, SettingsIcon, GlobeIcon, FileTextIcon, SunIcon, MoonIcon } from 'lucide-react';

// Components
import { Button, Badge } from '@/components/ui';

// Store
import { useAuth, useAuthActions } from '@/store/auth';
import { useNotifications } from '@/store/websocket';

// Utils
import { formatCurrency } from '@/utils/format';
import { clsx } from 'clsx';

interface LayoutProps {
  children: React.ReactNode;
}

interface NavItem {
  name: string;
  href: string;
  icon: React.ComponentType<{ className?: string }>;
  badge?: number;
  adminOnly?: boolean;
  operatorOnly?: boolean;
}

export const Layout: React.FC<LayoutProps> = ({ children }) => {
  const { user } = useAuth();
  const { logout } = useAuthActions();
  const { notifications } = useNotifications();
  const location = useLocation();
  const navigate = useNavigate();
  const [sidebarOpen, setSidebarOpen] = useState(false);
  const [darkMode, setDarkMode] = useState(
    localStorage.getItem('darkMode') === 'true' || 
    (!localStorage.getItem('darkMode') && window.matchMedia('(prefers-color-scheme: dark)').matches)
  );

  // Navigation items
  const navigation: NavItem[] = [
    { name: 'Dashboard', href: '/dashboard', icon: HomeIcon },
    { name: 'Orders', href: '/orders', icon: ShoppingCartIcon },
    { name: 'New Order', href: '/orders/new', icon: PlusIcon },
    { name: 'Balance', href: '/balance', icon: CreditCardIcon },
    { name: 'API Docs', href: '/api-docs', icon: DocumentTextIcon },
    { name: 'Settings', href: '/settings', icon: CogIcon },
  ];

  // Admin navigation
  const adminNavigation: NavItem[] = [
    { name: 'Admin Panel', href: '/admin', icon: BarChart3Icon, adminOnly: true },
    { name: 'Manage Orders', href: '/admin/orders', icon: ShoppingCartIcon, operatorOnly: true },
    { name: 'Manage Users', href: '/admin/users', icon: UsersIcon, adminOnly: true },
    { name: 'Traffic Sources', href: '/admin/traffic-sources', icon: GlobeIcon, adminOnly: true },
    { name: 'Reports', href: '/admin/reports', icon: FileTextIcon, operatorOnly: true },
    { name: 'System Settings', href: '/admin/settings', icon: SettingsIcon, adminOnly: true },
  ];

  // Filter navigation based on user role
  const visibleAdminNav = adminNavigation.filter(item => {
    if (item.adminOnly && user?.role !== 'ADMIN') return false;
    if (item.operatorOnly && !['ADMIN', 'OPERATOR'].includes(user?.role || '')) return false;
    return true;
  });

  // Toggle dark mode
  const toggleDarkMode = () => {
    const newDarkMode = !darkMode;
    setDarkMode(newDarkMode);
    localStorage.setItem('darkMode', newDarkMode.toString());
    document.documentElement.classList.toggle('dark', newDarkMode);
  };

  // Handle logout
  const handleLogout = async () => {
    try {
      await logout();
      navigate('/login');
    } catch (error) {
      console.error('Logout error:', error);
    }
  };

  // Check if current path is active
  const isActivePath = (href: string) => {
    if (href === '/dashboard') {
      return location.pathname === '/' || location.pathname === '/dashboard';
    }
    return location.pathname.startsWith(href);
  };

  // Apply dark mode on mount
  useEffect(() => {
    document.documentElement.classList.toggle('dark', darkMode);
  }, [darkMode]);

  // Navigation item component
  const NavItem = ({ item }: { item: NavItem }) => {
    const isActive = isActivePath(item.href);
    return (
      <Link
        to={item.href}
        onClick={() => setSidebarOpen(false)}
        className={clsx(
          'flex items-center px-3 py-2 text-sm font-medium rounded-md transition-colors',
          isActive
            ? 'bg-primary-100 dark:bg-primary-900 text-primary-700 dark:text-primary-300'
            : 'text-neutral-600 dark:text-neutral-400 hover:bg-neutral-100 dark:hover:bg-neutral-700 hover:text-neutral-900 dark:hover:text-neutral-100'
        )}
      >
        <item.icon className="w-5 h-5 mr-3" />
        {item.name}
        {item.badge && (
          <Badge variant="danger" size="sm" className="ml-auto">
            {item.badge}
          </Badge>
        )}
      </Link>
    );
  };

  return (
    <div className="min-h-screen bg-neutral-50 dark:bg-neutral-900">
      {/* Mobile sidebar backdrop */}
      <AnimatePresence>
        {sidebarOpen && (
          <motion.div
            initial={{ opacity: 0 }}
            animate={{ opacity: 1 }}
            exit={{ opacity: 0 }}
            className="fixed inset-0 z-40 lg:hidden"
          >
            <div
              className="fixed inset-0 bg-black bg-opacity-50"
              onClick={() => setSidebarOpen(false)}
            />
          </motion.div>
        )}
      </AnimatePresence>

      {/* Sidebar */}
      <div className={clsx(
        'fixed inset-y-0 left-0 z-50 w-64 bg-white dark:bg-neutral-800 border-r border-neutral-200 dark:border-neutral-700 transform transition-transform duration-300 ease-in-out lg:translate-x-0',
        sidebarOpen ? 'translate-x-0' : '-translate-x-full'
      )}>
        <div className="flex flex-col h-full">
          {/* Logo */}
          <div className="flex items-center justify-between h-16 px-4 border-b border-neutral-200 dark:border-neutral-700">
            <Link
              to="/dashboard"
              className="flex items-center space-x-2"
              onClick={() => setSidebarOpen(false)}
            >
              <div className="w-8 h-8 bg-primary-600 rounded-lg flex items-center justify-center">
                <span className="text-white font-bold text-lg">S</span>
              </div>
              <span className="text-xl font-bold text-neutral-900 dark:text-neutral-100">
                SMM Panel
              </span>
            </Link>
            <button
              className="lg:hidden p-2 rounded-md text-neutral-400 hover:text-neutral-600 dark:hover:text-neutral-300"
              onClick={() => setSidebarOpen(false)}
            >
              <XIcon className="w-5 h-5" />
            </button>
          </div>

          {/* User info */}
          <div className="p-4 border-b border-neutral-200 dark:border-neutral-700">
            <div className="flex items-center space-x-3">
              <div className="w-10 h-10 bg-primary-100 dark:bg-primary-900 rounded-full flex items-center justify-center">
                <UserIcon className="w-5 h-5 text-primary-600 dark:text-primary-400" />
              </div>
              <div className="flex-1 min-w-0">
                <p className="text-sm font-medium text-neutral-900 dark:text-neutral-100 truncate">
                  {user?.username}
                </p>
                <p className="text-xs text-neutral-500 dark:text-neutral-400">
                  Balance: {formatCurrency(parseFloat(user?.balance || '0'))}
                </p>
              </div>
              <Badge variant={user?.role === 'ADMIN' ? 'primary' : user?.role === 'OPERATOR' ? 'warning' : 'secondary'}>
                {user?.role}
              </Badge>
            </div>
          </div>

          {/* Navigation */}
          <nav className="flex-1 overflow-y-auto">
            <div className="p-4 space-y-1">
              {navigation.map((item) => (
                <NavItem key={item.href} item={item} />
              ))}

              {visibleAdminNav.length > 0 && (
                <>
                  <div className="pt-4 mt-4 border-t border-neutral-200 dark:border-neutral-700">
                    <p className="px-3 text-xs font-semibold text-neutral-500 dark:text-neutral-400 uppercase tracking-wider">
                      Administration
                    </p>
                  </div>
                  <div className="mt-2 space-y-1">
                    {visibleAdminNav.map((item) => (
                      <NavItem key={item.href} item={item} />
                    ))}
                  </div>
                </>
              )}
            </div>
          </nav>

          {/* Footer */}
          <div className="p-4 border-t border-neutral-200 dark:border-neutral-700">
            <div className="flex items-center justify-between">
              <button
                onClick={toggleDarkMode}
                className="p-2 rounded-md text-neutral-400 hover:text-neutral-600 dark:hover:text-neutral-300 transition-colors"
                title={darkMode ? 'Switch to light mode' : 'Switch to dark mode'}
              >
                {darkMode ? (
                  <SunIcon className="w-5 h-5" />
                ) : (
                  <MoonIcon className="w-5 h-5" />
                )}
              </button>
              <button
                onClick={handleLogout}
                className="flex items-center px-3 py-2 text-sm font-medium text-neutral-600 dark:text-neutral-400 hover:text-neutral-900 dark:hover:text-neutral-100 rounded-md transition-colors"
              >
                <LogOutIcon className="w-4 h-4 mr-2" />
                Logout
              </button>
            </div>
          </div>
        </div>
      </div>

      {/* Main content */}
      <div className="lg:pl-64">
        {/* Top bar */}
        <header className="bg-white dark:bg-neutral-800 border-b border-neutral-200 dark:border-neutral-700">
          <div className="flex items-center justify-between h-16 px-4 sm:px-6 lg:px-8">
            <button
              className="lg:hidden p-2 rounded-md text-neutral-400 hover:text-neutral-600 dark:hover:text-neutral-300"
              onClick={() => setSidebarOpen(true)}
            >
              <MenuIcon className="w-6 h-6" />
            </button>

            <div className="hidden lg:flex items-center space-x-2 text-sm">
              <Link
                to="/dashboard"
                className="text-neutral-500 dark:text-neutral-400 hover:text-neutral-700 dark:hover:text-neutral-300"
              >
                Dashboard
              </Link>
              {location.pathname !== '/' && location.pathname !== '/dashboard' && (
                <>
                  <span className="text-neutral-300 dark:text-neutral-600">/</span>
                  <span className="text-neutral-900 dark:text-neutral-100 font-medium capitalize">
                    {location.pathname.split('/').filter(Boolean).pop()?.replace('-', ' ')}
                  </span>
                </>
              )}
            </div>

            <div className="flex items-center space-x-4">
              <div className="relative">
                <button className="p-2 rounded-md text-neutral-400 hover:text-neutral-600 dark:hover:text-neutral-300 transition-colors">
                  <BellIcon className="w-5 h-5" />
                  {notifications.length > 0 && (
                    <span className="absolute -top-1 -right-1 w-4 h-4 bg-danger-500 text-white text-xs rounded-full flex items-center justify-center">
                      {notifications.length > 9 ? '9+' : notifications.length}
                    </span>
                  )}
                </button>
              </div>

              <div className="flex items-center space-x-3">
                <div className="hidden sm:block text-right">
                  <p className="text-sm font-medium text-neutral-900 dark:text-neutral-100">
                    {user?.username}
                  </p>
                  <p className="text-xs text-neutral-500 dark:text-neutral-400">
                    {formatCurrency(parseFloat(user?.balance || '0'))}
                  </p>
                </div>
                <Link
                  to="/settings"
                  className="w-8 h-8 bg-primary-100 dark:bg-primary-900 rounded-full flex items-center justify-center hover:bg-primary-200 dark:hover:bg-primary-800 transition-colors"
                >
                  <UserIcon className="w-4 h-4 text-primary-600 dark:text-primary-400" />
                </Link>
              </div>
            </div>
          </div>
        </header>

        {/* Page content */}
        <main className="flex-1">
          <div className="px-4 py-6 sm:px-6 lg:px-8">
            <motion.div
              initial={{ opacity: 0, y: 20 }}
              animate={{ opacity: 1, y: 0 }}
              transition={{ duration: 0.3 }}
            >
              {children}
            </motion.div>
          </div>
        </main>
      </div>
    </div>
  );
};

export default Layout;
