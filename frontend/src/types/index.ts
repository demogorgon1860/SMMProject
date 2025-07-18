// frontend/src/types/index.ts

export interface User {
    id: number;
    username: string;
    email: string;
    balance: string;
    role: 'USER' | 'OPERATOR' | 'ADMIN';
    apiKey?: string;
    timezone?: string;
    isActive?: boolean;
    createdAt?: string;
    updatedAt?: string;
  }
  
  export interface Service {
    id: number;
    name: string;
    category: string;
    minOrder: number;
    maxOrder: number;
    pricePer1000: string;
    description?: string;
    active?: boolean;
  }
  
  export interface Order {
    id: number;
    service: number;
    link: string;
    quantity: number;
    startCount: number;
    remains: number;
    status: OrderStatus;
    charge: string;
    createdAt?: string;
    updatedAt?: string;
    youtubeVideoId?: string;
    errorMessage?: string;
  }
  
  export type OrderStatus = 
    | 'Pending'
    | 'In progress' 
    | 'Processing'
    | 'Partial'
    | 'Completed'
    | 'Canceled'
    | 'Paused'
    | 'Refill';
  
  export interface CreateOrderRequest {
    service: number;
    link: string;
    quantity: number;
  }
  
  export interface CreateDepositRequest {
    amount: number;
    currency: 'BTC' | 'ETH' | 'USDT' | 'LTC' | 'USDC';
  }
  
  export interface DepositResponse {
    orderId: string;
    paymentUrl: string;
    amount: number;
    currency: string;
    cryptoAmount: string;
    expiresAt: string;
  }
  
  export interface DepositStatus {
    status: 'PENDING' | 'PROCESSING' | 'COMPLETED' | 'FAILED' | 'EXPIRED';
    confirmedAt?: string;
  }
  
  export interface BalanceTransaction {
    id: number;
    amount: string;
    balanceBefore: string;
    balanceAfter: string;
    transactionType: 'DEPOSIT' | 'ORDER_PAYMENT' | 'REFUND' | 'REFILL';
    description: string;
    createdAt: string;
  }
  
  // Типы для админки
  export interface AdminOrderDto {
    id: number;
    user: {
      id: number;
      username: string;
    };
    service: Service;
    link: string;
    quantity: number;
    startCount: number;
    remains: number;
    status: OrderStatus;
    charge: string;
    createdAt: string;
    updatedAt: string;
    videoProcessing?: {
      id: number;
      clipCreated: boolean;
      clipUrl?: string;
      processingStatus: string;
    };
    binomCampaign?: {
      id: number;
      campaignId: string;
      clicksRequired: number;
      clicksDelivered: number;
      status: string;
    };
  }
  
  export interface BulkActionRequest {
    orderIds: number[];
    action: 'STOP' | 'START' | 'RESTART' | 'PARTIAL_CANCEL' | 'SET_START_COUNT' | 'REFILL';
    params?: {
      startCount?: number;
      reason?: string;
    };
  }
  
  export interface DashboardStats {
    totalOrders: number;
    activeOrders: number;
    completedOrders: number;
    totalRevenue: string;
    todayOrders: number;
    todayRevenue: string;
    averageOrderValue: string;
    systemStatus: {
      youtube: 'ONLINE' | 'OFFLINE' | 'LIMITED';
      binom: 'ONLINE' | 'OFFLINE';
      cryptomus: 'ONLINE' | 'OFFLINE';
    };
  }
  
  export interface ConversionCoefficient {
    id: number;
    serviceId: number;
    withClip: string;
    withoutClip: string;
    updatedBy?: string;
    updatedAt: string;
  }
  
  export interface TrafficSource {
    id: number;
    name: string;
    sourceId: string;
    weight: number;
    dailyLimit?: number;
    clicksUsedToday: number;
    geoTargeting?: string;
    active: boolean;
    performanceScore: string;
  }
  
  export interface ApiResponse<T> {
    order?: T;
    orders?: T;
    services?: T;
    balance?: string;
    error?: string;
    errorCode?: number;
  }
  
  export interface PaginatedResponse<T> {
    content: T[];
    totalElements: number;
    totalPages: number;
    size: number;
    number: number;
    first: boolean;
    last: boolean;
  }