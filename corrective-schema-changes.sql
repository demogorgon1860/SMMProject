-- ============================================
-- Database Schema Corrective Changes
-- Generated from Architecture Comparison
-- ============================================

-- IMPORTANT: Review each section before executing
-- Some changes may require data migration

BEGIN TRANSACTION;

-- ============================================
-- SECTION 1: Remove Traffic Sources Dependencies
-- As per requirements, traffic sources are no longer needed
-- ============================================

-- Step 1.1: Remove foreign key constraints referencing traffic_sources
ALTER TABLE binom_campaigns 
    DROP CONSTRAINT IF EXISTS fk_binom_campaigns_traffic_source;

ALTER TABLE fixed_binom_campaigns 
    DROP CONSTRAINT IF EXISTS fk_fixed_campaigns_traffic_source;

-- Step 1.2: Drop the columns that reference traffic_sources
ALTER TABLE binom_campaigns 
    DROP COLUMN IF EXISTS traffic_source_id;

ALTER TABLE fixed_binom_campaigns 
    DROP COLUMN IF EXISTS traffic_source_id;

-- Step 1.3: Drop the traffic_sources table
DROP TABLE IF EXISTS traffic_sources CASCADE;

-- Step 1.4: Remove traffic_sources from any views
DROP VIEW IF EXISTS campaign_assignment_status;

-- Recreate the view without traffic_sources reference
CREATE OR REPLACE VIEW campaign_assignment_status AS
SELECT 
    fc.campaign_id,
    fc.campaign_name,
    fc.geo_targeting,
    fc.active,
    COUNT(bc.id) as orders_assigned,
    SUM(bc.clicks_required) as total_clicks_assigned,
    SUM(bc.clicks_delivered) as total_clicks_delivered
FROM fixed_binom_campaigns fc
LEFT JOIN binom_campaigns bc ON fc.campaign_id = bc.campaign_id
WHERE fc.active = true
GROUP BY fc.id, fc.campaign_id, fc.campaign_name, fc.geo_targeting, fc.active
ORDER BY fc.campaign_id;

-- ============================================
-- SECTION 2: Add Missing Cryptomus Accounts Table
-- Based on architecture diagram
-- ============================================

-- Only create if actually needed for Cryptomus integration
CREATE TABLE IF NOT EXISTS cryptomus_accounts (
    id BIGSERIAL PRIMARY KEY,
    username VARCHAR(255) NOT NULL,
    email VARCHAR(255) NOT NULL UNIQUE,
    merchant_id VARCHAR(255) NOT NULL,
    api_key_encrypted TEXT NOT NULL,
    payment_key_encrypted TEXT,
    wallet_address VARCHAR(255),
    daily_limit DECIMAL(10,2),
    total_processed DECIMAL(18,2) DEFAULT 0,
    status VARCHAR(50) DEFAULT 'ACTIVE',
    last_transaction_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

-- Add indexes for Cryptomus accounts
CREATE INDEX idx_cryptomus_accounts_status ON cryptomus_accounts(status);
CREATE INDEX idx_cryptomus_accounts_merchant_id ON cryptomus_accounts(merchant_id);

-- Add trigger for updated_at
CREATE TRIGGER update_cryptomus_accounts_updated_at 
    BEFORE UPDATE ON cryptomus_accounts 
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

-- ============================================
-- SECTION 3: Fix Column Inconsistencies
-- ============================================

-- Ensure geo_targeting is added to fixed_binom_campaigns if missing
ALTER TABLE fixed_binom_campaigns 
    ADD COLUMN IF NOT EXISTS geo_targeting VARCHAR(255);

-- Ensure all tables have version columns for optimistic locking
ALTER TABLE services 
    ADD COLUMN IF NOT EXISTS version BIGINT DEFAULT 0 NOT NULL;

ALTER TABLE video_processing 
    ADD COLUMN IF NOT EXISTS version BIGINT DEFAULT 0 NOT NULL;

ALTER TABLE youtube_accounts 
    ADD COLUMN IF NOT EXISTS version BIGINT DEFAULT 0 NOT NULL;

ALTER TABLE binom_campaigns 
    ADD COLUMN IF NOT EXISTS version BIGINT DEFAULT 0 NOT NULL;

ALTER TABLE fixed_binom_campaigns 
    ADD COLUMN IF NOT EXISTS version BIGINT DEFAULT 0 NOT NULL;

ALTER TABLE balance_deposits 
    ADD COLUMN IF NOT EXISTS version BIGINT DEFAULT 0 NOT NULL;

ALTER TABLE view_stats 
    ADD COLUMN IF NOT EXISTS version BIGINT DEFAULT 0 NOT NULL;

ALTER TABLE conversion_coefficients 
    ADD COLUMN IF NOT EXISTS version BIGINT DEFAULT 0 NOT NULL;

-- ============================================
-- SECTION 4: Clean Up Deprecated Columns
-- ============================================

-- Remove plain text API key column if it exists (security improvement)
ALTER TABLE users 
    DROP COLUMN IF EXISTS api_key CASCADE;

-- Remove the deprecated column that was renamed
ALTER TABLE users 
    DROP COLUMN IF EXISTS api_key_plaintext_deprecated CASCADE;

-- ============================================
-- SECTION 5: Add Missing Indexes for Performance
-- ============================================

-- Composite index for order queries
CREATE INDEX IF NOT EXISTS idx_orders_user_service_status 
    ON orders(user_id, service_id, status);

-- Index for Binom campaign lookups
CREATE INDEX IF NOT EXISTS idx_binom_campaigns_campaign_id_status 
    ON binom_campaigns(campaign_id, status);

-- Index for balance transaction auditing
CREATE INDEX IF NOT EXISTS idx_balance_transactions_user_type_created 
    ON balance_transactions(user_id, transaction_type, created_at DESC);

-- ============================================
-- SECTION 6: Add Missing Check Constraints
-- ============================================

-- Ensure positive amounts
ALTER TABLE balance_deposits 
    ADD CONSTRAINT chk_balance_deposits_positive_amount 
    CHECK (amount_usd > 0);

ALTER TABLE balance_transactions 
    ADD CONSTRAINT chk_balance_transactions_valid_balance 
    CHECK (balance_after >= 0);

-- Ensure valid coefficients
ALTER TABLE conversion_coefficients 
    ADD CONSTRAINT chk_conversion_coefficients_valid_values 
    CHECK (with_clip > 0 AND without_clip > 0);

-- ============================================
-- SECTION 7: Create Missing Partitions
-- ============================================

-- Ensure current and future month partitions exist for orders
DO $$
DECLARE
    start_date date := '2025-01-01';
    end_date date := '2025-12-01';
    partition_date date;
    partition_name text;
BEGIN
    partition_date := start_date;
    WHILE partition_date < end_date LOOP
        partition_name := 'orders_' || to_char(partition_date, 'YYYY_MM');
        
        IF NOT EXISTS (
            SELECT 1 FROM pg_tables 
            WHERE tablename = lower(partition_name)
        ) THEN
            EXECUTE format('CREATE TABLE IF NOT EXISTS %I PARTITION OF orders FOR VALUES FROM (%L) TO (%L)',
                partition_name,
                partition_date,
                partition_date + interval '1 month'
            );
        END IF;
        
        partition_date := partition_date + interval '1 month';
    END LOOP;
END $$;

-- Same for operator_logs
DO $$
DECLARE
    start_date date := '2025-01-01';
    end_date date := '2025-12-01';
    partition_date date;
    partition_name text;
BEGIN
    partition_date := start_date;
    WHILE partition_date < end_date LOOP
        partition_name := 'operator_logs_' || to_char(partition_date, 'YYYY_MM');
        
        IF NOT EXISTS (
            SELECT 1 FROM pg_tables 
            WHERE tablename = lower(partition_name)
        ) THEN
            EXECUTE format('CREATE TABLE IF NOT EXISTS %I PARTITION OF operator_logs FOR VALUES FROM (%L) TO (%L)',
                partition_name,
                partition_date,
                partition_date + interval '1 month'
            );
        END IF;
        
        partition_date := partition_date + interval '1 month';
    END LOOP;
END $$;

-- ============================================
-- SECTION 8: Drop Orphaned/Unused Tables
-- ============================================

-- Drop any tables that don't belong to the architecture
-- (Verify these are truly orphaned before running)
-- Example:
-- DROP TABLE IF EXISTS legacy_table_name CASCADE;

-- ============================================
-- SECTION 9: Validate Final Schema
-- ============================================

-- Create a validation view to check schema integrity
CREATE OR REPLACE VIEW schema_validation AS
SELECT 
    'Users Table' as component,
    EXISTS(SELECT 1 FROM information_schema.tables WHERE table_name = 'users') as exists,
    (SELECT COUNT(*) FROM information_schema.columns WHERE table_name = 'users') as column_count
UNION ALL
SELECT 
    'Orders Table (Partitioned)' as component,
    EXISTS(SELECT 1 FROM information_schema.tables WHERE table_name = 'orders') as exists,
    (SELECT COUNT(*) FROM information_schema.columns WHERE table_name = 'orders') as column_count
UNION ALL
SELECT 
    'Services Table' as component,
    EXISTS(SELECT 1 FROM information_schema.tables WHERE table_name = 'services') as exists,
    (SELECT COUNT(*) FROM information_schema.columns WHERE table_name = 'services') as column_count
UNION ALL
SELECT 
    'Binom Campaigns Table' as component,
    EXISTS(SELECT 1 FROM information_schema.tables WHERE table_name = 'binom_campaigns') as exists,
    (SELECT COUNT(*) FROM information_schema.columns WHERE table_name = 'binom_campaigns') as column_count
UNION ALL
SELECT 
    'Balance System Tables' as component,
    EXISTS(SELECT 1 FROM information_schema.tables WHERE table_name = 'balance_deposits' 
           AND EXISTS(SELECT 1 FROM information_schema.tables WHERE table_name = 'balance_transactions')) as exists,
    (SELECT COUNT(*) FROM information_schema.columns WHERE table_name IN ('balance_deposits', 'balance_transactions')) as column_count
UNION ALL
SELECT 
    'Traffic Sources Removed' as component,
    NOT EXISTS(SELECT 1 FROM information_schema.tables WHERE table_name = 'traffic_sources') as exists,
    0 as column_count;

-- ============================================
-- SECTION 10: Update Statistics
-- ============================================

-- Update table statistics for query planner
ANALYZE users;
ANALYZE orders;
ANALYZE services;
ANALYZE binom_campaigns;
ANALYZE fixed_binom_campaigns;
ANALYZE balance_deposits;
ANALYZE balance_transactions;
ANALYZE video_processing;
ANALYZE youtube_accounts;
ANALYZE view_stats;
ANALYZE conversion_coefficients;
ANALYZE operator_logs;

-- ============================================
-- VALIDATION QUERIES
-- ============================================

-- Check the validation view
SELECT * FROM schema_validation;

-- Verify no orphaned foreign keys
SELECT 
    tc.table_name,
    kcu.column_name,
    ccu.table_name AS foreign_table_name,
    tc.constraint_name
FROM information_schema.table_constraints AS tc
JOIN information_schema.key_column_usage AS kcu
    ON tc.constraint_name = kcu.constraint_name
JOIN information_schema.constraint_column_usage AS ccu
    ON ccu.constraint_name = tc.constraint_name
WHERE tc.constraint_type = 'FOREIGN KEY' 
AND ccu.table_name = 'traffic_sources';

-- If all validations pass:
COMMIT;

-- If issues found:
-- ROLLBACK;

-- ============================================
-- POST-MIGRATION NOTES
-- ============================================
/*
After running this script:

1. Update application code to remove references to traffic_sources
2. Remove the following Java entities if they exist:
   - TrafficSource.java
   - Any repository classes for traffic_sources
   
3. Update Liquibase changesets to reflect these changes

4. Test the application thoroughly, especially:
   - Binom campaign creation and management
   - Order processing flows
   - Balance operations
   
5. Update API documentation to reflect removed endpoints

6. Consider creating a backup before running in production:
   pg_dump -U smm_admin smm_panel > backup_before_schema_cleanup.sql
*/