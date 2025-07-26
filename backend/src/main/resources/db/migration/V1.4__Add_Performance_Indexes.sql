-- V1.4__Add_Performance_Indexes.sql
-- Add indexes for better performance on frequently queried columns

-- Orders table indexes
CREATE INDEX IF NOT EXISTS idx_orders_user_id ON orders(user_id);
CREATE INDEX IF NOT EXISTS idx_orders_status ON orders(status);
CREATE INDEX IF NOT EXISTS idx_orders_created_at ON orders(created_at);
CREATE INDEX IF NOT EXISTS idx_orders_user_status ON orders(user_id, status);
CREATE INDEX IF NOT EXISTS idx_orders_status_created_at ON orders(status, created_at);

-- Balance transactions indexes
CREATE INDEX IF NOT EXISTS idx_balance_transactions_user_id ON balance_transactions(user_id);
CREATE INDEX IF NOT EXISTS idx_balance_transactions_created_at ON balance_transactions(created_at);
CREATE INDEX IF NOT EXISTS idx_balance_transactions_user_created ON balance_transactions(user_id, created_at DESC);

-- Binom campaigns indexes
CREATE INDEX IF NOT EXISTS idx_binom_campaigns_order_id ON binom_campaigns(order_id);
CREATE INDEX IF NOT EXISTS idx_binom_campaigns_status ON binom_campaigns(status);
CREATE INDEX IF NOT EXISTS idx_binom_campaigns_campaign_id ON binom_campaigns(campaign_id);
CREATE INDEX IF NOT EXISTS idx_binom_campaigns_status_campaign_id ON binom_campaigns(status, campaign_id);

-- Video processing indexes
CREATE INDEX IF NOT EXISTS idx_video_processing_order_id ON video_processing(order_id);
CREATE INDEX IF NOT EXISTS idx_video_processing_status ON video_processing(status);
CREATE INDEX IF NOT EXISTS idx_video_processing_created_at ON video_processing(created_at);

-- Services indexes
CREATE INDEX IF NOT EXISTS idx_services_active ON services(active);
CREATE INDEX IF NOT EXISTS idx_services_category ON services(category);

-- YouTube accounts indexes
CREATE INDEX IF NOT EXISTS idx_youtube_accounts_status ON youtube_accounts(status);
CREATE INDEX IF NOT EXISTS idx_youtube_accounts_last_clip_date ON youtube_accounts(last_clip_date);
CREATE INDEX IF NOT EXISTS idx_youtube_accounts_daily_clips ON youtube_accounts(daily_clips_count);

-- Traffic sources indexes
CREATE INDEX IF NOT EXISTS idx_traffic_sources_active ON traffic_sources(active);
CREATE INDEX IF NOT EXISTS idx_traffic_sources_weight ON traffic_sources(weight);

-- Balance deposits indexes  
CREATE INDEX IF NOT EXISTS idx_balance_deposits_user_id ON balance_deposits(user_id);
CREATE INDEX IF NOT EXISTS idx_balance_deposits_status ON balance_deposits(status);
CREATE INDEX IF NOT EXISTS idx_balance_deposits_created_at ON balance_deposits(created_at);

-- Partial indexes for better performance on filtered queries
CREATE INDEX IF NOT EXISTS idx_orders_pending ON orders(created_at) WHERE status = 'PENDING';
CREATE INDEX IF NOT EXISTS idx_orders_processing ON orders(created_at) WHERE status = 'PROCESSING';
CREATE INDEX IF NOT EXISTS idx_users_active ON users(created_at) WHERE is_active = true;

-- Composite indexes for common query patterns
CREATE INDEX IF NOT EXISTS idx_orders_user_service ON orders(user_id, service_id);
CREATE INDEX IF NOT EXISTS idx_binom_campaigns_status_clicks ON binom_campaigns(status, clicks_delivered, clicks_required) 
    WHERE status = 'ACTIVE';

-- Add comments for documentation
COMMENT ON INDEX idx_orders_user_status IS 'Optimizes user order queries with status filtering';
COMMENT ON INDEX idx_orders_status_created_at IS 'Optimizes admin dashboard queries by status and date';
COMMENT ON INDEX idx_balance_transactions_user_created IS 'Optimizes user transaction history queries';
COMMENT ON INDEX idx_orders_pending IS 'Partial index for pending orders processing'; 