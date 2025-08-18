-- V1.2__Add_Security_Columns.sql
-- Migration: Enhance API key security with hashing and proper storage
-- This migration is idempotent and preserves existing data

-- Add API key security columns to users table
ALTER TABLE users 
    ADD COLUMN IF NOT EXISTS api_key_hash VARCHAR(256),
    ADD COLUMN IF NOT EXISTS api_key_salt VARCHAR(128),
    ADD COLUMN IF NOT EXISTS api_key_last_rotated TIMESTAMP WITH TIME ZONE,
    ADD COLUMN IF NOT EXISTS last_api_access TIMESTAMP WITH TIME ZONE;

-- Handle the plain text api_key column safely
DO $$
BEGIN
    -- Check if plain text api_key column exists
    IF EXISTS (
        SELECT 1 
        FROM information_schema.columns 
        WHERE table_name = 'users' 
        AND column_name = 'api_key'
    ) THEN
        -- Rename for backup instead of dropping (preserves data)
        ALTER TABLE users 
        RENAME COLUMN api_key TO api_key_plaintext_deprecated;
        
        -- Add constraint to prevent new data in deprecated column
        ALTER TABLE users 
        ADD CONSTRAINT chk_no_new_plaintext_keys 
        CHECK (api_key_plaintext_deprecated IS NULL);
        
        -- Log migration notice
        RAISE WARNING 'Plain text api_key column renamed to api_key_plaintext_deprecated for security. Please migrate existing keys to hashed format.';
        
        -- Create a migration tracking entry
        INSERT INTO liquibase_migration_metadata (changeset_id, version, context, notes, executed_by)
        VALUES ('V1.2-api-key-deprecation', '1.2', 'security', 
                'Plain text API keys deprecated. Column renamed to api_key_plaintext_deprecated. Manual migration required.', 
                'migration-script')
        ON CONFLICT DO NOTHING;
    END IF;
END $$;

-- Update balance precision for users table
ALTER TABLE users 
    ALTER COLUMN balance TYPE DECIMAL(18,8),
    ALTER COLUMN total_spent TYPE DECIMAL(18,8);

-- Update balance precision for orders table
ALTER TABLE orders
    ALTER COLUMN price TYPE DECIMAL(18,8),
    ALTER COLUMN charge TYPE DECIMAL(18,8);

-- Update balance precision for transactions table
ALTER TABLE transactions
    ALTER COLUMN amount TYPE DECIMAL(18,8),
    ALTER COLUMN balance_after TYPE DECIMAL(18,8);

-- Add index on api_key_hash for faster lookups
CREATE INDEX IF NOT EXISTS idx_users_api_key_hash ON users(api_key_hash);

-- Add index on last_api_access for monitoring and cleanup
CREATE INDEX IF NOT EXISTS idx_users_last_api_access ON users(last_api_access);

-- Add comment to explain the new security columns
COMMENT ON COLUMN users.api_key_hash IS 'Salted and hashed API key for secure storage';
COMMENT ON COLUMN users.api_key_salt IS 'Random salt used for API key hashing';
COMMENT ON COLUMN users.api_key_last_rotated IS 'Timestamp when the API key was last rotated';
COMMENT ON COLUMN users.last_api_access IS 'Timestamp of the last API access using this key';
