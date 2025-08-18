-- Migration: Add or update status column to video_processing table
-- This migration is idempotent and non-destructive

-- First, check if the column exists and rename old column if type is different
DO $$
BEGIN
    -- Check if status column exists
    IF EXISTS (
        SELECT 1 
        FROM information_schema.columns 
        WHERE table_name = 'video_processing' 
        AND column_name = 'status'
    ) THEN
        -- Check the current data type
        IF NOT EXISTS (
            SELECT 1 
            FROM information_schema.columns 
            WHERE table_name = 'video_processing' 
            AND column_name = 'status'
            AND data_type = 'character varying'
            AND character_maximum_length >= 32
        ) THEN
            -- Backup existing data by renaming column instead of dropping
            ALTER TABLE video_processing 
            RENAME COLUMN status TO status_backup_v2025_08_11;
            
            -- Add new column with proper type
            ALTER TABLE video_processing
            ADD COLUMN status VARCHAR(32) NOT NULL DEFAULT 'PENDING'
            CHECK (status IN ('PENDING', 'QUEUED', 'PROCESSING', 'COMPLETED', 'FAILED', 'CANCELLED', 'RETRYING'));
            
            -- Attempt to migrate data from backup column
            UPDATE video_processing 
            SET status = UPPER(CAST(status_backup_v2025_08_11 AS VARCHAR(32)))
            WHERE status_backup_v2025_08_11 IS NOT NULL
            AND UPPER(CAST(status_backup_v2025_08_11 AS VARCHAR(32))) 
                IN ('PENDING', 'QUEUED', 'PROCESSING', 'COMPLETED', 'FAILED', 'CANCELLED', 'RETRYING');
            
            RAISE NOTICE 'Status column recreated with correct type. Old data backed up to status_backup_v2025_08_11';
        ELSE
            -- Column exists with correct type, just ensure constraint
            IF NOT EXISTS (
                SELECT 1
                FROM information_schema.constraint_column_usage
                WHERE table_name = 'video_processing'
                AND column_name = 'status'
                AND constraint_name LIKE '%status_check%'
            ) THEN
                ALTER TABLE video_processing
                ADD CONSTRAINT video_processing_status_check 
                CHECK (status IN ('PENDING', 'QUEUED', 'PROCESSING', 'COMPLETED', 'FAILED', 'CANCELLED', 'RETRYING'));
            END IF;
            RAISE NOTICE 'Status column already exists with correct type';
        END IF;
    ELSE
        -- Column doesn't exist, create it
        ALTER TABLE video_processing
        ADD COLUMN status VARCHAR(32) NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING', 'QUEUED', 'PROCESSING', 'COMPLETED', 'FAILED', 'CANCELLED', 'RETRYING'));
        RAISE NOTICE 'Status column created successfully';
    END IF;
END $$;

-- Create index on status column
CREATE INDEX IF NOT EXISTS idx_video_processing_status ON video_processing(status);
