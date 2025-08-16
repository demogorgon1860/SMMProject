#!/bin/bash

# Liquibase Operations Script
# Utility script for common Liquibase operations in SMM Panel

set -e

# Configuration
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"
LIQUIBASE_CONFIG="$PROJECT_ROOT/src/main/resources/liquibase.properties"

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Logging function
log() {
    echo -e "${GREEN}[$(date +'%Y-%m-%d %H:%M:%S')] $1${NC}"
}

warn() {
    echo -e "${YELLOW}[$(date +'%Y-%m-%d %H:%M:%S')] WARNING: $1${NC}"
}

error() {
    echo -e "${RED}[$(date +'%Y-%m-%d %H:%M:%S')] ERROR: $1${NC}"
    exit 1
}

# Function to validate environment
validate_environment() {
    if [ ! -f "$LIQUIBASE_CONFIG" ]; then
        error "Liquibase config not found: $LIQUIBASE_CONFIG"
    fi
    
    if ! command -v java &> /dev/null; then
        error "Java not found. Please install Java 17 or higher."
    fi
    
    log "Environment validation passed"
}

# Function to show usage
show_usage() {
    echo "Liquibase Operations Script for SMM Panel"
    echo ""
    echo "Usage: $0 [COMMAND] [OPTIONS]"
    echo ""
    echo "Commands:"
    echo "  status              Show migration status"
    echo "  update              Apply pending migrations"
    echo "  update-count [N]    Apply N pending migrations"
    echo "  rollback [TAG]      Rollback to specific tag"
    echo "  rollback-count [N]  Rollback N changesets"
    echo "  validate            Validate changelog"
    echo "  generate-docs       Generate migration documentation"
    echo "  diff                Generate diff against current database"
    echo "  snapshot            Create database snapshot"
    echo "  clear-checksums     Clear checksums (use with caution)"
    echo "  mark-next-ran       Mark next changeset as ran without executing"
    echo "  help                Show this help message"
    echo ""
    echo "Context Examples:"
    echo "  $0 update --contexts=dev"
    echo "  $0 update --contexts=prod,all"
    echo "  $0 rollback v1.0.0"
    echo ""
    echo "Environment Variables:"
    echo "  LIQUIBASE_CONTEXTS  Set default contexts (default: all)"
    echo "  DB_HOST            Database host (default: localhost)"
    echo "  DB_PORT            Database port (default: 5432)"
    echo "  DB_NAME            Database name (default: smm_panel)"
    echo "  DB_USER            Database user (default: smm_admin)"
    echo "  DB_PASSWORD        Database password"
}

# Function to run Liquibase command via Gradle
run_liquibase() {
    local cmd="$1"
    shift
    local args="$@"
    
    log "Running Liquibase command: $cmd $args"
    
    cd "$PROJECT_ROOT"
    ./gradlew liquibase${cmd^} $args
}

# Function to show migration status
show_status() {
    log "Checking migration status..."
    run_liquibase "status" "$@"
}

# Function to apply migrations
update_database() {
    log "Applying pending migrations..."
    run_liquibase "update" "$@"
    log "Database update completed successfully"
}

# Function to apply specific number of migrations
update_count() {
    local count="$1"
    shift
    
    if [ -z "$count" ]; then
        error "Please specify number of migrations to apply"
    fi
    
    log "Applying $count pending migrations..."
    run_liquibase "updateCount" "$count" "$@"
    log "Applied $count migrations successfully"
}

# Function to rollback to tag
rollback_to_tag() {
    local tag="$1"
    shift
    
    if [ -z "$tag" ]; then
        error "Please specify rollback tag"
    fi
    
    warn "Rolling back to tag: $tag"
    read -p "Are you sure? This will modify the database. (y/N): " -n 1 -r
    echo
    
    if [[ $REPLY =~ ^[Yy]$ ]]; then
        run_liquibase "rollback" "$tag" "$@"
        log "Rollback to $tag completed successfully"
    else
        log "Rollback cancelled"
    fi
}

# Function to rollback specific number of changesets
rollback_count() {
    local count="$1"
    shift
    
    if [ -z "$count" ]; then
        error "Please specify number of changesets to rollback"
    fi
    
    warn "Rolling back $count changesets"
    read -p "Are you sure? This will modify the database. (y/N): " -n 1 -r
    echo
    
    if [[ $REPLY =~ ^[Yy]$ ]]; then
        run_liquibase "rollbackCount" "$count" "$@"
        log "Rollback of $count changesets completed successfully"
    else
        log "Rollback cancelled"
    fi
}

# Function to validate changelog
validate_changelog() {
    log "Validating changelog..."
    run_liquibase "validate" "$@"
    log "Changelog validation passed"
}

# Function to generate documentation
generate_docs() {
    log "Generating migration documentation..."
    
    # Create docs directory if it doesn't exist
    mkdir -p "$PROJECT_ROOT/docs/database"
    
    # Generate various documentation formats
    run_liquibase "dbDoc" "--outputDirectory=$PROJECT_ROOT/docs/database" "$@"
    
    log "Documentation generated in: docs/database/"
}

# Function to generate diff
generate_diff() {
    log "Generating database diff..."
    
    # Create diff output directory
    mkdir -p "$PROJECT_ROOT/logs/liquibase"
    
    run_liquibase "diff" "--outputFile=$PROJECT_ROOT/logs/liquibase/diff-$(date +%Y%m%d-%H%M%S).txt" "$@"
    
    log "Diff generated in: logs/liquibase/"
}

# Function to create snapshot
create_snapshot() {
    log "Creating database snapshot..."
    
    # Create snapshot output directory
    mkdir -p "$PROJECT_ROOT/logs/liquibase"
    
    run_liquibase "snapshot" "--outputFile=$PROJECT_ROOT/logs/liquibase/snapshot-$(date +%Y%m%d-%H%M%S).json" "$@"
    
    log "Snapshot created in: logs/liquibase/"
}

# Function to clear checksums
clear_checksums() {
    warn "Clearing checksums - this should only be used if checksums are corrupted"
    read -p "Are you sure? (y/N): " -n 1 -r
    echo
    
    if [[ $REPLY =~ ^[Yy]$ ]]; then
        run_liquibase "clearChecksums" "$@"
        log "Checksums cleared successfully"
    else
        log "Clear checksums cancelled"
    fi
}

# Function to mark next changeset as ran
mark_next_ran() {
    warn "Marking next changeset as ran without executing"
    read -p "Are you sure? This should only be used for troubleshooting. (y/N): " -n 1 -r
    echo
    
    if [[ $REPLY =~ ^[Yy]$ ]]; then
        run_liquibase "markNextChangesetRan" "$@"
        log "Next changeset marked as ran"
    else
        log "Mark next ran cancelled"
    fi
}

# Main script logic
main() {
    validate_environment
    
    local command="$1"
    shift
    
    case "$command" in
        "status")
            show_status "$@"
            ;;
        "update")
            update_database "$@"
            ;;
        "update-count")
            update_count "$@"
            ;;
        "rollback")
            rollback_to_tag "$@"
            ;;
        "rollback-count")
            rollback_count "$@"
            ;;
        "validate")
            validate_changelog "$@"
            ;;
        "generate-docs")
            generate_docs "$@"
            ;;
        "diff")
            generate_diff "$@"
            ;;
        "snapshot")
            create_snapshot "$@"
            ;;
        "clear-checksums")
            clear_checksums "$@"
            ;;
        "mark-next-ran")
            mark_next_ran "$@"
            ;;
        "help"|"--help"|"-h")
            show_usage
            ;;
        *)
            if [ -z "$command" ]; then
                show_usage
            else
                error "Unknown command: $command"
            fi
            ;;
    esac
}

# Run main function with all arguments
main "$@"