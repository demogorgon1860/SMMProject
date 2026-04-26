// =====================================================================
// SMMWorld domain types — shared between API client and components.
// Field names match the Spring Boot DTOs / JPA entities (see backend
// audit). Where the API still returns mixed casing, normalize at the
// store/hook layer, not here.
// =====================================================================

export type UserRole = 'USER' | 'ADMIN' | 'OPERATOR';

export interface User {
  id: number;
  username: string;
  email: string;
  role: UserRole;
  balance: number;
  isActive: boolean;
  createdAt: string;
  lastLoginAt?: string;
}

export interface AuthResponse {
  token: string;
  refreshToken: string;
  user: User;
}

// ----- Orders -------------------------------------------------------

/**
 * Spec values from the design + backend OrderStatus enum.
 * Always upper-snake on the wire; lower-case in components for
 * StatusBadge mapping (use status.toLowerCase()).
 */
export type OrderStatus =
  | 'PENDING'
  | 'PROCESSING'
  | 'IN_PROGRESS'
  | 'ACTIVE'
  | 'COMPLETED'
  | 'PARTIAL'
  | 'CANCELED'
  | 'CANCELLED'
  | 'FAILED'
  | 'PAUSED'
  | 'HOLDING'
  | 'REFILL'
  | 'ERROR'
  | 'SUSPENDED';

export interface Order {
  id: number;
  orderId?: string;
  userId: number;
  service?: Service;
  serviceName?: string;
  serviceId?: number;
  link: string;
  quantity: number;
  completed?: number;
  remains?: number;
  charge: number;
  status: OrderStatus;
  trafficStatus?: string;
  startCount?: number;
  currentCount?: number;
  instagramBotOrderId?: string;
  errorMessage?: string;
  createdAt: string;
  updatedAt?: string;
}

// ----- Services -----------------------------------------------------

export type ServiceCategory = 'likes' | 'follows' | 'comments';

export interface Service {
  id: number;
  name: string;
  description?: string;
  category: ServiceCategory | string;
  rate: number;
  min: number;
  max: number;
  pricePer1000?: number;
  pricePerThousand?: number;
  minOrder?: number;
  maxOrder?: number;
  conversionCoefficient?: number;
  ordersAllTime?: number;
  active?: boolean;
  isActive?: boolean;
  /** Marketing-only — UI category like 'ig'/'tt'/'yt'. */
  platform?: 'ig' | 'tt' | 'yt' | 'x' | 'tg' | 'sp' | 'fb' | 'dc';
}

// ----- Balance / Transactions --------------------------------------

export type TransactionType = 'DEPOSIT' | 'CHARGE' | 'REFUND' | 'MANUAL_ADJUST' | 'BONUS';

export interface BalanceTransaction {
  id: number;
  type: TransactionType;
  amount: number;
  balanceBefore?: number;
  balanceAfter?: number;
  orderId?: number | null;
  reason?: string;
  actor?: string;
  actorType?: 'system' | 'admin';
  createdAt: string;
}

export interface BalanceSummary {
  balance: number;
  currency: string;
  totalSpent?: number;
  totalOrders?: number;
  totalDelivered?: number;
  lastUpdated?: string;
}

// ----- Deposits (Cryptomus) ----------------------------------------

export type DepositStatus = 'PENDING' | 'CONFIRMING' | 'CONFIRMED' | 'EXPIRED' | 'FAILED' | 'PAID';
export type CryptoMethod = 'USDT_TRC20' | 'USDT_ERC20' | 'BTC' | 'ETH' | 'TON' | 'LTC';

export interface Deposit {
  id: number | string;
  amount: number;
  currency: string;
  method: CryptoMethod | string;
  status: DepositStatus | string;
  address?: string;
  expectedCrypto?: string;
  txHash?: string;
  confirmations?: number;
  expiresAt?: string;
  createdAt: string;
  confirmedAt?: string;
}

// ----- Notifications ----------------------------------------------

export interface Notification {
  id: string;
  kind: 'success' | 'warn' | 'danger' | 'info';
  title: string;
  body?: string;
  createdAt: string;
  read?: boolean;
}

// ----- Tickets / Help ---------------------------------------------

export type TicketStatus = 'OPEN' | 'WAITING' | 'CLOSED';

export interface Ticket {
  id: string;
  subject: string;
  status: TicketStatus;
  unread?: boolean;
  updatedAt: string;
  createdAt: string;
}

export interface FaqEntry {
  id: string;
  question: string;
  answer: string;
}

// ----- Public/landing stats ---------------------------------------

export interface PublicStats {
  ordersFulfilled?: number;
  ordersLast24h?: number;
  avgStartSeconds?: number;
  uptimePercent?: number;
  serviceCount?: number;
  nodeCount?: number;
}

export interface NetworkNode {
  id: string;
  region: string;
  accounts: number;
  latencyMs?: number;
  status: 'up' | 'degraded' | 'down';
}

// ----- Pagination wrapper ------------------------------------------

export interface PageResponse<T> {
  content: T[];
  totalElements: number;
  totalPages: number;
  number: number;
  size: number;
  first?: boolean;
  last?: boolean;
}
