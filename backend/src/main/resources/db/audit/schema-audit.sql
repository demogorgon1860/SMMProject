-- ==========================================
-- Liquibase Schema Audit Script
-- ==========================================
-- This script audits the actual PostgreSQL schema against expected structure

-- 1. CHECK ALL TABLES
-- ==========================================
SELECT '=== TABLES AUDIT ===' as audit_section;

SELECT 
    table_name,
    CASE 
        WHEN table_name IN (
            'users', 'services', 'orders', 'video_processing', 
            'youtube_accounts', 'traffic_sources', 'fixed_binom_campaigns',
            'binom_campaigns', 'balance_deposits', 'balance_transactions',
            'view_stats', 'conversion_coefficients', 'operator_logs',
            'binom_configuration', 'orders_2025_01', 'orders_2025_02',
            'operator_logs_2025_01', 'databasechangelog', 'databasechangeloglock'
        ) THEN '✓ Expected'
        ELSE '✗ UNEXPECTED'
    END as status
FROM information_schema.tables
WHERE table_schema = 'public'
ORDER BY table_name;

-- 2. CHECK ALL COLUMNS WITH DATA TYPES
-- ==========================================
SELECT '=== COLUMNS AUDIT ===' as audit_section;

-- Users table
SELECT 'users' as table_name, column_name, data_type, is_nullable, column_default
FROM information_schema.columns
WHERE table_schema = 'public' AND table_name = 'users'
ORDER BY ordinal_position;

-- Orders table (partitioned)
SELECT 'orders' as table_name, column_name, data_type, is_nullable, column_default
FROM information_schema.columns
WHERE table_schema = 'public' AND table_name = 'orders'
ORDER BY ordinal_position;

-- Services table
SELECT 'services' as table_name, column_name, data_type, is_nullable, column_default
FROM information_schema.columns
WHERE table_schema = 'public' AND table_name = 'services'
ORDER BY ordinal_position;

-- Video Processing table
SELECT 'video_processing' as table_name, column_name, data_type, is_nullable, column_default
FROM information_schema.columns
WHERE table_schema = 'public' AND table_name = 'video_processing'
ORDER BY ordinal_position;

-- 3. CHECK ALL INDEXES
-- ==========================================
SELECT '=== INDEXES AUDIT ===' as audit_section;

SELECT 
    schemaname,
    tablename,
    indexname,
    indexdef
FROM pg_indexes
WHERE schemaname = 'public'
AND tablename NOT IN ('databasechangelog', 'databasechangeloglock')
ORDER BY tablename, indexname;

-- 4. CHECK ALL CONSTRAINTS
-- ==========================================
SELECT '=== CONSTRAINTS AUDIT ===' as audit_section;

SELECT 
    tc.table_name,
    tc.constraint_name,
    tc.constraint_type,
    kcu.column_name,
    CASE 
        WHEN tc.constraint_type = 'FOREIGN KEY' THEN ccu.table_name || '(' || ccu.column_name || ')'
        WHEN tc.constraint_type = 'CHECK' THEN cc.check_clause
        ELSE NULL
    END as details
FROM information_schema.table_constraints tc
LEFT JOIN information_schema.key_column_usage kcu 
    ON tc.constraint_name = kcu.constraint_name
    AND tc.table_schema = kcu.table_schema
LEFT JOIN information_schema.constraint_column_usage ccu
    ON ccu.constraint_name = tc.constraint_name
    AND ccu.table_schema = tc.table_schema
LEFT JOIN information_schema.check_constraints cc
    ON cc.constraint_name = tc.constraint_name
    AND cc.constraint_schema = tc.table_schema
WHERE tc.table_schema = 'public'
AND tc.table_name NOT IN ('databasechangelog', 'databasechangeloglock')
ORDER BY tc.table_name, tc.constraint_type, tc.constraint_name;

-- 5. CHECK SEQUENCES
-- ==========================================
SELECT '=== SEQUENCES AUDIT ===' as audit_section;

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

-- 6. CHECK CUSTOM TYPES (ENUMS)
-- ==========================================
SELECT '=== CUSTOM TYPES AUDIT ===' as audit_section;

SELECT 
    t.typname as enum_name,
    array_agg(e.enumlabel ORDER BY e.enumsortorder) as enum_values
FROM pg_type t
JOIN pg_enum e ON t.oid = e.enumtypid
WHERE t.typnamespace = (SELECT oid FROM pg_namespace WHERE nspname = 'public')
GROUP BY t.typname
ORDER BY t.typname;

-- 7. CHECK TRIGGERS
-- ==========================================
SELECT '=== TRIGGERS AUDIT ===' as audit_section;

SELECT 
    trigger_name,
    event_object_table as table_name,
    action_timing,
    event_manipulation,
    action_statement
FROM information_schema.triggers
WHERE trigger_schema = 'public'
ORDER BY event_object_table, trigger_name;

-- 8. CHECK FUNCTIONS
-- ==========================================
SELECT '=== FUNCTIONS AUDIT ===' as audit_section;

SELECT 
    routine_name,
    routine_type,
    data_type as return_type
FROM information_schema.routines
WHERE routine_schema = 'public'
ORDER BY routine_name;

-- 9. CHECK VIEWS
-- ==========================================
SELECT '=== VIEWS AUDIT ===' as audit_section;

SELECT 
    table_name as view_name,
    view_definition
FROM information_schema.views
WHERE table_schema = 'public'
ORDER BY table_name;

-- 10. CHECK PARTITIONS
-- ==========================================
SELECT '=== PARTITIONS AUDIT ===' as audit_section;

SELECT 
    parent.relname as parent_table,
    child.relname as partition_name,
    pg_get_expr(child.relpartbound, child.oid) as partition_bounds
FROM pg_inherits
JOIN pg_class parent ON pg_inherits.inhparent = parent.oid
JOIN pg_class child ON pg_inherits.inhrelid = child.oid
WHERE parent.relnamespace = (SELECT oid FROM pg_namespace WHERE nspname = 'public')
ORDER BY parent.relname, child.relname;

-- 11. CHECK FOR MISSING ELEMENTS
-- ==========================================
SELECT '=== POTENTIAL ISSUES ===' as audit_section;

-- Check for tables without primary keys
SELECT 
    'Table without PK' as issue_type,
    t.table_name
FROM information_schema.tables t
LEFT JOIN information_schema.table_constraints tc
    ON t.table_name = tc.table_name 
    AND tc.constraint_type = 'PRIMARY KEY'
WHERE t.table_schema = 'public'
AND t.table_type = 'BASE TABLE'
AND tc.constraint_name IS NULL;

-- Check for missing indexes on foreign keys
SELECT 
    'FK without index' as issue_type,
    tc.table_name || '.' || kcu.column_name as location
FROM information_schema.table_constraints tc
JOIN information_schema.key_column_usage kcu
    ON tc.constraint_name = kcu.constraint_name
LEFT JOIN pg_indexes i
    ON tc.table_name = i.tablename
    AND kcu.column_name = ANY(string_to_array(i.indexdef, ' '))
WHERE tc.constraint_type = 'FOREIGN KEY'
AND i.indexname IS NULL;

-- 12. LIQUIBASE SYNC STATUS
-- ==========================================
SELECT '=== LIQUIBASE STATUS ===' as audit_section;

SELECT 
    COUNT(*) as total_changesets,
    COUNT(DISTINCT filename) as total_files,
    MIN(dateexecuted) as first_execution,
    MAX(dateexecuted) as last_execution
FROM databasechangelog;

SELECT 
    filename,
    COUNT(*) as changesets,
    STRING_AGG(id::text, ', ' ORDER BY orderexecuted) as changeset_ids
FROM databasechangelog
GROUP BY filename
ORDER BY MIN(orderexecuted);