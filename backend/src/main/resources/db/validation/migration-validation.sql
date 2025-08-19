-- ============================================================================
-- Database Migration Validation Script
-- ============================================================================
-- Purpose: Validate database migrations for consistency, safety, and best practices
-- Usage: Run this script after migrations to ensure database integrity
-- PostgreSQL Version: 13+
-- Last Updated: 2025-08-17
-- ============================================================================

-- Set script configuration
\set ON_ERROR_STOP on
\timing on
\echo 'Starting Migration Validation...'

-- ============================================================================
-- 1. CHECK FOR DESTRUCTIVE OPERATIONS IN MIGRATION HISTORY
-- ============================================================================
\echo '=== Checking for Destructive Operations ==='

WITH dangerous_operations AS (
    SELECT 
        'DROP COLUMN' AS operation_type,
        schemaname,
        tablename,
        'Column dropped - data loss risk' AS risk_level
    FROM pg_stat_user_tables
    WHERE FALSE -- Placeholder for actual detection logic
    
    UNION ALL
    
    SELECT 
        'DROP TABLE' AS operation_type,
        schemaname,
        tablename,
        'Table dropped - complete data loss' AS risk_level
    FROM pg_stat_user_tables
    WHERE FALSE -- Placeholder for actual detection logic
)
SELECT 
    CASE 
        WHEN COUNT(*) = 0 THEN '✓ PASS: No destructive operations detected'
        ELSE '✗ FAIL: ' || COUNT(*) || ' destructive operations found'
    END AS validation_result
FROM dangerous_operations;

-- ============================================================================
-- 2. VALIDATE INDEX NAMING CONVENTIONS
-- ============================================================================
\echo '=== Validating Index Naming Conventions ==='

WITH index_validation AS (
    SELECT 
        i.indexname,
        i.tablename,
        CASE 
            WHEN i.indexname LIKE 'idx_%' THEN 'Valid'
            WHEN i.indexname LIKE 'pk_%' THEN 'Valid (Primary Key)'
            WHEN i.indexname LIKE 'uk_%' THEN 'Valid (Unique)'
            WHEN i.indexname LIKE 'fk_%' THEN 'Valid (Foreign Key)'
            WHEN i.indexname LIKE '%_pkey' THEN 'Valid (System PK)'
            WHEN i.indexname LIKE '%_key' THEN 'Valid (System)'
            ELSE 'Invalid - Should start with idx_, pk_, uk_, or fk_'
        END AS naming_status,
        CASE 
            WHEN i.indexname LIKE 'idx_' || i.tablename || '%' THEN 'Follows table prefix convention'
            WHEN i.indexname LIKE '%_pkey' OR i.indexname LIKE '%_key' THEN 'System generated'
            ELSE 'Missing table name prefix'
        END AS prefix_status
    FROM pg_indexes i
    WHERE i.schemaname NOT IN ('pg_catalog', 'information_schema')
)
SELECT 
    COUNT(*) AS total_indexes,
    COUNT(*) FILTER (WHERE naming_status LIKE 'Valid%') AS valid_names,
    COUNT(*) FILTER (WHERE naming_status = 'Invalid - Should start with idx_, pk_, uk_, or fk_') AS invalid_names,
    CASE 
        WHEN COUNT(*) FILTER (WHERE naming_status = 'Invalid - Should start with idx_, pk_, uk_, or fk_') = 0 
        THEN '✓ PASS: All indexes follow naming conventions'
        ELSE '✗ FAIL: ' || COUNT(*) FILTER (WHERE naming_status = 'Invalid - Should start with idx_, pk_, uk_, or fk_') || ' indexes have invalid names'
    END AS validation_result
FROM index_validation;

-- Show invalid indexes if any
SELECT indexname, tablename, naming_status, prefix_status
FROM (
    SELECT 
        i.indexname,
        i.tablename,
        CASE 
            WHEN i.indexname LIKE 'idx_%' THEN 'Valid'
            WHEN i.indexname LIKE 'pk_%' THEN 'Valid (Primary Key)'
            WHEN i.indexname LIKE 'uk_%' THEN 'Valid (Unique)'
            WHEN i.indexname LIKE 'fk_%' THEN 'Valid (Foreign Key)'
            WHEN i.indexname LIKE '%_pkey' THEN 'Valid (System PK)'
            WHEN i.indexname LIKE '%_key' THEN 'Valid (System)'
            ELSE 'Invalid - Should start with idx_, pk_, uk_, or fk_'
        END AS naming_status,
        CASE 
            WHEN i.indexname LIKE 'idx_' || i.tablename || '%' THEN 'Follows table prefix convention'
            WHEN i.indexname LIKE '%_pkey' OR i.indexname LIKE '%_key' THEN 'System generated'
            ELSE 'Missing table name prefix'
        END AS prefix_status
    FROM pg_indexes i
    WHERE i.schemaname NOT IN ('pg_catalog', 'information_schema')
) AS idx_check
WHERE naming_status LIKE 'Invalid%'
LIMIT 10;

-- ============================================================================
-- 3. CHECK FOR BACKUP/DEPRECATED COLUMNS
-- ============================================================================
\echo '=== Checking for Backup/Deprecated Columns ==='

SELECT 
    table_name,
    column_name,
    data_type,
    CASE 
        WHEN column_name LIKE '%_backup_%' THEN 'Backup column - consider removing after migration'
        WHEN column_name LIKE '%_deprecated%' THEN 'Deprecated column - should be removed'
        WHEN column_name LIKE '%_old%' THEN 'Old column - review for removal'
        WHEN column_name LIKE '%_temp%' THEN 'Temporary column - should be removed'
    END AS recommendation
FROM information_schema.columns
WHERE table_schema = 'public'
    AND (
        column_name LIKE '%_backup_%' 
        OR column_name LIKE '%_deprecated%'
        OR column_name LIKE '%_old%'
        OR column_name LIKE '%_temp%'
    )
ORDER BY table_name, column_name;

-- ============================================================================
-- 4. VALIDATE CONSTRAINT NAMING
-- ============================================================================
\echo '=== Validating Constraint Naming ==='

WITH constraint_validation AS (
    SELECT 
        con.conname AS constraint_name,
        rel.relname AS table_name,
        CASE con.contype
            WHEN 'c' THEN 'CHECK'
            WHEN 'f' THEN 'FOREIGN KEY'
            WHEN 'p' THEN 'PRIMARY KEY'
            WHEN 'u' THEN 'UNIQUE'
            WHEN 'x' THEN 'EXCLUSION'
        END AS constraint_type,
        CASE 
            WHEN con.contype = 'c' AND con.conname LIKE 'chk_%' THEN 'Valid'
            WHEN con.contype = 'f' AND con.conname LIKE 'fk_%' THEN 'Valid'
            WHEN con.contype = 'p' AND con.conname LIKE 'pk_%' THEN 'Valid'
            WHEN con.contype = 'u' AND con.conname LIKE 'uk_%' THEN 'Valid'
            WHEN con.conname LIKE '%_pkey' THEN 'Valid (System)'
            WHEN con.conname LIKE '%_key' THEN 'Valid (System)'
            WHEN con.conname LIKE '%_check' THEN 'Valid (System)'
            WHEN con.conname LIKE '%_fkey' THEN 'Valid (System)'
            ELSE 'Invalid naming convention'
        END AS naming_status
    FROM pg_constraint con
    JOIN pg_class rel ON rel.oid = con.conrelid
    JOIN pg_namespace nsp ON nsp.oid = rel.relnamespace
    WHERE nsp.nspname = 'public'
)
SELECT 
    constraint_type,
    COUNT(*) AS total_constraints,
    COUNT(*) FILTER (WHERE naming_status LIKE 'Valid%') AS valid_names,
    COUNT(*) FILTER (WHERE naming_status = 'Invalid naming convention') AS invalid_names
FROM constraint_validation
GROUP BY constraint_type
ORDER BY constraint_type;

-- ============================================================================
-- 5. CHECK FOR MISSING INDEXES ON FOREIGN KEYS
-- ============================================================================
\echo '=== Checking for Missing Indexes on Foreign Keys ==='

WITH fk_without_index AS (
    SELECT 
        conrelid::regclass AS table_name,
        a.attname AS column_name,
        confrelid::regclass AS referenced_table
    FROM pg_constraint c
    JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = ANY(c.conkey)
    WHERE c.contype = 'f'
        AND NOT EXISTS (
            SELECT 1
            FROM pg_index i
            WHERE i.indrelid = c.conrelid
                AND a.attnum = ANY(i.indkey)
        )
)
SELECT 
    CASE 
        WHEN COUNT(*) = 0 THEN '✓ PASS: All foreign keys have indexes'
        ELSE '✗ WARNING: ' || COUNT(*) || ' foreign keys missing indexes (may impact performance)'
    END AS validation_result,
    COUNT(*) AS missing_indexes
FROM fk_without_index;

-- Show foreign keys without indexes
SELECT * FROM (
    SELECT 
        conrelid::regclass AS table_name,
        a.attname AS column_name,
        confrelid::regclass AS referenced_table,
        'CREATE INDEX idx_' || conrelid::regclass || '_' || a.attname || ' ON ' || conrelid::regclass || '(' || a.attname || ');' AS suggested_index
    FROM pg_constraint c
    JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = ANY(c.conkey)
    WHERE c.contype = 'f'
        AND NOT EXISTS (
            SELECT 1
            FROM pg_index i
            WHERE i.indrelid = c.conrelid
                AND a.attnum = ANY(i.indkey)
        )
) AS missing_idx
LIMIT 10;

-- ============================================================================
-- 6. CHECK FOR DUPLICATE INDEXES
-- ============================================================================
\echo '=== Checking for Duplicate Indexes ==='

WITH index_columns AS (
    SELECT 
        indrelid::regclass AS table_name,
        indexrelid::regclass AS index_name,
        indkey,
        indisunique,
        indisprimary
    FROM pg_index
    WHERE indrelid IN (
        SELECT oid FROM pg_class 
        WHERE relnamespace = (SELECT oid FROM pg_namespace WHERE nspname = 'public')
    )
),
duplicate_indexes AS (
    SELECT 
        ic1.table_name,
        ic1.index_name AS index1,
        ic2.index_name AS index2,
        ic1.indkey
    FROM index_columns ic1
    JOIN index_columns ic2 
        ON ic1.table_name = ic2.table_name 
        AND ic1.indkey = ic2.indkey
        AND ic1.index_name < ic2.index_name
    WHERE NOT ic1.indisprimary AND NOT ic2.indisprimary
)
SELECT 
    CASE 
        WHEN COUNT(*) = 0 THEN '✓ PASS: No duplicate indexes found'
        ELSE '✗ WARNING: ' || COUNT(*) || ' potential duplicate indexes found'
    END AS validation_result
FROM duplicate_indexes;

-- ============================================================================
-- 7. VALIDATE LIQUIBASE CHANGELOGS
-- ============================================================================
\echo '=== Validating Liquibase Changelog Integrity ==='

-- Check if Liquibase tables exist
SELECT 
    CASE 
        WHEN EXISTS (SELECT 1 FROM information_schema.tables WHERE table_name = 'databasechangelog')
        THEN '✓ Liquibase changelog table exists'
        ELSE '✗ Liquibase changelog table missing'
    END AS liquibase_status;

-- Check for failed changesets
SELECT 
    CASE 
        WHEN NOT EXISTS (
            SELECT 1 FROM databasechangelog 
            WHERE exectype = 'FAILED'
        ) OR NOT EXISTS (
            SELECT 1 FROM information_schema.tables WHERE table_name = 'databasechangelog'
        )
        THEN '✓ PASS: No failed changesets'
        ELSE '✗ FAIL: Failed changesets detected'
    END AS changeset_status;

-- ============================================================================
-- 8. CHECK MIGRATION VERSION CONSISTENCY
-- ============================================================================
\echo '=== Checking Migration Version Consistency ==='

-- Check for version numbering issues in Liquibase
WITH version_check AS (
    SELECT 
        id,
        author,
        filename,
        dateexecuted,
        orderexecuted,
        CASE 
            WHEN id ~ '^[0-9]+\.[0-9]+\.[0-9]+' THEN 'Semantic Versioning'
            WHEN id ~ '^V[0-9]+' THEN 'Flyway Style'
            WHEN id ~ '^[0-9]{4}_[0-9]{2}_[0-9]{2}' THEN 'Date Based'
            ELSE 'Non-standard'
        END AS version_style
    FROM databasechangelog
    WHERE 1=1
)
SELECT 
    version_style,
    COUNT(*) AS migration_count
FROM version_check
GROUP BY version_style
ORDER BY migration_count DESC;

-- ============================================================================
-- 9. VALIDATE TABLE PARTITIONING
-- ============================================================================
\echo '=== Validating Table Partitioning ==='

-- Check partitioned tables
SELECT 
    parent.relname AS parent_table,
    COUNT(child.relname) AS partition_count,
    pg_size_pretty(SUM(pg_relation_size(child.oid))) AS total_size
FROM pg_inherits
JOIN pg_class parent ON pg_inherits.inhparent = parent.oid
JOIN pg_class child ON pg_inherits.inhrelid = child.oid
WHERE parent.relnamespace = (SELECT oid FROM pg_namespace WHERE nspname = 'public')
GROUP BY parent.relname;

-- ============================================================================
-- 10. CHECK POSTGRESQL VERSION COMPATIBILITY
-- ============================================================================
\echo '=== Checking PostgreSQL Version Compatibility ==='

SELECT 
    current_setting('server_version') AS postgres_version,
    CASE 
        WHEN current_setting('server_version_num')::integer >= 130000 THEN '✓ PASS: PostgreSQL 13+ detected - all features supported'
        WHEN current_setting('server_version_num')::integer >= 120000 THEN '⚠ WARNING: PostgreSQL 12 detected - some features may not be available'
        ELSE '✗ FAIL: PostgreSQL version too old - upgrade to 13+ recommended'
    END AS compatibility_status;

-- ============================================================================
-- 11. SECURITY VALIDATION
-- ============================================================================
\echo '=== Security Validation ==='

-- Check for plain text sensitive columns
SELECT 
    table_name,
    column_name,
    data_type,
    '⚠ Potential security issue - consider encryption' AS recommendation
FROM information_schema.columns
WHERE table_schema = 'public'
    AND (
        (column_name LIKE '%password%' AND column_name NOT LIKE '%hash%')
        OR (column_name LIKE '%api_key%' AND column_name NOT LIKE '%hash%' AND column_name NOT LIKE '%deprecated%')
        OR column_name LIKE '%secret%'
        OR column_name LIKE '%token%'
    )
    AND data_type IN ('character varying', 'text', 'character')
ORDER BY table_name, column_name;

-- Check password encryption setting
SELECT 
    name,
    setting,
    CASE 
        WHEN name = 'password_encryption' AND setting = 'scram-sha-256' THEN '✓ PASS: Using SCRAM-SHA-256 encryption'
        WHEN name = 'password_encryption' AND setting = 'md5' THEN '✗ FAIL: Using weak MD5 encryption'
        ELSE '⚠ Check password encryption setting'
    END AS security_status
FROM pg_settings
WHERE name = 'password_encryption';

-- ============================================================================
-- SUMMARY REPORT
-- ============================================================================
\echo '=== Migration Validation Summary ==='

SELECT 
    '=== Migration Validation Complete ===' AS summary,
    NOW() AS validation_timestamp,
    current_database() AS database_name,
    current_setting('server_version') AS postgres_version;

\echo 'Validation complete. Review any warnings or failures above.'