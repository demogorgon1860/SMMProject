import { create } from 'zustand';
import { authAPI } from '../services/api';
import { User, AuthResponse } from '../types';

interface AuthState {
  user: User | null;
  token: string | null;
  isAuthenticated: boolean;
  isLoading: boolean;
  error: string | null;

  login: (username: string, password: string) => Promise<void>;
  register: (username: string, email: string, password: string, website?: string) => Promise<void>;
  logout: () => void;
  checkAuth: () => Promise<void>;
  clearError: () => void;
  updateBalance: (balance: number) => void;
}

// Safely read a JSON-encoded value from localStorage. Older builds wrote
// `undefined` (the literal string) into the `user` slot, which would crash
// the entire app on next page load via `JSON.parse("undefined")`. Anything
// that fails to parse is treated as missing and the bad slot is cleared so
// we don't trip on it next time.
function readJSON<T>(key: string): T | null {
  try {
    const raw = localStorage.getItem(key);
    if (raw == null || raw === 'undefined' || raw === '') return null;
    return JSON.parse(raw) as T;
  } catch {
    try {
      localStorage.removeItem(key);
    } catch {
      /* noop */
    }
    return null;
  }
}

function readString(key: string): string | null {
  try {
    const raw = localStorage.getItem(key);
    if (!raw || raw === 'undefined' || raw === 'null') return null;
    return raw;
  } catch {
    return null;
  }
}

// Defensive setters — refuse to persist `undefined` / nullish so we never
// write the literal string "undefined" into a slot that's later read as JSON.
function writeString(key: string, value: string | null | undefined): void {
  try {
    if (value == null || value === '') localStorage.removeItem(key);
    else localStorage.setItem(key, value);
  } catch {
    /* quota or privacy mode */
  }
}

function writeJSON(key: string, value: unknown): void {
  try {
    if (value == null) localStorage.removeItem(key);
    else localStorage.setItem(key, JSON.stringify(value));
  } catch {
    /* noop */
  }
}

// One-time migration: older builds wrote the refresh token to localStorage.
// We've moved to HttpOnly cookies — purge the stale slot so an attacker can't
// lift it later via XSS.
try {
  localStorage.removeItem('refreshToken');
} catch {
  /* noop */
}

const initialUser = readJSON<User>('user');
const initialToken = readString('token');

export const useAuthStore = create<AuthState>((set) => ({
  user: initialUser,
  token: initialToken,
  isAuthenticated: !!initialToken,
  isLoading: false,
  error: null,

  login: async (username: string, password: string) => {
    set({ isLoading: true, error: null });
    try {
      const response: AuthResponse = await authAPI.login(username, password);

      // Refresh token lives ONLY in the HttpOnly cookie set by the server.
      // We never persist it client-side.
      writeString('token', response.token);
      writeJSON('user', response.user);

      set({
        user: response.user ?? null,
        token: response.token ?? null,
        isAuthenticated: !!response.token,
        isLoading: false,
      });
    } catch (error: any) {
      set({
        error: error.response?.data?.message || 'Login failed',
        isLoading: false,
      });
      throw error;
    }
  },

  register: async (username: string, email: string, password: string, website?: string) => {
    set({ isLoading: true, error: null });
    try {
      const response: AuthResponse = await authAPI.register(username, email, password, website);

      writeString('token', response.token);
      writeJSON('user', response.user);

      set({
        user: response.user ?? null,
        token: response.token ?? null,
        isAuthenticated: !!response.token,
        isLoading: false,
      });
    } catch (error: any) {
      set({
        error: error.response?.data?.message || 'Registration failed',
        isLoading: false,
      });
      throw error;
    }
  },

  logout: () => {
    writeString('token', null);
    writeJSON('user', null);

    set({
      user: null,
      token: null,
      isAuthenticated: false,
    });
  },

  checkAuth: async () => {
    const token = readString('token');
    if (!token) {
      set({ isAuthenticated: false, user: null });
      return;
    }

    try {
      const user = await authAPI.getCurrentUser();
      writeJSON('user', user);
      set({ user: user ?? null, isAuthenticated: !!user });
    } catch (error) {
      writeString('token', null);
      writeJSON('user', null);
      set({ isAuthenticated: false, user: null });
    }
  },

  clearError: () => set({ error: null }),

  updateBalance: (balance: number) => {
    set((state) => {
      const updatedUser = state.user ? { ...state.user, balance } : null;
      writeJSON('user', updatedUser);
      return { user: updatedUser };
    });
  },
}));