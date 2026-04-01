import React from 'react';
import { useNavigate } from 'react-router-dom';
import { motion } from 'framer-motion';
import { ArrowLeft } from 'lucide-react';

const containerVariants = {
  hidden: { opacity: 0 },
  visible: { opacity: 1, transition: { staggerChildren: 0.06, delayChildren: 0.1 } },
};

const itemVariants = {
  hidden: { opacity: 0, y: 16 },
  visible: { opacity: 1, y: 0, transition: { duration: 0.4, ease: [0.16, 1, 0.3, 1] } },
};

const sectionColors = [
  'bg-primary-100 text-primary-700 dark:bg-primary-900/40 dark:text-primary-300',
  'bg-accent-100 text-accent-700 dark:bg-accent-900/40 dark:text-accent-300',
  'bg-purple-100 text-purple-700 dark:bg-purple-900/40 dark:text-purple-300',
  'bg-blue-100 text-blue-700 dark:bg-blue-900/40 dark:text-blue-300',
  'bg-red-100 text-red-700 dark:bg-red-900/40 dark:text-red-300',
  'bg-yellow-100 text-yellow-800 dark:bg-yellow-900/40 dark:text-yellow-300',
  'bg-teal-100 text-teal-700 dark:bg-teal-900/40 dark:text-teal-300',
];

export const TermsOfService: React.FC = () => {
  const navigate = useNavigate();

  return (
    <div className="min-h-screen bg-gradient-to-br from-dark-50 to-dark-100 dark:from-dark-950 dark:to-dark-900 py-12 px-4 sm:px-6 lg:px-8 transition-colors duration-300">
      <motion.div
        className="max-w-4xl mx-auto"
        variants={containerVariants}
        initial="hidden"
        animate="visible"
      >
        <motion.div
          className="bg-white dark:bg-dark-800 shadow-soft-lg dark:shadow-dark-lg rounded-2xl overflow-hidden border border-dark-100 dark:border-dark-700"
          variants={itemVariants}
        >
          {/* Header */}
          <div className="bg-gradient-to-r from-primary-600 to-primary-700 dark:from-primary-700 dark:to-primary-800 px-8 py-10 text-center">
            <h1 className="text-4xl font-extrabold text-white mb-3">TERMS OF SERVICE</h1>
            <p className="text-primary-100 text-lg font-medium">USER AGREEMENT</p>
            <div className="mt-4 inline-block bg-white/20 rounded-full px-6 py-2">
              <p className="text-white text-sm font-semibold">Last updated: 11.03.2025</p>
            </div>
          </div>

          {/* Content */}
          <div className="px-8 py-10 space-y-8">
            <Section num={1} title="WHAT IS COVERED IN THIS AGREEMENT" color={sectionColors[0]}>
              <p>
                This User Agreement establishes the terms and conditions for using our services.
                It explains what you can expect from us and what we expect from you.
              </p>
              <p className="font-semibold text-dark-800 dark:text-dark-200">The Agreement covers:</p>
              <ul className="list-disc list-inside space-y-2 ml-4">
                <li>What you can expect from us — how we provide and develop our services;</li>
                <li>What we expect from you — the rules and responsibilities when using our services;</li>
                <li>What to expect in case of problems, disagreements, or violations of these terms.</li>
              </ul>
              <div className="mt-4 bg-primary-50 dark:bg-primary-900/20 border-l-4 border-primary-500 p-4 rounded-r-lg">
                <p className="text-primary-900 dark:text-primary-200 font-medium">
                  By using our services, you confirm that you have read and agreed to these Terms of Service.
                </p>
              </div>
            </Section>

            <Section num={2} title="WHAT YOU CAN EXPECT FROM US" color={sectionColors[1]}>
              <p className="font-semibold text-dark-800 dark:text-dark-200">We are committed to providing:</p>
              <ul className="list-disc list-inside space-y-2 ml-4">
                <li>A wide range of useful and effective services;</li>
                <li>High-quality products;</li>
                <li>Professional and qualified customer support;</li>
                <li>A simple and secure automatic payment process;</li>
                <li>Competitive and favorable pricing;</li>
                <li>Opportunities to start or develop your own business using our platform.</li>
              </ul>
            </Section>

            <Section num={3} title="WHAT WE EXPECT FROM YOU" color={sectionColors[2]}>
              <p className="font-semibold text-dark-800 dark:text-dark-200">We expect all users to:</p>
              <ul className="list-disc list-inside space-y-2 ml-4">
                <li>Comply with our rules and policies;</li>
                <li>Use our services responsibly and lawfully;</li>
                <li>Complete the Know Your Customer (KYC) verification process if requested;</li>
                <li>Maintain a respectful attitude toward our staff and other users.</li>
              </ul>
            </Section>

            <Section num={4} title="OTHER RIGHTS YOU HAVE" color={sectionColors[3]}>
              <p className="font-semibold text-dark-800 dark:text-dark-200">As a user, you have the right to:</p>
              <ul className="list-disc list-inside space-y-2 ml-4">
                <li>Request cancellation of your order in accordance with these Terms of Service;</li>
                <li>Request a withdrawal of your funds in accordance with our Refund Policy;</li>
                <li>Be informed about all relevant opportunities and aspects of cooperation;</li>
                <li>Expect secure handling of your personal data.</li>
              </ul>
            </Section>

            <Section num={5} title="POSSIBLE CONSEQUENCES OF VIOLATING OUR RULES" color={sectionColors[4]}>
              <p className="font-semibold text-dark-800 dark:text-dark-200">Violation of our rules may result in the following actions:</p>
              <ul className="list-disc list-inside space-y-2 ml-4">
                <li>Account suspension or blocking after prior warning from our support team;</li>
                <li>Restriction or disabling of payment options in case of suspicious payment activity;</li>
                <li>Account blocking if you fail to respond to our attempts to contact you regarding suspicious transactions;</li>
                <li>Permanent account termination in case of fraudulent activity, including the use of unauthorized or stolen credit cards.</li>
              </ul>
            </Section>

            <Section num={6} title="TERMS OF SERVICE" color={sectionColors[5]}>
              <div className="space-y-6">
                <div>
                  <h3 className="text-xl font-bold text-dark-900 dark:text-white mb-3">6.1 Cancellation Requests</h3>
                  <p className="mb-3">Cancellation requests may be rejected in the following cases:</p>
                  <div className="space-y-4">
                    <SubSection title="a) Dropped Start Count">
                      <p>You are responsible for monitoring your start counts, as our services rely on accurate data. We are not responsible if your actual number of followers, likes, plays, or views drops below the initial start count.</p>
                      <p>If you own a large or unstable account with fluctuating metrics, you acknowledge that "drops" are almost unavoidable. We are not obligated to replenish them.</p>
                    </SubSection>
                    <SubSection title="b) Wrong Orders">
                      <p>Carefully read the description of each service before placing an order. Orders submitted with mistakes made by you will not be canceled.</p>
                      <p>Do not place an order with another provider using the same link until your current order has been completed.</p>
                    </SubSection>
                    <SubSection title="c) Unavailable Links">
                      <ul className="list-disc list-inside space-y-1 ml-4">
                        <li>Do not make your page private;</li>
                        <li>Do not change your username;</li>
                        <li>Do not delete your page.</li>
                      </ul>
                      <p className="mt-2">In such cases, your orders may be automatically marked as Completed.</p>
                    </SubSection>
                    <SubSection title="d) Duplicated Orders">
                      <p>The system operates according to start counts and may automatically mark your order as Completed once the required quantity is reached.</p>
                      <ul className="list-disc list-inside space-y-1 ml-4">
                        <li>Do not place multiple orders for the same link simultaneously.</li>
                        <li>Do not place identical orders twice until the first one has been fully processed.</li>
                        <li>If your order has experienced a drop, wait for us to Refill, Cancel, or Partially Complete it before submitting a new one.</li>
                      </ul>
                    </SubSection>
                  </div>
                </div>
                <div>
                  <h3 className="text-xl font-bold text-dark-900 dark:text-white mb-3">6.2 Late Delivery and Drops</h3>
                  <p className="mb-3">Delivery times may vary due to numerous external factors. Delayed delivery does not entitle you to a refund.</p>
                  <p className="font-semibold text-dark-800 dark:text-dark-200 mb-2">Accordingly:</p>
                  <ul className="list-disc list-inside space-y-1 ml-4">
                    <li>If you ordered a guaranteed service, we will refill the missing amount.</li>
                    <li>If you ordered a non-guaranteed service, you were informed about this before placing your order.</li>
                  </ul>
                </div>
              </div>
            </Section>

            <Section num={7} title="GENERAL CONDITIONS" color={sectionColors[6]}>
              <p>By placing an order on our platform, you automatically accept all the terms listed herein, regardless of whether you have read them in full.</p>
              <p>We reserve the right to amend these Terms of Service at any time without prior notice.</p>
              <p>You are responsible for reviewing the Terms before placing each order to ensure you are aware of any updates or modifications.</p>
              <p>You agree to use our website in compliance with the Terms of Service of each respective social media platform.</p>
              <p>Our rates are subject to change at any time without prior notice.</p>
              <p>We do not guarantee specific delivery times for any service. Delivery times provided are estimates only and may vary.</p>
              <p>We make every effort to deliver exactly what is expected by our resellers and customers.</p>
            </Section>

            {/* Footer notice */}
            <div className="border-t-2 border-dark-200 dark:border-dark-600 pt-8 mt-8">
              <div className="bg-gradient-to-r from-primary-50 to-primary-100/50 dark:from-primary-900/20 dark:to-primary-900/10 border-l-4 border-primary-500 p-6 rounded-r-lg">
                <p className="text-dark-800 dark:text-dark-200 font-semibold text-center text-lg">
                  Thank you for taking the time to review our Terms of Service.
                </p>
                <p className="text-dark-600 dark:text-dark-400 text-center mt-2">
                  If you have any questions or concerns, please contact our support team.
                </p>
              </div>
            </div>
          </div>
        </motion.div>

        {/* Back button */}
        <motion.div className="mt-8 text-center" variants={itemVariants}>
          <button
            onClick={() => navigate(-1)}
            className="inline-flex items-center px-6 py-3 bg-white dark:bg-dark-800 text-primary-600 dark:text-primary-400 font-semibold rounded-xl shadow-soft dark:shadow-dark-soft border border-dark-100 dark:border-dark-700 hover:bg-dark-50 dark:hover:bg-dark-700 transition-colors duration-200"
          >
            <ArrowLeft size={20} className="mr-2" />
            Go back
          </button>
        </motion.div>
      </motion.div>
    </div>
  );
};

function Section({ num, title, color, children }: { num: number; title: string; color: string; children: React.ReactNode }) {
  return (
    <motion.section
      className={num > 1 ? 'border-t border-dark-200 dark:border-dark-700 pt-8' : ''}
      variants={itemVariants}
    >
      <div className="flex items-center mb-4">
        <div className={`rounded-full w-10 h-10 flex items-center justify-center font-bold text-lg mr-3 ${color}`}>
          {num}
        </div>
        <h2 className="text-2xl font-bold text-dark-900 dark:text-white">{title}</h2>
      </div>
      <div className="ml-13 space-y-3 text-dark-700 dark:text-dark-300 leading-relaxed">
        {children}
      </div>
    </motion.section>
  );
}

function SubSection({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <div>
      <h4 className="font-bold text-dark-800 dark:text-dark-200 mb-2">{title}</h4>
      <div className="space-y-2">{children}</div>
    </div>
  );
}
