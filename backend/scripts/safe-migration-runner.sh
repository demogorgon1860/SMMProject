#!/bin/bash
# ============================================================================
# Safe Database Migration Runner Script
# ============================================================================
# Purpose: Safely apply database migrations with validation and rollback
# Usage: ./safe-migration-runner.sh [environment]
# Requirements: PostgreSQL client, Liquibase, Docker (optional)
# ============================================================================

set -euo pipefail  # Exit on error, undefined variables, and pipe failures

# Configuration
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"
TIMESTAMP=$(date +%Y%m%d_%H%M%S)
LOG_FILE="${PROJECT_ROOT}/logs/migration_${TIMESTAMP}.log"
BACKUP_DIR="${PROJECT_ROOT}/backups"

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Environment configuration
ENVIRONMENT="${1:-dev}"
case "$ENVIRONMENT" in
    dev)
        DB_HOST="${DB_HOST:-localhost}"
        DB_PORT="${DB_PORT:-5432}"
        DB_NAME="${DB_NAME:-smm_panel}"
        DB_USER="${DB_USER:-smm_admin}"
        LIQUIBASE_CONTEXTS="dev"
        ;;
    stage)
        DB_HOST="${DB_HOST:-localhost}"
        DB_PORT="${DB_PORT:-5432}"
        DB_NAME="${DB_NAME:-smm_panel_stage}"
        DB_USER="${DB_USER:-smm_admin}"
        LIQUIBASE_CONTEXTS="stage"
        ;;
    prod)
        DB_HOST="${DB_HOST:-localhost}"
        DB_PORT="${DB_PORT:-5432}"
        DB_NAME="${DB_NAME:-smm_panel_prod}"
        DB_USER="${DB_USER:-smm_admin}"
        LIQUIBASE_CONTEXTS="prod"
        # Production requires explicit confirmation
        echo -e "${RED}WARNING: You are about to run migrations on PRODUCTION!${NC}"
        read -p "Type 'PRODUCTION' to confirm: " confirmation
        if [ "$confirmation" != "PRODUCTION" ]; then
            echo -e "${RED}Migration cancelled.${NC}"
            exit 1
        fi
        ;;
    *)
        echo -e "${RED}Invalid environment: $ENVIRONMENT${NC}"
        echo "Usage: $0 [dev|stage|prod]"
        exit 1
        ;;
esac

# Create necessary directories
mkdir -p "${PROJECT_ROOT}/logs"
mkdir -p "$BACKUP_DIR"

# Logging function
log() {
    echo -e "$1" | tee -a "$LOG_FILE"
}

# Error handler
error_handler() {
    local line_no=$1
    log "${RED}✗ Error occurred in script at line: $line_no${NC}"
    log "${RED}✗ Migration failed! Check log: $LOG_FILE${NC}"
    
    # Attempt to show recent errors from log
    if [ -f "$LOG_FILE" ]; then
        log "\n${YELLOW}Recent errors:${NC}"
        tail -n 20 "$LOG_FILE" | grep -E "ERROR|FATAL|FAIL" || true
    fi
    
    exit 1
}

trap 'error_handler $LINENO' ERR

# Function to check prerequisites
check_prerequisites() {
    log "${BLUE}Checking prerequisites...${NC}"
    
    # Check PostgreSQL client
    if ! command -v psql &> /dev/null; then
        log "${RED}✗ PostgreSQL client (psql) not found${NC}"
        exit 1
    fi
    
    # Check Liquibase
    if ! command -v liquibase &> /dev/null; then
        log "${YELLOW}⚠ Liquibase not found in PATH. Checking Gradle wrapper...${NC}"
        if [ ! -f "${PROJECT_ROOT}/gradlew" ]; then
            log "${RED}✗ Neither Liquibase nor Gradle wrapper found${NC}"
            exit 1
        fi
        USE_GRADLE=true
    else
        USE_GRADLE=false
    fi
    
    # Check database connectivity
    log "Testing database connection..."
    if PGPASSWORD="${DB_PASSWORD}" psql -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" -d "$DB_NAME" -c "SELECT 1" &> /dev/null; then
        log "${GREEN}✓ Database connection successful${NC}"
    else
        log "${RED}✗ Cannot connect to database${NC}"
        exit 1
    fi
}

# Function to create database backup
create_backup() {
    log "${BLUE}Creating database backup...${NC}"
    
    local backup_file="${BACKUP_DIR}/backup_${DB_NAME}_${TIMESTAMP}.sql"
    
    if PGPASSWORD="${DB_PASSWORD}" pg_dump \
        -h "$DB_HOST" \
        -p "$DB_PORT" \
        -U "$DB_USER" \
        -d "$DB_NAME" \
        -f "$backup_file" \
        --verbose \
        --no-owner \
        --no-acl \
        2>> "$LOG_FILE"; then
        
        # Compress backup
        gzip "$backup_file"
        log "${GREEN}✓ Backup created: ${backup_file}.gz${NC}"
        
        # Keep only last 10 backups
        ls -t "${BACKUP_DIR}"/backup_*.gz 2>/dev/null | tail -n +11 | xargs -r rm
    else
        log "${RED}✗ Backup failed${NC}"
        exit 1
    fi
}

# Function to validate current schema
validate_schema() {
    log "${BLUE}Validating current schema...${NC}"
    
    # Run validation script
    if PGPASSWORD="${DB_PASSWORD}" psql \
        -h "$DB_HOST" \
        -p "$DB_PORT" \
        -U "$DB_USER" \
        -d "$DB_NAME" \
        -f "${PROJECT_ROOT}/src/main/resources/db/migration-validation.sql" \
        -o "${PROJECT_ROOT}/logs/validation_${TIMESTAMP}.txt" \
        2>> "$LOG_FILE"; then
        
        log "${GREEN}✓ Schema validation completed${NC}"
        
        # Check for critical issues
        if grep -E "✗ FAIL" "${PROJECT_ROOT}/logs/validation_${TIMESTAMP}.txt" > /dev/null; then
            log "${RED}✗ Critical validation issues found!${NC}"
            log "Check validation report: ${PROJECT_ROOT}/logs/validation_${TIMESTAMP}.txt"
            read -p "Continue anyway? (y/N): " continue_choice
            if [ "$continue_choice" != "y" ]; then
                exit 1
            fi
        fi
    else
        log "${YELLOW}⚠ Schema validation had warnings${NC}"
    fi
}

# Function to run Liquibase status
check_migration_status() {
    log "${BLUE}Checking migration status...${NC}"
    
    if [ "$USE_GRADLE" = true ]; then
        cd "$PROJECT_ROOT"
        ./gradlew liquibaseStatus \
            -PrunList="$LIQUIBASE_CONTEXTS" \
            2>&1 | tee -a "$LOG_FILE"
    else
        liquibase \
            --changeLogFile="${PROJECT_ROOT}/src/main/resources/db/liquibase/db.changelog-master.xml" \
            --url="jdbc:postgresql://${DB_HOST}:${DB_PORT}/${DB_NAME}" \
            --username="$DB_USER" \
            --password="$DB_PASSWORD" \
            --contexts="$LIQUIBASE_CONTEXTS" \
            status \
            2>&1 | tee -a "$LOG_FILE"
    fi
}

# Function to run migrations with dry run option
run_migrations() {
    log "${BLUE}Running database migrations...${NC}"
    
    # First, do a dry run
    log "Performing dry run..."
    if [ "$USE_GRADLE" = true ]; then
        cd "$PROJECT_ROOT"
        ./gradlew liquibaseUpdateSQL \
            -PrunList="$LIQUIBASE_CONTEXTS" \
            > "${PROJECT_ROOT}/logs/migration_dryrun_${TIMESTAMP}.sql" \
            2>&1
    else
        liquibase \
            --changeLogFile="${PROJECT_ROOT}/src/main/resources/db/liquibase/db.changelog-master.xml" \
            --url="jdbc:postgresql://${DB_HOST}:${DB_PORT}/${DB_NAME}" \
            --username="$DB_USER" \
            --password="$DB_PASSWORD" \
            --contexts="$LIQUIBASE_CONTEXTS" \
            updateSQL \
            > "${PROJECT_ROOT}/logs/migration_dryrun_${TIMESTAMP}.sql" \
            2>&1
    fi
    
    log "${GREEN}✓ Dry run completed. SQL preview: ${PROJECT_ROOT}/logs/migration_dryrun_${TIMESTAMP}.sql${NC}"
    
    # Show summary of changes
    log "\n${YELLOW}Migration Summary:${NC}"
    grep -E "CREATE|ALTER|DROP|INSERT|UPDATE|DELETE" "${PROJECT_ROOT}/logs/migration_dryrun_${TIMESTAMP}.sql" | \
        awk '{print $1}' | sort | uniq -c || true
    
    # Confirm before applying
    if [ "$ENVIRONMENT" != "dev" ]; then
        read -p "Apply these migrations? (y/N): " apply_choice
        if [ "$apply_choice" != "y" ]; then
            log "${YELLOW}Migration cancelled by user${NC}"
            exit 0
        fi
    fi
    
    # Apply migrations
    log "Applying migrations..."
    if [ "$USE_GRADLE" = true ]; then
        cd "$PROJECT_ROOT"
        ./gradlew liquibaseUpdate \
            -PrunList="$LIQUIBASE_CONTEXTS" \
            2>&1 | tee -a "$LOG_FILE"
    else
        liquibase \
            --changeLogFile="${PROJECT_ROOT}/src/main/resources/db/liquibase/db.changelog-master.xml" \
            --url="jdbc:postgresql://${DB_HOST}:${DB_PORT}/${DB_NAME}" \
            --username="$DB_USER" \
            --password="$DB_PASSWORD" \
            --contexts="$LIQUIBASE_CONTEXTS" \
            update \
            2>&1 | tee -a "$LOG_FILE"
    fi
    
    if [ $? -eq 0 ]; then
        log "${GREEN}✓ Migrations applied successfully${NC}"
    else
        log "${RED}✗ Migration failed${NC}"
        exit 1
    fi
}

# Function to verify migration success
verify_migration() {
    log "${BLUE}Verifying migration success...${NC}"
    
    # Check for failed changesets
    local failed_count=$(PGPASSWORD="${DB_PASSWORD}" psql \
        -h "$DB_HOST" \
        -p "$DB_PORT" \
        -U "$DB_USER" \
        -d "$DB_NAME" \
        -t \
        -c "SELECT COUNT(*) FROM databasechangelog WHERE exectype = 'FAILED'" \
        2>/dev/null || echo "0")
    
    if [ "$failed_count" -gt 0 ]; then
        log "${RED}✗ Found $failed_count failed changesets${NC}"
        exit 1
    fi
    
    # Run post-migration validation
    validate_schema
    
    log "${GREEN}✓ Migration verification completed${NC}"
}

# Function to generate migration report
generate_report() {
    log "${BLUE}Generating migration report...${NC}"
    
    local report_file="${PROJECT_ROOT}/logs/migration_report_${TIMESTAMP}.txt"
    
    {
        echo "========================================"
        echo "Database Migration Report"
        echo "========================================"
        echo "Timestamp: $(date)"
        echo "Environment: $ENVIRONMENT"
        echo "Database: $DB_NAME"
        echo "Host: $DB_HOST:$DB_PORT"
        echo "User: $DB_USER"
        echo "Contexts: $LIQUIBASE_CONTEXTS"
        echo ""
        echo "Applied Changesets:"
        echo "-------------------"
        
        PGPASSWORD="${DB_PASSWORD}" psql \
            -h "$DB_HOST" \
            -p "$DB_PORT" \
            -U "$DB_USER" \
            -d "$DB_NAME" \
            -c "SELECT id, author, filename, dateexecuted, orderexecuted, exectype 
                FROM databasechangelog 
                WHERE dateexecuted >= CURRENT_DATE 
                ORDER BY orderexecuted DESC 
                LIMIT 20"
        
        echo ""
        echo "Schema Statistics:"
        echo "-----------------"
        
        PGPASSWORD="${DB_PASSWORD}" psql \
            -h "$DB_HOST" \
            -p "$DB_PORT" \
            -U "$DB_USER" \
            -d "$DB_NAME" \
            -c "SELECT 
                    (SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = 'public') as tables,
                    (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = 'public') as columns,
                    (SELECT COUNT(*) FROM pg_indexes WHERE schemaname = 'public') as indexes,
                    (SELECT COUNT(*) FROM information_schema.table_constraints WHERE table_schema = 'public') as constraints"
    } > "$report_file"
    
    log "${GREEN}✓ Report generated: $report_file${NC}"
}

# Main execution
main() {
    log "========================================"
    log "Database Migration Runner"
    log "========================================"
    log "Environment: $ENVIRONMENT"
    log "Timestamp: $TIMESTAMP"
    log "Log file: $LOG_FILE"
    log ""
    
    # Ask for database password if not set
    if [ -z "${DB_PASSWORD:-}" ]; then
        read -sp "Enter database password for $DB_USER: " DB_PASSWORD
        echo
        export DB_PASSWORD
    fi
    
    # Run migration steps
    check_prerequisites
    create_backup
    validate_schema
    check_migration_status
    run_migrations
    verify_migration
    generate_report
    
    log ""
    log "${GREEN}✓ Migration completed successfully!${NC}"
    log "Backup: ${BACKUP_DIR}/backup_${DB_NAME}_${TIMESTAMP}.sql.gz"
    log "Log: $LOG_FILE"
}

# Run main function
main "$@"