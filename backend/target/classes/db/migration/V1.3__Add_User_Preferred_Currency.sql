-- Add preferred_currency column to users table
ALTER TABLE users
    ADD COLUMN preferred_currency VARCHAR(3) DEFAULT 'USD' NOT NULL;

-- Create index on preferred_currency for faster lookups
CREATE INDEX idx_users_preferred_currency ON users (preferred_currency);

-- Add comment to the column
COMMENT ON COLUMN users.preferred_currency IS 'User''s preferred currency code (ISO 4217) for display purposes';

-- Update existing users to have a default preferred currency
-- This ensures data consistency for existing records
UPDATE users SET preferred_currency = 'USD' WHERE preferred_currency IS NULL;
