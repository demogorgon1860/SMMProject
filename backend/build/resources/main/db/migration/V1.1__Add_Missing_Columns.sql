-- V1.1__Add_Missing_Columns.sql
-- Add missing final_url column to video_processing
ALTER TABLE video_processing ADD COLUMN IF NOT EXISTS final_url VARCHAR(500);

-- Add missing geo_targeting column to services
ALTER TABLE services ADD COLUMN IF NOT EXISTS geo_targeting VARCHAR(50) DEFAULT 'US';

-- Create conversion_coefficients table if not exists
CREATE TABLE IF NOT EXISTS conversion_coefficients (
    id BIGSERIAL PRIMARY KEY,
    service_id BIGINT NOT NULL,
    coefficient DECIMAL(4,2) NOT NULL,
    without_clip BOOLEAN NOT NULL,
    updated_by VARCHAR(100),
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    UNIQUE(service_id, without_clip)
);

-- Create fixed_binom_campaigns table if not exists
CREATE TABLE IF NOT EXISTS fixed_binom_campaigns (
    id BIGSERIAL PRIMARY KEY,
    campaign_id VARCHAR(100) UNIQUE NOT NULL,
    campaign_name VARCHAR(255) NOT NULL,
    geo_targeting VARCHAR(50) DEFAULT 'US',
    weight INTEGER DEFAULT 100,
    priority INTEGER DEFAULT 1,
    active BOOLEAN DEFAULT TRUE,
    description VARCHAR(500),
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

-- Insert default conversion coefficients
INSERT INTO conversion_coefficients (service_id, coefficient, without_clip, updated_by) 
SELECT id, 3.0, FALSE, 'SYSTEM' FROM services 
ON CONFLICT (service_id, without_clip) DO NOTHING;

INSERT INTO conversion_coefficients (service_id, coefficient, without_clip, updated_by) 
SELECT id, 4.0, TRUE, 'SYSTEM' FROM services 
ON CONFLICT (service_id, without_clip) DO NOTHING;
