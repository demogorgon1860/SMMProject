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

// Global 401 handler: bounce to /login except on auth endpoints
// (so login form can show "wrong password" instead of redirecting).
api.interceptors.response.use(
  (response) => response,
  (error: AxiosError) => {
    const url = error.config?.url ?? '';
    const isAuthEndpoint = /\/auth\/(login|register|forgot|reset|verify)/.test(url);
    if (error.response?.status === 401 && !isAuthEndpoint) {
      localStorage.removeItem('token');
      localStorage.removeItem('refreshToken');
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

  // Phase 3 backend
  refill: (id: number) => api.post(`/v1/orders/${id}/refill`).then((r) => r.data),

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
// Profile (Phase 3 backend — sessions, 2FA, IP allow-list, security score)
// All return 404 until backend is implemented; UI shows placeholder copy.
// =====================================================================
export const profileAPI = {
  me: () => api.get('/v1/me').then((r) => r.data),
  updateMe: (patch: Record<string, unknown>) => api.patch('/v1/me', patch).then((r) => r.data),

  changePassword: (current: string, next: string) =>
    api.post('/v1/me/password', { current, next }).then((r) => r.data),

  notifications: () => api.get('/v1/me/notifications').then((r) => r.data),
  updateNotifications: (patch: Record<string, boolean>) =>
    api.patch('/v1/me/notifications', patch).then((r) => r.data),

  twoFactorInit: () => api.post('/v1/me/2fa/init').then((r) => r.data),
  twoFactorVerify: (code: string) => api.post('/v1/me/2fa/verify', { code }).then((r) => r.data),
  twoFactorDisable: () => api.delete('/v1/me/2fa').then((r) => r.data),

  sessions: () => api.get('/v1/me/sessions').then((r) => r.data),
  revokeSession: (id: string) => api.delete(`/v1/me/sessions/${id}`).then((r) => r.data),
  signOutOthers: () => api.post('/v1/me/sessions/sign-out-others').then((r) => r.data),

  ipAllowlist: () => api.get('/v1/me/ip-allowlist').then((r) => r.data),
  addIp: (ip: string, label?: string) =>
    api.post('/v1/me/ip-allowlist', { ip, label }).then((r) => r.data),
  removeIp: (id: string) => api.delete(`/v1/me/ip-allowlist/${id}`).then((r) => r.data),

  securityScore: () => api.get('/v1/me/security-score').then((r) => r.data),
  exportData: () => api.post('/v1/me/export').then((r) => r.data),
  pauseApi: () => api.post('/v1/me/api-pause').then((r) => r.data),
  deleteAccount: (confirmation: string) =>
    api.delete('/v1/me', { data: { confirmation } }).then((r) => r.data),
};

// =====================================================================
// Tickets / FAQ (Phase 3 backend)
// =====================================================================
export const supportAPI = {
  faq: () => api.get('/v1/faq').then((r) => r.data),
  tickets: () => api.get('/v1/tickets').then((r) => r.data),
  ticket: (id: string) => api.get(`/v1/tickets/${id}`).then((r) => r.data),
  createTicket: (payload: { topic: string; orderId?: string; subject: string; description: string }) =>
    api.post('/v1/tickets', payload).then((r) => r.data),
};

// =====================================================================
// Public stats (landing page)
// =====================================================================
export const publicAPI = {
  stats: () => api.get('/v1/stats/public').then((r) => r.data),
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

  // Phase 3 — Telegram pending decisions / profit calendar
  telegramPending: () => api.get('/v2/admin/telegram/pending-decisions').then((r) => r.data),
  telegramProceed: (orderId: number) =>
    api.post(`/v2/admin/telegram/decisions/${orderId}/proceed`).then((r) => r.data),
  telegramCancel: (orderId: number) =>
    api.post(`/v2/admin/telegram/decisions/${orderId}/cancel`).then((r) => r.data),
  telegramHistory: (limit = 50) =>
    api.get('/v2/admin/telegram/history', { params: { limit } }).then((r) => r.data),
  telegramProfit: (month: string) =>
    api.get('/v2/admin/telegram/profit', { params: { month } }).then((r) => r.data),

  // Phase 3 — bot fleet
  bots: () => api.get('/v2/admin/bots').then((r) => r.data),
  botProfileGroups: () => api.get('/v2/admin/bots/profile-groups').then((r) => r.data),
  scaleBot: (id: string, delta: number) =>
    api.post(`/v2/admin/bots/${id}/scale`, { delta }).then((r) => r.data),

  // Phase 3 — system monitoring
  systemHealth: () => api.get('/v2/admin/system/health').then((r) => r.data),
  systemQueues: () => api.get('/v2/admin/system/rabbitmq/queues').then((r) => r.data),
  systemRedis: () => api.get('/v2/admin/system/redis/stats').then((r) => r.data),
  systemErrors: () => api.get('/v2/admin/system/errors').then((r) => r.data),
};

export default api;
