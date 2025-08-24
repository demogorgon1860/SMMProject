-- Add actual Binom campaigns to the database
-- Clear any existing test data
DELETE FROM fixed_binom_campaigns;

-- Insert actual Binom campaigns from your screenshot
INSERT INTO fixed_binom_campaigns (campaign_id, campaign_name, geo_targeting, weight, priority, active, description, created_at) VALUES
('1', 'INDIA', 'IN', 100, 1, true, 'India traffic campaign', NOW()),
('2', 'Tier 3 + black list', 'TIER3', 100, 2, true, 'Tier 3 countries with blacklist filtering', NOW());

-- Verify the campaigns were added
SELECT * FROM fixed_binom_campaigns;