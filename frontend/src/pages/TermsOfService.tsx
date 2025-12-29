import React from 'react';
import { useNavigate } from 'react-router-dom';

export const TermsOfService: React.FC = () => {
  const navigate = useNavigate();

  return (
    <div className="min-h-screen bg-gradient-to-br from-gray-50 to-gray-100 py-12 px-4 sm:px-6 lg:px-8">
      <div className="max-w-4xl mx-auto">
        <div className="bg-white shadow-2xl rounded-2xl overflow-hidden">
          {/* Header */}
          <div className="bg-gradient-to-r from-blue-600 to-blue-700 px-8 py-10 text-center">
            <h1 className="text-4xl font-extrabold text-white mb-3">
              TERMS OF SERVICE
            </h1>
            <p className="text-blue-100 text-lg font-medium">USER AGREEMENT</p>
            <div className="mt-4 inline-block bg-white/20 rounded-full px-6 py-2">
              <p className="text-white text-sm font-semibold">Last updated: 11.03.2025</p>
            </div>
          </div>

          {/* Content */}
          <div className="px-8 py-10 space-y-8">
            {/* Section 1 */}
            <section>
              <div className="flex items-center mb-4">
                <div className="bg-blue-100 text-blue-700 rounded-full w-10 h-10 flex items-center justify-center font-bold text-lg mr-3">
                  1
                </div>
                <h2 className="text-2xl font-bold text-gray-900">
                  WHAT IS COVERED IN THIS AGREEMENT
                </h2>
              </div>
              <div className="ml-13 space-y-3 text-gray-700 leading-relaxed">
                <p>
                  This User Agreement establishes the terms and conditions for using our services.
                  It explains what you can expect from us and what we expect from you.
                </p>
                <p className="font-semibold text-gray-800">The Agreement covers:</p>
                <ul className="list-disc list-inside space-y-2 ml-4">
                  <li>What you can expect from us — how we provide and develop our services;</li>
                  <li>What we expect from you — the rules and responsibilities when using our services;</li>
                  <li>What to expect in case of problems, disagreements, or violations of these terms.</li>
                </ul>
                <div className="mt-4 bg-blue-50 border-l-4 border-blue-500 p-4 rounded-r-lg">
                  <p className="text-blue-900 font-medium">
                    By using our services, you confirm that you have read and agreed to these Terms of Service.
                  </p>
                </div>
              </div>
            </section>

            {/* Section 2 */}
            <section className="border-t border-gray-200 pt-8">
              <div className="flex items-center mb-4">
                <div className="bg-green-100 text-green-700 rounded-full w-10 h-10 flex items-center justify-center font-bold text-lg mr-3">
                  2
                </div>
                <h2 className="text-2xl font-bold text-gray-900">
                  WHAT YOU CAN EXPECT FROM US
                </h2>
              </div>
              <div className="ml-13 space-y-3 text-gray-700 leading-relaxed">
                <p className="font-semibold text-gray-800">We are committed to providing:</p>
                <ul className="list-disc list-inside space-y-2 ml-4">
                  <li>A wide range of useful and effective services;</li>
                  <li>High-quality products;</li>
                  <li>Professional and qualified customer support;</li>
                  <li>A simple and secure automatic payment process;</li>
                  <li>Competitive and favorable pricing;</li>
                  <li>Opportunities to start or develop your own business using our platform.</li>
                </ul>
              </div>
            </section>

            {/* Section 3 */}
            <section className="border-t border-gray-200 pt-8">
              <div className="flex items-center mb-4">
                <div className="bg-purple-100 text-purple-700 rounded-full w-10 h-10 flex items-center justify-center font-bold text-lg mr-3">
                  3
                </div>
                <h2 className="text-2xl font-bold text-gray-900">
                  WHAT WE EXPECT FROM YOU
                </h2>
              </div>
              <div className="ml-13 space-y-3 text-gray-700 leading-relaxed">
                <p className="font-semibold text-gray-800">We expect all users to:</p>
                <ul className="list-disc list-inside space-y-2 ml-4">
                  <li>Comply with our rules and policies;</li>
                  <li>Use our services responsibly and lawfully;</li>
                  <li>Complete the Know Your Customer (KYC) verification process if requested;</li>
                  <li>Maintain a respectful attitude toward our staff and other users.</li>
                </ul>
              </div>
            </section>

            {/* Section 4 */}
            <section className="border-t border-gray-200 pt-8">
              <div className="flex items-center mb-4">
                <div className="bg-indigo-100 text-indigo-700 rounded-full w-10 h-10 flex items-center justify-center font-bold text-lg mr-3">
                  4
                </div>
                <h2 className="text-2xl font-bold text-gray-900">
                  OTHER RIGHTS YOU HAVE
                </h2>
              </div>
              <div className="ml-13 space-y-3 text-gray-700 leading-relaxed">
                <p className="font-semibold text-gray-800">As a user, you have the right to:</p>
                <ul className="list-disc list-inside space-y-2 ml-4">
                  <li>Request cancellation of your order in accordance with these Terms of Service;</li>
                  <li>Request a withdrawal of your funds in accordance with our Refund Policy;</li>
                  <li>Be informed about all relevant opportunities and aspects of cooperation;</li>
                  <li>Expect secure handling of your personal data.</li>
                </ul>
              </div>
            </section>

            {/* Section 5 */}
            <section className="border-t border-gray-200 pt-8">
              <div className="flex items-center mb-4">
                <div className="bg-red-100 text-red-700 rounded-full w-10 h-10 flex items-center justify-center font-bold text-lg mr-3">
                  5
                </div>
                <h2 className="text-2xl font-bold text-gray-900">
                  POSSIBLE CONSEQUENCES OF VIOLATING OUR RULES
                </h2>
              </div>
              <div className="ml-13 space-y-3 text-gray-700 leading-relaxed">
                <p className="font-semibold text-gray-800">Violation of our rules may result in the following actions:</p>
                <ul className="list-disc list-inside space-y-2 ml-4">
                  <li>Account suspension or blocking after prior warning from our support team;</li>
                  <li>Restriction or disabling of payment options in case of suspicious payment activity;</li>
                  <li>Account blocking if you fail to respond to our attempts to contact you regarding suspicious transactions;</li>
                  <li>Permanent account termination in case of fraudulent activity, including the use of unauthorized or stolen credit cards.</li>
                </ul>
              </div>
            </section>

            {/* Section 6 */}
            <section className="border-t border-gray-200 pt-8">
              <div className="flex items-center mb-4">
                <div className="bg-yellow-100 text-yellow-800 rounded-full w-10 h-10 flex items-center justify-center font-bold text-lg mr-3">
                  6
                </div>
                <h2 className="text-2xl font-bold text-gray-900">
                  TERMS OF SERVICE
                </h2>
              </div>
              <div className="ml-13 space-y-6 text-gray-700 leading-relaxed">
                {/* 6.1 */}
                <div>
                  <h3 className="text-xl font-bold text-gray-900 mb-3">6.1 Cancellation Requests</h3>
                  <p className="mb-3">Cancellation requests may be rejected in the following cases:</p>

                  <div className="space-y-4">
                    <div>
                      <h4 className="font-bold text-gray-800 mb-2">a) Dropped Start Count</h4>
                      <p className="mb-2">
                        You are responsible for monitoring your start counts, as our services rely on accurate data.
                        We are not responsible if your actual number of followers, likes, plays, or views drops below
                        the initial start count.
                      </p>
                      <p className="mb-2">
                        If you own a large or unstable account with fluctuating metrics, you acknowledge that "drops"
                        are almost unavoidable. We are not obligated to replenish them. By placing an order, you accept
                        full responsibility for your previous follower/like/play/view count.
                      </p>
                    </div>

                    <div>
                      <h4 className="font-bold text-gray-800 mb-2">b) Wrong Orders</h4>
                      <p className="mb-2">
                        Carefully read the description of each service before placing an order. Orders submitted with
                        mistakes made by you will not be canceled.
                      </p>
                      <p className="mb-2">
                        Ensure that you enter the correct link, quantity, interval, and other required information to
                        avoid errors.
                      </p>
                      <p>
                        Do not place an order with another provider using the same link until your current order has
                        been completed.
                      </p>
                    </div>

                    <div>
                      <h4 className="font-bold text-gray-800 mb-2">c) Unavailable Links</h4>
                      <ul className="list-disc list-inside space-y-1 ml-4">
                        <li>Do not make your page private;</li>
                        <li>Do not change your username;</li>
                        <li>Do not delete your page.</li>
                      </ul>
                      <p className="mt-2">
                        In such cases, your orders may be automatically marked as Completed.
                      </p>
                    </div>

                    <div>
                      <h4 className="font-bold text-gray-800 mb-2">d) Duplicated Orders</h4>
                      <p className="mb-2">
                        The system operates according to start counts and may automatically mark your order as Completed
                        once the required quantity is reached.
                      </p>
                      <ul className="list-disc list-inside space-y-1 ml-4">
                        <li>Do not place multiple orders for the same link simultaneously. Wait until the first order is Completed, Canceled, or Partial.</li>
                        <li>Do not place identical orders twice until the first one has been fully processed.</li>
                        <li>If your order has experienced a drop, wait for us to Refill, Cancel, or Partially Complete it before submitting a new one.</li>
                        <li>Do not request cancellations using reasons such as "followers/likes/plays/views came from real users, fans, or other sellers."</li>
                      </ul>
                    </div>
                  </div>
                </div>

                {/* 6.2 */}
                <div>
                  <h3 className="text-xl font-bold text-gray-900 mb-3">6.2 Late Delivery and Drops</h3>
                  <p className="mb-3">
                    Delivery times may vary due to numerous external factors. Delayed delivery does not entitle you to
                    a refund. Only "speed up" requests may be accepted.
                  </p>
                  <p className="mb-3">
                    Each service description specifies whether it includes a refill guarantee.
                  </p>
                  <p className="font-semibold text-gray-800 mb-2">Accordingly:</p>
                  <ul className="list-disc list-inside space-y-1 ml-4">
                    <li>If you ordered a guaranteed service, we will refill the missing amount.</li>
                    <li>If you ordered a non-guaranteed service, you were informed about this before placing your order.</li>
                  </ul>
                </div>
              </div>
            </section>

            {/* Section 7 */}
            <section className="border-t border-gray-200 pt-8">
              <div className="flex items-center mb-4">
                <div className="bg-teal-100 text-teal-700 rounded-full w-10 h-10 flex items-center justify-center font-bold text-lg mr-3">
                  7
                </div>
                <h2 className="text-2xl font-bold text-gray-900">
                  GENERAL CONDITIONS
                </h2>
              </div>
              <div className="ml-13 space-y-3 text-gray-700 leading-relaxed">
                <p>
                  By placing an order on our platform, you automatically accept all the terms listed herein, regardless
                  of whether you have read them in full.
                </p>
                <p>
                  We reserve the right to amend these Terms of Service at any time without prior notice.
                </p>
                <p>
                  You are responsible for reviewing the Terms before placing each order to ensure you are aware of any
                  updates or modifications.
                </p>
                <p>
                  You agree to use our website in compliance with the Terms of Service of each respective social media platform.
                </p>
                <p>
                  Our rates are subject to change at any time without prior notice. These Terms remain valid in the event
                  of pricing adjustments.
                </p>
                <p>
                  We do not guarantee specific delivery times for any service. Delivery times provided are estimates only
                  and may vary. Orders that are processing will not be refunded due to perceived delays.
                </p>
                <p>
                  We make every effort to deliver exactly what is expected by our resellers and customers. If necessary,
                  we reserve the right to modify the service type to complete an order successfully.
                </p>
              </div>
            </section>

            {/* Footer notice */}
            <div className="border-t-2 border-gray-300 pt-8 mt-8">
              <div className="bg-gradient-to-r from-blue-50 to-indigo-50 border-l-4 border-blue-500 p-6 rounded-r-lg">
                <p className="text-gray-800 font-semibold text-center text-lg">
                  Thank you for taking the time to review our Terms of Service.
                </p>
                <p className="text-gray-600 text-center mt-2">
                  If you have any questions or concerns, please contact our support team.
                </p>
              </div>
            </div>
          </div>
        </div>

        {/* Back button */}
        <div className="mt-8 text-center">
          <button
            onClick={() => navigate(-1)}
            className="inline-flex items-center px-6 py-3 bg-white text-blue-600 font-semibold rounded-lg shadow-md hover:bg-blue-50 transition-colors duration-200"
          >
            <svg className="w-5 h-5 mr-2" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M10 19l-7-7m0 0l7-7m-7 7h18" />
            </svg>
            Go back
          </button>
        </div>
      </div>
    </div>
  );
};
