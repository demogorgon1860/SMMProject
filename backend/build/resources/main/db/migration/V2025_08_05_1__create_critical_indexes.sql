-- Migration: Create critical indexes for order management and video processing
-- Version: 2025.08.05.1

-- Transaction block for atomic index creation
BEGIN;

-- Index for user order queries
-- This improves queries that filter by user and status, ordered by creation date
CREATE INDEX IF NOT EXISTS idx_orders_user_status_created ON orders (
    user_id,
    status,
    created_at DESC
) WITH (fillfactor = 90);

-- Index for video processing queue
-- Optimizes queries that look for videos with specific status and attempt counts
CREATE INDEX IF NOT EXISTS idx_video_processing_status_attempts ON video_processing_queue (
    status,
    attempt_number,
    created_at DESC
) WHERE status IN ('PENDING', 'PROCESSING', 'FAILED')
  AND attempt_number < max_attempts;

-- Index for balance transactions
-- Improves user transaction history queries filtered by type
CREATE INDEX IF NOT EXISTS idx_balance_transactions_user_type_created ON balance_transactions (
    user_id,
    transaction_type,
    created_at DESC
) INCLUDE (amount, balance_after)
WITH (fillfactor = 90);

-- Index for active services by category
-- Optimizes service lookup queries
CREATE INDEX IF NOT EXISTS idx_services_active_category ON services (
    category_id,
    is_active
) INCLUDE (name, price, min_quantity, max_quantity)
WHERE is_active = true;

-- Add monitoring for index usage
CREATE EXTENSION IF NOT EXISTS pg_stat_statements;

-- Create index usage monitoring view
CREATE OR REPLACE VIEW v_index_usage AS
SELECT
    schemaname || '.' || relname AS table,
    indexrelname AS index,
    pg_size_pretty(pg_relation_size(i.indexrelid)) AS index_size,
    idx_scan as index_scans,
    idx_tup_read as tuples_read,
    idx_tup_fetch as tuples_fetched
FROM pg_stat_user_indexes ui
JOIN pg_index i ON ui.indexrelid = i.indexrelid
WHERE schemaname NOT IN ('pg_catalog', 'pg_toast')
ORDER BY pg_relation_size(i.indexrelid) DESC;

-- Create index bloat monitoring view
CREATE OR REPLACE VIEW v_index_bloat AS
WITH btree_index_atts AS (
    SELECT nspname, relname, reltuples, relpages, indrelid, relam,
        regexp_split_to_table(indkey::text, ' ')::smallint AS attnum,
        indexrelid, index_tuple_hdr_bm, max_data_len
    FROM (
        SELECT
            n.nspname, i.relname, i.reltuples, i.relpages, i.indrelid, i.relam,
            i.indkey, i.indexrelid,
            maxalign, ps.max_data_len,
            ( 6 + maxalign - CASE
                WHEN index_tuple_hdr_bm&1 = 0 THEN maxalign
                ELSE CASE WHEN maxalign > 4 THEN 4 ELSE maxalign END
                END
            ) AS index_tuple_hdr_bm
        FROM pg_index
        JOIN pg_class i ON i.oid = indexrelid
        JOIN pg_namespace n ON n.oid = i.relnamespace
        CROSS JOIN (
            SELECT
                max(CASE WHEN attlen = -1 THEN null ELSE attlen END) AS max_data_len,
                bool_or(null_frac <> 0) AS null_frac
            FROM pg_stats s
            WHERE schemaname = 'public'
        ) AS ps
        WHERE pg_index.indisvalid = true
    ) AS rows_data_stats
)
SELECT 
    schemaname || '.' || tablename AS table_name,
    iname AS index_name,
    table_size,
    index_size,
    bloat_size,
    bloat_ratio
FROM (
    SELECT
        schemaname, tablename, bs*tblpages AS table_size,
        method, iname, ids.relpages AS index_pages,
        COALESCE(substring(array_to_string(reloptions, ' ') FROM 'fillfactor=([0-9]+)')::smallint, 90) AS fillfactor,
        CASE WHEN av_leaf_free > 0 THEN bs*(tblpages-est_tblpages)/(tblpages+1) ELSE 0::numeric END AS bloat_size,
        CASE WHEN av_leaf_free > 0 AND tblpages > 0
            THEN round(((tblpages-est_tblpages)/tblpages::numeric)*100, 2) ELSE 0::numeric END AS bloat_ratio
    FROM (
        SELECT
            schemaname, tablename, method, iname, bs,
            CEIL((cc*((relpages)::float8/(tblpages-est_tblpages))+3)/(n_distinct+1))::bigint AS est_pages,
            tblpages, est_tblpages, is_na
        FROM (
            SELECT
                schemaname, tablename, method, iname, bs,
                CEIL((reltuples*(4+tpl_hdr_size+tpl_data_size+2*6+33))/(bs-20::float)) AS est_tblpages,
                CEIL(reltuples/relpages::float) AS cc, relpages, tblpages,
                CASE WHEN tblpages - est_tblpages > 0 THEN true ELSE false END AS is_na
            FROM (
                SELECT
                    schemaname, tablename, method, iname, bs,
                    tblpages, relpages, reltuples,
                    ( index_tuple_hdr_bm +
                        maxalign - CASE
                            WHEN index_tuple_hdr_bm%maxalign = 0 THEN maxalign
                            ELSE index_tuple_hdr_bm%maxalign
                        END + nulldatawidth + maxalign - CASE
                            WHEN nulldatawidth::integer%maxalign = 0 THEN maxalign
                            ELSE nulldatawidth::integer%maxalign
                        END
                    ) AS tpl_hdr_size,
                    ( maxalign - CASE
                        WHEN nulldatawidth::integer%maxalign = 0 THEN maxalign
                        ELSE nulldatawidth::integer%maxalign
                        END
                    ) AS tpl_data_size
                FROM (
                    SELECT
                        n.nspname AS schemaname,
                        ct.relname AS tablename,
                        i.relname AS iname,
                        am.amname AS method,
                        i.relpages,
                        i.reltuples,
                        ct.relpages AS tblpages,
                        bs,
                        index_tuple_hdr_bm,
                        maxalign,
                        nulldatawidth,
                        maxvaralign,
                        4 AS nulldatahdrwidth,
                        pagehdr,
                        pageopqdata,
                        i.relam
                    FROM pg_index
                    JOIN pg_class ct ON ct.oid = indrelid
                    JOIN pg_class i ON i.oid = indexrelid
                    JOIN pg_am am ON i.relam = am.oid
                    JOIN pg_namespace n ON n.oid = ct.relnamespace
                    CROSS JOIN (
                        SELECT
                            current_setting('block_size')::numeric AS bs,
                            24 AS pagehdr,
                            16 AS pageopqdata,
                            CASE WHEN version() ~ 'mingw32' OR version() ~ '64-bit' THEN 8 ELSE 4 END AS maxalign,
                            4 AS nulldatawidth,
                            CASE WHEN version() ~ 'mingw32' OR version() ~ '64-bit' THEN 8 ELSE 4 END AS maxvaralign
                    ) AS constants
                    WHERE n.nspname NOT IN ('pg_catalog', 'information_schema')
                ) AS rows_data_stats
            ) AS table_stats
        ) AS rows_hdr_pdg_stats
    ) AS relation_stats
) AS pretty_rsa
WHERE bloat_ratio >= 30
ORDER BY bloat_size DESC LIMIT 20;

-- Add comments to document the indexes
COMMENT ON INDEX idx_orders_user_status_created IS 'Optimizes user order history queries and status filtering';
COMMENT ON INDEX idx_video_processing_status_attempts IS 'Improves video processing queue performance and retry handling';
COMMENT ON INDEX idx_balance_transactions_user_type_created IS 'Enhances transaction history queries with included columns';
COMMENT ON INDEX idx_services_active_category IS 'Speeds up active service lookups by category';

COMMIT;

-- Verification queries
-- Run these after index creation to verify they are being used

-- Test orders index
EXPLAIN ANALYZE
SELECT * FROM orders
WHERE user_id = 1 AND status = 'PENDING'
ORDER BY created_at DESC
LIMIT 10;

-- Test video processing queue index
EXPLAIN ANALYZE
SELECT * FROM video_processing_queue
WHERE status = 'PENDING' AND attempt_number < max_attempts
ORDER BY created_at DESC
LIMIT 100;

-- Test balance transactions index
EXPLAIN ANALYZE
SELECT user_id, transaction_type, created_at, amount, balance_after
FROM balance_transactions
WHERE user_id = 1 AND transaction_type = 'DEBIT'
ORDER BY created_at DESC
LIMIT 20;

-- Test services index
EXPLAIN ANALYZE
SELECT name, price, min_quantity, max_quantity
FROM services
WHERE category_id = 1 AND is_active = true;

-- Monitor index usage
SELECT * FROM v_index_usage
WHERE index IN (
    'idx_orders_user_status_created',
    'idx_video_processing_status_attempts',
    'idx_balance_transactions_user_type_created',
    'idx_services_active_category'
);

-- Monitor index bloat
SELECT * FROM v_index_bloat
WHERE index_name IN (
    'idx_orders_user_status_created',
    'idx_video_processing_status_attempts',
    'idx_balance_transactions_user_type_created',
    'idx_services_active_category'
);
