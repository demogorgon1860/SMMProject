import { create } from 'zustand';
import { persist } from 'zustand/middleware';
import { immer } from 'zustand/middleware/immer';
import { toast } from 'react-hot-toast';
import { apiService } from '../services/api';
import { User } from '../types';

interface AuthState {
  user: User | null;
  isAuthenticated: boolean;
  isLoading: boolean;
  error: string | null;
  login: (credentials: { username: string; password: string }) => Promise<void>;
  register: (data: { username: string; email: string; password: string }) => Promise<void>;
  logout: () => Promise<void>;
  refreshUser: () => Promise<void>;
  initializeAuth: () => Promise<void>;
  updateUser: (updates: Partial<User>) => void;
  clearError: () => void;
}

export const useAuthStore = create<AuthState>()(
  persist(
    immer((set, get) => ({
      user: null,
      isAuthenticated: false,
      isLoading: false,
      error: null,

      login: async (credentials) => {
        set({ isLoading: true, error: null });
        try {
          const { user, token, refreshToken } = await apiService.auth.login(credentials);
          localStorage.setItem('token', token);
          localStorage.setItem('refreshToken', refreshToken);
          set({ user, isAuthenticated: true });
          toast.success('Login successful');
        } catch (error: any) {
          set({ error: error.message || 'Login failed' });
          toast.error(error.message || 'Login failed');
          throw error;
        } finally {
          set({ isLoading: false });
        }
      },

      register: async (data) => {
        set({ isLoading: true, error: null });
        try {
          await apiService.auth.register(data);
          toast.success('Registration successful! Please log in.');
        } catch (error: any) {
          set({ error: error.message || 'Registration failed' });
          toast.error(error.message || 'Registration failed');
          throw error;
        } finally {
          set({ isLoading: false });
        }
      },

      logout: async () => {
        try {
          await apiService.auth.logout();
        } catch (error) {
          console.error('Logout error:', error);
        } finally {
          localStorage.removeItem('token');
          localStorage.removeItem('refreshToken');
          set({ user: null, isAuthenticated: false });
        }
      },

      refreshUser: async () => {
        try {
          const user = await apiService.auth.getCurrentUser();
          set({ user, isAuthenticated: true });
        } catch (error) {
          set({ user: null, isAuthenticated: false });
          throw error;
        }
      },

      initializeAuth: async () => {
        const token = localStorage.getItem('token');
        if (!token) return;

        try {
          const user = await apiService.auth.getCurrentUser();
          set({ user, isAuthenticated: true });
        } catch (error) {
          localStorage.removeItem('token');
          localStorage.removeItem('refreshToken');
          set({ user: null, isAuthenticated: false });
        }
      },

      updateUser: (updates) => {
        set((state) => {
          if (state.user) {
            state.user = { ...state.user, ...updates };
          }
        });
      },

      clearError: () => set({ error: null }),
    })),
    {
      name: 'auth-storage',
      partialize: (state) => ({
        user: state.user,
        isAuthenticated: state.isAuthenticated,
      }),
    }
  )
);

// Selectors
export const useAuth = () =>
  useAuthStore((state) => ({
    user: state.user,
    isAuthenticated: state.isAuthenticated,
    isLoading: state.isLoading,
    error: state.error,
  }));

export const useAuthActions = () =>
  useAuthStore((state) => ({
    login: state.login,
    register: state.register,
    logout: state.logout,
    refreshUser: state.refreshUser,
    initializeAuth: state.initializeAuth,
    updateUser: state.updateUser,
    clearError: state.clearError,
  }));
