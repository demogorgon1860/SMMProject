-- =====================================================
-- SMM Panel Database Initialization Script
-- =====================================================
-- This script runs only on first container startup
-- Liquibase will handle all schema migrations
-- =====================================================

-- Create database if not exists (handled by POSTGRES_DB env var)
-- Just ensure proper encoding
ALTER DATABASE smm_panel SET client_encoding TO 'UTF8';
ALTER DATABASE smm_panel SET default_transaction_isolation TO 'read committed';
ALTER DATABASE smm_panel SET timezone TO 'UTC';

-- Create schema if not exists
CREATE SCHEMA IF NOT EXISTS public;

-- Grant permissions
GRANT ALL ON SCHEMA public TO smm_admin;
GRANT CREATE ON SCHEMA public TO smm_admin;

-- Create extensions if needed
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
CREATE EXTENSION IF NOT EXISTS "pgcrypto";

-- Performance settings for the database
ALTER DATABASE smm_panel SET shared_preload_libraries TO 'pg_stat_statements';
ALTER DATABASE smm_panel SET random_page_cost TO 1.1;
ALTER DATABASE smm_panel SET effective_cache_size TO '3GB';
ALTER DATABASE smm_panel SET work_mem TO '16MB';
ALTER DATABASE smm_panel SET maintenance_work_mem TO '256MB';

-- Connection settings
ALTER DATABASE smm_panel SET max_connections TO 200;
ALTER DATABASE smm_panel SET effective_io_concurrency TO 200;

-- Logging settings for development
ALTER DATABASE smm_panel SET log_statement TO 'all';
ALTER DATABASE smm_panel SET log_duration TO on;
ALTER DATABASE smm_panel SET log_min_duration_statement TO 100;

-- Create role for read-only access (optional)
DO $$
BEGIN
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'smm_readonly') THEN
        CREATE ROLE smm_readonly;
    END IF;
END
$$;

GRANT CONNECT ON DATABASE smm_panel TO smm_readonly;
GRANT USAGE ON SCHEMA public TO smm_readonly;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT ON TABLES TO smm_readonly;

-- Note: All table creation and migrations are handled by Liquibase
-- This script only sets up the database environment