-- Insert fixed campaigns data for development
INSERT INTO traffic_sources (name, source_id, weight, daily_limit, geo_targeting, active) VALUES
('Clickadoo Source 1', 'CLICKADOO_001', 1, 10000, 'US,UK,CA', true),
('Clickadoo Source 2', 'CLICKADOO_002', 1, 8000, 'US,DE,FR', true),
('Clickadoo Source 3', 'CLICKADOO_003', 1, 12000, 'US,AU,NZ', true);

INSERT INTO fixed_binom_campaigns (campaign_id, campaign_name, traffic_source_id, description, active) VALUES
('DEV_CAMPAIGN_001', 'Development Fixed Campaign 1', 1, 'First fixed campaign for development', true),
('DEV_CAMPAIGN_002', 'Development Fixed Campaign 2', 2, 'Second fixed campaign for development', true),
('DEV_CAMPAIGN_003', 'Development Fixed Campaign 3', 3, 'Third fixed campaign for development', true);
