-- Query Plan Analysis and Optimization
-- Date: 2025-08-05
-- Purpose: Analyze and optimize critical query performance

-- Enable query performance tracking
CREATE EXTENSION IF NOT EXISTS pg_stat_statements;

-- Reset statistics to get fresh data
SELECT pg_stat_statements_reset();

-- Create helper function for query analysis
CREATE OR REPLACE FUNCTION analyze_query_performance(
    p_query text,
    p_description text DEFAULT NULL
) RETURNS TABLE (
    plan_detail jsonb,
    execution_time numeric,
    planning_time numeric,
    actual_rows bigint,
    actual_parallel_workers integer
) AS $$
BEGIN
    RETURN QUERY
    EXECUTE 'EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON) ' || p_query;
    
    IF p_description IS NOT NULL THEN
        RAISE NOTICE 'Analysis for: %', p_description;
    END IF;
END;
$$ LANGUAGE plpgsql;

-- Analyze Order Processing Queries
\echo 'Analyzing Order Processing Queries...'

-- 1. User Order History Query
EXPLAIN (ANALYZE, BUFFERS) 
SELECT o.*, s.name as service_name
FROM orders o
JOIN services s ON o.service_id = s.id
WHERE o.user_id = 1  -- Test with actual user_id
AND o.status IN ('PENDING', 'IN_PROGRESS')
ORDER BY o.created_at DESC
LIMIT 20;

-- Add index hint if needed
/*
SELECT /*+ INDEX(orders idx_orders_user_status_created) */ 
    o.*, s.name as service_name
FROM orders o
JOIN services s ON o.service_id = s.id
WHERE o.user_id = 1
AND o.status IN ('PENDING', 'IN_PROGRESS')
ORDER BY o.created_at DESC
LIMIT 20;
*/

-- 2. Video Processing Queue Query
EXPLAIN (ANALYZE, BUFFERS) 
SELECT v.*, o.user_id
FROM video_processing_queue v
JOIN orders o ON v.order_id = o.id
WHERE v.status = 'PENDING'
AND v.attempt_number < v.max_attempts
ORDER BY v.priority DESC, v.created_at ASC
LIMIT 100;

-- 3. Balance Transaction History
EXPLAIN (ANALYZE, BUFFERS) 
SELECT bt.*, u.username
FROM balance_transactions bt
JOIN users u ON bt.user_id = u.id
WHERE bt.user_id = 1  -- Test with actual user_id
AND bt.created_at >= CURRENT_DATE - INTERVAL '30 days'
ORDER BY bt.created_at DESC;

-- 4. Active Services Query
EXPLAIN (ANALYZE, BUFFERS) 
SELECT s.*, c.name as category_name
FROM services s
JOIN categories c ON s.category_id = c.id
WHERE s.is_active = true
AND c.is_active = true
ORDER BY s.category_id, s.price;

-- Identify Slow Queries
SELECT 
    substring(query, 1, 100) as query_preview,
    round(total_exec_time::numeric, 2) as total_exec_time_ms,
    calls,
    round(mean_exec_time::numeric, 2) as mean_exec_time_ms,
    round((100 * total_exec_time / sum(total_exec_time) over ())::numeric, 2) as percentage_cpu,
    round(stddev_exec_time::numeric, 2) as stddev_exec_time_ms,
    round(rows/calls::numeric, 2) as avg_rows,
    shared_blks_hit + shared_blks_read as total_blocks
FROM pg_stat_statements
WHERE dbid = (SELECT oid FROM pg_database WHERE datname = current_database())
AND total_exec_time > 0
ORDER BY total_exec_time DESC
LIMIT 20;

-- Query Plan Analysis for Slow Queries
WITH slow_queries AS (
    SELECT query
    FROM pg_stat_statements
    WHERE total_exec_time > 1000  -- queries taking more than 1 second
    AND calls > 100  -- executed frequently
    ORDER BY total_exec_time DESC
    LIMIT 5
)
SELECT query, 
       analyze_query_performance(query, 'Slow Query Analysis') as analysis
FROM slow_queries;

-- Check for Missing Indexes
SELECT 
    schemaname || '.' || relname as table_name,
    seq_scan,
    seq_tup_read,
    idx_scan,
    seq_tup_read / NULLIF(seq_scan, 0) as avg_rows_per_scan,
    n_live_tup
FROM pg_stat_user_tables
WHERE seq_scan > 0
AND (idx_scan = 0 OR (seq_scan::float / NULLIF(idx_scan, 0)) > 3)
ORDER BY seq_tup_read DESC;

-- Check Index Usage
SELECT 
    schemaname || '.' || tablename as table_name,
    indexrelname as index_name,
    pg_size_pretty(pg_relation_size(i.indexrelid)) as index_size,
    idx_scan as number_of_scans,
    idx_tup_read as tuples_read,
    idx_tup_fetch as tuples_fetched
FROM pg_stat_user_indexes i
JOIN pg_index USING (indexrelid)
WHERE idx_scan = 0
AND indisunique is false
ORDER BY pg_relation_size(i.indexrelid) DESC;

-- Buffer Cache Hit Ratio
SELECT 
    sum(heap_blks_read) as heap_read,
    sum(heap_blks_hit)  as heap_hit,
    sum(heap_blks_hit) / NULLIF((sum(heap_blks_hit) + sum(heap_blks_read))::numeric, 0) as ratio
FROM pg_statio_user_tables;

-- Table Access Statistics
SELECT
    schemaname || '.' || relname as table_name,
    seq_scan,
    idx_scan,
    n_tup_ins as inserts,
    n_tup_upd as updates,
    n_tup_del as deletes,
    n_live_tup as live_tuples,
    n_dead_tup as dead_tuples
FROM pg_stat_user_tables
ORDER BY n_live_tup DESC;

-- Suggested Query Optimizations
COMMENT ON VIEW query_optimizations IS $$
Based on the analysis above, consider the following optimizations:

1. For Order Processing:
   - Use cursor-based pagination instead of offset
   - Add covering index for frequently accessed columns
   - Consider materialized view for complex aggregations

2. For Video Processing Queue:
   - Implement partitioning by status
   - Use priority queue pattern
   - Add composite index on (status, priority, created_at)

3. For Balance Transactions:
   - Implement table partitioning by date
   - Use BRIN index for date ranges
   - Add covering index for summary queries

4. For Service Lookups:
   - Cache active services
   - Use materialized view for category aggregations
   - Implement hierarchical cache invalidation

General Recommendations:
- Set work_mem appropriately for complex sorts
- Adjust maintenance_work_mem for index operations
- Configure effective_cache_size based on available memory
- Regular ANALYZE to update statistics
- Implement connection pooling
- Consider query timeouts for long-running queries
$$;

-- Create monitoring view for query performance
CREATE OR REPLACE VIEW v_query_performance AS
WITH query_stats AS (
    SELECT 
        substring(query, 1, 200) as query_snippet,
        total_exec_time,
        calls,
        mean_exec_time,
        rows,
        shared_blks_hit,
        shared_blks_read,
        shared_blks_dirtied,
        shared_blks_written,
        local_blks_hit,
        local_blks_read,
        temp_blks_read,
        temp_blks_written
    FROM pg_stat_statements
    WHERE dbid = (SELECT oid FROM pg_database WHERE datname = current_database())
    AND total_exec_time > 0
)
SELECT 
    query_snippet,
    round(total_exec_time::numeric, 2) as total_exec_time_ms,
    calls,
    round(mean_exec_time::numeric, 2) as avg_exec_time_ms,
    round(rows::numeric / calls, 2) as avg_rows,
    round(shared_blks_hit::numeric / calls, 2) as avg_shared_blks_hit,
    round(shared_blks_read::numeric / calls, 2) as avg_shared_blks_read,
    round(temp_blks_read::numeric / calls, 2) as avg_temp_blks_read,
    round(temp_blks_written::numeric / calls, 2) as avg_temp_blks_written
FROM query_stats
ORDER BY total_exec_time DESC;

-- Vacuum Analysis
SELECT 
    schemaname || '.' || relname as table_name,
    last_vacuum,
    last_autovacuum,
    vacuum_count,
    autovacuum_count,
    n_dead_tup,
    n_live_tup,
    round(n_dead_tup::numeric / NULLIF(n_live_tup, 0) * 100, 2) as dead_tuple_percentage
FROM pg_stat_user_tables
WHERE n_dead_tup > 0
ORDER BY n_dead_tup DESC;
