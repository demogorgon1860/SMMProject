#!/bin/bash

# ===================================================================
# Environment Variables Validation Script
# ===================================================================
# This script validates that all required environment variables are set
# Run this before starting the application
# ===================================================================

set -e

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

echo "====================================================================="
echo "Validating Environment Variables for SMM Panel"
echo "====================================================================="

# Check if .env file exists
if [ ! -f .env ]; then
    echo -e "${RED}ERROR: .env file not found!${NC}"
    echo "Please copy .env.example to .env and configure it:"
    echo "  cp .env.example .env"
    exit 1
fi

# Load .env file
export $(cat .env | grep -v '^#' | xargs)

echo "Loading environment variables from .env file..."
echo ""

# Required variables
REQUIRED_VARS=(
    "DB_PASSWORD"
    "POSTGRES_PASSWORD"
    "REDIS_PASSWORD"
    "JWT_SECRET"
    "API_KEY_GLOBAL_SALT"
)

# Optional but recommended variables
OPTIONAL_VARS=(
    "BINOM_API_KEY"
    "BINOM_API_URL"
    "YOUTUBE_API_KEY"
    "CRYPTOMUS_API_KEY"
    "CRYPTOMUS_API_SECRET"
    "CRYPTOMUS_MERCHANT_ID"
    "CRYPTOMUS_WEBHOOK_SECRET"
    "GRAFANA_PASSWORD"
)

# Database connection variables
DB_VARS=(
    "DB_HOST"
    "DB_PORT"
    "DB_NAME"
    "DB_USER"
)

ERRORS=0
WARNINGS=0

echo "Checking Required Variables:"
echo "-----------------------------"
for var in "${REQUIRED_VARS[@]}"; do
    if [ -z "${!var}" ]; then
        echo -e "${RED}✗ $var is not set${NC}"
        ERRORS=$((ERRORS + 1))
    else
        # Check for placeholder values
        if [[ "${!var}" == *"placeholder"* ]] || [[ "${!var}" == *"your_"* ]] || [[ "${!var}" == "dev_password_123" ]]; then
            echo -e "${YELLOW}⚠ $var is using a placeholder value${NC}"
            WARNINGS=$((WARNINGS + 1))
        else
            echo -e "${GREEN}✓ $var is set${NC}"
        fi
    fi
done

echo ""
echo "Checking Database Configuration:"
echo "-----------------------------"
for var in "${DB_VARS[@]}"; do
    if [ -z "${!var}" ]; then
        echo -e "${RED}✗ $var is not set${NC}"
        ERRORS=$((ERRORS + 1))
    else
        echo -e "${GREEN}✓ $var is set${NC}"
    fi
done

echo ""
echo "Checking Optional Variables:"
echo "-----------------------------"
for var in "${OPTIONAL_VARS[@]}"; do
    if [ -z "${!var}" ]; then
        echo -e "${YELLOW}⚠ $var is not set (optional)${NC}"
        WARNINGS=$((WARNINGS + 1))
    elif [[ "${!var}" == *"placeholder"* ]] || [[ "${!var}" == *"your_"* ]]; then
        echo -e "${YELLOW}⚠ $var is using a placeholder value${NC}"
        WARNINGS=$((WARNINGS + 1))
    else
        echo -e "${GREEN}✓ $var is set${NC}"
    fi
done

echo ""
echo "====================================================================="

# Check password strength
if [ ! -z "$DB_PASSWORD" ]; then
    if [ ${#DB_PASSWORD} -lt 8 ]; then
        echo -e "${YELLOW}⚠ DB_PASSWORD is less than 8 characters${NC}"
        WARNINGS=$((WARNINGS + 1))
    fi
fi

if [ ! -z "$JWT_SECRET" ]; then
    if [ ${#JWT_SECRET} -lt 64 ]; then
        echo -e "${YELLOW}⚠ JWT_SECRET should be at least 64 characters for security${NC}"
        WARNINGS=$((WARNINGS + 1))
    fi
fi

# Summary
echo ""
if [ $ERRORS -gt 0 ]; then
    echo -e "${RED}❌ Validation FAILED with $ERRORS errors${NC}"
    echo ""
    echo "Please fix the errors above before starting the application."
    echo "Edit the .env file and set all required variables."
    exit 1
elif [ $WARNINGS -gt 0 ]; then
    echo -e "${YELLOW}⚠ Validation completed with $WARNINGS warnings${NC}"
    echo ""
    echo "The application can start, but some features may not work properly."
    echo "Consider updating placeholder values for production use."
else
    echo -e "${GREEN}✅ All environment variables are properly configured!${NC}"
fi

echo "====================================================================="

# Test database connection if possible
if command -v psql &> /dev/null && [ $ERRORS -eq 0 ]; then
    echo ""
    echo "Testing database connection..."
    PGPASSWORD=$DB_PASSWORD psql -h $DB_HOST -p $DB_PORT -U $DB_USER -d $DB_NAME -c "SELECT 1" > /dev/null 2>&1
    if [ $? -eq 0 ]; then
        echo -e "${GREEN}✓ Database connection successful${NC}"
    else
        echo -e "${YELLOW}⚠ Could not connect to database (it may not be running yet)${NC}"
    fi
fi

echo ""
echo "To start the application:"
echo "  1. Development mode: cd backend && ./gradlew bootRun"
echo "  2. Docker mode: docker-compose -f docker-compose.dev.yml up -d"
echo ""