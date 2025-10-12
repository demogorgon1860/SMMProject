#!/bin/bash
# Start Spring Boot application in development mode

set -e

# Colors
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m'

echo -e "${GREEN}Starting SMM Panel Backend in Development Mode${NC}"

# Check if services are running
echo -e "${YELLOW}Checking required services...${NC}"

# Check PostgreSQL
if ! docker-compose ps | grep -q "postgres.*Up"; then
    echo -e "${RED}PostgreSQL is not running. Starting it...${NC}"
    docker-compose -f docker-compose.prod.yml -f docker-compose.dev.yml up -d postgres
    sleep 5
fi

# Check Redis
if ! docker-compose ps | grep -q "redis.*Up"; then
    echo -e "${RED}Redis is not running. Starting it...${NC}"
    docker-compose -f docker-compose.prod.yml -f docker-compose.dev.yml up -d redis
    sleep 3
fi

# Check Kafka
if ! docker-compose ps | grep -q "kafka.*Up"; then
    echo -e "${RED}Kafka is not running. Starting Zookeeper and Kafka...${NC}"
    docker-compose -f docker-compose.prod.yml -f docker-compose.dev.yml up -d zookeeper
    sleep 5
    docker-compose -f docker-compose.prod.yml -f docker-compose.dev.yml up -d kafka
    sleep 10
fi

echo -e "${GREEN}✓ All required services are running${NC}"

# Set environment variables for development
export SPRING_PROFILES_ACTIVE=dev
export DB_HOST=localhost
export DB_PORT=5432
export DB_NAME=smm_panel
export DB_USER=smm_admin
export DB_PASSWORD=postgres123
export REDIS_HOST=localhost
export REDIS_PORT=6379
export REDIS_PASSWORD=redis123
export KAFKA_BOOTSTRAP_SERVERS=localhost:9092
export JWT_SECRET=dev_jwt_secret_key_min_256_bits_long_for_security_hs256_algorithm_needs_this

# Load additional env vars from .env if it exists
if [ -f .env ]; then
    export $(cat .env | grep -v '^#' | xargs)
fi

# Navigate to backend directory
cd backend

# Clean and build
echo -e "${YELLOW}Building application...${NC}"
if [ -f ./gradlew ]; then
    ./gradlew clean build -x test
else
    echo -e "${RED}Gradle wrapper not found!${NC}"
    exit 1
fi

# Run the application
echo -e "${GREEN}Starting Spring Boot application...${NC}"
echo -e "${YELLOW}Access the API at: http://localhost:8080${NC}"
echo -e "${YELLOW}Health check: http://localhost:8080/actuator/health${NC}"
echo -e "${YELLOW}Swagger UI: http://localhost:8080/swagger-ui.html${NC}"

./gradlew bootRun