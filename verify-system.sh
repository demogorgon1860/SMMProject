#!/bin/bash

# ==========================================
# SMM Panel System Verification Script
# ==========================================
# Verifies the complete system after cleanup
# Run this after docker-compose up

set -e

echo "=========================================="
echo "SMM Panel System Verification"
echo "=========================================="

# Color codes for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Configuration
POSTGRES_HOST=${POSTGRES_HOST:-localhost}
POSTGRES_PORT=${POSTGRES_PORT:-5432}
POSTGRES_DB=${POSTGRES_DB:-smm_panel}
POSTGRES_USER=${POSTGRES_USER:-smm_admin}
POSTGRES_PASSWORD=${POSTGRES_PASSWORD:-dev_password_123}
APP_HOST=${APP_HOST:-localhost}
APP_PORT=${APP_PORT:-8080}

echo ""
echo "Configuration:"
echo "  PostgreSQL: $POSTGRES_HOST:$POSTGRES_PORT/$POSTGRES_DB"
echo "  Application: $APP_HOST:$APP_PORT"
echo ""

# Function to check service health
check_service() {
    local service=$1
    local port=$2
    echo -n "Checking $service on port $port... "
    
    if nc -z localhost $port 2>/dev/null; then
        echo -e "${GREEN}✓ UP${NC}"
        return 0
    else
        echo -e "${RED}✗ DOWN${NC}"
        return 1
    fi
}

# Function to check HTTP endpoint
check_http() {
    local endpoint=$1
    local expected_status=$2
    echo -n "Checking HTTP $endpoint... "
    
    status=$(curl -s -o /dev/null -w "%{http_code}" http://$APP_HOST:$APP_PORT$endpoint)
    
    if [ "$status" = "$expected_status" ]; then
        echo -e "${GREEN}✓ $status${NC}"
        return 0
    else
        echo -e "${RED}✗ $status (expected $expected_status)${NC}"
        return 1
    fi
}

echo "=========================================="
echo "1. Infrastructure Services"
echo "=========================================="

check_service "PostgreSQL" 5432
check_service "Redis" 6379
check_service "Kafka" 9092
check_service "Spring Boot" 8080

echo ""
echo "=========================================="
echo "2. Application Health Checks"
echo "=========================================="

# Wait for app to be ready
echo "Waiting for application startup..."
for i in {1..30}; do
    if curl -s http://$APP_HOST:$APP_PORT/actuator/health > /dev/null 2>&1; then
        break
    fi
    sleep 2
done

# Check health endpoints
check_http "/actuator/health" 200
check_http "/actuator/health/db" 200
check_http "/actuator/health/redis" 200
check_http "/actuator/health/kafka" 200

echo ""
echo "=========================================="
echo "3. Database Schema Verification"
echo "=========================================="

echo "Checking Liquibase status..."
PGPASSWORD=$POSTGRES_PASSWORD psql -h $POSTGRES_HOST -p $POSTGRES_PORT -U $POSTGRES_USER -d $POSTGRES_DB -c "\dt databasechangelog" > /dev/null 2>&1
if [ $? -eq 0 ]; then
    echo -e "${GREEN}✓ Liquibase changelog table exists${NC}"
    
    # Check for recent migrations
    recent_count=$(PGPASSWORD=$POSTGRES_PASSWORD psql -h $POSTGRES_HOST -p $POSTGRES_PORT -U $POSTGRES_USER -d $POSTGRES_DB -t -c "SELECT COUNT(*) FROM databasechangelog WHERE dateexecuted > NOW() - INTERVAL '7 days'")
    echo "  Recent migrations (last 7 days): $recent_count"
    
    # Check for locks
    lock_status=$(PGPASSWORD=$POSTGRES_PASSWORD psql -h $POSTGRES_HOST -p $POSTGRES_PORT -U $POSTGRES_USER -d $POSTGRES_DB -t -c "SELECT locked FROM databasechangeloglock LIMIT 1")
    if [ "$lock_status" = " f" ]; then
        echo -e "${GREEN}✓ No database locks${NC}"
    else
        echo -e "${YELLOW}⚠ Database may be locked${NC}"
    fi
else
    echo -e "${RED}✗ Liquibase not initialized${NC}"
fi

echo ""
echo "Checking core tables..."
tables=("users" "services" "orders" "video_processing" "balance_transactions")
for table in "${tables[@]}"; do
    PGPASSWORD=$POSTGRES_PASSWORD psql -h $POSTGRES_HOST -p $POSTGRES_PORT -U $POSTGRES_USER -d $POSTGRES_DB -c "\dt $table" > /dev/null 2>&1
    if [ $? -eq 0 ]; then
        echo -e "  $table: ${GREEN}✓${NC}"
    else
        echo -e "  $table: ${RED}✗${NC}"
    fi
done

echo ""
echo "=========================================="
echo "4. API Endpoints"
echo "=========================================="

# Check API endpoints
check_http "/api/v1/services" 401  # Should require auth
check_http "/api/actuator/metrics" 200
check_http "/api/actuator/prometheus" 200

echo ""
echo "=========================================="
echo "5. Cache Verification"
echo "=========================================="

echo -n "Testing Redis connection... "
if redis-cli -h localhost ping > /dev/null 2>&1; then
    echo -e "${GREEN}✓ PONG${NC}"
    
    # Check for keys
    key_count=$(redis-cli -h localhost DBSIZE | cut -d' ' -f2)
    echo "  Cache keys: $key_count"
else
    echo -e "${RED}✗ Connection failed${NC}"
fi

echo ""
echo "=========================================="
echo "6. Kafka Topics"
echo "=========================================="

echo "Checking Kafka topics..."
if command -v kafka-topics.sh &> /dev/null; then
    topics=$(kafka-topics.sh --bootstrap-server localhost:9092 --list 2>/dev/null | grep "smm\." | wc -l)
    echo "  SMM topics found: $topics"
else
    echo "  ${YELLOW}Kafka CLI tools not found - install kafka-clients to verify topics${NC}"
fi

echo ""
echo "=========================================="
echo "VERIFICATION SUMMARY"
echo "=========================================="

# Count successes and failures
echo ""
echo -e "${GREEN}System verification complete!${NC}"
echo ""
echo "Next steps:"
echo "  1. Run: psql -h localhost -U smm_admin -d smm_panel < backend/src/main/resources/db/audit/verify-schema.sql"
echo "  2. Check application logs: docker-compose logs spring-boot-app"
echo "  3. Access metrics: http://localhost:8080/actuator/metrics"
echo "  4. View Prometheus metrics: http://localhost:8080/actuator/prometheus"
echo ""