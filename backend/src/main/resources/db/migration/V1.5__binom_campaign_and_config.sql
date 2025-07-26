-- =====================================================
-- CRITICAL: Binom Campaign & Configuration Migration
-- =====================================================

-- 1. Create binom_configuration table (if not exists)
CREATE TABLE IF NOT EXISTS binom_configuration (
    id BIGSERIAL PRIMARY KEY,
    config_key VARCHAR(100) UNIQUE NOT NULL,
    config_value TEXT NOT NULL,
    description TEXT,
    updated_by VARCHAR(100),
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

-- 2. Insert/Update Binom tracker settings (edit these values!)
INSERT INTO binom_configuration (config_key, config_value, description, updated_by) VALUES
('binom_tracker_url', 'https://your-binom-domain.com', 'Main Binom tracker URL', 'SYSTEM'),
('binom_api_key', 'your-binom-api-key-here', 'Binom API authentication key', 'SYSTEM'),
('default_geo_targeting', 'US', 'Default geo targeting for campaigns', 'SYSTEM'),
('campaign_distribution_mode', 'EQUAL', 'How to distribute clicks: EQUAL, WEIGHTED, PRIORITY', 'SYSTEM')
ON CONFLICT (config_key) DO UPDATE SET 
    config_value = EXCLUDED.config_value,
    updated_at = NOW();

-- 3. Insert 3 sample fixed_binom_campaigns (edit campaign_id, campaign_name, traffic_source_id, geo_targeting as needed)
INSERT INTO fixed_binom_campaigns (campaign_id, campaign_name, traffic_source_id, geo_targeting, priority, weight, active, description) VALUES
('CAMP_SAMPLE_001', 'Sample Campaign 1', 1, 'US,UK,CA,AU', 1, 100, true, 'Primary traffic source for US/UK/CA/AU geo'),
('CAMP_SAMPLE_002', 'Sample Campaign 2', 2, 'US,DE,FR,IT', 2, 100, true, 'Secondary traffic source for US/DE/FR/IT geo'),
('CAMP_SAMPLE_003', 'Sample Campaign 3', 3, 'US,ES,NL,SE', 3, 100, true, 'Tertiary traffic source for US/ES/NL/SE geo')
ON CONFLICT (campaign_id) DO NOTHING;

-- 4. Create/Replace campaign_assignment_status view for monitoring
CREATE OR REPLACE VIEW campaign_assignment_status AS
SELECT 
    fc.campaign_id,
    fc.campaign_name,
    fc.geo_targeting,
    fc.priority,
    fc.active,
    COUNT(bc.id) as orders_assigned,
    SUM(bc.clicks_required) as total_clicks_assigned,
    SUM(bc.clicks_delivered) as total_clicks_delivered
FROM fixed_binom_campaigns fc
LEFT JOIN binom_campaigns bc ON fc.campaign_id = bc.campaign_id
WHERE fc.active = true
GROUP BY fc.id, fc.campaign_id, fc.campaign_name, fc.geo_targeting, fc.priority, fc.active
ORDER BY fc.priority;

-- 5. Verification query (run manually after migration):
-- SELECT * FROM campaign_assignment_status; 