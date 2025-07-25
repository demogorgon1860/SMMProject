import axios, { AxiosResponse, AxiosError } from 'axios'
import toast from 'react-hot-toast'
import {
  User,
  Service,
  Order,
  BalanceTransaction,
  CryptoPayment,
  TrafficSource,
  ConversionCoefficient,
  DashboardStats,
  CreateOrderRequest,
  CreateDepositRequest,
  BulkActionRequest,
  LoginRequest,
  RegisterRequest,
  AuthResponse,
  PerfectPanelResponse,
  PaginatedResponse,
  OrderFilters,
  UserFilters,
  SystemSettings,
  ApiError,
} from '@/types'

// API Configuration
const API_BASE_URL = import.meta.env.VITE_API_URL || '/api/v2'
const API_TIMEOUT = 30000

// Create axios instance with Perfect Panel compatibility
const api = axios.create({
  baseURL: API_BASE_URL,
  timeout: API_TIMEOUT,
  headers: {
    'Content-Type': 'application/json',
    'Accept': 'application/json',
  },
})

// Secure token storage utility
const TokenStorage = {
  setToken: (token: string) => {
    try {
      // Use sessionStorage for better security, or implement secure storage
      sessionStorage.setItem('authToken', token)
    } catch (error) {
      console.error('Failed to store auth token:', error)
    }
  },
  
  getToken: (): string | null => {
    try {
      return sessionStorage.getItem('authToken')
    } catch (error) {
      console.error('Failed to retrieve auth token:', error)
      return null
    }
  },
  
  removeToken: () => {
    try {
      sessionStorage.removeItem('authToken')
      sessionStorage.removeItem('refreshToken')
    } catch (error) {
      console.error('Failed to remove auth token:', error)
    }
  },
  
  setRefreshToken: (token: string) => {
    try {
      sessionStorage.setItem('refreshToken', token)
    } catch (error) {
      console.error('Failed to store refresh token:', error)
    }
  },
  
  getRefreshToken: (): string | null => {
    try {
      return sessionStorage.getItem('refreshToken')
    } catch (error) {
      console.error('Failed to retrieve refresh token:', error)
      return null
    }
  }
}

// Request interceptor for authentication
api.interceptors.request.use(
  (config) => {
    const token = TokenStorage.getToken()
    if (token) {
      config.headers.Authorization = `Bearer ${token}`
    }
    
    // Add request ID for tracking
    config.headers['X-Request-ID'] = crypto.randomUUID()
    
    return config
  },
  (error) => {
    return Promise.reject(error)
  }
)

// Response interceptor for error handling and token refresh
api.interceptors.response.use(
  (response: AxiosResponse) => {
    return response
  },
  async (error: AxiosError<ApiError>) => {
    const originalRequest = error.config

    // Handle 401 - Token expired
    if (error.response?.status === 401 && originalRequest && !originalRequest._retry) {
      originalRequest._retry = true
      
      try {
        const refreshToken = TokenStorage.getRefreshToken()
        if (refreshToken) {
          const response = await axios.post(`${API_BASE_URL}/auth/refresh`, {
            refreshToken,
          })
          
          const { accessToken, refreshToken: newRefreshToken } = response.data
          TokenStorage.setToken(accessToken)
          TokenStorage.setRefreshToken(newRefreshToken)
          
          // Retry original request with new token
          originalRequest.headers.Authorization = `Bearer ${accessToken}`
          return api(originalRequest)
        }
      } catch (refreshError) {
        // Refresh failed, redirect to login
        localStorage.removeItem('authToken')
        localStorage.removeItem('refreshToken')
        window.location.href = '/login'
        return Promise.reject(refreshError)
      }
    }

    // Handle other errors
    if (error.response?.status === 429) {
      toast.error('Too many requests. Please wait a moment.')
    } else if (error.response?.status >= 500) {
      toast.error('Server error. Please try again later.')
    } else if (error.response?.data?.message) {
      toast.error(error.response.data.message)
    }

    return Promise.reject(error)
  }
)

// Perfect Panel compatible error handling
const handleApiError = (error: AxiosError<ApiError>): never => {
  const message = error.response?.data?.message || 'An unexpected error occurred'
  const code = error.response?.status || 500
  
  throw {
    message,
    code,
    details: error.response?.data?.details,
  } as ApiError
}

// API Service
export const apiService = {
  // Authentication
  auth: {
    login: async (data: LoginRequest): Promise<AuthResponse> => {
      try {
        const response = await api.post<AuthResponse>('/auth/login', data)
        
        // Store tokens
        localStorage.setItem('authToken', response.data.token)
        localStorage.setItem('refreshToken', response.data.refreshToken)
        
        return response.data
      } catch (error) {
        handleApiError(error as AxiosError<ApiError>)
      }
    },

    register: async (data: RegisterRequest): Promise<AuthResponse> => {
      try {
        const response = await api.post<AuthResponse>('/auth/register', data)
        
        // Store tokens
        localStorage.setItem('authToken', response.data.token)
        localStorage.setItem('refreshToken', response.data.refreshToken)
        
        return response.data
      } catch (error) {
        handleApiError(error as AxiosError<ApiError>)
      }
    },

    getCurrentUser: async (): Promise<User> => {
      try {
        const response = await api.get<User>('/auth/me')
        return response.data
      } catch (error) {
        handleApiError(error as AxiosError<ApiError>)
      }
    },

    logout: async (): Promise<void> => {
      try {
        await api.post('/auth/logout')
      } catch (error) {
        // Continue with logout even if API call fails
        console.warn('Logout API call failed:', error)
      } finally {
        localStorage.removeItem('authToken')
        localStorage.removeItem('refreshToken')
      }
    },

    refreshToken: async (refreshToken: string): Promise<{ accessToken: string; refreshToken: string }> => {
      try {
        const response = await api.post('/auth/refresh', { refreshToken })
        return response.data
      } catch (error) {
        handleApiError(error as AxiosError<ApiError>)
      }
    },
  },

  // Services (Perfect Panel compatible)
  services: {
    getAll: async (): Promise<Service[]> => {
      try {
        const response = await api.get<PerfectPanelResponse<Service[]>>('/services')
        return response.data.services || []
      } catch (error) {
        handleApiError(error as AxiosError<ApiError>)
      }
    },

    getById: async (id: number): Promise<Service> => {
      try {
        const response = await api.get<Service>(`/services/${id}`)
        return response.data
      } catch (error) {
        handleApiError(error as AxiosError<ApiError>)
      }
    },
  },

  // Orders (Perfect Panel compatible)
  orders: {
    create: async (data: CreateOrderRequest): Promise<Order> => {
      try {
        const response = await api.post<PerfectPanelResponse<Order>>('/orders', data)
        
        if (response.data.error) {
          throw new Error(response.data.error)
        }
        
        return response.data.order!
      } catch (error) {
        if (error instanceof Error) {
          throw error
        }
        handleApiError(error as AxiosError<ApiError>)
      }
    },

    getAll: async (filters?: Partial<OrderFilters>): Promise<PaginatedResponse<Order>> => {
      try {
        const params = new URLSearchParams()
        
        if (filters?.status && filters.status !== 'all') {
          params.append('status', filters.status)
        }
        if (filters?.username) {
          params.append('username', filters.username)
        }
        if (filters?.dateFrom) {
          params.append('dateFrom', filters.dateFrom)
        }
        if (filters?.dateTo) {
          params.append('dateTo', filters.dateTo)
        }
        if (filters?.serviceId) {
          params.append('serviceId', filters.serviceId.toString())
        }
        if (filters?.page !== undefined) {
          params.append('page', filters.page.toString())
        }
        if (filters?.size !== undefined) {
          params.append('size', filters.size.toString())
        }

        const response = await api.get<PerfectPanelResponse<PaginatedResponse<Order>>>(`/orders?${params}`)
        return response.data.orders!
      } catch (error) {
        handleApiError(error as AxiosError<ApiError>)
      }
    },

    getById: async (id: number): Promise<Order> => {
      try {
        const response = await api.get<PerfectPanelResponse<Order>>(`/orders/${id}`)
        return response.data.order!
      } catch (error) {
        handleApiError(error as AxiosError<ApiError>)
      }
    },

    cancel: async (id: number): Promise<void> => {
      try {
        await api.put(`/orders/${id}/cancel`)
      } catch (error) {
        handleApiError(error as AxiosError<ApiError>)
      }
    },

    refill: async (id: number): Promise<void> => {
      try {
        await api.put(`/orders/${id}/refill`)
      } catch (error) {
        handleApiError(error as AxiosError<ApiError>)
      }
    },
  },

  // Balance & Payments (Perfect Panel compatible)
  balance: {
    get: async (): Promise<string> => {
      try {
        const response = await api.get<PerfectPanelResponse>('/balance')
        return response.data.balance!
      } catch (error) {
        handleApiError(error as AxiosError<ApiError>)
      }
    },

    createDeposit: async (data: CreateDepositRequest): Promise<CryptoPayment> => {
      try {
        const response = await api.post<CryptoPayment>('/deposits', data)
        return response.data
      } catch (error) {
        handleApiError(error as AxiosError<ApiError>)
      }
    },

    getDepositStatus: async (orderId: string): Promise<CryptoPayment> => {
      try {
        const response = await api.get<CryptoPayment>(`/deposits/${orderId}/status`)
        return response.data
      } catch (error) {
        handleApiError(error as AxiosError<ApiError>)
      }
    },

    getTransactions: async (params?: { page?: number; size?: number }): Promise<PaginatedResponse<BalanceTransaction>> => {
      try {
        const response = await api.get<PaginatedResponse<BalanceTransaction>>('/balance/transactions', { params })
        return response.data
      } catch (error) {
        handleApiError(error as AxiosError<ApiError>)
      }
    },

    getDeposits: async (params?: { page?: number; size?: number }): Promise<PaginatedResponse<CryptoPayment>> => {
      try {
        const response = await api.get<PaginatedResponse<CryptoPayment>>('/deposits', { params })
        return response.data
      } catch (error) {
        handleApiError(error as AxiosError<ApiError>)
      }
    },
  },

  // Admin Panel Functions
  admin: {
    getDashboardStats: async (): Promise<DashboardStats> => {
      try {
        const response = await api.get<DashboardStats>('/admin/dashboard/stats')
        return response.data
      } catch (error) {
        handleApiError(error as AxiosError<ApiError>)
      }
    },

    getAllOrders: async (filters?: Partial<OrderFilters>): Promise<PaginatedResponse<Order>> => {
      try {
        const params = new URLSearchParams()
        
        if (filters?.status && filters.status !== 'all') {
          params.append('status', filters.status)
        }
        if (filters?.username) {
          params.append('username', filters.username)
        }
        if (filters?.dateFrom) {
          params.append('dateFrom', filters.dateFrom)
        }
        if (filters?.dateTo) {
          params.append('dateTo', filters.dateTo)
        }
        if (filters?.serviceId) {
          params.append('serviceId', filters.serviceId.toString())
        }
        if (filters?.page !== undefined) {
          params.append('page', filters.page.toString())
        }
        if (filters?.size !== undefined) {
          params.append('size', filters.size.toString())
        }

        const response = await api.get<PaginatedResponse<Order>>(`/admin/orders?${params}`)
        return response.data
      } catch (error) {
        handleApiError(error as AxiosError<ApiError>)
      }
    },

    bulkAction: async (data: BulkActionRequest): Promise<void> => {
      try {
        await api.post('/admin/orders/bulk-action', data)
        toast.success(`Bulk action completed for ${data.orderIds.length} orders`)
      } catch (error) {
        handleApiError(error as AxiosError<ApiError>)
      }
    },

    updateOrderStatus: async (orderId: number, status: string, reason?: string): Promise<void> => {
      try {
        await api.put(`/admin/orders/${orderId}/status`, { status, reason })
        toast.success('Order status updated successfully')
      } catch (error) {
        handleApiError(error as AxiosError<ApiError>)
      }
    },

    setStartCount: async (orderId: number, startCount: number): Promise<void> => {
      try {
        await api.put(`/admin/orders/${orderId}/start-count`, { startCount })
        toast.success('Start count updated successfully')
      } catch (error) {
        handleApiError(error as AxiosError<ApiError>)
      }
    },

    getUsers: async (filters?: Partial<UserFilters>): Promise<PaginatedResponse<User>> => {
      try {
        const params = new URLSearchParams()
        
        if (filters?.role && filters.role !== 'all') {
          params.append('role', filters.role)
        }
        if (filters?.isActive !== undefined) {
          params.append('isActive', filters.isActive.toString())
        }
        if (filters?.search) {
          params.append('search', filters.search)
        }
        if (filters?.page !== undefined) {
          params.append('page', filters.page.toString())
        }
        if (filters?.size !== undefined) {
          params.append('size', filters.size.toString())
        }

        const response = await api.get<PaginatedResponse<User>>(`/admin/users?${params}`)
        return response.data
      } catch (error) {
        handleApiError(error as AxiosError<ApiError>)
      }
    },

    updateUser: async (userId: number, data: Partial<User>): Promise<User> => {
      try {
        const response = await api.put<User>(`/admin/users/${userId}`, data)
        toast.success('User updated successfully')
        return response.data
      } catch (error) {
        handleApiError(error as AxiosError<ApiError>)
      }
    },

    addBalance: async (userId: number, amount: number, reason: string): Promise<void> => {
      try {
        await api.post(`/admin/users/${userId}/balance`, { amount, reason })
        toast.success('Balance added successfully')
      } catch (error) {
        handleApiError(error as AxiosError<ApiError>)
      }
    },

    getConversionCoefficients: async (): Promise<ConversionCoefficient[]> => {
      try {
        const response = await api.get<ConversionCoefficient[]>('/admin/conversion-coefficients')
        return response.data
      } catch (error) {
        handleApiError(error as AxiosError<ApiError>)
      }
    },

    updateConversionCoefficient: async (id: number, data: Partial<ConversionCoefficient>): Promise<ConversionCoefficient> => {
      try {
        const response = await api.put<ConversionCoefficient>(`/admin/conversion-coefficients/${id}`, data)
        toast.success('Conversion coefficient updated successfully')
        return response.data
      } catch (error) {
        handleApiError(error as AxiosError<ApiError>)
      }
    },

    getTrafficSources: async (): Promise<TrafficSource[]> => {
      try {
        const response = await api.get<TrafficSource[]>('/admin/traffic-sources')
        return response.data
      } catch (error) {
        handleApiError(error as AxiosError<ApiError>)
      }
    },

    createTrafficSource: async (data: Omit<TrafficSource, 'id' | 'createdAt'>): Promise<TrafficSource> => {
      try {
        const response = await api.post<TrafficSource>('/admin/traffic-sources', data)
        toast.success('Traffic source created successfully')
        return response.data
      } catch (error) {
        handleApiError(error as AxiosError<ApiError>)
      }
    },

    updateTrafficSource: async (id: number, data: Partial<TrafficSource>): Promise<TrafficSource> => {
      try {
        const response = await api.put<TrafficSource>(`/admin/traffic-sources/${id}`, data)
        toast.success('Traffic source updated successfully')
        return response.data
      } catch (error) {
        handleApiError(error as AxiosError<ApiError>)
      }
    },

    deleteTrafficSource: async (id: number): Promise<void> => {
      try {
        await api.delete(`/admin/traffic-sources/${id}`)
        toast.success('Traffic source deleted successfully')
      } catch (error) {
        handleApiError(error as AxiosError<ApiError>)
      }
    },

    getSystemSettings: async (): Promise<SystemSettings> => {
      try {
        const response = await api.get<SystemSettings>('/admin/settings')
        return response.data
      } catch (error) {
        handleApiError(error as AxiosError<ApiError>)
      }
    },

    updateSystemSettings: async (data: Partial<SystemSettings>): Promise<SystemSettings> => {
      try {
        const response = await api.put<SystemSettings>('/admin/settings', data)
        toast.success('System settings updated successfully')
        return response.data
      } catch (error) {
        handleApiError(error as AxiosError<ApiError>)
      }
    },

    getOperatorLogs: async (params?: { page?: number; size?: number }): Promise<PaginatedResponse<any>> => {
      try {
        const response = await api.get<PaginatedResponse<any>>('/admin/logs', { params })
        return response.data
      } catch (error) {
        handleApiError(error as AxiosError<ApiError>)
      }
    },
  },

  // File uploads
  files: {
    uploadOrders: async (file: File): Promise<{ imported: number; failed: number; errors: string[] }> => {
      try {
        const formData = new FormData()
        formData.append('file', file)

        const response = await api.post('/admin/orders/import', formData, {
          headers: {
            'Content-Type': 'multipart/form-data',
          },
        })

        toast.success(`Imported ${response.data.imported} orders successfully`)
        return response.data
      } catch (error) {
        handleApiError(error as AxiosError<ApiError>)
      }
    },

    exportOrders: async (filters?: Partial<OrderFilters>): Promise<Blob> => {
      try {
        const params = new URLSearchParams()
        
        if (filters?.status && filters.status !== 'all') {
          params.append('status', filters.status)
        }
        if (filters?.username) {
          params.append('username', filters.username)
        }
        if (filters?.dateFrom) {
          params.append('dateFrom', filters.dateFrom)
        }
        if (filters?.dateTo) {
          params.append('dateTo', filters.dateTo)
        }

        const response = await api.get(`/admin/orders/export?${params}`, {
          responseType: 'blob',
        })

        return response.data
      } catch (error) {
        handleApiError(error as AxiosError<ApiError>)
      }
    },
  },

  // WebSocket connection for real-time updates
  ws: {
    connect: (onMessage: (message: any) => void, onError?: (error: Event) => void): WebSocket | null => {
      try {
        const token = localStorage.getItem('authToken')
        if (!token) {
          console.warn('No auth token available for WebSocket connection')
          return null
        }

        const wsUrl = `${import.meta.env.VITE_WS_URL || 'ws://localhost:8080'}/ws?token=${token}`
        const ws = new WebSocket(wsUrl)

        ws.onopen = () => {
          console.log('WebSocket connected')
        }

        ws.onmessage = (event) => {
          try {
            const message = JSON.parse(event.data)
            onMessage(message)
          } catch (error) {
            console.error('Failed to parse WebSocket message:', error)
          }
        }

        ws.onerror = (error) => {
          console.error('WebSocket error:', error)
          onError?.(error)
        }

        ws.onclose = (event) => {
          console.log('WebSocket closed:', event.code, event.reason)
          
          // Attempt to reconnect after 3 seconds if not manually closed
          if (event.code !== 1000) {
            setTimeout(() => {
              console.log('Attempting to reconnect WebSocket...')
              apiService.ws.connect(onMessage, onError)
            }, 3000)
          }
        }

        return ws
      } catch (error) {
        console.error('Failed to connect WebSocket:', error)
        onError?.(error as Event)
        return null
      }
    },
  },
}

// Export individual services for direct access
export const { auth, services, orders, balance, admin, files, ws } = apiService

// Export default
export default apiService