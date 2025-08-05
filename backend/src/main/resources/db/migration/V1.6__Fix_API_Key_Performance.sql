-- V1.6: Fix API Key Authentication Performance
-- This migration optimizes API key lookups by creating targeted indexes
-- for the authentication flow, replacing the generic api_key_hash index

-- Drop the existing generic index first
DROP INDEX IF EXISTS idx_users_api_key_hash;

-- Create optimized partial index for active users with API keys
-- This significantly reduces index size and improves lookup performance
CREATE INDEX IF NOT EXISTS idx_users_api_key_hash_active 
ON users(api_key_hash) 
WHERE is_active = true AND api_key_hash IS NOT NULL;

-- Create composite index for API key authentication queries
-- This supports both api_key_hash lookup and is_active filtering
-- The order matters: api_key_hash first for exact match, then is_active
CREATE INDEX IF NOT EXISTS idx_users_api_key_lookup 
ON users(api_key_hash, is_active) 
WHERE api_key_hash IS NOT NULL;

-- Add comments for documentation
COMMENT ON INDEX idx_users_api_key_hash_active IS 
'Partial index for API key lookups on active users only - reduces index size and improves performance';

COMMENT ON INDEX idx_users_api_key_lookup IS 
'Composite index for API key authentication queries - supports hash lookup with active status filtering';