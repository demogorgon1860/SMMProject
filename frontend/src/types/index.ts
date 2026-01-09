export interface User {
  id: number;
  username: string;
  email: string;
  role: 'USER' | 'ADMIN';
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

export interface Order {
  id: number;
  orderId: string;
  userId: number;
  service?: Service;
  serviceName?: string;
  link: string;
  quantity: number;
  charge: number;
  startCount?: number;
  remains?: number;
  status: OrderStatus;
  youtubeVideoId?: string;
  targetViews?: number;
  createdAt: string;
  updatedAt: string;
}

export type OrderStatus = 
  | 'PENDING'
  | 'IN_PROGRESS'
  | 'COMPLETED'
  | 'CANCELED'
  | 'PARTIAL'
  | 'FAILED';

export interface Service {
  id: number;
  name: string;
  category: string;
  rate: number;
  min: number;
  max: number;
  description?: string;
  pricePer1000?: number;
  minOrder?: number;
  maxOrder?: number;
  isActive: boolean;
}

export interface BinomOffer {
  id: number;
  name: string;
  url: string;
  payout: number;
  status: string;
}

export interface YouTubeVideo {
  videoId: string;
  title: string;
  viewCount: number;
  likeCount: number;
  commentCount: number;
  duration: string;
  publishedAt: string;
}

export interface SeleniumJob {
  jobId: string;
  status: 'PENDING' | 'PROCESSING' | 'COMPLETED' | 'FAILED';
  videoUrl: string;
  clipUrl?: string;
  progress: number;
  error?: string;
  createdAt: string;
  completedAt?: string;
}

export interface DashboardStats {
  totalOrders: number;
  totalRevenue: number;
  activeUsers: number;
  pendingOrders: number;
  completedOrders: number;
  failedOrders: number;
}