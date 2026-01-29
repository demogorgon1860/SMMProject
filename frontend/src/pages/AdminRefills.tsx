import React, { useEffect, useState } from 'react';
import { adminAPI } from '../services/api';
import { formatDateShort } from '../utils/timezone';

interface AdminRefill {
  refillId: number;
  originalOrderId: number;
  refillOrderId: number;
  refillNumber: number;
  originalQuantity: number;
  deliveredQuantity: number;
  refillQuantity: number;
  startCountAtRefill: number;
  username: string;
  link: string;
  status: string;
  startCount: number;
  remains: number;
  refillCreatedAt: string;
  orderName: string;
  binomOfferId: string;
  youtubeVideoId: string;
}

export const AdminRefills: React.FC = () => {
  const [refills, setRefills] = useState<AdminRefill[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    fetchRefills();
  }, []);

  const fetchRefills = async () => {
    try {
      const response = await adminAPI.getAllRefills();
      if (response.refills) {
        setRefills(response.refills);
      } else {
        setRefills([]);
      }
    } catch (error: any) {
      console.error('Error fetching refills:', error);
      setError(error.response?.data?.message || 'Failed to fetch refills');
      setRefills([]);
    } finally {
      setLoading(false);
    }
  };

  const formatDate = (dateString: string) => {
    return formatDateShort(dateString);
  };

  const getStatusColor = (status: string) => {
    switch (status.toUpperCase()) {
      case 'COMPLETED':
        return 'bg-green-100 text-green-800';
      case 'IN_PROGRESS':
        return 'bg-blue-100 text-blue-800';
      case 'PENDING':
        return 'bg-yellow-100 text-yellow-800';
      case 'PARTIAL':
        return 'bg-orange-100 text-orange-800';
      case 'CANCELLED':
        return 'bg-red-100 text-red-800';
      case 'ERROR':
        return 'bg-red-100 text-red-800';
      default:
        return 'bg-gray-100 text-gray-800';
    }
  };

  const formatLink = (link: string) => {
    if (link.length > 40) {
      return link.substring(0, 37) + '...';
    }
    return link;
  };

  if (loading) {
    return (
      <div className="flex items-center justify-center min-h-screen">
        <div className="text-center">
          <div className="inline-block animate-spin rounded-full h-8 w-8 border-b-2 border-blue-600"></div>
          <p className="mt-2 text-gray-600">Loading refills...</p>
        </div>
      </div>
    );
  }

  if (error) {
    return (
      <div className="flex items-center justify-center min-h-screen">
        <div className="text-center text-red-600">
          <p className="text-xl font-semibold">Error</p>
          <p>{error}</p>
        </div>
      </div>
    );
  }

  return (
    <div className="w-full">
      <div className="px-4 py-6">
        <div className="mb-6">
          <h1 className="text-2xl font-bold text-gray-900">Order Refills</h1>
          <p className="text-gray-600 mt-2">View all order refills across the system</p>
        </div>

        <div className="flex justify-end mb-4">
          <button
            onClick={fetchRefills}
            className="bg-blue-500 hover:bg-blue-600 text-white px-6 py-2 rounded-md transition font-medium"
          >
            Refresh
          </button>
        </div>
      </div>

      {refills.length === 0 ? (
        <div className="bg-white rounded-lg shadow-md p-12">
          <div className="text-center">
            <svg className="mx-auto h-12 w-12 text-gray-400" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M9 5H7a2 2 0 00-2 2v12a2 2 0 002 2h10a2 2 0 002-2V7a2 2 0 00-2-2h-2M9 5a2 2 0 002 2h2a2 2 0 002-2M9 5a2 2 0 012-2h2a2 2 0 012 2" />
            </svg>
            <p className="text-gray-500 text-lg mt-4">No refills found</p>
            <p className="text-gray-400 text-sm mt-2">Refills will appear here when orders are refilled</p>
          </div>
        </div>
      ) : (
        <div className="w-full bg-white shadow-md overflow-hidden">
          <div className="overflow-x-auto">
            <table className="w-full table-auto divide-y divide-gray-200">
              <thead className="bg-gray-50">
                <tr>
                  <th scope="col" className="px-6 py-3 text-left text-xs font-medium text-gray-700 uppercase tracking-wider">
                    Refill ID
                  </th>
                  <th scope="col" className="px-6 py-3 text-left text-xs font-medium text-gray-700 uppercase tracking-wider">
                    Order Name
                  </th>
                  <th scope="col" className="px-6 py-3 text-left text-xs font-medium text-gray-700 uppercase tracking-wider">
                    User
                  </th>
                  <th scope="col" className="px-6 py-3 text-left text-xs font-medium text-gray-700 uppercase tracking-wider">
                    Link
                  </th>
                  <th scope="col" className="px-6 py-3 text-center text-xs font-medium text-gray-700 uppercase tracking-wider">
                    Original Qty
                  </th>
                  <th scope="col" className="px-6 py-3 text-center text-xs font-medium text-gray-700 uppercase tracking-wider">
                    Delivered
                  </th>
                  <th scope="col" className="px-6 py-3 text-center text-xs font-medium text-gray-700 uppercase tracking-wider">
                    Refill Qty
                  </th>
                  <th scope="col" className="px-6 py-3 text-center text-xs font-medium text-gray-700 uppercase tracking-wider">
                    Status
                  </th>
                  <th scope="col" className="px-6 py-3 text-center text-xs font-medium text-gray-700 uppercase tracking-wider">
                    Remains
                  </th>
                  <th scope="col" className="px-6 py-3 text-left text-xs font-medium text-gray-700 uppercase tracking-wider">
                    Created
                  </th>
                </tr>
              </thead>
              <tbody className="bg-white divide-y divide-gray-200">
                {refills.map((refill) => (
                  <tr key={refill.refillId} className="hover:bg-gray-50 transition">
                    <td className="px-6 py-4 whitespace-nowrap">
                      <div className="text-sm font-medium text-gray-900">{refill.refillId}</div>
                      <div className="text-xs text-gray-400">{refill.binomOfferId || '-'}</div>
                    </td>
                    <td className="px-6 py-4 whitespace-nowrap">
                      <div className="text-sm font-medium text-gray-900">{refill.orderName}</div>
                      <div className="text-xs text-gray-400">Refill Order #{refill.refillOrderId}</div>
                    </td>
                    <td className="px-6 py-4 whitespace-nowrap">
                      <div className="text-sm text-gray-900">{refill.username}</div>
                    </td>
                    <td className="px-6 py-4">
                      <div className="text-sm text-gray-900 max-w-xs truncate" title={refill.link}>
                        <a href={refill.link} target="_blank" rel="noopener noreferrer" className="text-blue-600 hover:text-blue-800">
                          {formatLink(refill.link)}
                        </a>
                      </div>
                      {refill.youtubeVideoId && (
                        <div className="text-xs text-gray-400">Video: {refill.youtubeVideoId}</div>
                      )}
                    </td>
                    <td className="px-6 py-4 whitespace-nowrap text-center">
                      <div className="text-sm text-gray-900">{refill.originalQuantity.toLocaleString()}</div>
                    </td>
                    <td className="px-6 py-4 whitespace-nowrap text-center">
                      <div className="text-sm text-gray-900">{refill.deliveredQuantity.toLocaleString()}</div>
                      <div className="text-xs text-gray-400">
                        {((refill.deliveredQuantity / refill.originalQuantity) * 100).toFixed(1)}%
                      </div>
                    </td>
                    <td className="px-6 py-4 whitespace-nowrap text-center">
                      <span className="inline-flex items-center px-2.5 py-0.5 rounded-full text-xs font-medium bg-blue-100 text-blue-800">
                        {refill.refillQuantity.toLocaleString()}
                      </span>
                    </td>
                    <td className="px-6 py-4 whitespace-nowrap text-center">
                      <span className={`px-3 py-1 inline-flex text-xs leading-5 font-semibold rounded-full ${getStatusColor(refill.status)}`}>
                        {refill.status}
                      </span>
                    </td>
                    <td className="px-6 py-4 whitespace-nowrap text-center">
                      <div className="text-sm text-gray-900">
                        {refill.remains !== null && refill.remains !== undefined ? refill.remains.toLocaleString() : '-'}
                      </div>
                    </td>
                    <td className="px-6 py-4 whitespace-nowrap">
                      <div className="text-sm text-gray-900">{formatDate(refill.refillCreatedAt)}</div>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>

          {/* Table footer with row count */}
          <div className="bg-gray-50 px-6 py-3 border-t border-gray-200">
            <div className="text-sm text-gray-600">
              Showing <span className="font-medium">{refills.length}</span> refill{refills.length !== 1 ? 's' : ''}
            </div>
          </div>
        </div>
      )}
    </div>
  );
};
