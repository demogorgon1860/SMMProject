-- Table Partitioning Validation and Maintenance
-- Version: 2025.08.05.1

-- Create partition monitoring functions
CREATE OR REPLACE FUNCTION check_partition_pruning(p_table_name text, p_query text)
RETURNS TABLE (
    is_pruning_enabled boolean,
    partitions_scanned int,
    total_partitions int,
    pruning_ratio numeric
) AS $$
DECLARE
    v_total_partitions int;
    v_explain_output text;
    v_partitions_scanned int;
BEGIN
    -- Get total number of partitions
    EXECUTE format('SELECT count(*) FROM pg_partitions WHERE tablename = %L', p_table_name) INTO v_total_partitions;
    
    -- Get query plan
    EXECUTE format('EXPLAIN (FORMAT JSON) %s', p_query) INTO v_explain_output;
    
    -- Parse explain output to count scanned partitions
    SELECT count(*)
    FROM json_array_elements(v_explain_output::json->'Plan'->'Partitions')
    INTO v_partitions_scanned;
    
    -- Return results
    RETURN QUERY SELECT 
        v_partitions_scanned < v_total_partitions,
        v_partitions_scanned,
        v_total_partitions,
        CASE WHEN v_total_partitions > 0 
             THEN (v_total_partitions - v_partitions_scanned)::numeric / v_total_partitions 
             ELSE 0 END;
END;
$$ LANGUAGE plpgsql;

-- Create partition size monitoring view
CREATE OR REPLACE VIEW v_partition_sizes AS
WITH RECURSIVE partition_tree AS (
    -- Base case: parent partitioned tables
    SELECT 
        c.oid,
        c.relname AS table_name,
        n.nspname AS schema_name,
        NULL::name AS parent_table,
        0 AS level
    FROM pg_class c
    JOIN pg_namespace n ON n.oid = c.relnamespace
    WHERE c.relkind = 'p'  -- partitioned table
    AND n.nspname NOT IN ('pg_catalog', 'information_schema')
    
    UNION ALL
    
    -- Recursive case: child partitions
    SELECT 
        c.oid,
        c.relname,
        n.nspname,
        p.table_name,
        p.level + 1
    FROM pg_class c
    JOIN pg_namespace n ON n.oid = c.relnamespace
    JOIN pg_inherits i ON i.inhrelid = c.oid
    JOIN partition_tree p ON p.oid = i.inhparent
    WHERE c.relkind IN ('r', 'p')  -- regular table or partitioned table
)
SELECT 
    schema_name,
    table_name,
    parent_table,
    level,
    pg_size_pretty(pg_table_size(format('%I.%I', schema_name, table_name)::regclass)) AS partition_size,
    pg_table_size(format('%I.%I', schema_name, table_name)::regclass) AS partition_size_bytes,
    (SELECT count(*) FROM pg_stats WHERE schemaname = schema_name AND tablename = table_name) AS columns_analyzed,
    age(tableoid) AS xid_age,
    pg_stat_get_live_tuples(format('%I.%I', schema_name, table_name)::regclass) AS live_tuples,
    pg_stat_get_dead_tuples(format('%I.%I', schema_name, table_name)::regclass) AS dead_tuples
FROM partition_tree
ORDER BY schema_name, table_name, level;

-- Create partition maintenance procedures
CREATE OR REPLACE PROCEDURE maintain_partitions(
    p_table_name text,
    p_schema_name text DEFAULT 'public',
    p_months_to_keep int DEFAULT 12,
    p_months_ahead int DEFAULT 3
)
LANGUAGE plpgsql
AS $$
DECLARE
    v_sql text;
    v_partition_name text;
    v_min_date date;
    v_max_date date;
    v_current_date date;
    v_partition_interval interval;
BEGIN
    -- Set current date
    v_current_date := current_date;
    
    -- Determine partition interval (assuming monthly partitioning)
    v_partition_interval := '1 month'::interval;
    
    -- Calculate date range
    v_min_date := date_trunc('month', v_current_date - (p_months_to_keep * v_partition_interval));
    v_max_date := date_trunc('month', v_current_date + (p_months_ahead * v_partition_interval));
    
    -- Create future partitions
    FOR v_partition_name IN 
        SELECT to_char(dt, 'YYYY_MM')
        FROM generate_series(v_current_date, v_max_date, '1 month') dt
    LOOP
        -- Check if partition exists
        IF NOT EXISTS (
            SELECT 1 
            FROM pg_class c
            JOIN pg_namespace n ON n.oid = c.relnamespace
            WHERE c.relname = format('%s_%s', p_table_name, v_partition_name)
            AND n.nspname = p_schema_name
        ) THEN
            -- Create new partition
            v_sql := format(
                'CREATE TABLE IF NOT EXISTS %I.%I_%s 
                PARTITION OF %I.%I 
                FOR VALUES FROM (%L) TO (%L)',
                p_schema_name,
                p_table_name,
                v_partition_name,
                p_schema_name,
                p_table_name,
                date_trunc('month', to_date(v_partition_name, 'YYYY_MM')),
                date_trunc('month', to_date(v_partition_name, 'YYYY_MM')) + '1 month'::interval
            );
            EXECUTE v_sql;
            
            RAISE NOTICE 'Created partition: %.%_%', p_schema_name, p_table_name, v_partition_name;
        END IF;
    END LOOP;
    
    -- Drop old partitions
    FOR v_partition_name IN 
        SELECT c.relname
        FROM pg_class c
        JOIN pg_namespace n ON n.oid = c.relnamespace
        WHERE c.relname LIKE format('%s_%%', p_table_name)
        AND n.nspname = p_schema_name
        AND c.relkind = 'r'  -- regular table (partition)
        AND to_date(split_part(c.relname, '_', -2) || '_' || split_part(c.relname, '_', -1), 'YYYY_MM') < v_min_date
    LOOP
        v_sql := format('DROP TABLE IF EXISTS %I.%I', p_schema_name, v_partition_name);
        EXECUTE v_sql;
        
        RAISE NOTICE 'Dropped partition: %.%', p_schema_name, v_partition_name;
    END LOOP;
END;
$$;

-- Create partition analysis function
CREATE OR REPLACE FUNCTION analyze_partition_performance(
    p_table_name text,
    p_schema_name text DEFAULT 'public'
)
RETURNS TABLE (
    partition_name text,
    total_rows bigint,
    estimated_rows bigint,
    sequential_scans bigint,
    index_scans bigint,
    avg_seq_scan_time numeric,
    avg_index_scan_time numeric,
    last_vacuum timestamp,
    last_analyze timestamp,
    bloat_percentage numeric
)
AS $$
BEGIN
    RETURN QUERY
    WITH partition_stats AS (
        SELECT 
            c.relname as partition_name,
            pg_stat_get_live_tuples(c.oid) as total_rows,
            c.reltuples::bigint as estimated_rows,
            s.seq_scan as sequential_scans,
            s.idx_scan as index_scans,
            (s.seq_tup_read::numeric / NULLIF(s.seq_scan, 0))::numeric(10,2) as avg_seq_scan_time,
            (s.idx_tup_fetch::numeric / NULLIF(s.idx_scan, 0))::numeric(10,2) as avg_index_scan_time,
            s.last_vacuum,
            s.last_analyze,
            CASE 
                WHEN c.relpages > 0 AND c.reltuples > 0 THEN
                    round(((c.relpages::numeric - 
                           (c.reltuples * (25 + 8 * every.avg_width)::numeric / 
                            (current_setting('block_size')::numeric - 20))
                          ) / c.relpages * 100)::numeric, 2)
                ELSE 0
            END as bloat_percentage
        FROM pg_class c
        JOIN pg_namespace n ON n.oid = c.relnamespace
        LEFT JOIN pg_stat_user_tables s ON c.relname = s.relname
        CROSS JOIN (
            SELECT avg(pg_column_size(t)) as avg_width 
            FROM (SELECT * FROM format('%I.%I', p_schema_name, p_table_name)::regclass LIMIT 100) t
        ) every
        WHERE c.relname LIKE format('%s_%%', p_table_name)
        AND n.nspname = p_schema_name
        AND c.relkind = 'r'
    )
    SELECT * FROM partition_stats
    ORDER BY partition_name;
END;
$$ LANGUAGE plpgsql;

-- Create monitoring triggers for partition sizes
CREATE OR REPLACE FUNCTION log_partition_size_change()
RETURNS trigger AS $$
BEGIN
    INSERT INTO partition_size_history (
        table_name,
        partition_name,
        size_bytes,
        row_count,
        measured_at
    ) VALUES (
        TG_ARGV[0],
        TG_RELNAME,
        pg_total_relation_size(TG_RELID),
        pg_stat_get_live_tuples(TG_RELID),
        current_timestamp
    );
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

-- Create partition size history table
CREATE TABLE IF NOT EXISTS partition_size_history (
    id bigserial PRIMARY KEY,
    table_name text NOT NULL,
    partition_name text NOT NULL,
    size_bytes bigint NOT NULL,
    row_count bigint NOT NULL,
    measured_at timestamp with time zone NOT NULL,
    CONSTRAINT partition_size_history_unique UNIQUE (table_name, partition_name, measured_at)
);

-- Create index on partition size history
CREATE INDEX idx_partition_size_history_measured_at 
ON partition_size_history (measured_at DESC);

-- Test partition pruning
SELECT check_partition_pruning(
    'orders',
    'SELECT * FROM orders WHERE created_at >= ''2025-01-01'' AND created_at < ''2025-02-01'''
);

-- View partition sizes
SELECT * FROM v_partition_sizes
WHERE parent_table IS NOT NULL
ORDER BY partition_size_bytes DESC;

-- Analyze partition performance
SELECT * FROM analyze_partition_performance('orders')
WHERE total_rows > 0;

-- Schedule partition maintenance
DO $$
BEGIN
    -- Maintain orders table partitions
    CALL maintain_partitions('orders', 'public', 12, 3);
    
    -- Maintain video_processing_queue table partitions
    CALL maintain_partitions('video_processing_queue', 'public', 6, 2);
    
    -- Maintain balance_transactions table partitions
    CALL maintain_partitions('balance_transactions', 'public', 24, 3);
END $$;
