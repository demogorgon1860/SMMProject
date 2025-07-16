-- SMM Panel Optimized Database Schema
-- PostgreSQL 15+ with partitioning, indexes, and performance optimizations

-- Enable required extensions
CREATE EXTENSION IF NOT EXISTS pg_trgm; -- For text search optimization
CREATE EXTENSION IF NOT EXISTS btree_gin; -- For composite indexes
CREATE EXTENSION IF NOT EXISTS pg_stat_statements; -- For query performance monitoring

-- Create enum types for better performance and consistency
CREATE TYPE user_role AS ENUM ('USER', 'OPERATOR', 'ADMIN');
CREATE TYPE order_status AS ENUM (
    'PENDING', 'IN_PROGRESS', 'PROCESSING', 'ACTIVE', 
    'PARTIAL', 'COMPLETED', 'CANCELLED', 'PAUSED', 
    'HOLDING', 'REFILL'
);
CREATE TYPE video_type AS ENUM ('STANDARD', 'SHORTS', 'LIVE');
CREATE TYPE payment_status AS ENUM ('PENDING', 'PROCESSING', 'COMPLETED', 'FAILED', 'EXPIRED');
CREATE TYPE transaction_type AS ENUM ('DEPOSIT', 'ORDER_PAYMENT', 'REFUND', 'REFILL');
CREATE TYPE youtube_account_status AS ENUM ('ACTIVE', 'BLOCKED', 'SUSPENDED', 'RATE_LIMITED');

-- Users table with optimized indexes
CREATE TABLE users (
    id BIGSERIAL PRIMARY KEY,
    username VARCHAR(255) UNIQUE NOT NULL,
    email VARCHAR(255) UNIQUE NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    api_key VARCHAR(255) UNIQUE,
    balance DECIMAL(10,2) DEFAULT 0.00 CHECK (balance >= 0),
    role user_role DEFAULT 'USER',
    timezone VARCHAR(50) DEFAULT 'UTC',
    is_active BOOLEAN DEFAULT TRUE,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

-- Indexes for users
CREATE INDEX idx_users_api_key_active ON users(api_key) WHERE is_active = TRUE;
CREATE INDEX idx_users_role ON users(role) WHERE role != 'USER';
CREATE INDEX idx_users_balance ON users(balance) WHERE balance > 0;
CREATE INDEX idx_users_created_at ON users(created_at DESC);

-- Services table (Perfect Panel compatible)
CREATE TABLE services (
    id BIGINT PRIMARY KEY, -- Fixed IDs for Perfect Panel compatibility
    name VARCHAR(255) NOT NULL,
    category VARCHAR(100) NOT NULL,
    min_order INTEGER NOT NULL CHECK (min_order > 0),
    max_order INTEGER NOT NULL CHECK (max_order >= min_order),
    price_per_1000 DECIMAL(8,4) NOT NULL CHECK (price_per_1000 > 0),
    description TEXT,
    active BOOLEAN DEFAULT TRUE,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

-- Insert Perfect Panel compatible services
INSERT INTO services (id, name, category, min_order, max_order, price_per_1000) VALUES
(1, 'YouTube Views (Standard)', 'YouTube', 100, 1000000, 1.0000),
(2, 'YouTube Views (Premium)', 'YouTube', 100, 1000000, 2.0000),
(3, 'YouTube Views (High Quality)', 'YouTube', 100, 1000000, 3.0000);

-- Orders table with partitioning by created_at for performance
CREATE TABLE orders (
    id BIGSERIAL,
    user_id BIGINT NOT NULL REFERENCES users(id),
    service_id BIGINT NOT NULL REFERENCES services(id),
    link VARCHAR(500) NOT NULL,
    quantity INTEGER NOT NULL CHECK (quantity > 0),
    charge DECIMAL(10,2) NOT NULL CHECK (charge > 0),
    start_count INTEGER DEFAULT 0,
    remains INTEGER,
    status order_status DEFAULT 'PENDING',
    youtube_video_id VARCHAR(100), -- Extracted video ID for faster lookups
    processing_priority INTEGER DEFAULT 0, -- For queue management
    error_message TEXT,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    PRIMARY KEY (id, created_at)
) PARTITION BY RANGE (created_at);

-- Create monthly partitions for orders (automated in production)
CREATE TABLE orders_2025_01 PARTITION OF orders 
    FOR VALUES FROM ('2025-01-01') TO ('2025-02-01');
CREATE TABLE orders_2025_02 PARTITION OF orders 
    FOR VALUES FROM ('2025-02-01') TO ('2025-03-01');

-- Indexes for orders
CREATE INDEX idx_orders_user_id ON orders(user_id, created_at DESC);
CREATE INDEX idx_orders_status ON orders(status) WHERE status NOT IN ('COMPLETED', 'CANCELLED');
CREATE INDEX idx_orders_youtube_video_id ON orders(youtube_video_id) WHERE youtube_video_id IS NOT NULL;
CREATE INDEX idx_orders_processing ON orders(status, processing_priority DESC) 
    WHERE status IN ('PENDING', 'IN_PROGRESS', 'PROCESSING');
CREATE INDEX idx_orders_active_monitoring ON orders(status, updated_at) 
    WHERE status IN ('ACTIVE', 'HOLDING');

-- Video processing table
CREATE TABLE video_processing (
    id BIGSERIAL PRIMARY KEY,
    order_id BIGINT NOT NULL,
    order_created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    original_url VARCHAR(500) NOT NULL,
    video_type video_type,
    clip_created BOOLEAN DEFAULT FALSE,
    clip_url VARCHAR(500),
    youtube_account_id BIGINT,
    processing_status VARCHAR(50) DEFAULT 'PENDING',
    processing_attempts INTEGER DEFAULT 0,
    error_message TEXT,
    metadata JSONB,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    FOREIGN KEY (order_id, order_created_at) REFERENCES orders(id, created_at)
);

-- YouTube accounts table
CREATE TABLE youtube_accounts (
    id BIGSERIAL PRIMARY KEY,
    username VARCHAR(255) NOT NULL,
    email VARCHAR(255) NOT NULL,
    status youtube_account_status DEFAULT 'ACTIVE',
    daily_clips_count INTEGER DEFAULT 0,
    last_clip_date DATE,
    daily_limit INTEGER DEFAULT 50,
    total_clips_created INTEGER DEFAULT 0,
    last_error TEXT,
    last_error_at TIMESTAMP WITH TIME ZONE,
    proxy_config JSONB,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

-- Traffic sources table
CREATE TABLE traffic_sources (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    source_id VARCHAR(100) UNIQUE NOT NULL,
    weight INTEGER DEFAULT 1 CHECK (weight > 0),
    daily_limit INTEGER,
    clicks_used_today INTEGER DEFAULT 0,
    last_reset_date DATE DEFAULT CURRENT_DATE,
    geo_targeting VARCHAR(255),
    active BOOLEAN DEFAULT TRUE,
    performance_score DECIMAL(5,2) DEFAULT 100.00,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

-- Binom campaigns table
CREATE TABLE binom_campaigns (
    id BIGSERIAL PRIMARY KEY,
    order_id BIGINT NOT NULL,
    order_created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    campaign_id VARCHAR(100) UNIQUE NOT NULL,
    offer_id VARCHAR(100),
    target_url VARCHAR(500) NOT NULL,
    traffic_source_id BIGINT REFERENCES traffic_sources(id),
    coefficient DECIMAL(4,2) NOT NULL,
    clicks_required INTEGER NOT NULL,
    clicks_delivered INTEGER DEFAULT 0,
    views_generated INTEGER DEFAULT 0,
    status VARCHAR(50) DEFAULT 'ACTIVE',
    cost_per_click DECIMAL(8,6),
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    FOREIGN KEY (order_id, order_created_at) REFERENCES orders(id, created_at)
);

-- Balance deposits table
CREATE TABLE balance_deposits (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id),
    order_id VARCHAR(255) UNIQUE NOT NULL,
    amount_usd DECIMAL(10,2) NOT NULL CHECK (amount_usd >= 5.00),
    currency VARCHAR(10) NOT NULL,
    crypto_amount DECIMAL(20,8),
    cryptomus_payment_id VARCHAR(255),
    payment_url VARCHAR(500),
    status payment_status DEFAULT 'PENDING',
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    confirmed_at TIMESTAMP WITH TIME ZONE,
    webhook_data JSONB,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

-- Balance transactions table
CREATE TABLE balance_transactions (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id),
    order_id BIGINT,
    order_created_at TIMESTAMP WITH TIME ZONE,
    deposit_id BIGINT REFERENCES balance_deposits(id),
    amount DECIMAL(10,2) NOT NULL,
    balance_before DECIMAL(10,2) NOT NULL,
    balance_after DECIMAL(10,2) NOT NULL,
    transaction_type transaction_type NOT NULL,
    description TEXT,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    FOREIGN KEY (order_id, order_created_at) REFERENCES orders(id, created_at)
);

-- View stats table
CREATE TABLE view_stats (
    id BIGSERIAL PRIMARY KEY,
    order_id BIGINT NOT NULL,
    order_created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    video_processing_id BIGINT REFERENCES video_processing(id),
    current_views INTEGER DEFAULT 0,
    target_views INTEGER NOT NULL,
    views_velocity DECIMAL(10,2),
    last_checked TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    check_interval INTEGER DEFAULT 1800,
    check_count INTEGER DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    FOREIGN KEY (order_id, order_created_at) REFERENCES orders(id, created_at)
);

-- Conversion coefficients table
CREATE TABLE conversion_coefficients (
    id BIGSERIAL PRIMARY KEY,
    service_id BIGINT REFERENCES services(id),
    with_clip DECIMAL(4,2) DEFAULT 3.0,
    without_clip DECIMAL(4,2) DEFAULT 4.0,
    updated_by BIGINT REFERENCES users(id),
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

-- Insert default coefficients
INSERT INTO conversion_coefficients (service_id, with_clip, without_clip) VALUES
(1, 3.0, 4.0),
(2, 3.0, 4.0),
(3, 3.0, 4.0);

-- Operator logs table
CREATE TABLE operator_logs (
    id BIGSERIAL PRIMARY KEY,
    operator_id BIGINT NOT NULL REFERENCES users(id),
    action VARCHAR(100) NOT NULL,
    target_type VARCHAR(50) NOT NULL,
    target_id BIGINT NOT NULL,
    details JSONB,
    ip_address INET,
    user_agent TEXT,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
) PARTITION BY RANGE (created_at);

-- Create monthly partitions for operator logs
CREATE TABLE operator_logs_2025_01 PARTITION OF operator_logs 
    FOR VALUES FROM ('2025-01-01') TO ('2025-02-01');

-- Create triggers for updated_at columns
CREATE OR REPLACE FUNCTION update_updated_at_column()
RETURNS TRIGGER AS $
BEGIN
    NEW.updated_at = NOW();
    RETURN NEW;
END;
$ language 'plpgsql';

-- Apply triggers to all tables with updated_at
CREATE TRIGGER update_users_updated_at BEFORE UPDATE ON users 
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();
CREATE TRIGGER update_orders_updated_at BEFORE UPDATE ON orders 
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();
CREATE TRIGGER update_video_processing_updated_at BEFORE UPDATE ON video_processing 
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();
CREATE TRIGGER update_youtube_accounts_updated_at BEFORE UPDATE ON youtube_accounts 
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();
CREATE TRIGGER update_traffic_sources_updated_at BEFORE UPDATE ON traffic_sources 
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();
CREATE TRIGGER update_binom_campaigns_updated_at BEFORE UPDATE ON binom_campaigns 
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

-- Function to reset daily limits
CREATE OR REPLACE FUNCTION reset_daily_limits() RETURNS void AS $
BEGIN
    -- Reset YouTube accounts daily clips
    UPDATE youtube_accounts 
    SET daily_clips_count = 0, last_clip_date = CURRENT_DATE 
    WHERE last_clip_date < CURRENT_DATE;
    
    -- Reset traffic sources daily clicks
    UPDATE traffic_sources 
    SET clicks_used_today = 0, last_reset_date = CURRENT_DATE 
    WHERE last_reset_date < CURRENT_DATE;
END;
$ LANGUAGE plpgsql;