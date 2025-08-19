-- ==========================================
-- Liquibase Manual Sync Script
-- ==========================================
-- This script manually creates Liquibase tracking tables and marks all changeSets as executed
-- Use this if you cannot run Liquibase changelog-sync command
-- Run this script ONCE on your existing database before enabling Liquibase

-- Step 1: Create Liquibase tracking tables
-- ==========================================

-- Create DATABASECHANGELOGLOCK table
CREATE TABLE IF NOT EXISTS databasechangeloglock (
    id INTEGER NOT NULL,
    locked BOOLEAN NOT NULL,
    lockgranted TIMESTAMP,
    lockedby VARCHAR(255),
    CONSTRAINT pk_databasechangeloglock PRIMARY KEY (id)
);

-- Initialize lock table with one row
INSERT INTO databasechangeloglock (id, locked) 
VALUES (1, FALSE) 
ON CONFLICT (id) DO NOTHING;

-- Create DATABASECHANGELOG table
CREATE TABLE IF NOT EXISTS databasechangelog (
    id VARCHAR(255) NOT NULL,
    author VARCHAR(255) NOT NULL,
    filename VARCHAR(255) NOT NULL,
    dateexecuted TIMESTAMP NOT NULL,
    orderexecuted INTEGER NOT NULL,
    exectype VARCHAR(10) NOT NULL,
    md5sum VARCHAR(35),
    description VARCHAR(255),
    comments VARCHAR(255),
    tag VARCHAR(255),
    liquibase VARCHAR(20),
    contexts VARCHAR(255),
    labels VARCHAR(255),
    deployment_id VARCHAR(10)
);

-- Create index for better performance
CREATE INDEX IF NOT EXISTS idx_databasechangelog_filename ON databasechangelog(filename);

-- Step 2: Mark all changeSets from db.changelog-master.xml as executed
-- ==========================================
-- These entries tell Liquibase that the schema already exists

-- Baseline tag
INSERT INTO databasechangelog (id, author, filename, dateexecuted, orderexecuted, exectype, description, liquibase)
VALUES ('baseline-1', 'system', 'db/changelog/db.changelog-baseline.xml', NOW(), 1, 'EXECUTED', 'tagDatabase', '4.20.0')
ON CONFLICT DO NOTHING;

-- Extensions and Types
INSERT INTO databasechangelog (id, author, filename, dateexecuted, orderexecuted, exectype, description, liquibase, contexts)
VALUES 
('1', 'system', 'db/changelog/db.changelog-master.xml', NOW(), 2, 'EXECUTED', 'sql', '4.20.0', NULL),
('2', 'system', 'db/changelog/db.changelog-master.xml', NOW(), 3, 'EXECUTED', 'sql', '4.20.0', NULL)
ON CONFLICT DO NOTHING;

-- Core Tables (3-30)
DO $$
DECLARE
    i INTEGER;
BEGIN
    FOR i IN 3..30 LOOP
        INSERT INTO databasechangelog (id, author, filename, dateexecuted, orderexecuted, exectype, description, liquibase, contexts)
        VALUES (i::TEXT, 'system', 'db/changelog/db.changelog-master.xml', NOW(), i + 1, 'EXECUTED', 'auto-generated', '4.20.0', NULL)
        ON CONFLICT DO NOTHING;
    END LOOP;
END $$;

-- Development context changeSets (31-32)
INSERT INTO databasechangelog (id, author, filename, dateexecuted, orderexecuted, exectype, description, liquibase, contexts)
VALUES 
('31', 'system', 'db/changelog/db.changelog-master.xml', NOW(), 32, 'EXECUTED', 'insert', '4.20.0', 'dev'),
('32', 'system', 'db/changelog/db.changelog-master.xml', NOW(), 33, 'EXECUTED', 'insert', '4.20.0', 'dev')
ON CONFLICT DO NOTHING;

-- Production context changeSet (33)
INSERT INTO databasechangelog (id, author, filename, dateexecuted, orderexecuted, exectype, description, liquibase, contexts)
VALUES ('33', 'system', 'db/changelog/db.changelog-master.xml', NOW(), 34, 'EXECUTED', 'sql', '4.20.0', 'prod')
ON CONFLICT DO NOTHING;

-- Migration changeSets from db.changelog-migrations.xml (100-110)
DO $$
DECLARE
    i INTEGER;
    order_num INTEGER := 35;
BEGIN
    FOR i IN 100..110 LOOP
        INSERT INTO databasechangelog (id, author, filename, dateexecuted, orderexecuted, exectype, description, liquibase, contexts)
        VALUES (i::TEXT, 'migration', 'db/changelog/db.changelog-migrations.xml', NOW(), order_num, 'EXECUTED', 'auto-generated', '4.20.0', NULL)
        ON CONFLICT DO NOTHING;
        order_num := order_num + 1;
    END LOOP;
END $$;

-- Step 3: Verify the sync
-- ==========================================
SELECT 'Liquibase sync completed!' AS status;
SELECT COUNT(*) AS "Total ChangeSets Marked as Executed" FROM databasechangelog;

-- Display summary
SELECT 
    filename,
    COUNT(*) as changesets,
    MIN(dateexecuted) as first_executed,
    MAX(dateexecuted) as last_executed
FROM databasechangelog
GROUP BY filename
ORDER BY MIN(orderexecuted);

-- Step 4: Grant permissions if needed
-- ==========================================
-- Uncomment if your application user needs permissions
-- GRANT ALL ON databasechangelog TO smm_admin;
-- GRANT ALL ON databasechangeloglock TO smm_admin;

-- Step 5: Final verification
-- ==========================================
-- This query shows what Liquibase thinks is the current state
SELECT 
    'Database is now synced with Liquibase.' AS message,
    'You can now enable Liquibase in your application.' AS next_step
FROM databasechangelog
LIMIT 1;