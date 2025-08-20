-- ==========================================
-- Database Schema Verification Script
-- Verifies PostgreSQL schema matches Liquibase
-- ==========================================

-- 1. VERIFY LIQUIBASE TRACKING TABLES
-- ==========================================
SELECT '=== LIQUIBASE TRACKING ===' as verification_step;

SELECT EXISTS (
    SELECT 1 FROM information_schema.tables 
    WHERE table_schema = 'public' 
    AND table_name = 'databasechangelog'
) as changelog_exists,
EXISTS (
    SELECT 1 FROM information_schema.tables 
    WHERE table_schema = 'public' 
    AND table_name = 'databasechangeloglock'
) as changeloglock_exists;

-- Show recent changelog entries
SELECT id, author, filename, dateexecuted, orderexecuted, exectype, md5sum
FROM databasechangelog
ORDER BY orderexecuted DESC
LIMIT 10;

-- Check for lock
SELECT * FROM databasechangeloglock;

-- 2. VERIFY ENUM TYPES
-- ==========================================
SELECT '=== ENUM TYPES ===' as verification_step;

SELECT typname, typtype 
FROM pg_type 
WHERE typtype = 'e' 
AND typnamespace = (SELECT oid FROM pg_namespace WHERE nspname = 'public')
ORDER BY typname;

-- 3. VERIFY CORE TABLES
-- ==========================================
SELECT '=== CORE TABLES ===' as verification_step;

WITH expected_tables AS (
    SELECT unnest(ARRAY[
        'users', 'services', 'orders', 'video_processing',
        'youtube_accounts', 'binom_campaigns', 'fixed_binom_campaigns',
        'balance_deposits', 'balance_transactions', 'view_stats',
        'conversion_coefficients', 'operator_logs', 'outbox_events'
    ]) as table_name
)
SELECT 
    e.table_name,
    CASE WHEN t.table_name IS NOT NULL THEN '✓ EXISTS' ELSE '✗ MISSING' END as status,
    t.table_type
FROM expected_tables e
LEFT JOIN information_schema.tables t 
    ON t.table_name = e.table_name 
    AND t.table_schema = 'public'
ORDER BY e.table_name;

-- 4. VERIFY CRITICAL INDEXES
-- ==========================================
SELECT '=== CRITICAL INDEXES ===' as verification_step;

SELECT 
    schemaname,
    tablename,
    indexname,
    indexdef
FROM pg_indexes
WHERE schemaname = 'public'
AND tablename IN ('users', 'orders', 'services')
ORDER BY tablename, indexname;

-- 5. VERIFY FOREIGN KEY CONSTRAINTS
-- ==========================================
SELECT '=== FOREIGN KEYS ===' as verification_step;

SELECT
    tc.table_name,
    kcu.column_name,
    ccu.table_name AS foreign_table_name,
    ccu.column_name AS foreign_column_name,
    tc.constraint_name
FROM information_schema.table_constraints AS tc
JOIN information_schema.key_column_usage AS kcu
    ON tc.constraint_name = kcu.constraint_name
    AND tc.table_schema = kcu.table_schema
JOIN information_schema.constraint_column_usage AS ccu
    ON ccu.constraint_name = tc.constraint_name
    AND ccu.table_schema = tc.table_schema
WHERE tc.constraint_type = 'FOREIGN KEY' 
AND tc.table_schema = 'public'
ORDER BY tc.table_name, kcu.column_name;

-- 6. VERIFY CHECK CONSTRAINTS
-- ==========================================
SELECT '=== CHECK CONSTRAINTS ===' as verification_step;

SELECT 
    tc.table_name,
    tc.constraint_name,
    cc.check_clause
FROM information_schema.table_constraints tc
JOIN information_schema.check_constraints cc
    ON tc.constraint_name = cc.constraint_name
    AND tc.table_schema = cc.constraint_schema
WHERE tc.constraint_type = 'CHECK'
AND tc.table_schema = 'public'
ORDER BY tc.table_name, tc.constraint_name;

-- 7. VERIFY SEQUENCES
-- ==========================================
SELECT '=== SEQUENCES ===' as verification_step;

SELECT 
    sequence_name,
    data_type,
    start_value,
    minimum_value,
    maximum_value,
    increment
FROM information_schema.sequences
WHERE sequence_schema = 'public'
ORDER BY sequence_name;

-- 8. CHECK FOR ORPHANED OBJECTS
-- ==========================================
SELECT '=== ORPHANED OBJECTS CHECK ===' as verification_step;

-- Tables not in expected list
SELECT 
    table_name as orphaned_table
FROM information_schema.tables
WHERE table_schema = 'public'
AND table_type = 'BASE TABLE'
AND table_name NOT IN (
    'users', 'services', 'orders', 'video_processing',
    'youtube_accounts', 'binom_campaigns', 'fixed_binom_campaigns',
    'balance_deposits', 'balance_transactions', 'view_stats',
    'conversion_coefficients', 'operator_logs', 'outbox_events',
    'databasechangelog', 'databasechangeloglock'
)
AND table_name NOT LIKE 'orders_%' -- Ignore order partitions
AND table_name NOT LIKE 'operator_logs_%'; -- Ignore operator log partitions

-- 9. VERIFY COLUMN DATA TYPES
-- ==========================================
SELECT '=== KEY COLUMN VERIFICATION ===' as verification_step;

-- Users table critical columns
SELECT column_name, data_type, is_nullable, column_default
FROM information_schema.columns
WHERE table_schema = 'public' 
AND table_name = 'users'
AND column_name IN ('id', 'username', 'email', 'password_hash', 'api_key', 'balance', 'role')
ORDER BY ordinal_position;

-- Orders table critical columns
SELECT column_name, data_type, is_nullable, column_default
FROM information_schema.columns
WHERE table_schema = 'public' 
AND table_name = 'orders'
AND column_name IN ('id', 'user_id', 'service_id', 'status', 'charge', 'created_at')
ORDER BY ordinal_position;

-- 10. DATABASE SIZE METRICS
-- ==========================================
SELECT '=== DATABASE METRICS ===' as verification_step;

SELECT 
    pg_database.datname,
    pg_size_pretty(pg_database_size(pg_database.datname)) AS size
FROM pg_database
WHERE datname = current_database();

SELECT 
    schemaname,
    tablename,
    pg_size_pretty(pg_total_relation_size(schemaname||'.'||tablename)) AS size
FROM pg_tables
WHERE schemaname = 'public'
ORDER BY pg_total_relation_size(schemaname||'.'||tablename) DESC
LIMIT 10;