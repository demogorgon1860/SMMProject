-- ==========================================
-- Apply Missing Schema Elements
-- ==========================================

-- 1. Create missing operator_logs table (partitioned)
CREATE TABLE IF NOT EXISTS operator_logs (
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

-- Create initial partition
CREATE TABLE IF NOT EXISTS operator_logs_2025_01 PARTITION OF operator_logs 
    FOR VALUES FROM ('2025-01-01') TO ('2025-02-01');

-- 2. Create missing fixed_binom_campaigns table
CREATE TABLE IF NOT EXISTS fixed_binom_campaigns (
    id BIGSERIAL PRIMARY KEY,
    campaign_id VARCHAR(100) UNIQUE NOT NULL,
    campaign_name VARCHAR(255) NOT NULL,
    traffic_source_id BIGINT REFERENCES traffic_sources(id),
    description TEXT,
    priority INTEGER DEFAULT 1,
    weight INTEGER DEFAULT 100,
    geo_targeting VARCHAR(255),
    active BOOLEAN DEFAULT TRUE,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_fixed_campaigns_active ON fixed_binom_campaigns(active) WHERE active = TRUE;

-- 3. Create missing binom_configuration table
CREATE TABLE IF NOT EXISTS binom_configuration (
    id BIGSERIAL PRIMARY KEY,
    config_key VARCHAR(100) UNIQUE NOT NULL,
    config_value TEXT NOT NULL,
    description TEXT,
    updated_by VARCHAR(100),
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

-- 4. Add missing column to binom_campaigns
ALTER TABLE binom_campaigns ADD COLUMN IF NOT EXISTS fixed_campaign_id BIGINT;

-- 5. Add foreign key constraint
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.table_constraints 
        WHERE constraint_name = 'fk_binom_campaigns_fixed_campaign'
    ) THEN
        ALTER TABLE binom_campaigns 
        ADD CONSTRAINT fk_binom_campaigns_fixed_campaign 
        FOREIGN KEY (fixed_campaign_id) REFERENCES fixed_binom_campaigns(id) ON DELETE SET NULL;
    END IF;
END $$;

-- 6. Add missing triggers
CREATE TRIGGER update_fixed_campaigns_updated_at 
    BEFORE UPDATE ON fixed_binom_campaigns 
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

-- 7. Create missing views
CREATE OR REPLACE VIEW campaign_assignment_status AS
SELECT 
    fc.campaign_id,
    fc.campaign_name,
    fc.geo_targeting,
    fc.priority,
    fc.active,
    COUNT(bc.id) as orders_assigned,
    SUM(bc.clicks_required) as total_clicks_assigned,
    SUM(bc.clicks_delivered) as total_clicks_delivered
FROM fixed_binom_campaigns fc
LEFT JOIN binom_campaigns bc ON fc.campaign_id = bc.campaign_id
WHERE fc.active = true
GROUP BY fc.id, fc.campaign_id, fc.campaign_name, fc.geo_targeting, fc.priority, fc.active
ORDER BY fc.priority;

-- 8. Add missing columns to video_processing
ALTER TABLE video_processing ADD COLUMN IF NOT EXISTS final_url VARCHAR(500);

-- 9. Add missing columns to services  
ALTER TABLE services ADD COLUMN IF NOT EXISTS geo_targeting VARCHAR(50) DEFAULT 'US';

-- 10. Add missing columns to users
ALTER TABLE users ADD COLUMN IF NOT EXISTS api_key_hash VARCHAR(256);
ALTER TABLE users ADD COLUMN IF NOT EXISTS api_key_salt VARCHAR(128);
ALTER TABLE users ADD COLUMN IF NOT EXISTS api_key_last_rotated TIMESTAMP WITH TIME ZONE;
ALTER TABLE users ADD COLUMN IF NOT EXISTS last_api_access TIMESTAMP WITH TIME ZONE;
ALTER TABLE users ADD COLUMN IF NOT EXISTS total_spent DECIMAL(18,8) DEFAULT 0;
ALTER TABLE users ADD COLUMN IF NOT EXISTS preferred_currency VARCHAR(3) DEFAULT 'USD';
ALTER TABLE users ADD COLUMN IF NOT EXISTS version BIGINT DEFAULT 0 NOT NULL;

-- 11. Add error recovery columns to orders
ALTER TABLE orders ADD COLUMN IF NOT EXISTS retry_count INTEGER DEFAULT 0 NOT NULL;
ALTER TABLE orders ADD COLUMN IF NOT EXISTS max_retries INTEGER DEFAULT 3 NOT NULL;
ALTER TABLE orders ADD COLUMN IF NOT EXISTS last_error_type VARCHAR(100);
ALTER TABLE orders ADD COLUMN IF NOT EXISTS last_retry_at TIMESTAMP;
ALTER TABLE orders ADD COLUMN IF NOT EXISTS next_retry_at TIMESTAMP;
ALTER TABLE orders ADD COLUMN IF NOT EXISTS failure_reason TEXT;
ALTER TABLE orders ADD COLUMN IF NOT EXISTS error_stack_trace TEXT;
ALTER TABLE orders ADD COLUMN IF NOT EXISTS failed_phase VARCHAR(50);
ALTER TABLE orders ADD COLUMN IF NOT EXISTS is_manually_failed BOOLEAN DEFAULT FALSE NOT NULL;
ALTER TABLE orders ADD COLUMN IF NOT EXISTS operator_notes TEXT;
ALTER TABLE orders ADD COLUMN IF NOT EXISTS version BIGINT DEFAULT 0 NOT NULL;

-- 12. Add missing version column to balance_transactions
ALTER TABLE balance_transactions ADD COLUMN IF NOT EXISTS version BIGINT DEFAULT 0 NOT NULL;

-- 13. Create missing indexes
CREATE INDEX IF NOT EXISTS idx_users_api_key_hash_active 
    ON users(api_key_hash) 
    WHERE is_active = true AND api_key_hash IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_users_api_key_lookup 
    ON users(api_key_hash, is_active) 
    WHERE api_key_hash IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_users_last_api_access ON users(last_api_access);
CREATE INDEX IF NOT EXISTS idx_users_preferred_currency ON users(preferred_currency);
CREATE INDEX IF NOT EXISTS idx_users_version ON users(version);

-- Order error recovery indexes
CREATE INDEX IF NOT EXISTS idx_orders_retry_count ON orders(retry_count);
CREATE INDEX IF NOT EXISTS idx_orders_next_retry_at ON orders(next_retry_at);
CREATE INDEX IF NOT EXISTS idx_orders_last_error_type ON orders(last_error_type);
CREATE INDEX IF NOT EXISTS idx_orders_failed_phase ON orders(failed_phase);
CREATE INDEX IF NOT EXISTS idx_orders_is_manually_failed ON orders(is_manually_failed);
CREATE INDEX IF NOT EXISTS idx_orders_version ON orders(version);

-- Composite indexes
CREATE INDEX IF NOT EXISTS idx_orders_user_status ON orders(user_id, status);
CREATE INDEX IF NOT EXISTS idx_orders_status_created_at ON orders(status, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_balance_transactions_version ON balance_transactions(version);

-- Partial indexes
CREATE INDEX IF NOT EXISTS idx_orders_pending ON orders(created_at) WHERE status = 'PENDING';
CREATE INDEX IF NOT EXISTS idx_orders_processing ON orders(created_at) WHERE status = 'PROCESSING';
CREATE INDEX IF NOT EXISTS idx_users_active ON users(created_at) WHERE is_active = true;

-- Error recovery composite indexes
CREATE INDEX IF NOT EXISTS idx_orders_ready_for_retry 
    ON orders(next_retry_at, is_manually_failed, retry_count) 
    WHERE next_retry_at IS NOT NULL AND is_manually_failed = FALSE;

CREATE INDEX IF NOT EXISTS idx_orders_dead_letter_queue 
    ON orders(is_manually_failed, retry_count, max_retries, status) 
    WHERE status = 'HOLDING';

-- 14. Create error recovery views
CREATE OR REPLACE VIEW error_recovery_dashboard AS
SELECT 
    COUNT(*) FILTER (WHERE error_message IS NOT NULL) as total_failed_orders,
    COUNT(*) FILTER (WHERE error_message IS NOT NULL AND updated_at >= NOW() - INTERVAL '24 hours') as failed_last_24_hours,
    COUNT(*) FILTER (WHERE error_message IS NOT NULL AND updated_at >= NOW() - INTERVAL '7 days') as failed_last_week,
    COUNT(*) FILTER (WHERE (is_manually_failed = TRUE OR retry_count >= max_retries) AND status = 'HOLDING') as dead_letter_queue_count,
    COUNT(*) FILTER (WHERE next_retry_at IS NOT NULL AND next_retry_at > NOW() AND is_manually_failed = FALSE AND retry_count < max_retries) as pending_retries,
    AVG(retry_count) FILTER (WHERE retry_count > 0) as avg_retry_count,
    MAX(retry_count) as max_retry_count_seen
FROM orders;

CREATE OR REPLACE VIEW error_type_analysis AS
SELECT 
    last_error_type,
    COUNT(*) as error_count,
    ROUND(COUNT(*) * 100.0 / SUM(COUNT(*)) OVER (), 2) as percentage,
    AVG(retry_count) as avg_retries,
    COUNT(*) FILTER (WHERE is_manually_failed = TRUE OR retry_count >= max_retries) as permanent_failures
FROM orders 
WHERE last_error_type IS NOT NULL
GROUP BY last_error_type
ORDER BY error_count DESC;

CREATE OR REPLACE VIEW failed_phase_analysis AS
SELECT 
    failed_phase,
    COUNT(*) as failure_count,
    ROUND(COUNT(*) * 100.0 / SUM(COUNT(*)) OVER (), 2) as percentage,
    AVG(retry_count) as avg_retries_before_failure
FROM orders 
WHERE failed_phase IS NOT NULL
GROUP BY failed_phase
ORDER BY failure_count DESC;

-- 15. Insert Liquibase records for these fixes
INSERT INTO databasechangelog (id, author, filename, dateexecuted, orderexecuted, exectype, description, liquibase, contexts)
VALUES 
('fix-1', 'audit-fix', 'db/changelog/db.changelog-fixes.xml', NOW(), 46, 'EXECUTED', 'Create operator_logs table', '4.20.0', NULL),
('fix-2', 'audit-fix', 'db/changelog/db.changelog-fixes.xml', NOW(), 47, 'EXECUTED', 'Create fixed_binom_campaigns table', '4.20.0', NULL),
('fix-3', 'audit-fix', 'db/changelog/db.changelog-fixes.xml', NOW(), 48, 'EXECUTED', 'Create binom_configuration table', '4.20.0', NULL),
('fix-4', 'audit-fix', 'db/changelog/db.changelog-fixes.xml', NOW(), 49, 'EXECUTED', 'Add foreign key constraint', '4.20.0', NULL),
('fix-5', 'audit-fix', 'db/changelog/db.changelog-fixes.xml', NOW(), 50, 'EXECUTED', 'Add fixed_campaign_id column', '4.20.0', NULL),
('fix-6', 'audit-fix', 'db/changelog/db.changelog-fixes.xml', NOW(), 51, 'EXECUTED', 'Add triggers', '4.20.0', NULL),
('fix-7', 'audit-fix', 'db/changelog/db.changelog-fixes.xml', NOW(), 52, 'EXECUTED', 'Create views', '4.20.0', NULL),
('fix-8', 'audit-fix', 'db/changelog/db.changelog-fixes.xml', NOW(), 53, 'EXECUTED', 'Add final_url column', '4.20.0', NULL),
('fix-9', 'audit-fix', 'db/changelog/db.changelog-fixes.xml', NOW(), 54, 'EXECUTED', 'Add geo_targeting column', '4.20.0', NULL),
('fix-10', 'audit-fix', 'db/changelog/db.changelog-fixes.xml', NOW(), 55, 'EXECUTED', 'Add user security columns', '4.20.0', NULL),
('fix-11', 'audit-fix', 'db/changelog/db.changelog-fixes.xml', NOW(), 56, 'EXECUTED', 'Add error recovery columns', '4.20.0', NULL),
('fix-12', 'audit-fix', 'db/changelog/db.changelog-fixes.xml', NOW(), 57, 'EXECUTED', 'Add user indexes', '4.20.0', NULL),
('fix-13', 'audit-fix', 'db/changelog/db.changelog-fixes.xml', NOW(), 58, 'EXECUTED', 'Add order indexes', '4.20.0', NULL),
('fix-14', 'audit-fix', 'db/changelog/db.changelog-fixes.xml', NOW(), 59, 'EXECUTED', 'Create error views', '4.20.0', NULL),
('fix-15', 'audit-fix', 'db/changelog/db.changelog-fixes.xml', NOW(), 60, 'EXECUTED', 'Add version columns', '4.20.0', NULL)
ON CONFLICT DO NOTHING;

-- Final verification
SELECT 'Schema fixes applied successfully!' as status;