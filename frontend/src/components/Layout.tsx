import React, { useState } from 'react';
import { Link, useNavigate, Outlet, useLocation } from 'react-router-dom';
import { useAuthStore } from '../store/authStore';

export const Layout: React.FC = () => {
  const navigate = useNavigate();
  const location = useLocation();
  const { user, logout } = useAuthStore();
  const [isMobileMenuOpen, setIsMobileMenuOpen] = useState(false);

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

  return (
    <div className="min-h-screen bg-gray-100">
      <nav className="bg-white shadow-md">
        <div className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8">
          <div className="flex justify-between h-16">
            <div className="flex items-center w-full">
              <Link to="/dashboard" className="text-lg sm:text-xl font-bold text-gray-900 flex-shrink-0">
                SMM Panel
              </Link>

              {/* Desktop navigation */}
              <div className="hidden md:flex md:ml-10 md:items-baseline md:space-x-4">
                <Link
                  to="/dashboard"
                  className="text-gray-700 hover:text-gray-900 px-3 py-2 rounded-md text-sm font-medium"
                >
                  Dashboard
                </Link>

                <Link
                  to="/services"
                  className="text-gray-700 hover:text-gray-900 px-3 py-2 rounded-md text-sm font-medium"
                >
                  Services
                </Link>

                <Link
                  to="/orders"
                  className="text-gray-700 hover:text-gray-900 px-3 py-2 rounded-md text-sm font-medium"
                >
                  Orders
                </Link>

                {user?.role === 'ADMIN' && (
                  <>
                    <Link
                      to="/admin"
                      className="text-gray-700 hover:text-gray-900 px-3 py-2 rounded-md text-sm font-medium"
                    >
                      Admin
                    </Link>
                    <Link
                      to="/services-test"
                      className="text-gray-700 hover:text-gray-900 px-3 py-2 rounded-md text-sm font-medium"
                    >
                      Test Services
                    </Link>
                  </>
                )}
              </div>
            </div>

            {/* Desktop user info and logout */}
            <div className="hidden md:flex md:items-center md:space-x-4">
              <span className="text-sm text-gray-700 whitespace-nowrap">
                {user?.username} ({user?.role})
              </span>
              <Link
                to="/terms"
                className="text-blue-600 hover:text-blue-800 px-3 py-2 rounded text-sm font-medium whitespace-nowrap border border-blue-600 hover:border-blue-800 transition-colors"
              >
                Terms of Service
              </Link>
              <div className="group relative">
                <button className="text-green-600 hover:text-green-800 px-3 py-2 rounded text-sm font-medium whitespace-nowrap border border-green-600 hover:border-green-800 transition-colors">
                  Contact Us
                </button>
                <div className="invisible group-hover:visible absolute top-full right-0 mt-2 px-4 py-3 bg-gray-800 text-white text-sm rounded shadow-lg whitespace-nowrap z-50">
                  smmdata.top@gmail.com
                </div>
              </div>
              <button
                onClick={handleLogout}
                className="bg-red-500 hover:bg-red-600 text-white px-4 py-2 rounded text-sm whitespace-nowrap"
              >
                Logout
              </button>
            </div>

            {/* Mobile menu button */}
            <div className="md:hidden flex items-center">
              <button
                onClick={toggleMobileMenu}
                className="inline-flex items-center justify-center p-2 rounded-md text-gray-700 hover:text-gray-900 hover:bg-gray-100 focus:outline-none focus:ring-2 focus:ring-inset focus:ring-blue-500"
                aria-expanded="false"
              >
                <span className="sr-only">Open main menu</span>
                {/* Hamburger icon */}
                {!isMobileMenuOpen ? (
                  <svg className="block h-6 w-6" xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                    <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M4 6h16M4 12h16M4 18h16" />
                  </svg>
                ) : (
                  <svg className="block h-6 w-6" xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                    <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M6 18L18 6M6 6l12 12" />
                  </svg>
                )}
              </button>
            </div>
          </div>
        </div>

        {/* Mobile menu */}
        {isMobileMenuOpen && (
          <div className="md:hidden border-t border-gray-200">
            <div className="px-2 pt-2 pb-3 space-y-1">
              <Link
                to="/dashboard"
                onClick={closeMobileMenu}
                className="text-gray-700 hover:text-gray-900 hover:bg-gray-100 block px-3 py-2 rounded-md text-base font-medium"
              >
                Dashboard
              </Link>

              <Link
                to="/services"
                onClick={closeMobileMenu}
                className="text-gray-700 hover:text-gray-900 hover:bg-gray-100 block px-3 py-2 rounded-md text-base font-medium"
              >
                Services
              </Link>

              <Link
                to="/orders"
                onClick={closeMobileMenu}
                className="text-gray-700 hover:text-gray-900 hover:bg-gray-100 block px-3 py-2 rounded-md text-base font-medium"
              >
                Orders
              </Link>

              {user?.role === 'ADMIN' && (
                <>
                  <Link
                    to="/admin"
                    onClick={closeMobileMenu}
                    className="text-gray-700 hover:text-gray-900 hover:bg-gray-100 block px-3 py-2 rounded-md text-base font-medium"
                  >
                    Admin
                  </Link>
                  <Link
                    to="/services-test"
                    onClick={closeMobileMenu}
                    className="text-gray-700 hover:text-gray-900 hover:bg-gray-100 block px-3 py-2 rounded-md text-base font-medium"
                  >
                    Test Services
                  </Link>
                </>
              )}

              <div className="border-t border-gray-200 pt-4 mt-4">
                <div className="px-3 pb-2">
                  <p className="text-sm text-gray-700">
                    Logged in as: <span className="font-medium">{user?.username}</span>
                  </p>
                  <p className="text-xs text-gray-500 mt-1">
                    Role: {user?.role}
                  </p>
                </div>
                <Link
                  to="/terms"
                  onClick={closeMobileMenu}
                  className="block text-center bg-blue-600 hover:bg-blue-700 text-white px-3 py-2 rounded-md text-base font-medium mx-2 mb-2"
                  style={{ width: 'calc(100% - 1rem)' }}
                >
                  Terms of Service
                </Link>
                <div className="mx-2 mb-2 relative" style={{ width: 'calc(100% - 1rem)' }}>
                  <div className="group relative inline-block w-full">
                    <button className="w-full text-center bg-green-600 hover:bg-green-700 text-white px-3 py-2 rounded-md text-base font-medium">
                      Contact Us
                    </button>
                    <div className="invisible group-hover:visible absolute top-full left-1/2 transform -translate-x-1/2 mt-2 px-3 py-2 bg-gray-800 text-white text-sm rounded shadow-lg whitespace-nowrap z-50">
                      smmdata.top@gmail.com
                    </div>
                  </div>
                </div>
                <button
                  onClick={() => {
                    closeMobileMenu();
                    handleLogout();
                  }}
                  className="w-full text-left bg-red-500 hover:bg-red-600 text-white px-3 py-2 rounded-md text-base font-medium mx-2"
                  style={{ width: 'calc(100% - 1rem)' }}
                >
                  Logout
                </button>
              </div>
            </div>
          </div>
        )}
      </nav>

      <main className={`${location.pathname === '/orders' || location.pathname === '/admin/orders' ? 'w-full' : 'max-w-7xl mx-auto px-4 sm:px-6 lg:px-8'} py-6`}>
        <Outlet />
      </main>
    </div>
  );
};
