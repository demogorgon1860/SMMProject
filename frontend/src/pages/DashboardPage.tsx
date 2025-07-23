import React, { useState } from 'react'
import { useQuery } from 'react-query'
import { Helmet } from 'react-helmet-async'
import { Link } from 'react-router-dom'
import { motion } from 'framer-motion'
import { LineChart, Line, XAxis, YAxis, CartesianGrid, Tooltip, ResponsiveContainer, PieChart, Pie, Cell } from 'recharts'
import { 
  PlusIcon, 
  CreditCardIcon, 
  CheckCircleIcon,
  PlayIcon,
  DocumentTextIcon,
  CurrencyDollarIcon,
  ChartBarIcon
} from 'lucide-react'

// Components
import { Layout } from '@/components/layout/Layout'
import { Card, Button, Badge, LoadingSpinner, EmptyState } from '@/components/ui'

// Services & Types
import { apiService } from '@/services/api'
import { useAuth } from '@/store/auth'
import { useOrderUpdates } from '@/store/websocket'
import type { Order, DashboardStats } from '@/types'

// Utils
import { formatCurrency, formatDate, formatNumber } from '@/utils/format'

const DashboardPage: React.FC = () => {
  const { user } = useAuth()
  const { orders: realtimeOrders } = useOrderUpdates()
  const [timeRange, setTimeRange] = useState<'24h' | '7d' | '30d'>('7d')

  // Fetch dashboard stats
  const { data: stats, isLoading: statsLoading } = useQuery<DashboardStats>(
    ['dashboard-stats', timeRange],
    () => apiService.admin.getDashboardStats(),
    { refetchInterval: 30000, staleTime: 20000 }
  )

  // Fetch recent orders
  const { data: ordersData, isLoading: ordersLoading } = useQuery(
    ['recent-orders'],
    () => apiService.orders.getAll({ page: 0, size: 10 }),
    { refetchInterval: 60000 }
  )

  // Fetch current balance
  const { data: balance, isLoading: balanceLoading } = useQuery(
    ['balance'],
    () => apiService.balance.get(),
    { refetchInterval: 30000 }
  )

  const recentOrders = ordersData?.content || []
  const mergedOrders = React.useMemo(() => {
    if (!recentOrders.length) return []
    const orderMap = new Map(recentOrders.map(order => [order.id, order]))
    realtimeOrders.forEach(order => orderMap.has(order.id) && orderMap.set(order.id, order))
    return Array.from(orderMap.values()).slice(0, 10)
  }, [recentOrders, realtimeOrders])

  const getStatusBadge = (status: string) => {
    const statusMap = {
      'PENDING': { variant: 'warning' as const, label: 'Pending' },
      'IN_PROGRESS': { variant: 'primary' as const, label: 'In Progress' },
      'PROCESSING': { variant: 'primary' as const, label: 'Processing' },
      'ACTIVE': { variant: 'success' as const, label: 'Active' },
      'PARTIAL': { variant: 'warning' as const, label: 'Partial' },
      'COMPLETED': { variant: 'success' as const, label: 'Completed' },
      'CANCELLED': { variant: 'danger' as const, label: 'Cancelled' },
      'PAUSED': { variant: 'secondary' as const, label: 'Paused' },
      'HOLDING': { variant: 'warning' as const, label: 'Holding' },
      'REFILL': { variant: 'primary' as const, label: 'Refill' },
    }
    const config = statusMap[status as keyof typeof statusMap] || { variant: 'secondary' as const, label: status }
    return <Badge variant={config.variant}>{config.label}</Badge>
  }

  const CHART_COLORS = ['#3b82f6', '#10b981', '#f59e0b', '#ef4444', '#8b5cf6']

  // ... rest of the component will be added in the next chunk ...
  return (
    <>
      <Helmet>
        <title>Dashboard - SMM Panel</title>
        <meta name="description" content="SMM Panel dashboard with orders, analytics and balance overview" />
      </Helmet>

      <Layout>
        <div className="space-y-6">
          {/* Header */}
          <div className="flex flex-col sm:flex-row sm:items-center sm:justify-between">
            <div>
              <h1 className="text-2xl font-bold text-neutral-900 dark:text-neutral-100">
                Welcome back, {user?.username}!
              </h1>
              <p className="mt-1 text-sm text-neutral-500 dark:text-neutral-400">
                Here's what's happening with your account today.
              </p>
            </div>
            
            <div className="mt-4 sm:mt-0 flex space-x-3">
              <Link to="/orders/new">
                <Button variant="primary" className="flex items-center">
                  <PlusIcon className="w-4 h-4 mr-2" />
                  New Order
                </Button>
              </Link>
              <Link to="/balance">
                <Button variant="secondary" className="flex items-center">
                  <CreditCardIcon className="w-4 h-4 mr-2" />
                  Add Balance
                </Button>
              </Link>
            </div>
          </div>

          {/* Stats Cards */}
          <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-4 gap-6">
            {/* Balance Card */}
            <motion.div
              initial={{ opacity: 0, y: 20 }}
              animate={{ opacity: 1, y: 0 }}
              transition={{ delay: 0.1 }}
            >
              <Card className="relative overflow-hidden">
                <div className="p-6">
                  <div className="flex items-center">
                    <div className="flex-shrink-0">
                      <CurrencyDollarIcon className="w-8 h-8 text-success-600" />
                    </div>
                    <div className="ml-4">
                      <p className="text-sm font-medium text-neutral-500 dark:text-neutral-400">
                        Account Balance
                      </p>
                      <p className="text-2xl font-bold text-neutral-900 dark:text-neutral-100">
                        {balanceLoading ? (
                          <LoadingSpinner size="sm" />
                        ) : (
                          formatCurrency(parseFloat(balance || '0'))
                        )}
                      </p>
                    </div>
                  </div>
                </div>
                <div className="absolute inset-0 bg-gradient-to-r from-success-500/5 to-success-600/5" />
              </Card>
            </motion.div>

            {/* Total Orders */}
            <motion.div
              initial={{ opacity: 0, y: 20 }}
              animate={{ opacity: 1, y: 0 }}
              transition={{ delay: 0.2 }}
            >
              <Card className="relative overflow-hidden">
                <div className="p-6">
                  <div className="flex items-center">
                    <div className="flex-shrink-0">
                      <ChartBarIcon className="w-8 h-8 text-primary-600" />
                    </div>
                    <div className="ml-4">
                      <p className="text-sm font-medium text-neutral-500 dark:text-neutral-400">
                        Total Orders
                      </p>
                      <p className="text-2xl font-bold text-neutral-900 dark:text-neutral-100">
                        {statsLoading ? (
                          <LoadingSpinner size="sm" />
                        ) : (
                          formatNumber(stats?.totalOrders || 0)
                        )}
                      </p>
                    </div>
                  </div>
                </div>
                <div className="absolute inset-0 bg-gradient-to-r from-primary-500/5 to-primary-600/5" />
              </Card>
            </motion.div>

            {/* Active Orders */}
            <motion.div
              initial={{ opacity: 0, y: 20 }}
              animate={{ opacity: 1, y: 0 }}
              transition={{ delay: 0.3 }}
            >
              <Card className="relative overflow-hidden">
                <div className="p-6">
                  <div className="flex items-center">
                    <div className="flex-shrink-0">
                      <PlayIcon className="w-8 h-8 text-warning-600" />
                    </div>
                    <div className="ml-4">
                      <p className="text-sm font-medium text-neutral-500 dark:text-neutral-400">
                        Active Orders
                      </p>
                      <p className="text-2xl font-bold text-neutral-900 dark:text-neutral-100">
                        {statsLoading ? (
                          <LoadingSpinner size="sm" />
                        ) : (
                          formatNumber(stats?.activeOrders || 0)
                        )}
                      </p>
                    </div>
                  </div>
                </div>
                <div className="absolute inset-0 bg-gradient-to-r from-warning-500/5 to-warning-600/5" />
              </Card>
            </motion.div>

            {/* Completed Orders */}
            <motion.div
              initial={{ opacity: 0, y: 20 }}
              animate={{ opacity: 1, y: 0 }}
              transition={{ delay: 0.4 }}
            >
              <Card className="relative overflow-hidden">
                <div className="p-6">
                  <div className="flex items-center">
                    <div className="flex-shrink-0">
                      <CheckCircleIcon className="w-8 h-8 text-success-600" />
                    </div>
                    <div className="ml-4">
                      <p className="text-sm font-medium text-neutral-500 dark:text-neutral-400">
                        Completed Orders
                      </p>
                      <p className="text-2xl font-bold text-neutral-900 dark:text-neutral-100">
                        {statsLoading ? (
                          <LoadingSpinner size="sm" />
                        ) : (
                          formatNumber(stats?.completedOrders || 0)
                        )}
                      </p>
                    </div>
                  </div>
                </div>
                <div className="absolute inset-0 bg-gradient-to-r from-success-500/5 to-success-600/5" />
              </Card>
            </motion.div>
          </div>

          {/* Charts Section */}
          <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
            {/* Orders Chart */}
            <motion.div
              initial={{ opacity: 0, y: 20 }}
              animate={{ opacity: 1, y: 0 }}
              transition={{ delay: 0.5 }}
            >
              <Card>
                <div className="p-6">
                  <div className="flex items-center justify-between mb-4">
                    <h3 className="text-lg font-medium text-neutral-900 dark:text-neutral-100">
                      Orders & Revenue
                    </h3>
                    <div className="flex space-x-2">
                      {(['24h', '7d', '30d'] as const).map((range) => (
                        <button
                          key={range}
                          onClick={() => setTimeRange(range)}
                          className={`px-3 py-1 text-sm font-medium rounded-md transition-colors ${
                            timeRange === range
                              ? 'bg-primary-100 text-primary-700 dark:bg-primary-900 dark:text-primary-300'
                              : 'text-neutral-500 hover:text-neutral-700 dark:text-neutral-400 dark:hover:text-neutral-200'
                          }`}
                        >
                          {range}
                        </button>
                      ))}
                    </div>
                  </div>

                  {statsLoading ? (
                    <div className="h-64 flex items-center justify-center">
                      <LoadingSpinner size="lg" />
                    </div>
                  ) : stats?.ordersChart?.length ? (
                    <ResponsiveContainer width="100%" height={300}>
                      <LineChart data={stats.ordersChart}>
                        <CartesianGrid strokeDasharray="3 3" stroke="#374151" opacity={0.3} />
                        <XAxis 
                          dataKey="date" 
                          stroke="#6b7280"
                          fontSize={12}
                          tickFormatter={(value) => formatDate(value, 'MM/dd')}
                        />
                        <YAxis stroke="#6b7280" fontSize={12} />
                        <Tooltip
                          contentStyle={{
                            backgroundColor: '#1f2937',
                            border: 'none',
                            borderRadius: '8px',
                            color: '#f9fafb'
                          }}
                          labelFormatter={(value) => formatDate(value, 'MMM dd, yyyy')}
                          formatter={(value: any, name: string) => [
                            name === 'revenue' ? formatCurrency(value) : formatNumber(value),
                            name === 'revenue' ? 'Revenue' : 'Orders'
                          ]}
                        />
                        <Line
                          type="monotone"
                          dataKey="orders"
                          stroke="#3b82f6"
                          strokeWidth={2}
                          dot={{ fill: '#3b82f6', strokeWidth: 2, r: 4 }}
                          activeDot={{ r: 6, stroke: '#3b82f6', strokeWidth: 2 }}
                        />
                        <Line
                          type="monotone"
                          dataKey="revenue"
                          stroke="#10b981"
                          strokeWidth={2}
                          dot={{ fill: '#10b981', strokeWidth: 2, r: 4 }}
                          activeDot={{ r: 6, stroke: '#10b981', strokeWidth: 2 }}
                        />
                      </LineChart>
                    </ResponsiveContainer>
                  ) : (
                    <EmptyState
                      title="No data available"
                      description="Chart data will appear here once you have orders"
                      icon={<ChartBarIcon className="w-12 h-12" />}
                    />
                  )}
                </div>
              </Card>
            </motion.div>

            {/* Status Distribution */}
            <motion.div
              initial={{ opacity: 0, y: 20 }}
              animate={{ opacity: 1, y: 0 }}
              transition={{ delay: 0.6 }}
            >
              <Card>
                <div className="p-6">
                  <h3 className="text-lg font-medium text-neutral-900 dark:text-neutral-100 mb-4">
                    Order Status Distribution
                  </h3>

                  {statsLoading ? (
                    <div className="h-64 flex items-center justify-center">
                      <LoadingSpinner size="lg" />
                    </div>
                  ) : stats?.statusDistribution?.length ? (
                    <div className="flex flex-col lg:flex-row items-center">
                      <div className="w-full lg:w-1/2">
                        <ResponsiveContainer width="100%" height={200}>
                          <PieChart>
                            <Pie
                              data={stats.statusDistribution}
                              cx="50%"
                              cy="50%"
                              innerRadius={40}
                              outerRadius={80}
                              paddingAngle={2}
                              dataKey="count"
                            >
                              {stats.statusDistribution.map((entry, index) => (
                                <Cell key={`cell-${index}`} fill={CHART_COLORS[index % CHART_COLORS.length]} />
                              ))}
                            </Pie>
                            <Tooltip
                              contentStyle={{
                                backgroundColor: '#1f2937',
                                border: 'none',
                                borderRadius: '8px',
                                color: '#f9fafb'
                              }}
                              formatter={(value: any) => [formatNumber(value), 'Orders']}
                            />
                          </PieChart>
                        </ResponsiveContainer>
                      </div>
                      <div className="w-full lg:w-1/2 mt-4 lg:mt-0">
                        <div className="space-y-2">
                          {stats.statusDistribution.map((item, index) => (
                            <div key={item.status} className="flex items-center justify-between">
                              <div className="flex items-center">
                                <div
                                  className="w-3 h-3 rounded-full mr-2"
                                  style={{ backgroundColor: CHART_COLORS[index % CHART_COLORS.length] }}
                                />
                                <span className="text-sm text-neutral-600 dark:text-neutral-400">
                                  {item.status}
                                </span>
                              </div>
                              <div className="text-right">
                                <div className="text-sm font-medium text-neutral-900 dark:text-neutral-100">
                                  {formatNumber(item.count)}
                                </div>
                                <div className="text-xs text-neutral-500 dark:text-neutral-400">
                                  {item.percentage.toFixed(1)}%
                                </div>
                              </div>
                            </div>
                          ))}
                        </div>
                      </div>
                    </div>
                  ) : (
                    <EmptyState
                      title="No orders yet"
                      description="Status distribution will appear here once you create orders"
                      icon={<ChartBarIcon className="w-12 h-12" />}
                    />
                  )}
                </div>
              </Card>
            </motion.div>
          </div>

          {/* Recent Orders */}
          <motion.div
            initial={{ opacity: 0, y: 20 }}
            animate={{ opacity: 1, y: 0 }}
            transition={{ delay: 0.7 }}
          >
            <Card>
              <div className="p-6">
                <div className="flex items-center justify-between mb-4">
                  <h3 className="text-lg font-medium text-neutral-900 dark:text-neutral-100">
                    Recent Orders
                  </h3>
                  <Link
                    to="/orders"
                    className="text-sm text-primary-600 hover:text-primary-700 dark:text-primary-400 dark:hover:text-primary-300 font-medium"
                  >
                    View all orders →
                  </Link>
                </div>

                {ordersLoading ? (
                  <div className="h-32 flex items-center justify-center">
                    <LoadingSpinner size="lg" />
                  </div>
                ) : mergedOrders.length > 0 ? (
                  <div className="overflow-x-auto">
                    <table className="min-w-full divide-y divide-neutral-200 dark:divide-neutral-700">
                      <thead>
                        <tr>
                          <th className="px-4 py-3 text-left text-xs font-medium text-neutral-500 dark:text-neutral-400 uppercase tracking-wider">
                            Order ID
                          </th>
                          <th className="px-4 py-3 text-left text-xs font-medium text-neutral-500 dark:text-neutral-400 uppercase tracking-wider">
                            Service
                          </th>
                          <th className="px-4 py-3 text-left text-xs font-medium text-neutral-500 dark:text-neutral-400 uppercase tracking-wider">
                            Quantity
                          </th>
                          <th className="px-4 py-3 text-left text-xs font-medium text-neutral-500 dark:text-neutral-400 uppercase tracking-wider">
                            Status
                          </th>
                          <th className="px-4 py-3 text-left text-xs font-medium text-neutral-500 dark:text-neutral-400 uppercase tracking-wider">
                            Created
                          </th>
                          <th className="px-4 py-3 text-left text-xs font-medium text-neutral-500 dark:text-neutral-400 uppercase tracking-wider">
                            Charge
                          </th>
                        </tr>
                      </thead>
                      <tbody className="divide-y divide-neutral-200 dark:divide-neutral-700">
                        {mergedOrders.map((order: Order) => (
                          <tr key={order.id} className="hover:bg-neutral-50 dark:hover:bg-neutral-800/50">
                            <td className="px-4 py-3 whitespace-nowrap text-sm font-medium text-neutral-900 dark:text-neutral-100">
                              #{order.id}
                            </td>
                            <td className="px-4 py-3 whitespace-nowrap text-sm text-neutral-500 dark:text-neutral-400">
                              {order.service?.name || 'N/A'}
                            </td>
                            <td className="px-4 py-3 whitespace-nowrap text-sm text-neutral-500 dark:text-neutral-400">
                              {formatNumber(order.quantity)}
                            </td>
                            <td className="px-4 py-3 whitespace-nowrap">
                              {getStatusBadge(order.status)}
                            </td>
                            <td className="px-4 py-3 whitespace-nowrap text-sm text-neutral-500 dark:text-neutral-400">
                              {formatDate(order.createdAt)}
                            </td>
                            <td className="px-4 py-3 whitespace-nowrap text-sm font-medium text-neutral-900 dark:text-neutral-100">
                              {formatCurrency(parseFloat(order.charge))}
                            </td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </div>
                ) : (
                  <EmptyState
                    title="No orders yet"
                    description="Your recent orders will appear here"
                    icon={<DocumentTextIcon className="w-12 h-12" />}
                  />
                )}
              </div>
            </Card>
          </motion.div>
        </div>
      </Layout>
    </>
  )
}

export default DashboardPage
