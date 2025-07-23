// Perfect Panel Compatible Types
export type OrderStatus = 
  | 'PENDING' 
  | 'IN_PROGRESS' 
  | 'PROCESSING' 
  | 'ACTIVE'
  | 'PARTIAL' 
  | 'COMPLETED' 
  | 'CANCELLED' 
  | 'PAUSED' 
  | 'HOLDING' 
  | 'REFILL'

export type UserRole = 'USER' | 'OPERATOR' | 'ADMIN'

export type PaymentStatus = 'PENDING' | 'PROCESSING' | 'COMPLETED' | 'FAILED' | 'EXPIRED'

export type CryptoCurrency = 'BTC' | 'ETH' | 'USDT' | 'LTC' | 'USDC'

export type TransactionType = 'DEPOSIT' | 'ORDER_PAYMENT' | 'REFUND' | 'REFILL'

export type VideoType = 'STANDARD' | 'SHORTS' | 'LIVE'

export type CampaignStatus = 'ACTIVE' | 'PAUSED' | 'STOPPED' | 'COMPLETED'

// Core Entities
export interface User {
  id: number
  username: string
  email: string
  balance: string
  role: UserRole
  apiKey?: string
  timezone: string
  isActive: boolean
  createdAt: string
  updatedAt: string
}

export interface Service {
  id: number
  name: string
  category: string
  minOrder: number
  maxOrder: number
  pricePer1000: string
  description?: string
  active: boolean
  createdAt: string
}

export interface Order {
  id: number
  user?: {
    id: number
    username: string
  }
  service: Service
  link: string
  quantity: number
  startCount: number
  remains: number
  status: OrderStatus
  charge: string
  youtubeVideoId?: string
  processingPriority: number
  errorMessage?: string
  createdAt: string
  updatedAt: string
  videoProcessing?: VideoProcessing
  binomCampaign?: BinomCampaign
}

export interface VideoProcessing {
  id: number
  orderId: number
  originalUrl: string
  videoId: string
  videoType: VideoType
  clipCreated: boolean
  clipUrl?: string
  youtubeAccountId?: number
  processingStatus: string
  errorMessage?: string
  createdAt: string
  updatedAt: string
}

export interface BinomCampaign {
  id: number
  orderId: number
  campaignId: string
  status: CampaignStatus
  dailyClips: number
  trafficSourceId: number
  paymentMethod: string
  active: boolean
  createdAt: string
  clicksRequired?: number
  clicksDelivered?: number
}

export interface TrafficSource {
  id: number
  name: string
  sourceId: string
  status: string
  dailyClips: number
  cryptoPerDay: number
  geoTargeting: string
  active: boolean
  createdAt: string
  weight?: number
  dailyLimit?: number
  clicksUsedToday?: number
  performanceScore?: string
}

export interface ConversionCoefficient {
  id: number
  serviceId: number
  withClip: number | string
  withoutClip: number | string
  updatedBy?: string
  updatedAt: string
}

// Balance & Payments
export interface BalanceTransaction {
  id: number
  userId: number
  orderId?: number
  amount: string
  balanceBefore: string
  balanceAfter: string
  transactionType: TransactionType
  description: string
  createdAt: string
}

export interface CryptoPayment {
  id: number
  userId: number
  orderId: string
  amount: number
  currency: CryptoCurrency
  cryptoAmount: string
  paymentUrl: string
  status: PaymentStatus
  expiresAt: string
  confirmedAt?: string
  createdAt: string
}

export interface DepositResponse {
  orderId: string
  paymentUrl: string
  amount: number
  currency: string
  cryptoAmount: string
  expiresAt: string
}

// API Requests & Responses
export interface CreateOrderRequest {
  service: number
  link: string
  quantity: number
}

export interface CreateDepositRequest {
  amount: number
  currency: CryptoCurrency
}

export interface BulkActionRequest {
  orderIds: number[]
  action: 'STOP' | 'START' | 'RESTART' | 'PARTIAL_CANCEL' | 'SET_START_COUNT' | 'REFILL'
  params?: {
    startCount?: number
    reason?: string
  }
}

export interface DashboardStats {
  totalOrders: number
  activeOrders: number
  completedOrders: number
  totalRevenue: string
  todayOrders: number
  todayRevenue: string
  averageOrderValue: string
  systemStatus: {
    youtube: 'ONLINE' | 'OFFLINE' | 'LIMITED'
    binom: 'ONLINE' | 'OFFLINE'
    cryptomus: 'ONLINE' | 'OFFLINE'
  }
  conversionRate?: number
  recentActivity?: Array<{
    id: number
    type: 'ORDER_CREATED' | 'ORDER_COMPLETED' | 'PAYMENT_RECEIVED' | 'USER_REGISTERED'
    message: string
    timestamp: string
    user?: string
  }>
}

export interface ApiResponse<T = any> {
  order?: T
  orders?: T
  services?: T
  balance?: string
  error?: string
  status?: string
  errorCode?: number
}

export interface PaginatedResponse<T> {
  content: T[]
  totalElements: number
  totalPages: number
  size: number
  number: number
  first: boolean
  last: boolean
}

// Forms & UI
export interface OrderFilters {
  status?: OrderStatus | 'all'
  username?: string
  dateFrom?: string
  dateTo?: string
  serviceId?: number
  page: number
  size: number
}

export interface UserFilters {
  role?: UserRole | 'all'
  isActive?: boolean
  search?: string
  page: number
  size: number
}

export interface NotificationSettings {
  orderCompleted: boolean
  paymentReceived: boolean
  lowBalance: boolean
  systemMaintenance: boolean
  email: boolean
  browser: boolean
}

export interface SystemSettings {
  siteName: string
  maintenanceMode: boolean
  registrationEnabled: boolean
  emailVerificationRequired: boolean
  twoFactorEnabled: boolean
  apiDocsEnabled: boolean
  minDepositAmount: number
  maxDepositAmount: number
  defaultTimezone: string
  supportEmail: string
  supportUrl: string
}

// WebSocket Events
export interface WebSocketMessage {
  type: 'ORDER_UPDATE' | 'BALANCE_UPDATE' | 'NOTIFICATION' | 'SYSTEM_MESSAGE'
  data: any
  timestamp: string
}

// Auth
export interface LoginRequest {
  username: string
  password: string
}

export interface RegisterRequest {
  username: string
  email: string
  password: string
}

export interface AuthResponse {
  token: string
  refreshToken: string
  user: User
}

// Error Types
export interface ApiError {
  message: string
  code: number
  details?: Record<string, string[]>
}