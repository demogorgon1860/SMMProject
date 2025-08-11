-- Drop existing status column if it exists with wrong type
ALTER TABLE video_processing DROP COLUMN IF EXISTS status;

-- Add status column with proper enum type
ALTER TABLE video_processing
ADD COLUMN status VARCHAR(32) NOT NULL DEFAULT 'PENDING'
CHECK (status IN ('PENDING', 'QUEUED', 'PROCESSING', 'COMPLETED', 'FAILED', 'CANCELLED', 'RETRYING'));

-- Create index on status column
CREATE INDEX IF NOT EXISTS idx_video_processing_status ON video_processing(status);
