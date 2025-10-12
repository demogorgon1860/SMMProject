#!/bin/bash

echo "======================================"
echo "🔍 COMPREHENSIVE STARTUP DIAGNOSTICS"
echo "======================================"
echo ""

# Color codes for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Infrastructure Check
echo "1. INFRASTRUCTURE STATUS"
echo "------------------------"
docker-compose ps | grep -v "Up" | grep -v "State" && echo -e "${RED}⚠ Some containers are not running${NC}" || echo -e "${GREEN}✓ All containers running${NC}"
echo ""

# Connection Tests
echo "2. CONNECTION TESTS"
echo "-------------------"
timeout 2 docker-compose exec postgres pg_isready -U smm_admin -d smm_panel 2>/dev/null && echo -e "${GREEN}✓ Postgres OK${NC}" || echo -e "${RED}✗ Postgres FAIL${NC}"
timeout 2 docker-compose exec redis redis-cli ping 2>/dev/null | grep -q PONG && echo -e "${GREEN}✓ Redis OK${NC}" || echo -e "${RED}✗ Redis FAIL${NC}"
timeout 2 docker-compose exec kafka kafka-metadata --list --bootstrap-server localhost:9092 2>/dev/null >/dev/null && echo -e "${GREEN}✓ Kafka OK${NC}" || echo -e "${RED}✗ Kafka FAIL${NC}"
echo ""

# Liquibase Check
echo "3. LIQUIBASE VALIDATION"
echo "-----------------------"
cd backend 2>/dev/null
./gradlew updateSql 2>&1 | grep -q "checksum" && echo -e "${RED}✗ Liquibase checksum mismatch detected${NC}" || echo -e "${GREEN}✓ Liquibase validation OK${NC}"
./gradlew updateSql 2>&1 | grep "was:" | head -1
cd .. 2>/dev/null
echo ""

# Build Check
echo "4. COMPILATION CHECK"
echo "--------------------"
cd backend 2>/dev/null
./gradlew clean assemble --quiet 2>&1 | grep -q "BUILD SUCCESSFUL" && echo -e "${GREEN}✓ Build successful${NC}" || echo -e "${RED}✗ Build failed${NC}"
cd .. 2>/dev/null
echo ""

# Application Startup Test
echo "5. APPLICATION STARTUP TEST"
echo "---------------------------"
cd backend 2>/dev/null
timeout 60 ./gradlew bootRun --args='--spring.profiles.active=dev' 2>&1 | tee .startup-test.log | grep -q "Started SmmPanelApplication" &
PID=$!
sleep 40
if ps -p $PID > /dev/null 2>&1; then
    kill $PID 2>/dev/null
    echo -e "${YELLOW}⚠ Application startup timeout - checking for errors${NC}"
else
    grep -q "Started SmmPanelApplication" .startup-test.log && echo -e "${GREEN}✓ Application started successfully${NC}" || echo -e "${RED}✗ Application failed to start${NC}"
fi
cd .. 2>/dev/null
echo ""

# Error Summary
echo "6. ERROR SUMMARY"
echo "----------------"
cd backend 2>/dev/null
if [ -f .startup-test.log ]; then
    ERRORS=$(grep -E "ERROR|Exception|Failed|Cannot|Unable|refused" .startup-test.log | wc -l)
    if [ $ERRORS -gt 0 ]; then
        echo -e "${RED}Found $ERRORS error(s):${NC}"
        grep -E "ERROR|Exception|Failed|Cannot|Unable|refused|checksum" .startup-test.log | head -10
    else
        echo -e "${GREEN}No errors detected${NC}"
    fi
    rm .startup-test.log 2>/dev/null
fi
cd .. 2>/dev/null
echo ""

# Quick Fix Suggestions
echo "7. QUICK FIX SUGGESTIONS"
echo "------------------------"
cd backend 2>/dev/null
if ./gradlew updateSql 2>&1 | grep -q "checksum"; then
    echo -e "${YELLOW}→ Liquibase checksum issue: Run 'cd backend && ./gradlew liquibase clearChecksums' or update changeset${NC}"
fi

if ! timeout 2 docker-compose exec postgres pg_isready 2>/dev/null; then
    echo -e "${YELLOW}→ PostgreSQL issue: Check 'docker-compose logs postgres'${NC}"
fi

if ! timeout 2 docker-compose exec redis redis-cli ping 2>/dev/null | grep -q PONG; then
    echo -e "${YELLOW}→ Redis issue: Check 'docker-compose logs redis'${NC}"
fi

if ! timeout 2 docker-compose exec kafka kafka-metadata --list --bootstrap-server localhost:9092 2>/dev/null >/dev/null; then
    echo -e "${YELLOW}→ Kafka issue: Check 'docker-compose logs kafka zookeeper'${NC}"
fi
cd .. 2>/dev/null

echo ""
echo "======================================"
echo "Diagnostic complete. Check output above for issues."
echo "======================================" 