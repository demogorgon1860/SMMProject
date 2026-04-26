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
    const isInputValidationEndpoint =
      /\/auth\/(login|register|forgot|reset|verify)/.test(url) ||
      /\/me\/password\b/.test(url);

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

  register: (username: string, email: string, password: string) =>
    api.post('/v1/auth/register', { username, email, password }).then((r) => r.data),

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
export const orderAPI = {
  create: (data: { service: number; link: string; quantity: number; comments?: string }) =>
    api.post('/v1/orders', data).then((r) => r.data),

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

  transactions: (page = 0, size = 50) =>
    api.get('/v1/balance/transactions', { params: { page, size } }).then((r) => r.data),

  recentTransactions: () => api.get('/v1/balance/transactions/recent').then((r) => r.data),
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

export const profileAPI = {
  me: () => api.get('/v1/me').then((r) => r.data),
  updateMe: (patch: Record<string, unknown>) => api.patch('/v1/me', patch).then((r) => r.data),

  changePassword: (current: string, next: string) =>
    api.post('/v1/me/password', { current, next }).then((r) => r.data),

  notifications: () => api.get('/v1/me/notifications').then((r) => r.data),
  updateNotifications: (patch: Record<string, boolean>) =>
    api.patch('/v1/me/notifications', patch).then((r) => r.data),

  myRefillRequests: () => api.get('/v1/me/refill-requests').then((r) => r.data),

  // Not implemented — UI must hide or disable controls that depend on these.
  sessions: notImplemented('sessions'),
  signOutOthers: notImplemented('signOutOthers'),
  exportData: notImplemented('exportData'),
  pauseApi: notImplemented('pauseApi'),
  deleteAccount: (_confirmation: string) =>
    Promise.reject(new Error('deleteAccount is not implemented in this release')),
};

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
// Admin (kept for Phase 2 implementation; new admin endpoints land in P3)
// =====================================================================
export const adminAPI = {
  getDashboard: () => api.get('/v2/admin/dashboard').then((r) => r.data),

  getAllOrders: (params: { status?: string; search?: string; dateFrom?: string; dateTo?: string; page?: number; size?: number } = {}) =>
    api.get('/v2/admin/orders', { params: { page: 0, size: 50, ...params } }).then((r) => r.data),

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

  // System monitoring (only health is implemented today; queue/redis/error
  // stats are not in scope for this release).
  systemHealth: () => api.get('/v2/admin/system/health').then((r) => r.data),

  // Bot fleet — read-only health view today; scale operations are not yet
  // exposed. Surfaced as explicit rejections so the UI can disable controls
  // rather than render a 404 from the backend.
  bots: notImplemented('bots'),
  botProfileGroups: notImplemented('botProfileGroups'),
  scaleBot: (_id: string, _delta: number) =>
    Promise.reject(new Error('scaleBot is not implemented in this release')),

  // Tickets — admin side
  ticketsList: (status?: string, page = 0, size = 25) =>
    api.get('/v2/admin/tickets', { params: { status, page, size } }).then((r) => r.data),
  ticketsGet: (id: number | string) => api.get(`/v2/admin/tickets/${id}`).then((r) => r.data),
  ticketsReply: (id: number | string, body: string) =>
    api.post(`/v2/admin/tickets/${id}/messages`, { body }).then((r) => r.data),
  ticketsSetStatus: (id: number | string, status: 'OPEN' | 'WAITING' | 'CLOSED') =>
    api.put(`/v2/admin/tickets/${id}/status`, { status }).then((r) => r.data),

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
