-- V1.7__Add_Optimistic_Locking.sql
-- Add optimistic locking support with version column

-- Add version column to users table for optimistic locking
ALTER TABLE users 
    ADD COLUMN IF NOT EXISTS version BIGINT DEFAULT 0 NOT NULL;

-- Add version column to orders table for optimistic locking
ALTER TABLE orders 
    ADD COLUMN IF NOT EXISTS version BIGINT DEFAULT 0 NOT NULL;

-- Add version column to balance_transactions table for optimistic locking  
ALTER TABLE balance_transactions 
    ADD COLUMN IF NOT EXISTS version BIGINT DEFAULT 0 NOT NULL;

-- Add index on version column for users table (for performance on concurrent updates)
CREATE INDEX IF NOT EXISTS idx_users_version ON users(version);

-- Add index on version column for orders table
CREATE INDEX IF NOT EXISTS idx_orders_version ON orders(version);

-- Add index on version column for balance_transactions table
CREATE INDEX IF NOT EXISTS idx_balance_transactions_version ON balance_transactions(version);

-- Add comments explaining the purpose of version columns
COMMENT ON COLUMN users.version IS 'Optimistic locking version counter - incremented on each update';
COMMENT ON COLUMN orders.version IS 'Optimistic locking version counter - incremented on each update';
COMMENT ON COLUMN balance_transactions.version IS 'Optimistic locking version counter - incremented on each update';

-- Update existing records to have version = 0 (this is already the default, but making it explicit)
UPDATE users SET version = 0 WHERE version IS NULL;
UPDATE orders SET version = 0 WHERE version IS NULL;
UPDATE balance_transactions SET version = 0 WHERE version IS NULL;