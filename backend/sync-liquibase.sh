#!/bin/bash

# Liquibase Database Sync Script
# This script safely syncs an existing database with Liquibase
# It marks all existing schema as already applied without recreating tables

set -e

echo "=========================================="
echo "Liquibase Database Sync Script"
echo "=========================================="

# Configuration
DB_HOST="localhost"
DB_PORT="5432"
DB_NAME="smm_panel"
DB_USER="smm_admin"
DB_PASSWORD="postgres123"
JDBC_URL="jdbc:postgresql://${DB_HOST}:${DB_PORT}/${DB_NAME}"

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

echo -e "${YELLOW}Database: ${DB_NAME}${NC}"
echo -e "${YELLOW}User: ${DB_USER}${NC}"
echo ""

# Step 1: Check if Liquibase tables exist
echo -e "${GREEN}Step 1: Checking for existing Liquibase tables...${NC}"
TABLES_EXIST=$(psql -h $DB_HOST -p $DB_PORT -U $DB_USER -d $DB_NAME -t -c "SELECT COUNT(*) FROM information_schema.tables WHERE table_name IN ('databasechangelog', 'databasechangeloglock');" 2>/dev/null || echo "0")

if [ "$TABLES_EXIST" -eq "2" ]; then
    echo -e "${YELLOW}Liquibase tables already exist. Checking if baseline is needed...${NC}"
    
    # Check if there are any executed changeSets
    CHANGESETS_COUNT=$(psql -h $DB_HOST -p $DB_PORT -U $DB_USER -d $DB_NAME -t -c "SELECT COUNT(*) FROM databasechangelog;" 2>/dev/null || echo "0")
    
    if [ "$CHANGESETS_COUNT" -gt "0" ]; then
        echo -e "${YELLOW}Found $CHANGESETS_COUNT existing changeSets. Skipping baseline.${NC}"
        exit 0
    fi
fi

# Step 2: Run Liquibase changelog-sync to mark all changeSets as executed
echo -e "${GREEN}Step 2: Syncing Liquibase with existing database schema...${NC}"
cd backend

# Use Gradle to run Liquibase changelog-sync
./gradlew liquibaseChangelogSync \
    -Pliquibase.url="${JDBC_URL}" \
    -Pliquibase.username="${DB_USER}" \
    -Pliquibase.password="${DB_PASSWORD}" \
    -Pliquibase.changeLogFile="src/main/resources/db/changelog/db.changelog-master.xml" \
    -Pliquibase.contexts="dev"

if [ $? -eq 0 ]; then
    echo -e "${GREEN}✓ Liquibase sync completed successfully!${NC}"
else
    echo -e "${RED}✗ Liquibase sync failed!${NC}"
    exit 1
fi

# Step 3: Verify the sync
echo -e "${GREEN}Step 3: Verifying sync...${NC}"
SYNCED_COUNT=$(psql -h $DB_HOST -p $DB_PORT -U $DB_USER -d $DB_NAME -t -c "SELECT COUNT(*) FROM databasechangelog;" 2>/dev/null || echo "0")
echo -e "${GREEN}✓ Marked $SYNCED_COUNT changeSets as already executed${NC}"

# Step 4: Display current status
echo ""
echo -e "${GREEN}Step 4: Current Liquibase status:${NC}"
./gradlew liquibaseStatus \
    -Pliquibase.url="${JDBC_URL}" \
    -Pliquibase.username="${DB_USER}" \
    -Pliquibase.password="${DB_PASSWORD}" \
    -Pliquibase.changeLogFile="src/main/resources/db/changelog/db.changelog-master.xml" \
    -Pliquibase.contexts="dev"

echo ""
echo -e "${GREEN}=========================================="
echo -e "Liquibase sync completed successfully!"
echo -e "==========================================${NC}"
echo ""
echo "Next steps:"
echo "1. Review the databasechangelog table to confirm all changeSets are marked as executed"
echo "2. Enable Liquibase in your application.yml or .env file"
echo "3. Future schema changes should be added as new changeSets"