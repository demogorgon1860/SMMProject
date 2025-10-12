#!/bin/bash
# Complete Development Environment Setup for SMM Panel
# This script ensures all services are properly configured and running

set -e

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

echo -e "${GREEN}========================================${NC}"
echo -e "${GREEN}  SMM Panel Development Environment Setup${NC}"
echo -e "${GREEN}========================================${NC}"

# Function to check if a command exists
command_exists() {
    command -v "$1" >/dev/null 2>&1
}

# Function to wait for a service to be healthy
wait_for_service() {
    local service=$1
    local max_attempts=30
    local attempt=1
    
    echo -e "${YELLOW}Waiting for $service to be healthy...${NC}"
    
    while [ $attempt -le $max_attempts ]; do
        if docker-compose ps | grep -q "$service.*healthy"; then
            echo -e "${GREEN}✓ $service is healthy${NC}"
            return 0
        elif docker-compose ps | grep -q "$service.*Up"; then
            echo -e "${GREEN}✓ $service is running${NC}"
            return 0
        fi
        echo -n "."
        sleep 2
        attempt=$((attempt + 1))
    done
    
    echo -e "${RED}✗ $service failed to start${NC}"
    return 1
}

# Step 1: Check prerequisites
echo -e "\n${BLUE}Step 1: Checking prerequisites...${NC}"

if ! command_exists docker; then
    echo -e "${RED}✗ Docker is not installed${NC}"
    exit 1
fi
echo -e "${GREEN}✓ Docker installed${NC}"

if ! command_exists docker-compose; then
    echo -e "${RED}✗ Docker Compose is not installed${NC}"
    exit 1
fi
echo -e "${GREEN}✓ Docker Compose installed${NC}"

if ! command_exists java; then
    echo -e "${YELLOW}⚠ Java is not installed (required for backend development)${NC}"
fi

if ! command_exists node; then
    echo -e "${YELLOW}⚠ Node.js is not installed (required for frontend development)${NC}"
fi

# Step 2: Check and create .env file
echo -e "\n${BLUE}Step 2: Setting up environment variables...${NC}"

if [ ! -f .env ]; then
    echo -e "${YELLOW}Creating .env file from template...${NC}"
    cat > .env << 'EOF'
# Development Environment Variables
# Generated for local development

# Database
DB_HOST=localhost
DB_PORT=5432
DB_NAME=smm_panel
DB_USER=smm_admin
DB_PASSWORD=postgres123
DB_MAX_POOL_SIZE=20
DB_MIN_IDLE=5

# Redis
REDIS_HOST=localhost
REDIS_PORT=6379
REDIS_PASSWORD=redis123

# Kafka
KAFKA_BOOTSTRAP_SERVERS=localhost:9092

# JWT
JWT_SECRET=dev_jwt_secret_key_min_256_bits_long_for_security_hs256_algorithm_needs_this
JWT_EXPIRATION=86400000
JWT_REFRESH_EXPIRATION=604800000

# Admin defaults
ADMIN_USERNAME=admin
ADMIN_EMAIL=admin@localhost
ADMIN_PASSWORD=admin123
ADMIN_INITIAL_BALANCE=10000

# External APIs (development defaults)
BINOM_API_KEY=dev_binom_key
BINOM_API_URL=https://api.binom.com
BINOM_INTEGRATION_ENABLED=false

YOUTUBE_API_KEY=dev_youtube_key
YOUTUBE_API_URL=https://www.googleapis.com/youtube/v3

CRYPTOMUS_API_KEY=dev_cryptomus_key
CRYPTOMUS_API_SECRET=dev_cryptomus_secret
CRYPTOMUS_MERCHANT_ID=dev_merchant
CRYPTOMUS_WEBHOOK_SECRET=dev_webhook_secret
CRYPTOMUS_API_URL=https://api.cryptomus.com/v1

# Spring profiles
SPRING_PROFILES_ACTIVE=dev
SPRING_LIQUIBASE_ENABLED=true
SPRING_LIQUIBASE_CONTEXTS=dev

# Monitoring
GRAFANA_USER=admin
GRAFANA_PASSWORD=admin

# Docker
DOCKER_REGISTRY=
VERSION=latest
EOF
    echo -e "${GREEN}✓ .env file created${NC}"
else
    echo -e "${GREEN}✓ .env file exists${NC}"
fi

# Step 3: Clean up old containers and volumes
echo -e "\n${BLUE}Step 3: Cleaning up old containers...${NC}"

echo -e "${YELLOW}Stopping any running containers...${NC}"
docker-compose -f docker-compose.prod.yml -f docker-compose.dev.yml down 2>/dev/null || true
docker-compose down 2>/dev/null || true

echo -e "${YELLOW}Removing orphan containers...${NC}"
docker container prune -f

echo -e "${YELLOW}Cleaning unused volumes...${NC}"
docker volume prune -f

echo -e "${GREEN}✓ Cleanup complete${NC}"

# Step 4: Start infrastructure services
echo -e "\n${BLUE}Step 4: Starting infrastructure services...${NC}"

# Start services in correct order
echo -e "${YELLOW}Starting PostgreSQL...${NC}"
docker-compose -f docker-compose.prod.yml -f docker-compose.dev.yml up -d postgres
wait_for_service postgres

echo -e "${YELLOW}Starting Redis...${NC}"
docker-compose -f docker-compose.prod.yml -f docker-compose.dev.yml up -d redis
wait_for_service redis

echo -e "${YELLOW}Starting Zookeeper...${NC}"
docker-compose -f docker-compose.prod.yml -f docker-compose.dev.yml up -d zookeeper
wait_for_service zookeeper

echo -e "${YELLOW}Starting Kafka...${NC}"
docker-compose -f docker-compose.prod.yml -f docker-compose.dev.yml up -d kafka
wait_for_service kafka

# Step 5: Create Kafka topics
echo -e "\n${BLUE}Step 5: Creating Kafka topics...${NC}"

# Wait for Kafka to be fully ready
sleep 10

# Create topics (use sh -c to avoid Git Bash path conversion issues on Windows)
docker-compose exec -T kafka sh -c "kafka-topics --bootstrap-server localhost:9092 --create --if-not-exists --topic smm.order.processing --partitions 3 --replication-factor 1" 2>/dev/null || true
docker-compose exec -T kafka sh -c "kafka-topics --bootstrap-server localhost:9092 --create --if-not-exists --topic smm.payment.confirmations --partitions 3 --replication-factor 1" 2>/dev/null || true
docker-compose exec -T kafka sh -c "kafka-topics --bootstrap-server localhost:9092 --create --if-not-exists --topic smm.video.processing --partitions 3 --replication-factor 1" 2>/dev/null || true
docker-compose exec -T kafka sh -c "kafka-topics --bootstrap-server localhost:9092 --create --if-not-exists --topic smm.notifications --partitions 3 --replication-factor 1" 2>/dev/null || true

echo -e "${GREEN}✓ Kafka topics created${NC}"

# Step 6: Start monitoring services
echo -e "\n${BLUE}Step 6: Starting monitoring services...${NC}"

echo -e "${YELLOW}Starting Prometheus...${NC}"
docker-compose -f docker-compose.prod.yml -f docker-compose.dev.yml up -d prometheus
wait_for_service prometheus

echo -e "${YELLOW}Starting Grafana...${NC}"
docker-compose -f docker-compose.prod.yml -f docker-compose.dev.yml up -d grafana
wait_for_service grafana

echo -e "${YELLOW}Starting Loki...${NC}"
docker-compose -f docker-compose.prod.yml -f docker-compose.dev.yml up -d loki
wait_for_service loki

# Step 7: Start Selenium Grid for Chrome automation
echo -e "\n${BLUE}Step 7: Starting Selenium Grid...${NC}"

echo -e "${YELLOW}Starting Selenium Hub...${NC}"
docker-compose -f docker-compose.prod.yml -f docker-compose.dev.yml up -d selenium-hub
wait_for_service selenium-hub

echo -e "${YELLOW}Starting Chrome nodes...${NC}"
docker-compose -f docker-compose.prod.yml -f docker-compose.dev.yml up -d chrome
sleep 5

# Step 8: Start development tools
echo -e "\n${BLUE}Step 8: Starting development tools...${NC}"

echo -e "${YELLOW}Starting Kafka UI...${NC}"
docker-compose -f docker-compose.prod.yml -f docker-compose.dev.yml up -d kafka-ui
wait_for_service kafka-ui

echo -e "${YELLOW}Starting pgAdmin...${NC}"
docker-compose -f docker-compose.prod.yml -f docker-compose.dev.yml up -d pgadmin
wait_for_service pgadmin

echo -e "${YELLOW}Starting RedisInsight...${NC}"
docker-compose -f docker-compose.prod.yml -f docker-compose.dev.yml up -d redis-insight
wait_for_service redis-insight

# Step 9: Build and start the Spring Boot application
echo -e "\n${BLUE}Step 9: Building Spring Boot application...${NC}"

cd backend

# Check if gradlew exists
if [ -f ./gradlew ]; then
    echo -e "${YELLOW}Building application...${NC}"
    ./gradlew clean build -x test
    echo -e "${GREEN}✓ Application built${NC}"
else
    echo -e "${YELLOW}⚠ Gradle wrapper not found. Please build manually.${NC}"
fi

cd ..

# Step 10: Verify all services
echo -e "\n${BLUE}Step 10: Verifying services...${NC}"

echo -e "\n${YELLOW}Service Status:${NC}"
docker-compose -f docker-compose.prod.yml -f docker-compose.dev.yml ps

# Step 11: Run health checks
echo -e "\n${BLUE}Step 11: Running health checks...${NC}"

# Check PostgreSQL
if docker-compose exec -T postgres pg_isready -U smm_admin -d smm_panel > /dev/null 2>&1; then
    echo -e "${GREEN}✓ PostgreSQL is healthy${NC}"
else
    echo -e "${RED}✗ PostgreSQL health check failed${NC}"
fi

# Check Redis
if docker-compose exec -T redis redis-cli ping > /dev/null 2>&1; then
    echo -e "${GREEN}✓ Redis is healthy${NC}"
else
    echo -e "${RED}✗ Redis health check failed${NC}"
fi

# Check Kafka
if docker-compose exec -T kafka kafka-broker-api-versions.sh --bootstrap-server localhost:9092 > /dev/null 2>&1; then
    echo -e "${GREEN}✓ Kafka is healthy${NC}"
else
    echo -e "${RED}✗ Kafka health check failed${NC}"
fi

# Step 12: Display access information
echo -e "\n${GREEN}========================================${NC}"
echo -e "${GREEN}  Development Environment Ready!${NC}"
echo -e "${GREEN}========================================${NC}"

echo -e "\n${BLUE}Service URLs:${NC}"
echo -e "  ${YELLOW}Application:${NC}     http://localhost:8080"
echo -e "  ${YELLOW}Kafka UI:${NC}        http://localhost:8081"
echo -e "  ${YELLOW}pgAdmin:${NC}         http://localhost:5050 (admin@smmpanel.com / admin)"
echo -e "  ${YELLOW}RedisInsight:${NC}    http://localhost:8001"
echo -e "  ${YELLOW}Grafana:${NC}         http://localhost:3000 (admin / admin)"
echo -e "  ${YELLOW}Prometheus:${NC}      http://localhost:9090"
echo -e "  ${YELLOW}Jaeger:${NC}          http://localhost:16686"
echo -e "  ${YELLOW}Selenium Grid:${NC}   http://localhost:4444"

echo -e "\n${BLUE}Database Connection:${NC}"
echo -e "  ${YELLOW}Host:${NC}        localhost"
echo -e "  ${YELLOW}Port:${NC}        5432"
echo -e "  ${YELLOW}Database:${NC}    smm_panel"
echo -e "  ${YELLOW}Username:${NC}    smm_admin"
echo -e "  ${YELLOW}Password:${NC}    postgres123"

echo -e "\n${BLUE}Next Steps:${NC}"
echo -e "  1. Run the Spring Boot application:"
echo -e "     ${YELLOW}cd backend && ./gradlew bootRun${NC}"
echo -e "  2. Run the frontend development server:"
echo -e "     ${YELLOW}cd frontend && npm install && npm run dev${NC}"
echo -e "  3. Access the application at:"
echo -e "     ${YELLOW}http://localhost:3000${NC} (Frontend)"
echo -e "     ${YELLOW}http://localhost:8080${NC} (Backend API)"

echo -e "\n${BLUE}Useful Commands:${NC}"
echo -e "  ${YELLOW}View logs:${NC}           docker-compose logs -f [service]"
echo -e "  ${YELLOW}Stop all services:${NC}   docker-compose down"
echo -e "  ${YELLOW}Restart a service:${NC}   docker-compose restart [service]"
echo -e "  ${YELLOW}Scale Chrome nodes:${NC}  docker-compose up -d --scale chrome=5"

echo -e "\n${GREEN}Development environment setup complete!${NC}"