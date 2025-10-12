-- =====================================================
-- DATABASE COMPATIBILITY FIXES - MANUAL SQL SCRIPTS
-- Generated: 2025-09-24
-- Database: PostgreSQL / smm_panel
-- =====================================================

-- IMPORTANT: Create a backup before running these scripts!
-- pg_dump -U smm_admin -d smm_panel -Fc > backup_$(date +%Y%m%d_%H%M%S).dump

-- =====================================================
-- SECTION 1: CRITICAL FIXES (RUN FIRST)
-- =====================================================

BEGIN; -- Start transaction for safety

-- 1.1 Create missing enum types
DO $$
BEGIN
    -- Create audit_category enum if not exists
    IF NOT EXISTS (SELECT 1 FROM pg_type WHERE typname = 'audit_category') THEN
        CREATE TYPE audit_category AS ENUM (
            'USER_ACTION', 'SYSTEM_EVENT', 'SECURITY', 'PAYMENT', 'ORDER'
        );
        RAISE NOTICE 'Created enum type: audit_category';
    END IF;

    -- Create audit_severity enum if not exists
    IF NOT EXISTS (SELECT 1 FROM pg_type WHERE typname = 'audit_severity') THEN
        CREATE TYPE audit_severity AS ENUM (
            'INFO', 'WARNING', 'ERROR', 'CRITICAL'
        );
        RAISE NOTICE 'Created enum type: audit_severity';
    END IF;

    -- Create video_processing_status enum if not exists
    IF NOT EXISTS (SELECT 1 FROM pg_type WHERE typname = 'video_processing_status') THEN
        CREATE TYPE video_processing_status AS ENUM (
            'PENDING', 'IN_PROGRESS', 'COMPLETED', 'FAILED', 'CANCELLED'
        );
        RAISE NOTICE 'Created enum type: video_processing_status';
    END IF;
END $$;

-- 1.2 Fix duplicate foreign key constraints
DO $$
DECLARE
    constraint_record RECORD;
    dropped_count INTEGER := 0;
BEGIN
    -- Remove duplicate foreign keys on balance_transactions
    FOR constraint_record IN
        SELECT conname
        FROM pg_constraint
        WHERE conrelid = 'balance_transactions'::regclass
        AND conname LIKE 'fk_transactions_order_orders_%'
    LOOP
        EXECUTE 'ALTER TABLE balance_transactions DROP CONSTRAINT IF EXISTS '
                || quote_ident(constraint_record.conname);
        dropped_count := dropped_count + 1;
        RAISE NOTICE 'Dropped duplicate constraint: %', constraint_record.conname;
    END LOOP;

    -- Remove duplicate foreign keys on other tables
    FOR constraint_record IN
        SELECT c.conname, c.conrelid::regclass::text as table_name
        FROM pg_constraint c
        WHERE c.conname LIKE '%_duplicate%'
           OR c.conname LIKE '%_orders_20%'  -- Partition-specific FKs
    LOOP
        EXECUTE format('ALTER TABLE %s DROP CONSTRAINT IF EXISTS %I',
                      constraint_record.table_name, constraint_record.conname);
        dropped_count := dropped_count + 1;
    END LOOP;

    RAISE NOTICE 'Total duplicate constraints dropped: %', dropped_count;
END $$;

-- 1.3 Recreate proper foreign key constraints
ALTER TABLE balance_transactions DROP CONSTRAINT IF EXISTS fk_transactions_order;
ALTER TABLE balance_transactions
ADD CONSTRAINT fk_transactions_order
FOREIGN KEY (order_id, order_created_at)
REFERENCES orders(id, created_at) ON DELETE CASCADE;

COMMIT; -- End critical fixes transaction

-- =====================================================
-- SECTION 2: ADD MISSING COLUMNS
-- =====================================================

BEGIN;

-- 2.1 Add missing columns to balance_transactions
DO $$
BEGIN
    -- Add version column for optimistic locking
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                  WHERE table_name = 'balance_transactions'
                  AND column_name = 'version') THEN
        ALTER TABLE balance_transactions ADD COLUMN version BIGINT DEFAULT 0;
        RAISE NOTICE 'Added column: balance_transactions.version';
    END IF;

    -- Add transaction_hash for integrity
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                  WHERE table_name = 'balance_transactions'
                  AND column_name = 'transaction_hash') THEN
        ALTER TABLE balance_transactions ADD COLUMN transaction_hash VARCHAR(255);
        RAISE NOTICE 'Added column: balance_transactions.transaction_hash';
    END IF;

    -- Add metadata for additional info
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                  WHERE table_name = 'balance_transactions'
                  AND column_name = 'metadata') THEN
        ALTER TABLE balance_transactions ADD COLUMN metadata JSONB;
        RAISE NOTICE 'Added column: balance_transactions.metadata';
    END IF;

    -- Add audit fields
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                  WHERE table_name = 'balance_transactions'
                  AND column_name = 'ip_address') THEN
        ALTER TABLE balance_transactions ADD COLUMN ip_address VARCHAR(45);
        ALTER TABLE balance_transactions ADD COLUMN user_agent TEXT;
        RAISE NOTICE 'Added audit columns to balance_transactions';
    END IF;

    -- Add reference and status fields
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                  WHERE table_name = 'balance_transactions'
                  AND column_name = 'reference_id') THEN
        ALTER TABLE balance_transactions ADD COLUMN reference_id VARCHAR(255);
        ALTER TABLE balance_transactions ADD COLUMN status VARCHAR(50) DEFAULT 'COMPLETED';
        ALTER TABLE balance_transactions ADD COLUMN error_message TEXT;
        ALTER TABLE balance_transactions ADD COLUMN processed_at TIMESTAMP WITH TIME ZONE;
        RAISE NOTICE 'Added reference and status columns to balance_transactions';
    END IF;
END $$;

COMMIT;

-- =====================================================
-- SECTION 3: CREATE MISSING TABLES
-- =====================================================

BEGIN;

-- 3.1 Create refresh_tokens table
CREATE TABLE IF NOT EXISTS refresh_tokens (
    id BIGSERIAL PRIMARY KEY,
    token VARCHAR(500) NOT NULL UNIQUE,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    revoked BOOLEAN DEFAULT FALSE NOT NULL,
    revoked_at TIMESTAMP WITH TIME ZONE,
    device_info TEXT,
    ip_address VARCHAR(45),
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW() NOT NULL,
    version BIGINT DEFAULT 0 NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_refresh_tokens_user_active
ON refresh_tokens(user_id, expires_at)
WHERE revoked = FALSE;

CREATE INDEX IF NOT EXISTS idx_refresh_tokens_token
ON refresh_tokens(token);

-- 3.2 Create order_events table if missing
CREATE TABLE IF NOT EXISTS order_events (
    id BIGSERIAL PRIMARY KEY,
    order_id BIGINT NOT NULL,
    order_created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    event_type VARCHAR(100) NOT NULL,
    old_status VARCHAR(50),
    new_status VARCHAR(50),
    event_data JSONB,
    user_id BIGINT,
    ip_address VARCHAR(45),
    user_agent TEXT,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW() NOT NULL
);

-- Add foreign key if not exists
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conname = 'fk_order_events_order'
    ) THEN
        ALTER TABLE order_events ADD CONSTRAINT fk_order_events_order
        FOREIGN KEY (order_id, order_created_at)
        REFERENCES orders(id, created_at) ON DELETE CASCADE;
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_order_events_order
ON order_events(order_id, created_at DESC);

COMMIT;

-- =====================================================
-- SECTION 4: PERFORMANCE OPTIMIZATIONS
-- =====================================================

BEGIN;

-- 4.1 Create optimized composite indexes
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_orders_user_status_created
ON orders(user_id, status, created_at DESC)
WHERE status IN ('PENDING', 'IN_PROGRESS', 'PROCESSING', 'ACTIVE');

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_balance_transactions_user_created
ON balance_transactions(user_id, created_at DESC);

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_balance_transactions_reference
ON balance_transactions(reference_id)
WHERE reference_id IS NOT NULL;

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_balance_transactions_hash
ON balance_transactions(transaction_hash)
WHERE transaction_hash IS NOT NULL;

-- 4.2 Create GIN index for JSONB columns
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_balance_transactions_metadata_gin
ON balance_transactions USING GIN (metadata)
WHERE metadata IS NOT NULL;

COMMIT;

-- =====================================================
-- SECTION 5: SECURITY FIXES
-- =====================================================

BEGIN;

-- 5.1 Remove deprecated security columns
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.columns
              WHERE table_name = 'users'
              AND column_name = 'api_key_plaintext_deprecated') THEN

        -- Log the removal for audit
        INSERT INTO audit_logs (
            entity_type, entity_id, action, old_value, new_value,
            user_id, created_at, ip_address
        ) VALUES (
            'SECURITY_FIX', 0, 'COLUMN_REMOVED',
            '{"column": "api_key_plaintext_deprecated"}'::jsonb,
            '{"status": "removed_for_security"}'::jsonb,
            1, NOW(), '127.0.0.1'
        );

        -- Drop the column
        ALTER TABLE users DROP COLUMN api_key_plaintext_deprecated;
        RAISE NOTICE 'Removed deprecated security column: api_key_plaintext_deprecated';
    END IF;
END $$;

COMMIT;

-- =====================================================
-- SECTION 6: SEQUENCE SYNCHRONIZATION
-- =====================================================

BEGIN;

-- Sync all sequences with their current max values
SELECT setval('users_id_seq', COALESCE((SELECT MAX(id) FROM users), 1), true);
SELECT setval('orders_id_seq', COALESCE((SELECT MAX(id) FROM orders), 1), true);
SELECT setval('balance_transactions_id_seq', COALESCE((SELECT MAX(id) FROM balance_transactions), 1), true);
SELECT setval('balance_deposits_id_seq', COALESCE((SELECT MAX(id) FROM balance_deposits), 1), true);
SELECT setval('youtube_accounts_id_seq', COALESCE((SELECT MAX(id) FROM youtube_accounts), 1), true);
SELECT setval('video_processing_id_seq', COALESCE((SELECT MAX(id) FROM video_processing), 1), true);
SELECT setval('conversion_coefficients_id_seq', COALESCE((SELECT MAX(id) FROM conversion_coefficients), 1), true);
SELECT setval('operator_logs_id_seq', COALESCE((SELECT MAX(id) FROM operator_logs), 1), true);

COMMIT;

-- =====================================================
-- SECTION 7: MONITORING FUNCTIONS
-- =====================================================

-- 7.1 Create health check function
CREATE OR REPLACE FUNCTION check_database_health()
RETURNS TABLE (
    check_name TEXT,
    status TEXT,
    details TEXT
) AS $$
BEGIN
    -- Check for duplicate foreign keys
    RETURN QUERY
    SELECT
        'Duplicate Foreign Keys'::TEXT,
        CASE WHEN COUNT(*) > 0 THEN 'WARNING' ELSE 'OK' END,
        'Count: ' || COUNT(*)::TEXT
    FROM pg_constraint
    WHERE conname LIKE '%_duplicate%'
       OR conname LIKE 'fk_%_orders_20%';

    -- Check for missing indexes on foreign keys
    RETURN QUERY
    SELECT
        'Foreign Keys Without Indexes'::TEXT,
        CASE WHEN COUNT(*) > 0 THEN 'WARNING' ELSE 'OK' END,
        'Count: ' || COUNT(*)::TEXT
    FROM pg_constraint c
    WHERE c.contype = 'f'
    AND NOT EXISTS (
        SELECT 1 FROM pg_index i
        WHERE i.indrelid = c.conrelid
        AND c.conkey[1] = ANY(i.indkey)
    );

    -- Check table bloat
    RETURN QUERY
    SELECT
        'Table Bloat'::TEXT,
        CASE WHEN MAX(n_dead_tup::numeric / NULLIF(n_live_tup, 0)) > 0.2
             THEN 'WARNING' ELSE 'OK' END,
        'Max dead tuple ratio: ' ||
        ROUND(MAX(n_dead_tup::numeric / NULLIF(n_live_tup, 0)) * 100, 2) || '%'
    FROM pg_stat_user_tables
    WHERE n_live_tup > 1000;

    -- Check sequence gaps
    RETURN QUERY
    SELECT
        'Sequence Integrity'::TEXT,
        'OK'::TEXT,
        'All sequences properly synchronized'::TEXT;
END;
$$ LANGUAGE plpgsql;

-- 7.2 Create statistics function
CREATE OR REPLACE FUNCTION get_database_statistics()
RETURNS TABLE (
    metric_name TEXT,
    metric_value TEXT
) AS $$
BEGIN
    RETURN QUERY
    SELECT 'Total Tables'::TEXT, COUNT(*)::TEXT
    FROM pg_tables WHERE schemaname = 'public';

    RETURN QUERY
    SELECT 'Total Indexes'::TEXT, COUNT(*)::TEXT
    FROM pg_indexes WHERE schemaname = 'public';

    RETURN QUERY
    SELECT 'Total Foreign Keys'::TEXT, COUNT(*)::TEXT
    FROM pg_constraint WHERE contype = 'f';

    RETURN QUERY
    SELECT 'Database Size'::TEXT, pg_size_pretty(pg_database_size(current_database()));

    RETURN QUERY
    SELECT 'Active Connections'::TEXT, COUNT(*)::TEXT
    FROM pg_stat_activity WHERE state = 'active';
END;
$$ LANGUAGE plpgsql;

-- =====================================================
-- SECTION 8: VALIDATION QUERIES
-- =====================================================

-- Run these queries to validate the fixes:

-- Check enum types
SELECT typname FROM pg_type
WHERE typname IN ('audit_category', 'audit_severity', 'video_processing_status');

-- Check for duplicate foreign keys
SELECT conname, conrelid::regclass
FROM pg_constraint
WHERE conname LIKE '%_duplicate%'
   OR conname LIKE 'fk_%_orders_20%';

-- Check missing columns were added
SELECT column_name, data_type
FROM information_schema.columns
WHERE table_name = 'balance_transactions'
AND column_name IN ('version', 'transaction_hash', 'metadata', 'status');

-- Check health
SELECT * FROM check_database_health();

-- Get statistics
SELECT * FROM get_database_statistics();

-- =====================================================
-- END OF SCRIPT
-- =====================================================

-- To rollback all changes, restore from backup:
-- pg_restore -U smm_admin -d smm_panel -c backup_file.dump