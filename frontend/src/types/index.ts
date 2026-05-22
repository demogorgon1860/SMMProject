// =====================================================================
// SMMWorld domain types — shared between API client and components.
// Field names match the Spring Boot DTOs / JPA entities (see backend
// audit). Where the API still returns mixed casing, normalize at the
// store/hook layer, not here.
//
// Generated-types workflow (see Task 11.D):
//   1. Start backend locally (`./gradlew bootRun` or `docker-compose -f
//      docker-compose.dev.yml up -d`).
//   2. Run `npm run gen-types` — writes `src/types/api.gen.ts` from
//      `/v3/api-docs`. Never hand-edit that file.
//   3. Replace any hand-typed DTO in this file with a thin re-export:
//        import type { components } from './api.gen';
//        export type Order = components['schemas']['OrderResponse'];
//      TypeScript compile errors will surface field-name drift across
//      consumers — fix call-sites; that's the whole point.
//   4. Commit `api.gen.ts` so CI can compare via `npm run gen-types:check`
//      and fail the build on drift.
//
// Until that loop runs end-to-end, the hand-written interfaces below
// remain authoritative.
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
  /** True when this order was created as a refill for an earlier completed/partial order. */
  isRefill?: boolean;
  /** Admin-only: id of the original order this refill was issued for. */
  refillParentId?: number | null;
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

/**
 * Mirrors {@code com.smmpanel.entity.TransactionType} on the backend. Values come straight off
 * the enum via {@code transaction.getTransactionType().toString()}, so the frontend has to know
 * every value or it'll silently bucket unknowns into "Adjustment".
 *
 * Older builds used a slim 5-value union (DEPOSIT/CHARGE/REFUND/MANUAL_ADJUST/BONUS); we keep
 * the legacy names as accepted aliases so a deploy lag doesn't render existing rows wrong.
 */
export type TransactionType =
  | 'DEPOSIT'
  | 'ORDER_PAYMENT'
  | 'REFUND'
  | 'REFILL'
  | 'ADJUSTMENT'
  | 'BONUS'
  | 'TRANSFER_IN'
  | 'TRANSFER_OUT'
  | 'COMMISSION'
  | 'PENALTY'
  // Legacy aliases — older API versions used these.
  | 'CHARGE'
  | 'MANUAL_ADJUST';

export interface BalanceTransaction {
  id: number;
  type: TransactionType;
  /** Numeric on the wire; coerce with {@code Number()} since backend uses BigDecimal-as-string. */
  amount: number;
  balanceBefore?: number;
  balanceAfter?: number;
  orderId?: number | null;
  /** Backend field name on TransactionHistoryResponse. */
  description?: string;
  /** Legacy alias kept for build/back-compat. Read both. */
  reason?: string;
  /** Cryptomus order id, refund reference, etc. — opaque external identifier. */
  referenceNumber?: string;
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
