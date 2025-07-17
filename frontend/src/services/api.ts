// frontend/src/services/api.ts

import axios, { AxiosResponse } from 'axios';
import {
  User,
  Service,
  Order,
  CreateOrderRequest,
  CreateDepositRequest,
  DepositResponse,
  DepositStatus,
  BalanceTransaction,
  AdminOrderDto,
  BulkActionRequest,
  DashboardStats,
  ConversionCoefficient,
  TrafficSource,
  ApiResponse,
  PaginatedResponse
} from '../types';

const API_BASE_URL = process.env.NODE_ENV === 'production' 
  ? '/api/v2' 
  : 'http://localhost:8080/api/v2';

// Создаем экземпляр axios
const api = axios.create({
  baseURL: API_BASE_URL,
  headers: {
    'Content-Type': 'application/json',
  },
  timeout: 30000,
});

// Интерсептор для добавления токена авторизации
api.interceptors.request.use((config) => {
  const token = localStorage.getItem('token');
  if (token) {
    config.headers.Authorization = `Bearer ${token}`;
  }
  return config;
});

// Интерсептор для обработки ошибок
api.interceptors.response.use(
  (response) => response,
  (error) => {
    if (error.response?.status === 401) {
      localStorage.removeItem('token');
      window.location.href = '/login';
    }
    return Promise.reject(error);
  }
);

// Типы для запросов авторизации
interface LoginRequest {
  username: string;
  password: string;
}

interface RegisterRequest {
  username: string;
  email: string;
  password: string;
}

interface AuthResponse {
  token: string;
  refreshToken: string;
  user: User;
}

// API сервис
export const apiService = {
  // Авторизация
  auth: {
    login: async (data: LoginRequest): Promise<AuthResponse> => {
      const response = await api.post<AuthResponse>('/auth/login', data);
      return response.data;
    },

    register: async (data: RegisterRequest): Promise<AuthResponse> => {
      const response = await api.post<AuthResponse>('/auth/register', data);
      return response.data;
    },

    getCurrentUser: async (): Promise<User> => {
      const response = await api.get<User>('/auth/me');
      return response.data;
    },

    refreshToken: async (refreshToken: string): Promise<{ accessToken: string; refreshToken: string }> => {
      const response = await api.post('/auth/refresh', { refreshToken });
      return response.data;
    },
  },

  // Услуги
  services: {
    getAll: async (): Promise<Service[]> => {
      const response = await api.get<ApiResponse<Service[]>>('/services');
      return response.data.services || [];
    },
  },

  // Заказы
  orders: {
    create: async (data: CreateOrderRequest): Promise<Order> => {
      const response = await api.post<ApiResponse<Order>>('/orders', data);
      if (response.data.error) {
        throw new Error(response.data.error);
      }
      return response.data.order!;
    },

    getAll: async (params?: { 
      status?: string; 
      page?: number; 
      size?: number; 
    }): Promise<PaginatedResponse<Order>> => {
      const response = await api.get<ApiResponse<PaginatedResponse<Order>>>('/orders', { params });
      return response.data.orders!;
    },

    getById: async (id: number): Promise<Order> => {
      const response = await api.get<ApiResponse<Order>>(`/orders/${id}`);
      return response.data.order!;
    },
  },

  // Баланс
  balance: {
    get: async (): Promise<string> => {
      const response = await api.get<ApiResponse<never>>('/balance');
      return response.data.balance!;
    },

    createDeposit: async (data: CreateDepositRequest): Promise<DepositResponse> => {
      const response = await api.post<DepositResponse>('/deposits', data);
      return response.data;
    },

    getDepositStatus: async (orderId: string): Promise<DepositStatus> => {
      const response = await api.get<DepositStatus>(`/deposits/${orderId}/status`);
      return response.data;
    },

    getTransactions: async (params?: { 
      page?: number; 
      size?: number; 
    }): Promise<PaginatedResponse<BalanceTransaction>> => {
      const response = await api.get<PaginatedResponse<BalanceTransaction>>('/balance/transactions', { params });
      return response.data;
    },
  },

  // Административные функции
  admin: {
    getDashboardStats: async (): Promise<DashboardStats> => {
      const response = await api.get<DashboardStats>('/admin/dashboard/stats');
      return response.data;
    },

    getAllOrders: async (params?: {
      status?: string;
      username?: string;
      page?: number;
      size?: number;
    }): Promise<PaginatedResponse<AdminOrderDto>> => {
      const response = await api.get<PaginatedResponse<AdminOrderDto>>('/admin/orders', { params });
      return response.data;
    },

    bulkAction: async (data: BulkActionRequest): Promise<void> => {
      await api.post('/admin/orders/bulk-action', data);
    },

    updateOrderStatus: async (orderId: number, status: string, reason?: string): Promise<void> => {
      await api.patch(`/admin/orders/${orderId}/status`, { status, reason });
    },

    setStartCount: async (orderId: number, startCount: number): Promise<void> => {
      await api.patch(`/admin/orders/${orderId}/start-count`, { startCount });
    },

    refreshStartCount: async (orderId: number): Promise<void> => {
      await api.post(`/admin/orders/${orderId}/refresh-start-count`);
    },

    refillOrder: async (orderId: number): Promise<void> => {
      await api.post(`/admin/orders/${orderId}/refill`);
    },

    // Коэффициенты конверсии
    getConversionCoefficients: async (): Promise<ConversionCoefficient[]> => {
      const response = await api.get<ConversionCoefficient[]>('/admin/coefficients');
      return response.data;
    },

    updateConversionCoefficient: async (id: number, data: {
      withClip: number;
      withoutClip: number;
    }): Promise<void> => {
      await api.put(`/admin/coefficients/${id}`, data);
    },

    // Источники трафика
    getTrafficSources: async (): Promise<TrafficSource[]> => {
      const response = await api.get<TrafficSource[]>('/admin/traffic-sources');
      return response.data;
    },

    updateTrafficSource: async (id: number, data: Partial<TrafficSource>): Promise<void> => {
      await api.put(`/admin/traffic-sources/${id}`, data);
    },

    createTrafficSource: async (data: Omit<TrafficSource, 'id' | 'clicksUsedToday'>): Promise<TrafficSource> => {
      const response = await api.post<TrafficSource>('/admin/traffic-sources', data);
      return response.data;
    },
  },

  // Операторские функции
  operator: {
    getActiveOrders: async (params?: {
      page?: number;
      size?: number;
    }): Promise<PaginatedResponse<AdminOrderDto>> => {
      const response = await api.get<PaginatedResponse<AdminOrderDto>>('/operator/orders/active', { params });
      return response.data;
    },

    updateOrder: async (orderId: number, action: string, params?: any): Promise<void> => {
      await api.post(`/operator/orders/${orderId}/action`, { action, params });
    },
  },
};

// Экспортируем базовый экземпляр для совместимости
export default api;
export { api };