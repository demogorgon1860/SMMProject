import React from 'react';
import { Link } from 'react-router-dom';

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

// Check Icon Component
const CheckIcon: React.FC<{ className?: string }> = ({ className }) => (
  <svg className={className} fill="currentColor" viewBox="0 0 20 20">
    <path fillRule="evenodd" d="M10 18a8 8 0 100-16 8 8 0 000 16zm3.707-9.293a1 1 0 00-1.414-1.414L9 10.586 7.707 9.293a1 1 0 00-1.414 1.414l2 2a1 1 0 001.414 0l4-4z" clipRule="evenodd" />
  </svg>
);

// Feature Item Component
const FeatureItem: React.FC<{ children: React.ReactNode }> = ({ children }) => (
  <li className="flex items-start">
    <CheckIcon className="w-4 h-4 text-green-500 mr-2 mt-0.5 flex-shrink-0" />
    <span>{children}</span>
  </li>
);

// Service Card Component
interface ServiceCardProps {
  icon: React.ReactNode;
  iconBgColor: string;
  title: string;
  badge?: string;
  badgeColor?: string;
  description: string;
  features: string[];
  borderColor: string;
  gradientFrom: string;
  gradientTo: string;
  comingSoon?: boolean;
}

const ServiceCard: React.FC<ServiceCardProps> = ({
  icon,
  iconBgColor,
  title,
  badge,
  badgeColor = 'bg-green-100 text-green-800',
  description,
  features,
  borderColor,
  gradientFrom,
  gradientTo,
  comingSoon = false,
}) => (
  <div className={`border-2 ${borderColor} rounded-lg p-6 hover:shadow-lg transition-shadow bg-gradient-to-br ${gradientFrom} ${gradientTo}`}>
    <div className="flex items-start justify-between mb-4">
      <div className="flex items-center space-x-3">
        <div className={`${iconBgColor} rounded-full p-3`}>
          {icon}
        </div>
        <div>
          <h2 className="text-2xl font-bold text-gray-900">{title}</h2>
          {badge && (
            <span className={`inline-block ${badgeColor} text-xs font-semibold px-2.5 py-0.5 rounded mt-1`}>
              {badge}
            </span>
          )}
        </div>
      </div>
      {comingSoon && (
        <div className="text-right">
          <div className="text-lg font-bold text-gray-500">Coming Soon</div>
          <div className="text-sm text-gray-400">Price TBD</div>
        </div>
      )}
    </div>

    <div className="space-y-3 mb-6">
      <p className="text-gray-700 leading-relaxed">{description}</p>
    </div>

    {/* Features */}
    <div className="bg-white rounded-lg p-4 mb-4 border border-gray-100">
      <h3 className="font-semibold text-gray-900 mb-3 flex items-center">
        <svg className="w-5 h-5 text-indigo-600 mr-2" fill="none" stroke="currentColor" viewBox="0 0 24 24">
          <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M5 13l4 4L19 7" />
        </svg>
        Service Features
      </h3>
      <ul className="space-y-2 text-sm text-gray-700">
        {features.map((feature, index) => (
          <FeatureItem key={index}>{feature}</FeatureItem>
        ))}
      </ul>
    </div>

    {/* Order Button */}
    <div className="flex items-center justify-between pt-4 border-t border-gray-100">
      <div className="text-sm text-gray-600">
        <CheckIcon className="w-4 h-4 inline mr-1 text-green-500" />
        {comingSoon ? 'Available soon' : 'Instant order processing'}
      </div>
      {comingSoon ? (
        <button
          disabled
          className="bg-gray-300 text-gray-500 font-semibold px-6 py-2.5 rounded-lg cursor-not-allowed"
        >
          Coming Soon
        </button>
      ) : (
        <Link
          to="/orders/new"
          className="bg-gradient-to-r from-indigo-600 to-blue-600 hover:from-indigo-700 hover:to-blue-700 text-white font-semibold px-6 py-2.5 rounded-lg transition-all transform hover:scale-105 shadow-md"
        >
          Order Now
        </Link>
      )}
    </div>
  </div>
);

export const Services: React.FC = () => {
  return (
    <div className="max-w-6xl mx-auto">
      <div className="bg-white shadow-lg rounded-lg overflow-hidden">
        {/* Header */}
        <div className="bg-gradient-to-r from-blue-600 to-indigo-700 px-6 py-8">
          <h1 className="text-3xl font-bold text-white mb-2">Our Services</h1>
          <p className="text-blue-100 text-lg">
            Professional social media marketing solutions at competitive prices
          </p>
        </div>

        {/* Content */}
        <div className="p-6 space-y-6">
          {/* YouTube Section */}
          <div className="mb-8">
            <h2 className="text-xl font-bold text-gray-800 mb-4 flex items-center">
              <YouTubeIcon className="w-6 h-6 text-red-600 mr-2" />
              YouTube Services
            </h2>

            {/* YouTube Views Service Card */}
            <ServiceCard
              icon={<YouTubeIcon className="w-8 h-8 text-red-600" />}
              iconBgColor="bg-red-100"
              title="YouTube Views"
              badge="Most Popular"
              description="Boost your YouTube video visibility with our premium view delivery service. We provide high-quality, real-looking views that help improve your video's ranking and social proof."
              features={[
                'High-quality views',
                'Gradual delivery for natural-looking growth',
                '24/7 automated processing and monitoring',
                'Safe and compliant delivery methods',
                'Real-time order tracking and status updates',
              ]}
              borderColor="border-indigo-200"
              gradientFrom="from-white"
              gradientTo="to-indigo-50"
            />
          </div>

          {/* Instagram Section */}
          <div className="mb-8">
            <h2 className="text-xl font-bold text-gray-800 mb-4 flex items-center">
              <InstagramIcon className="w-6 h-6 text-pink-600 mr-2" />
              Instagram Services
              <span className="ml-2 bg-pink-100 text-pink-800 text-xs font-semibold px-2.5 py-0.5 rounded">
                New
              </span>
            </h2>

            <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
              {/* Instagram Likes */}
              <ServiceCard
                icon={<InstagramIcon className="w-8 h-8 text-pink-600" />}
                iconBgColor="bg-pink-100"
                title="Instagram Likes"
                badge="New"
                badgeColor="bg-pink-100 text-pink-800"
                description="Increase engagement on your Instagram posts with real-looking likes. Boost your post visibility and attract more organic engagement."
                features={[
                  'High-quality likes from real-looking accounts',
                  'Fast delivery with natural growth pattern',
                  'Works with posts and reels',
                  'Safe for your account',
                  'Real-time order tracking',
                ]}
                borderColor="border-pink-200"
                gradientFrom="from-white"
                gradientTo="to-pink-50"
                comingSoon={true}
              />

              {/* Instagram Followers */}
              <ServiceCard
                icon={<InstagramIcon className="w-8 h-8 text-purple-600" />}
                iconBgColor="bg-purple-100"
                title="Instagram Followers"
                badge="New"
                badgeColor="bg-purple-100 text-purple-800"
                description="Grow your Instagram audience with quality followers. Build social proof and increase your account's credibility and reach."
                features={[
                  'Real-looking follower profiles',
                  'Gradual delivery to avoid detection',
                  'Profile pictures and bio included',
                  'No password required',
                  'Retention guarantee',
                ]}
                borderColor="border-purple-200"
                gradientFrom="from-white"
                gradientTo="to-purple-50"
                comingSoon={true}
              />

              {/* Instagram Comments */}
              <ServiceCard
                icon={<InstagramIcon className="w-8 h-8 text-fuchsia-600" />}
                iconBgColor="bg-fuchsia-100"
                title="Instagram Thematic Comments"
                badge="AI-Powered"
                badgeColor="bg-fuchsia-100 text-fuchsia-800"
                description="Get relevant, contextual comments on your posts generated by AI. Comments are tailored to your content for authentic-looking engagement."
                features={[
                  'AI-generated contextual comments',
                  'Comments match your post content',
                  'Multiple language support',
                  'Natural timing distribution',
                  'Custom comment themes available',
                ]}
                borderColor="border-fuchsia-200"
                gradientFrom="from-white"
                gradientTo="to-fuchsia-50"
                comingSoon={true}
              />
            </div>
          </div>

          {/* Info Section */}
          <div className="bg-blue-50 border border-blue-200 rounded-lg p-4">
            <h3 className="font-semibold text-blue-900 mb-2 flex items-center">
              <svg className="w-5 h-5 text-blue-600 mr-2" fill="currentColor" viewBox="0 0 20 20">
                <path fillRule="evenodd" d="M18 10a8 8 0 11-16 0 8 8 0 0116 0zm-7-4a1 1 0 11-2 0 1 1 0 012 0zM9 9a1 1 0 000 2v3a1 1 0 001 1h1a1 1 0 100-2v-3a1 1 0 00-1-1H9z" clipRule="evenodd" />
              </svg>
              How It Works
            </h3>
            <ol className="list-decimal list-inside space-y-2 text-sm text-gray-700 ml-4">
              <li>Create an account and add funds to your balance</li>
              <li>Select a service and click "Order Now"</li>
              <li>Paste your YouTube video URL or Instagram post/profile URL</li>
              <li>Select the quantity you want to purchase</li>
              <li>Confirm your order and we'll start processing immediately</li>
              <li>Track your order progress in real-time from your dashboard</li>
            </ol>
          </div>

          {/* Support Info */}
          <div className="bg-gray-50 border border-gray-200 rounded-lg p-4">
            <div className="flex items-start space-x-3">
              <svg className="w-6 h-6 text-green-600 flex-shrink-0 mt-0.5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M18.364 5.636l-3.536 3.536m0 5.656l3.536 3.536M9.172 9.172L5.636 5.636m3.536 9.192l-3.536 3.536M21 12a9 9 0 11-18 0 9 9 0 0118 0zm-5 0a4 4 0 11-8 0 4 4 0 018 0z" />
              </svg>
              <div>
                <h3 className="font-semibold text-gray-900 mb-1">Need Help?</h3>
                <p className="text-sm text-gray-700 mb-2">
                  Our support team is available 24/7 to assist you with any questions or issues.
                </p>
                <a
                  href="mailto:smmdata.top@gmail.com"
                  className="text-sm text-indigo-600 hover:text-indigo-800 font-medium"
                >
                  Contact Support: smmdata.top@gmail.com
                </a>
              </div>
            </div>
          </div>
        </div>

        {/* Footer */}
        <div className="bg-gray-100 px-6 py-4 border-t border-gray-200">
          <div className="flex items-center justify-between flex-wrap gap-2">
            <div className="text-sm text-gray-600">
              <Link to="/terms" className="text-indigo-600 hover:text-indigo-800 font-medium">
                Terms of Service
              </Link>
              {' · '}
              <a href="mailto:smmdata.top@gmail.com" className="text-indigo-600 hover:text-indigo-800 font-medium">
                Contact Us
              </a>
            </div>
            <Link
              to="/dashboard"
              className="text-sm text-gray-600 hover:text-gray-900 font-medium"
            >
              ← Back to Dashboard
            </Link>
          </div>
        </div>
      </div>
    </div>
  );
};
