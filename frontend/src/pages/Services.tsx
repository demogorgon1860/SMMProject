import React from 'react';
import { Link } from 'react-router-dom';
import {
  CheckCircle,
  ArrowRight,
  Sparkles,
  HelpCircle,
  Mail,
  ExternalLink,
  Zap,
} from 'lucide-react';

// Instagram Icon Component
const InstagramIcon: React.FC<{ className?: string }> = ({ className }) => (
  <svg className={className} fill="currentColor" viewBox="0 0 24 24" xmlns="http://www.w3.org/2000/svg">
    <path d="M12 2.163c3.204 0 3.584.012 4.85.07 3.252.148 4.771 1.691 4.919 4.919.058 1.265.069 1.645.069 4.849 0 3.205-.012 3.584-.069 4.849-.149 3.225-1.664 4.771-4.919 4.919-1.266.058-1.644.07-4.85.07-3.204 0-3.584-.012-4.849-.07-3.26-.149-4.771-1.699-4.919-4.92-.058-1.265-.07-1.644-.07-4.849 0-3.204.013-3.583.07-4.849.149-3.227 1.664-4.771 4.919-4.919 1.266-.057 1.645-.069 4.849-.069zm0-2.163c-3.259 0-3.667.014-4.947.072-4.358.2-6.78 2.618-6.98 6.98-.059 1.281-.073 1.689-.073 4.948 0 3.259.014 3.668.072 4.948.2 4.358 2.618 6.78 6.98 6.98 1.281.058 1.689.072 4.948.072 3.259 0 3.668-.014 4.948-.072 4.354-.2 6.782-2.618 6.979-6.98.059-1.28.073-1.689.073-4.948 0-3.259-.014-3.667-.072-4.947-.196-4.354-2.617-6.78-6.979-6.98-1.281-.059-1.69-.073-4.949-.073zm0 5.838c-3.403 0-6.162 2.759-6.162 6.162s2.759 6.163 6.162 6.163 6.162-2.759 6.162-6.163c0-3.403-2.759-6.162-6.162-6.162zm0 10.162c-2.209 0-4-1.79-4-4 0-2.209 1.791-4 4-4s4 1.791 4 4c0 2.21-1.791 4-4 4zm6.406-11.845c-.796 0-1.441.645-1.441 1.44s.645 1.44 1.441 1.44c.795 0 1.439-.645 1.439-1.44s-.644-1.44-1.439-1.44z"/>
  </svg>
);

// YouTube Icon Component
const YouTubeIcon: React.FC<{ className?: string }> = ({ className }) => (
  <svg className={className} fill="currentColor" viewBox="0 0 24 24" xmlns="http://www.w3.org/2000/svg">
    <path d="M23.498 6.186a3.016 3.016 0 0 0-2.122-2.136C19.505 3.545 12 3.545 12 3.545s-7.505 0-9.377.505A3.017 3.017 0 0 0 .502 6.186C0 8.07 0 12 0 12s0 3.93.502 5.814a3.016 3.016 0 0 0 2.122 2.136c1.871.505 9.376.505 9.376.505s7.505 0 9.377-.505a3.015 3.015 0 0 0 2.122-2.136C24 15.93 24 12 24 12s0-3.93-.502-5.814zM9.545 15.568V8.432L15.818 12l-6.273 3.568z" />
  </svg>
);

// Service Card Component
interface ServiceCardProps {
  icon: React.ReactNode;
  iconColor: string;
  title: string;
  badge?: string;
  badgeColor?: string;
  description: string;
  features: string[];
  comingSoon?: boolean;
}

const ServiceCard: React.FC<ServiceCardProps> = ({
  icon,
  iconColor,
  title,
  badge,
  badgeColor = 'bg-accent-100 text-accent-700 dark:bg-accent-900/30 dark:text-accent-400',
  description,
  features,
  comingSoon = false,
}) => (
  <div className="bg-white dark:bg-dark-800 rounded-2xl border border-dark-100 dark:border-dark-700 shadow-soft dark:shadow-dark-soft overflow-hidden hover:shadow-soft-lg dark:hover:shadow-dark-lg transition-shadow duration-300">
    <div className="p-6">
      {/* Header */}
      <div className="flex items-start gap-4 mb-4">
        <div className={`w-14 h-14 rounded-xl ${iconColor} flex items-center justify-center flex-shrink-0`}>
          {icon}
        </div>
        <div className="flex-1 min-w-0">
          <div className="flex items-center gap-2 flex-wrap">
            <h3 className="text-lg font-semibold text-dark-900 dark:text-white">{title}</h3>
            {badge && (
              <span className={`px-2 py-0.5 rounded-lg text-xs font-medium ${badgeColor}`}>
                {badge}
              </span>
            )}
          </div>
          <p className="text-sm text-dark-500 dark:text-dark-400 mt-1">{description}</p>
        </div>
      </div>

      {/* Features */}
      <div className="space-y-2 mb-6">
        {features.map((feature, index) => (
          <div key={index} className="flex items-start gap-2">
            <CheckCircle size={16} className="text-accent-500 flex-shrink-0 mt-0.5" />
            <span className="text-sm text-dark-600 dark:text-dark-300">{feature}</span>
          </div>
        ))}
      </div>

      {/* Action */}
      <div className="pt-4 border-t border-dark-100 dark:border-dark-700">
        {comingSoon ? (
          <button
            disabled
            className="w-full flex items-center justify-center gap-2 py-2.5 px-4 rounded-xl text-dark-400 bg-dark-100 dark:bg-dark-700 font-medium cursor-not-allowed"
          >
            Coming Soon
          </button>
        ) : (
          <Link
            to="/orders/new"
            className="w-full flex items-center justify-center gap-2 py-2.5 px-4 rounded-xl text-white bg-primary-600 hover:bg-primary-700 font-medium transition-colors"
          >
            Order Now
            <ArrowRight size={16} />
          </Link>
        )}
      </div>
    </div>
  </div>
);

export const Services: React.FC = () => {
  return (
    <div className="space-y-8 animate-fade-in">
      {/* Header */}
      <div className="text-center max-w-2xl mx-auto">
        <h1 className="text-3xl font-bold text-dark-900 dark:text-white mb-3">
          Our Services
        </h1>
        <p className="text-dark-500 dark:text-dark-400">
          Professional social media marketing solutions at competitive prices.
          Choose from our range of services to grow your online presence.
        </p>
      </div>

      {/* YouTube Section */}
      <div>
        <div className="flex items-center gap-3 mb-4">
          <div className="w-10 h-10 rounded-xl bg-red-100 dark:bg-red-900/30 flex items-center justify-center">
            <YouTubeIcon className="w-5 h-5 text-red-600 dark:text-red-400" />
          </div>
          <h2 className="text-xl font-semibold text-dark-900 dark:text-white">YouTube Services</h2>
        </div>

        <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-6">
          <ServiceCard
            icon={<YouTubeIcon className="w-7 h-7 text-red-600 dark:text-red-400" />}
            iconColor="bg-red-100 dark:bg-red-900/30"
            title="YouTube Views"
            badge="Popular"
            badgeColor="bg-primary-100 text-primary-700 dark:bg-primary-900/30 dark:text-primary-400"
            description="Boost video visibility with premium view delivery"
            features={[
              'High-quality views',
              'Gradual delivery',
              '24/7 processing',
              'Real-time tracking',
            ]}
          />
        </div>
      </div>

      {/* Instagram Section */}
      <div>
        <div className="flex items-center gap-3 mb-4">
          <div className="w-10 h-10 rounded-xl bg-gradient-to-br from-pink-100 to-purple-100 dark:from-pink-900/30 dark:to-purple-900/30 flex items-center justify-center">
            <InstagramIcon className="w-5 h-5 text-pink-600 dark:text-pink-400" />
          </div>
          <h2 className="text-xl font-semibold text-dark-900 dark:text-white">Instagram Services</h2>
          <span className="px-2 py-0.5 rounded-lg text-xs font-medium bg-pink-100 text-pink-700 dark:bg-pink-900/30 dark:text-pink-400">
            New
          </span>
        </div>

        <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-6">
          <ServiceCard
            icon={<InstagramIcon className="w-7 h-7 text-pink-600 dark:text-pink-400" />}
            iconColor="bg-pink-100 dark:bg-pink-900/30"
            title="Instagram Likes"
            badge="New"
            badgeColor="bg-pink-100 text-pink-700 dark:bg-pink-900/30 dark:text-pink-400"
            description="Increase engagement on your posts"
            features={[
              'Real-looking accounts',
              'Fast delivery',
              'Works with reels',
              'Safe for account',
            ]}
          />

          <ServiceCard
            icon={<InstagramIcon className="w-7 h-7 text-purple-600 dark:text-purple-400" />}
            iconColor="bg-purple-100 dark:bg-purple-900/30"
            title="Instagram Followers"
            badge="New"
            badgeColor="bg-purple-100 text-purple-700 dark:bg-purple-900/30 dark:text-purple-400"
            description="Grow your Instagram audience"
            features={[
              'Real-looking profiles',
              'Gradual delivery',
              'No password required',
              'Retention guarantee',
            ]}
          />

          <ServiceCard
            icon={
              <div className="relative">
                <InstagramIcon className="w-7 h-7 text-fuchsia-600 dark:text-fuchsia-400" />
                <Sparkles size={12} className="absolute -top-1 -right-1 text-yellow-500" />
              </div>
            }
            iconColor="bg-fuchsia-100 dark:bg-fuchsia-900/30"
            title="Thematic Comments"
            badge="AI-Powered"
            badgeColor="bg-fuchsia-100 text-fuchsia-700 dark:bg-fuchsia-900/30 dark:text-fuchsia-400"
            description="AI-generated contextual comments"
            features={[
              'Matches post content',
              'Multiple languages',
              'Natural timing',
              'Custom themes',
            ]}
          />
        </div>
      </div>

      {/* How It Works */}
      <div className="bg-white dark:bg-dark-800 rounded-2xl border border-dark-100 dark:border-dark-700 p-6 shadow-soft dark:shadow-dark-soft">
        <div className="flex items-center gap-3 mb-6">
          <div className="w-10 h-10 rounded-xl bg-primary-100 dark:bg-primary-900/30 flex items-center justify-center">
            <Zap size={20} className="text-primary-600 dark:text-primary-400" />
          </div>
          <h2 className="text-lg font-semibold text-dark-900 dark:text-white">How It Works</h2>
        </div>

        <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4">
          {[
            { step: '1', title: 'Create Account', desc: 'Sign up and add funds to your balance' },
            { step: '2', title: 'Select Service', desc: 'Choose from our range of services' },
            { step: '3', title: 'Enter Details', desc: 'Paste your URL and select quantity' },
            { step: '4', title: 'Confirm Order', desc: 'Review and place your order' },
            { step: '5', title: 'Auto Processing', desc: 'We start processing immediately' },
            { step: '6', title: 'Track Progress', desc: 'Monitor order status in real-time' },
          ].map((item) => (
            <div key={item.step} className="flex items-start gap-3">
              <div className="w-8 h-8 rounded-lg bg-primary-100 dark:bg-primary-900/30 flex items-center justify-center flex-shrink-0">
                <span className="text-sm font-bold text-primary-600 dark:text-primary-400">{item.step}</span>
              </div>
              <div>
                <p className="font-medium text-dark-900 dark:text-white">{item.title}</p>
                <p className="text-sm text-dark-500 dark:text-dark-400">{item.desc}</p>
              </div>
            </div>
          ))}
        </div>
      </div>

      {/* Support */}
      <div className="bg-dark-50 dark:bg-dark-800/50 rounded-2xl border border-dark-100 dark:border-dark-700 p-6">
        <div className="flex flex-col sm:flex-row items-start sm:items-center gap-4">
          <div className="w-12 h-12 rounded-xl bg-accent-100 dark:bg-accent-900/30 flex items-center justify-center flex-shrink-0">
            <HelpCircle size={24} className="text-accent-600 dark:text-accent-400" />
          </div>
          <div className="flex-1">
            <h3 className="font-semibold text-dark-900 dark:text-white">Need Help?</h3>
            <p className="text-sm text-dark-500 dark:text-dark-400 mt-1">
              Our support team is available 24/7 to assist you with any questions or issues.
            </p>
          </div>
          <a
            href="mailto:smmdata.top@gmail.com"
            className="flex items-center gap-2 px-4 py-2 rounded-xl bg-white dark:bg-dark-700 border border-dark-200 dark:border-dark-600 text-dark-700 dark:text-dark-300 hover:bg-dark-50 dark:hover:bg-dark-600 transition-colors text-sm font-medium"
          >
            <Mail size={16} />
            Contact Support
            <ExternalLink size={14} />
          </a>
        </div>
      </div>

      {/* Footer Links */}
      <div className="flex items-center justify-between flex-wrap gap-4 pt-4 border-t border-dark-100 dark:border-dark-800">
        <div className="flex items-center gap-4 text-sm">
          <Link
            to="/terms"
            className="text-dark-500 hover:text-primary-600 dark:text-dark-400 dark:hover:text-primary-400 transition-colors"
          >
            Terms of Service
          </Link>
          <span className="text-dark-300 dark:text-dark-600">|</span>
          <a
            href="mailto:smmdata.top@gmail.com"
            className="text-dark-500 hover:text-primary-600 dark:text-dark-400 dark:hover:text-primary-400 transition-colors"
          >
            Contact Us
          </a>
        </div>
        <Link
          to="/dashboard"
          className="text-sm text-dark-500 hover:text-dark-700 dark:text-dark-400 dark:hover:text-dark-200 font-medium transition-colors"
        >
          Back to Dashboard
        </Link>
      </div>
    </div>
  );
};
