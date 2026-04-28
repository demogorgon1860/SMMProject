import axios, { AxiosError } from 'axios';

// =====================================================================
// SMMWorld API client.
// Base URL is `/api`. Vite proxies `/api/*` → `http://localhost:8080`
// in dev (see vite.config.ts). In prod, nginx routes the same prefix.
// =====================================================================

const api = axios.create({
  baseURL: '/api',
  headers: { 'Content-Type': 'application/json' },
});

// Inject Bearer token on every request.
api.interceptors.request.use((config) => {
  const token = localStorage.getItem('token');
  if (token) config.headers.Authorization = `Bearer ${token}`;
  return config;
});

// Global 401 handler: bounce to /login on session-expired ONLY. Endpoints that
// can return 401 as a legitimate input-validation result (login form, password
// confirmation) must be skipped — otherwise a typo on the password field
// would log the user out of the entire app.
api.interceptors.response.use(
  (response) => response,
  (error: AxiosError) => {
    const url = error.config?.url ?? '';
    // /auth/* — login/register/forgot/reset/verify-email all return 401 on bad input
    // /me/password — this is now 422 server-side, but kept here as belt-and-braces
    //                in case a deployment lag returns 401 from an old container
    // /me/account  — DELETE re-asks for the password and returns 422 on mismatch; if a stale
    //                container ever returns 401, we still don't want to bounce the user out
    //                while they're staring at a delete-confirmation modal.
    const isInputValidationEndpoint =
      /\/auth\/(login|register|forgot|reset|verify)/.test(url) ||
      /\/me\/password\b/.test(url) ||
      /\/me\/account\b/.test(url);

    if (error.response?.status === 401 && !isInputValidationEndpoint) {
      localStorage.removeItem('token');
      localStorage.removeItem('user');
      window.location.href = '/login';
    }
    return Promise.reject(error);
  },
);

// =====================================================================
// Auth
// =====================================================================
export const authAPI = {
  login: (username: string, password: string) =>
    api.post('/v1/auth/login', { username, password }).then((r) => r.data),

  // `website` is the off-screen honeypot from Register.tsx — real users always send empty.
  // The backend treats any non-blank value as bot traffic and silently drops the request.
  register: (username: string, email: string, password: string, website?: string) =>
    api
      .post('/v1/auth/register', { username, email, password, website: website ?? '' })
      .then((r) => r.data),

  getCurrentUser: () => api.get('/v1/auth/me').then((r) => r.data),

  refreshToken: (refreshToken: string) =>
    api.post('/v1/auth/refresh', { refreshToken }).then((r) => r.data),

  logout: () => api.post('/v1/auth/logout').then((r) => r.data),

  // ---- Phase 3 backend endpoints (return 404 until implemented) ----
  verifyEmail: (email: string, code: string) =>
    api.post('/v1/auth/verify-email', { email, code }).then((r) => r.data),

  resendVerification: (email: string) =>
    api.post('/v1/auth/resend-verification', { email }).then((r) => r.data),

  forgotPassword: (email: string) =>
    api.post('/v1/auth/forgot-password', { email }).then((r) => r.data),

  resetPassword: (token: string, password: string) =>
    api.post('/v1/auth/reset-password', { token, password }).then((r) => r.data),
};

// =====================================================================
// Orders (user-facing)
// =====================================================================

/**
 * Subset of the backend's OrderResponse DTO that the create flow consumes.
 * Money fields are serialized as JSON strings on the wire (BigDecimal); coerce
 * with `toNum` before arithmetic.
 */
export interface CreatedOrder {
  id: number;
  service: number;
  serviceName?: string;
  status: string;
  link: string;
  quantity: number;
  charge: string;
  startCount?: number | null;
  remains?: number | null;
  orderId?: string | null;
  createdAt: string;
}

export const orderAPI = {
  /**
   * Place a new order. Note the wire field for custom-comments services is
   * `customComments` (matches backend CreateOrderRequest); we accept the friendlier
   * `comments` here in callers and remap on the way out so the rest of the app
   * stays terse.
   *
   * Backend wraps the result in PerfectPanelResponse `{ data, success, message }` —
   * we unwrap so callers get the OrderResponse directly (id, charge, status, ...).
   */
  // `idempotencyKey` is a stable per-attempt UUID. The backend dedupes within a 5-minute
  // window per (user, key, operation) so a network retry doesn't double-charge: same key
  // returns the same order, different key creates a new one.
  create: (
    {
      comments,
      ...rest
    }: {
      service: number;
      link: string;
      quantity: number;
      comments?: string;
    },
    idempotencyKey?: string,
  ): Promise<CreatedOrder> =>
    api
      .post<{ data: CreatedOrder }>(
        '/v1/orders',
        { ...rest, customComments: comments },
        idempotencyKey ? { headers: { 'Idempotency-Key': idempotencyKey } } : undefined,
      )
      .then((r) => r.data.data),

  list: (params: { status?: string; search?: string; from?: string; to?: string; page?: number; size?: number } = {}) =>
    api.get('/v1/orders', { params: { page: 0, size: 50, ...params } }).then((r) => r.data),

  get: (id: number) => api.get(`/v1/orders/${id}`).then((r) => r.data),

  cancel: (id: number) => api.post(`/v1/orders/${id}/cancel`).then((r) => r.data),

  // Refill is a *request* — admin must approve before the actual refill order is created.
  // Returns RefillRequestResponse: { id, orderId, status: 'PENDING' | 'APPROVED' | 'REJECTED', ... }
  requestRefill: (id: number, note?: string) =>
    api.post(`/v1/orders/${id}/refill`, note ? { note } : {}).then((r) => r.data),

  // Current user's request status for one order (404 if user never requested a refill on it).
  getRefillRequest: (id: number) =>
    api.get(`/v1/orders/${id}/refill`).then((r) => r.data),

  // Mass order (kept from existing client)
  createMass: (payload: { ordersText: string; delimiter?: string; maxOrders?: number }) =>
    api.post('/v1/orders/mass', payload).then((r) => r.data),

  previewMass: (payload: { ordersText: string; delimiter?: string; maxOrders?: number }) =>
    api.post('/v1/orders/mass/preview', payload).then((r) => r.data),
};

// =====================================================================
// Services catalog (user)
// =====================================================================
export const serviceAPI = {
  list: () => api.get('/v1/service/services').then((r) => r.data),
};

// =====================================================================
// Balance + transactions (user)
// =====================================================================
export const balanceAPI = {
  get: () => api.get('/v1/balance').then((r) => r.data),

  // `types` is the optional CSV of TransactionType enum names
  // (DEPOSIT,ORDER_PAYMENT,REFUND,REFILL,ADJUSTMENT,…). Active users have thousands
  // of ORDER_PAYMENT rows; without a filter the first page drowns DEPOSIT/REFUND
  // entries. The Transactions page passes the bucket→types map per active tab.
  // `from`/`to` are inclusive YYYY-MM-DD calendar dates (backend interprets `to`
  // as exclusive end-of-day). Both must be present for the range filter to apply.
  transactions: (page = 0, size = 50, types?: string[], from?: string, to?: string) =>
    api
      .get('/v1/balance/transactions', {
        params: {
          page,
          size,
          ...(types && types.length ? { type: types.join(',') } : {}),
          ...(from && to ? { from, to } : {}),
        },
      })
      .then((r) => r.data),

  recentTransactions: () => api.get('/v1/balance/transactions/recent').then((r) => r.data),

  // Lifetime sums by TransactionType — single GROUP BY on backend. Used by the
  // Transactions page stat cards (Deposited / Spent / Refunded). Computing from a
  // paginated last-N is wrong for active users (old deposits get pushed off-window).
  // BigDecimal serializes as string, hence Record<string, string>.
  transactionSummary: (): Promise<Record<string, string>> =>
    api.get('/v1/balance/transactions/summary').then((r) => r.data),
};

// =====================================================================
// Deposits (Cryptomus)
// =====================================================================
export const depositsAPI = {
  create: (amount: number) =>
    api.post('/v1/deposits/create', { amount, currency: 'USD' }).then((r) => r.data),

  list: (page = 0, size = 50) =>
    api.get('/v1/deposits', { params: { page, size } }).then((r) => r.data),

  get: (id: number) => api.get(`/v1/deposits/${id}`).then((r) => r.data),

  recent: () => api.get('/v1/deposits/recent').then((r) => r.data),
};

// =====================================================================
// API key (per-user)
// =====================================================================
export const apiKeyAPI = {
  generate: () => api.post('/v1/apikey/generate').then((r) => r.data),
  rotate: () => api.post('/v1/apikey/rotate').then((r) => r.data),
  status: () => api.get('/v1/apikey/status').then((r) => r.data),
};

// =====================================================================
// Profile.
//
// Implemented (real endpoints): me, updateMe, changePassword, notifications.
//
// Stubs (notImplemented) — surfaced as a clear rejection so callers can
// disable the corresponding UI rather than silently failing with a 404.
// Replace each stub with a real `api.{verb}(...)` once the backend ships.
// =====================================================================
const notImplemented = (label: string) => () =>
  Promise.reject(new Error(`${label} is not implemented in this release`));

export type Session = {
  id: number;
  userAgent: string | null;
  ipAddress: string | null;
  location?: string | null;
  createdAt: string;
  lastUsedAt: string;
  expiresAt: string;
  current: boolean;
};

export type LifetimeStats = {
  ordersTotal: number;
  ordersCompleted: number;
  ordersPartial: number;
  ordersCancelled: number;
  totalSpent: string | number;
  ticketsTotal: number;
  refillsRequested: number;
  memberSince: string;
  firstOrderAt: string | null;
  lastOrderAt: string | null;
};

export const profileAPI = {
  me: () => api.get('/v1/me').then((r) => r.data),
  updateMe: (patch: Record<string, unknown>) => api.patch('/v1/me', patch).then((r) => r.data),

  changePassword: (current: string, next: string) =>
    api.post('/v1/me/password', { current, next }).then((r) => r.data),

  notifications: () => api.get('/v1/me/notifications').then((r) => r.data),
  updateNotifications: (patch: Record<string, boolean>) =>
    api.patch('/v1/me/notifications', patch).then((r) => r.data),

  myRefillRequests: () => api.get('/v1/me/refill-requests').then((r) => r.data),

  /** Daily order/spend series for the user's own dashboard charts (last `days` days, default 30). */
  dailyStats: (days = 30) =>
    api
      .get<Array<{ date: string; total: number; completed: number; partial: number; cancelled: number; revenue: string | number }>>(
        '/v1/me/stats/daily',
        { params: { days } },
      )
      .then((r) => r.data),

  /** Roll-up tile on Profile → Account. Cached server-side at 60s TTL. */
  lifetimeStats: () =>
    api.get<LifetimeStats>('/v1/me/stats/lifetime').then((r) => r.data),

  // ---- Sessions ----
  sessions: () => api.get<Session[]>('/v1/me/sessions').then((r) => r.data),
  revokeSession: (id: number) =>
    api.delete<void>(`/v1/me/sessions/${id}`).then((r) => r.data),
  signOutOthers: () =>
    api.post<{ revoked: number }>('/v1/me/sessions/sign-out-others').then((r) => r.data),

  // ---- Account-data export — returns the file as a Blob so the browser can save it. ----
  exportData: () =>
    api
      .post('/v1/me/export', undefined, { responseType: 'blob' })
      .then((r) => ({
        blob: r.data as Blob,
        filename: extractFilename(r.headers as unknown as Record<string, unknown>),
      })),

  // ---- API-key pause/resume (see Profile → Danger Zone) ----
  apiKeyPauseStatus: () =>
    api.get<{ paused: boolean; pausedAt: string | null }>('/v1/me/api-key/pause-status').then((r) => r.data),
  pauseApi: () =>
    api.post<{ paused: boolean; pausedAt: string | null }>('/v1/me/api-key/pause').then((r) => r.data),
  resumeApi: () =>
    api.post<{ paused: boolean; pausedAt: string | null }>('/v1/me/api-key/resume').then((r) => r.data),

  /**
   * GDPR-compliant account deletion (soft-delete now, hard-delete in 30 days).
   * Re-asks for the current password as a second factor — even if the access token is open
   * on a shared machine, the deletion requires `password` + literal "DELETE" confirmation.
   *
   * Backend semantics:
   *  - 204 — accepted, account anonymized + scheduled for hard-delete in 30 days
   *  - 400 — confirmation token != "DELETE"
   *  - 409 — wallet balance > 0 OR in-flight orders block deletion (body has `reason` + `message`)
   *  - 422 — password mismatch
   */
  deleteAccount: (confirmation: string, password: string) =>
    api
      .delete<void>('/v1/me/account', { data: { confirmation, password } })
      .then((r) => r.data),
};

/**
 * Pull a filename out of a `Content-Disposition` header. We send {@code attachment;
 * filename="…"} from {@code POST /v1/me/export}; if the server omits it for any reason we fall
 * back to a sensible default. Headers can be lower-cased by some intermediaries, so we check
 * both spellings.
 */
function extractFilename(headers: Record<string, unknown> | undefined): string {
  const raw = headers?.['content-disposition'] ?? headers?.['Content-Disposition'];
  const cd = typeof raw === 'string' ? raw : null;
  if (cd) {
    const m = /filename="?([^";]+)"?/.exec(cd);
    if (m) return m[1];
  }
  return `smmworld-account-${new Date().toISOString().slice(0, 10)}.json`;
}

// =====================================================================
// Tickets / FAQ
// =====================================================================
export const supportAPI = {
  faq: () => api.get('/v1/faq').then((r) => r.data),
  tickets: () => api.get('/v1/tickets').then((r) => r.data),
  ticket: (id: string) => api.get(`/v1/tickets/${id}`).then((r) => r.data),
  createTicket: (payload: { topic: string; orderId?: number; subject: string; description: string }) =>
    api.post('/v1/tickets', payload).then((r) => r.data),
  addMessage: (id: string | number, body: string) =>
    api.post(`/v1/tickets/${id}/messages`, { body }).then((r) => r.data),
};

// =====================================================================
// Public stats (landing page)
// =====================================================================
export const publicAPI = {
  stats: () => api.get('/v1/stats/public').then((r) => r.data),
  /**
   * Recent orders for the landing ticker. Returns up to 12 sanitized rows:
   * { id, quantity, service, status, ageSeconds }. No usernames or URLs.
   */
  recentOrders: () =>
    api
      .get<Array<{ id: number; quantity: number; service: string; status: string; ageSeconds: number }>>(
        '/v1/stats/recent-orders',
      )
      .then((r) => r.data),
};

// =====================================================================
// Admin types (subset — surface what the /admin/bot page consumes)
// =====================================================================
export type BotInstanceStatus = {
  id: string;
  baseUrl: string;
  online: boolean;
  status?: string;
  version?: string;
  uptime?: string;
  uptimeSec?: number;
  heartbeatAt?: string;
  running?: boolean;
  draining?: boolean;
  activeWorkers?: number;
  queueDepth?: number;
  inProgress?: number;
  totalOrders?: number;
  completedOrders?: number;
  failedOrders?: number;
  cancelledOrders?: number;
  lastError?: string;
};

export type BotWebhookEvent = {
  ts: string;
  source?: 'webhook' | 'rabbitmq';
  externalId?: string;
  botOrderId?: string;
  event?: string;
  status?: string;
  completed?: number;
  failed?: number;
  message?: string;
  severity?: 'info' | 'success' | 'warn' | 'error';
};

// =====================================================================
// /admin/system types — Logs / Errors / Queues / Cache tabs
// =====================================================================
export type LogLevel = 'TRACE' | 'DEBUG' | 'INFO' | 'WARN' | 'ERROR';

export type SystemLogEntry = {
  ts: string;
  level: LogLevel;
  source: string;
  logger?: string;
  thread?: string;
  msg: string;
  throwable?: string;
  throwableClass?: string;
  mdc?: Record<string, string>;
};

export type SystemErrorGroup = {
  hash: string;
  sample?: string;
  throwableClass?: string;
  count: number;
  firstSeen?: string;
  lastSeen?: string;
  sources?: string[];
  samples?: SystemLogEntry[];
};

export type QueueStats = {
  name: string;
  depth: number;
  consumers: number;
  unacked: number;
  /** -1 when the underlying source can't report rates (AMQP fallback). */
  deliverRate: number;
  ackRate: number;
  publishRate: number;
  /** -1 when the queue has no DLX configured. */
  dlqDepth: number;
  isDlq: boolean;
};

export type AppSetting = {
  key: string;
  value: string;
  valueType: 'STRING' | 'NUMBER' | 'BOOLEAN';
  description?: string;
  updatedAt?: string;
  updatedBy?: string;
};

export type CacheStats = {
  usedMemory: number;
  usedMemoryPeak: number;
  /** 0 means uncapped. */
  maxMemory: number;
  usedMemoryHuman: string;
  totalKeys: number;
  keyspaceHits: number;
  keyspaceMisses: number;
  /** Percentage 0..100. -1 if undefined (no traffic yet). */
  hitRate: number;
  /** ops/sec from Redis itself. -1 if unavailable. */
  opsPerSec: number;
  evictedKeys: number;
  expiredKeys: number;
  connectedClients: number;
  uptimeSeconds: number;
  version: string;
};

// =====================================================================
// Admin (kept for Phase 2 implementation; new admin endpoints land in P3)
// =====================================================================
export const adminAPI = {
  getDashboard: () => api.get('/v2/admin/dashboard').then((r) => r.data),

  // `urlSearch` is independent from `search`. It always targets the URL column, so admin can
  // paste a short fragment (e.g. "/p/ABC123") that the legacy `search` heuristic would have
  // mis-routed to the username column.
  getAllOrders: (params: { status?: string; search?: string; urlSearch?: string; dateFrom?: string; dateTo?: string; page?: number; size?: number } = {}) =>
    api.get('/v2/admin/orders', { params: { page: 0, size: 50, ...params } }).then((r) => r.data),

  // Persistent admin-action feed. Replaces the in-memory Zustand store on the dashboard so
  // refresh doesn't wipe the history. Server returns rows newest-first.
  getAuditLog: (limit = 50) =>
    api.get('/v2/admin/audit-log', { params: { limit } }).then((r) => r.data),

  performOrderAction: (orderId: number, data: { action: string; reason?: string; remains?: number }) =>
    api.post(`/v2/admin/orders/${orderId}/actions`, data).then((r) => r.data),

  getUsers: (page = 0, size = 20, search?: string) =>
    api.get('/v2/admin/users', { params: { page, size, search } }).then((r) => r.data),

  getAllDeposits: (status?: string, page = 0, size = 50) =>
    api.get('/v2/admin/deposits', { params: { status, page, size } }).then((r) => r.data),

  adjustUserBalance: (userId: number, amount: number, reason: string) =>
    api.put(`/v2/admin/users/${userId}/balance`, { amount, reason }).then((r) => r.data),

  updateUserRole: (userId: number, role: 'USER' | 'ADMIN' | 'OPERATOR') =>
    api.put(`/v2/admin/users/${userId}/role`, { role }).then((r) => r.data),

  bulkOrderAction: (data: { orderIds: number[]; action: string; reason?: string }) =>
    api.post('/v2/admin/orders/bulk-actions', data).then((r) => r.data),

  // Telegram pending decisions / profit calendar
  telegramPending: () => api.get('/v2/admin/telegram/pending-decisions').then((r) => r.data),
  telegramProceed: (orderId: number) =>
    api.post(`/v2/admin/telegram/decisions/${orderId}/proceed`).then((r) => r.data),
  telegramCancel: (orderId: number) =>
    api.post(`/v2/admin/telegram/decisions/${orderId}/cancel`).then((r) => r.data),
  telegramProfit: (month: string) =>
    api.get('/v2/admin/telegram/profit', { params: { month } }).then((r) => r.data),

  // System monitoring — /admin/system page.
  // Logs / Errors → Redis-backed entries pushed by LogbackRedisAppender.
  // Queues → RabbitMQ HTTP Management API (or AMQP fallback).
  // Cache → Redis INFO snapshot.
  systemHealth: () => api.get('/v2/admin/system/health').then((r) => r.data),

  systemLogs: (params: { level?: string; search?: string; source?: string; limit?: number } = {}) =>
    api
      .get<SystemLogEntry[]>('/v2/admin/system/logs', { params: { limit: 200, ...params } })
      .then((r) => r.data),
  systemErrors: (sinceHours = 24) =>
    api
      .get<SystemErrorGroup[]>('/v2/admin/system/errors', { params: { since: sinceHours } })
      .then((r) => r.data),
  systemErrorsCount: (sinceHours = 24) =>
    api
      .get<{ count: number }>('/v2/admin/system/errors/count', { params: { since: sinceHours } })
      .then((r) => r.data),
  systemQueues: () => api.get<QueueStats[]>('/v2/admin/system/queues').then((r) => r.data),
  systemQueuePurge: (name: string) =>
    api
      .post<{ queue: string; purged: number }>(
        `/v2/admin/system/queues/${encodeURIComponent(name)}/purge`,
      )
      .then((r) => r.data),
  systemCache: () => api.get<CacheStats>('/v2/admin/system/cache').then((r) => r.data),
  systemCacheFlush: (confirmation: string) =>
    api
      .post<{ flushed: boolean; keysBefore: number; actor: string }>(
        '/v2/admin/system/cache/flush',
        { confirmation },
      )
      .then((r) => r.data),

  /** Daily order/revenue breakdown for admin dashboard charts. */
  dailyStats: (days = 30) =>
    api
      .get<Array<{ date: string; total: number; completed: number; partial: number; cancelled: number; revenue: string | number }>>(
        '/v2/admin/stats/daily',
        { params: { days } },
      )
      .then((r) => r.data),

  // Bot fleet — real /admin/bot page. Status proxies /api/health + /api/orders/stats
  // on each configured bot instance; controls proxy /api/orders/workers, /drain, and
  // /admin/reload through the panel backend. Live webhook tail uses an SSE stream.
  botStatus: () => api.get('/v2/admin/bot/status').then((r) => r.data),
  botWorkersStart: (id: string) =>
    api.post(`/v2/admin/bot/instances/${id}/workers/start`).then((r) => r.data),
  botWorkersStop: (id: string) =>
    api.post(`/v2/admin/bot/instances/${id}/workers/stop`).then((r) => r.data),
  botWorkersDrain: (id: string) =>
    api.post(`/v2/admin/bot/instances/${id}/workers/drain`).then((r) => r.data),
  botWorkersResume: (id: string) =>
    api.post(`/v2/admin/bot/instances/${id}/workers/resume`).then((r) => r.data),
  botReload: (id: string) =>
    api.post(`/v2/admin/bot/instances/${id}/reload`).then((r) => r.data),
  botQueue: (id: string, params?: { limit?: number; status?: string }) =>
    api
      .get(`/v2/admin/bot/instances/${id}/queue`, { params })
      .then((r) => r.data as Array<Record<string, unknown>>),
  botRecentWebhooks: (limit = 50) =>
    api
      .get('/v2/admin/bot/webhooks/recent', { params: { limit } })
      .then((r) => r.data as BotWebhookEvent[]),

  // Tickets — admin side
  ticketsList: (status?: string, page = 0, size = 25) =>
    api.get('/v2/admin/tickets', { params: { status, page, size } }).then((r) => r.data),
  ticketsGet: (id: number | string) => api.get(`/v2/admin/tickets/${id}`).then((r) => r.data),
  ticketsReply: (id: number | string, body: string) =>
    api.post(`/v2/admin/tickets/${id}/messages`, { body }).then((r) => r.data),
  ticketsSetStatus: (id: number | string, status: 'OPEN' | 'WAITING' | 'CLOSED') =>
    api.put(`/v2/admin/tickets/${id}/status`, { status }).then((r) => r.data),

  // App settings — backs /admin/settings (real persistence, not the old mock).
  // GET returns the full list; PUT upserts one key. Server validates the value
  // against the slot's value_type (NUMBER/BOOLEAN/STRING) and writes an audit
  // entry to operator_logs on success.
  settingsList: () =>
    api.get<AppSetting[]>('/v2/admin/settings').then((r) => r.data),
  settingsUpdate: (key: string, value: string) =>
    api
      .put<AppSetting>(`/v2/admin/settings/${encodeURIComponent(key)}`, { value })
      .then((r) => r.data),

  // Refill requests — admin queue
  refillRequestsList: (status?: 'PENDING' | 'APPROVED' | 'REJECTED', page = 0, size = 25) =>
    api.get('/v2/admin/refill-requests', { params: { status, page, size } }).then((r) => r.data),
  refillRequestsGet: (id: number | string) =>
    api.get(`/v2/admin/refill-requests/${id}`).then((r) => r.data),
  refillRequestsApprove: (id: number | string) =>
    api.post(`/v2/admin/refill-requests/${id}/approve`).then((r) => r.data),
  refillRequestsReject: (id: number | string, reason: string) =>
    api.post(`/v2/admin/refill-requests/${id}/reject`, { reason }).then((r) => r.data),
};

export default api;
