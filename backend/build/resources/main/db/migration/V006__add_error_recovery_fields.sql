-- Migration: Add Error Recovery Fields to Orders Table
-- Description: Adds comprehensive error tracking and retry management fields
-- Version: V006
-- Date: 2024-01-01

-- Add error recovery tracking fields to orders table
ALTER TABLE orders 
ADD COLUMN retry_count INTEGER DEFAULT 0 NOT NULL,
ADD COLUMN max_retries INTEGER DEFAULT 3 NOT NULL,
ADD COLUMN last_error_type VARCHAR(100),
ADD COLUMN last_retry_at TIMESTAMP,
ADD COLUMN next_retry_at TIMESTAMP,
ADD COLUMN failure_reason TEXT,
ADD COLUMN error_stack_trace TEXT,
ADD COLUMN failed_phase VARCHAR(50),
ADD COLUMN is_manually_failed BOOLEAN DEFAULT FALSE NOT NULL,
ADD COLUMN operator_notes TEXT;

-- Create indexes for error recovery queries
CREATE INDEX idx_orders_retry_count ON orders(retry_count);
CREATE INDEX idx_orders_next_retry_at ON orders(next_retry_at);
CREATE INDEX idx_orders_last_error_type ON orders(last_error_type);
CREATE INDEX idx_orders_failed_phase ON orders(failed_phase);
CREATE INDEX idx_orders_is_manually_failed ON orders(is_manually_failed);

-- Create composite index for finding orders ready for retry
CREATE INDEX idx_orders_ready_for_retry ON orders(next_retry_at, is_manually_failed, retry_count) 
WHERE next_retry_at IS NOT NULL AND is_manually_failed = FALSE;

-- Create composite index for dead letter queue queries
CREATE INDEX idx_orders_dead_letter_queue ON orders(is_manually_failed, retry_count, max_retries, status) 
WHERE status = 'HOLDING';

-- Add comments for documentation
COMMENT ON COLUMN orders.retry_count IS 'Number of retry attempts made for this order';
COMMENT ON COLUMN orders.max_retries IS 'Maximum number of retry attempts allowed';
COMMENT ON COLUMN orders.last_error_type IS 'Type/class of the last error encountered';
COMMENT ON COLUMN orders.last_retry_at IS 'Timestamp of the last retry attempt';
COMMENT ON COLUMN orders.next_retry_at IS 'Scheduled time for next retry attempt';
COMMENT ON COLUMN orders.failure_reason IS 'Detailed reason for order failure';
COMMENT ON COLUMN orders.error_stack_trace IS 'Stack trace of the last error for debugging';
COMMENT ON COLUMN orders.failed_phase IS 'Processing phase where the order failed';
COMMENT ON COLUMN orders.is_manually_failed IS 'Flag indicating if order was manually marked as failed';
COMMENT ON COLUMN orders.operator_notes IS 'Notes added by operators during manual interventions';

-- Update existing orders to have default values
UPDATE orders 
SET retry_count = 0, 
    max_retries = 3, 
    is_manually_failed = FALSE 
WHERE retry_count IS NULL OR max_retries IS NULL OR is_manually_failed IS NULL;

-- Create view for error recovery dashboard
CREATE OR REPLACE VIEW error_recovery_dashboard AS
SELECT 
    COUNT(*) FILTER (WHERE error_message IS NOT NULL) as total_failed_orders,
    COUNT(*) FILTER (WHERE error_message IS NOT NULL AND updated_at >= NOW() - INTERVAL '24 hours') as failed_last_24_hours,
    COUNT(*) FILTER (WHERE error_message IS NOT NULL AND updated_at >= NOW() - INTERVAL '7 days') as failed_last_week,
    COUNT(*) FILTER (WHERE (is_manually_failed = TRUE OR retry_count >= max_retries) AND status = 'HOLDING') as dead_letter_queue_count,
    COUNT(*) FILTER (WHERE next_retry_at IS NOT NULL AND next_retry_at > NOW() AND is_manually_failed = FALSE AND retry_count < max_retries) as pending_retries,
    AVG(retry_count) FILTER (WHERE retry_count > 0) as avg_retry_count,
    MAX(retry_count) as max_retry_count_seen
FROM orders;

-- Create view for error type analysis
CREATE OR REPLACE VIEW error_type_analysis AS
SELECT 
    last_error_type,
    COUNT(*) as error_count,
    ROUND(COUNT(*) * 100.0 / SUM(COUNT(*)) OVER (), 2) as percentage,
    AVG(retry_count) as avg_retries,
    COUNT(*) FILTER (WHERE is_manually_failed = TRUE OR retry_count >= max_retries) as permanent_failures
FROM orders 
WHERE last_error_type IS NOT NULL
GROUP BY last_error_type
ORDER BY error_count DESC;

-- Create view for failed phase analysis
CREATE OR REPLACE VIEW failed_phase_analysis AS
SELECT 
    failed_phase,
    COUNT(*) as failure_count,
    ROUND(COUNT(*) * 100.0 / SUM(COUNT(*)) OVER (), 2) as percentage,
    AVG(retry_count) as avg_retries_before_failure
FROM orders 
WHERE failed_phase IS NOT NULL
GROUP BY failed_phase
ORDER BY failure_count DESC;