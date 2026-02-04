import axios from 'axios';

const API_BASE_URL = '/api';

const api = axios.create({
  baseURL: API_BASE_URL,
  headers: {
    'Content-Type': 'application/json',
  },
});

api.interceptors.request.use(
  (config) => {
    const token = localStorage.getItem('token');
    if (token) {
      config.headers.Authorization = `Bearer ${token}`;
    }
    return config;
  },
  (error) => {
    return Promise.reject(error);
  }
);

api.interceptors.response.use(
  (response) => response,
  async (error) => {
    if (error.response?.status === 401) {
      localStorage.removeItem('token');
      localStorage.removeItem('user');
      window.location.href = '/login';
    }
    return Promise.reject(error);
  }
);

// Auth API
export const authAPI = {
  login: async (username: string, password: string) => {
    const response = await api.post('/v1/auth/login', { username, password });
    return response.data;
  },
  
  register: async (username: string, email: string, password: string) => {
    const response = await api.post('/v1/auth/register', { username, email, password });
    return response.data;
  },
  
  getCurrentUser: async () => {
    const response = await api.get('/v1/auth/me');
    return response.data;
  },
  
  refreshToken: async (refreshToken: string) => {
    const response = await api.post('/v1/auth/refresh', { refreshToken });
    return response.data;
  },
};

// Order API
export const orderAPI = {
  createOrder: async (orderData: {
    service: number;
    link: string;
    quantity: number;
  }) => {
    const response = await api.post('/v1/orders', orderData);
    return response.data;
  },
  
  deleteOrder: async (orderId: number) => {
    const response = await api.delete(`/v1/orders/${orderId}`);
    return response.data;
  },
  
  getOrders: async (status?: string, dateFrom?: string, dateTo?: string, page = 0, size = 100) => {
    const response = await api.get('/v1/orders', {
      params: { status, dateFrom, dateTo, page, size }
    });
    return response.data;
  },
  
  getOrderStatus: async (orderId: number) => {
    const response = await api.get(`/v1/orders/${orderId}/status`);
    return response.data;
  },

  createMassOrder: async (massOrderData: {
    ordersText: string;
    delimiter?: string;
    maxOrders?: number;
  }) => {
    const response = await api.post('/v1/orders/mass', massOrderData);
    return response.data;
  },

  previewMassOrder: async (massOrderData: {
    ordersText: string;
    delimiter?: string;
    maxOrders?: number;
  }) => {
    const response = await api.post('/v1/orders/mass/preview', massOrderData);
    return response.data;
  },
};

// Service API
export const serviceAPI = {
  getServices: async () => {
    const response = await api.get('/v1/service/services');
    return response.data;
  },
};

// User API
export const userAPI = {
  getBalance: async () => {
    const response = await api.get('/v1/balance');
    return response.data;
  },
};

// API Key Management
export const apiKeyAPI = {
  generate: async () => {
    const response = await api.post('/v1/apikey/generate');
    return response.data;
  },

  rotate: async () => {
    const response = await api.post('/v1/apikey/rotate');
    return response.data;
  },

  getStatus: async () => {
    const response = await api.get('/v1/apikey/status');
    return response.data;
  },
};

// Admin API
export const adminAPI = {
  getDashboard: async () => {
    const response = await api.get('/v2/admin/dashboard');
    return response.data;
  },

  getAllOrders: async (status?: string, username?: string, dateFrom?: string, dateTo?: string, page = 0, size = 100) => {
    const response = await api.get('/v2/admin/orders', {
      params: { status, username, dateFrom, dateTo, page, size }
    });
    return response.data;
  },

  performOrderAction: async (orderId: number, data: { action: string; reason?: string; remains?: number }) => {
    const response = await api.post(`/v2/admin/orders/${orderId}/actions`, data);
    return response.data;
  },

  updateOrderStatus: async (orderId: number, action: string) => {
    const response = await api.post(`/v2/admin/orders/${orderId}/actions`, { action });
    return response.data;
  },

  getUsers: async (page = 0, size = 20) => {
    const response = await api.get('/v2/admin/users', {
      params: { page, size }
    });
    return response.data;
  },

  getAllDeposits: async (status?: string, page = 0, size = 100) => {
    const response = await api.get('/v2/admin/deposits', {
      params: { status, page, size }
    });
    return response.data;
  },

  createRefill: async (orderId: number) => {
    const response = await api.post(`/v2/admin/orders/${orderId}/refill`);
    return response.data;
  },

  getRefillHistory: async (orderId: number) => {
    const response = await api.get(`/v2/admin/orders/${orderId}/refills`);
    return response.data;
  },

  getAllRefills: async (page = 0, size = 100) => {
    const response = await api.get('/v2/admin/refills', {
      params: { page, size, sort: 'createdAt,desc' }
    });
    return response.data;
  },
};

// Binom API
export const binomAPI = {
  testConnection: async () => {
    const response = await api.get('/v2/admin/binom/test');
    return response.data;
  },
  
  syncCampaigns: async () => {
    const response = await api.post('/v2/admin/binom/sync');
    return response.data;
  },
  
  getOffers: async () => {
    const response = await api.get('/v2/admin/binom/offers');
    return response.data;
  },
};

// YouTube API
export const youtubeAPI = {
  checkVideoViews: async (videoUrl: string) => {
    const response = await api.post('/v2/admin/youtube/check-views', { videoUrl });
    return response.data;
  },
  
  getVideoStats: async (videoId: string) => {
    const response = await api.get(`/v2/admin/youtube/video/${videoId}/stats`);
    return response.data;
  },
};

// Selenium API
export const seleniumAPI = {
  createClip: async (data: {
    videoUrl: string;
    startTime: number;
    endTime: number;
  }) => {
    const response = await api.post('/v2/admin/selenium/create-clip', data);
    return response.data;
  },
  
  getClipStatus: async (jobId: string) => {
    const response = await api.get(`/v2/admin/selenium/clip/${jobId}/status`);
    return response.data;
  },
};

// Deposits API
export const depositsAPI = {
  createDeposit: async (amount: number) => {
    const response = await api.post('/v1/deposits/create', {
      amount,
      currency: 'USD'
    });
    return response.data;
  },

  getDeposits: async (page = 0, size = 100) => {
    const response = await api.get(`/v1/deposits?page=${page}&size=${size}`);
    return response.data;
  },

  getDepositById: async (id: number) => {
    const response = await api.get(`/v1/deposits/${id}`);
    return response.data;
  },

  getRecentDeposits: async () => {
    const response = await api.get('/v1/deposits/recent');
    return response.data;
  },
};

export default api;