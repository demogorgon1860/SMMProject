-- Таблица для хранения конфигурации фиксированных кампаний
CREATE TABLE IF NOT EXISTS fixed_binom_campaigns (
    id BIGSERIAL PRIMARY KEY,
    campaign_id VARCHAR(100) UNIQUE NOT NULL, -- ID кампании в Binom (созданной вручную)
    campaign_name VARCHAR(255) NOT NULL,
    traffic_source_id BIGINT REFERENCES traffic_sources(id),
    description TEXT,
    active BOOLEAN DEFAULT TRUE,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

-- Вставляем 3 фиксированные кампании (пример)
INSERT INTO fixed_binom_campaigns (campaign_id, campaign_name, traffic_source_id, description) VALUES
('CAMPAIGN_001', 'Fixed Campaign Source 1', 1, 'Primary traffic source'),
('CAMPAIGN_002', 'Fixed Campaign Source 2', 2, 'Secondary traffic source'),
('CAMPAIGN_003', 'Fixed Campaign Source 3', 3, 'Tertiary traffic source')
ON CONFLICT (campaign_id) DO NOTHING;

-- Обновляем существующую схему для связи с фиксированными кампаниями
ALTER TABLE binom_campaigns 
ADD COLUMN IF NOT EXISTS fixed_campaign_id BIGINT REFERENCES fixed_binom_campaigns(id);

-- Индекс для быстрого поиска активных фиксированных кампаний
CREATE INDEX IF NOT EXISTS idx_fixed_campaigns_active ON fixed_binom_campaigns(active) WHERE active = TRUE;
