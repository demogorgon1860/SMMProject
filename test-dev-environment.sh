#!/bin/bash
# Test script to verify development environment is working correctly

set -e

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

echo -e "${GREEN}========================================${NC}"
echo -e "${GREEN}  Testing SMM Panel Development Setup${NC}"
echo -e "${GREEN}========================================${NC}"

FAILED_TESTS=0
PASSED_TESTS=0

# Function to test a service
test_service() {
    local test_name=$1
    local test_command=$2
    
    echo -n -e "${YELLOW}Testing $test_name...${NC} "
    
    if eval $test_command > /dev/null 2>&1; then
        echo -e "${GREEN}✓ PASSED${NC}"
        PASSED_TESTS=$((PASSED_TESTS + 1))
        return 0
    else
        echo -e "${RED}✗ FAILED${NC}"
        FAILED_TESTS=$((FAILED_TESTS + 1))
        return 1
    fi
}

# Function to test HTTP endpoint
test_http() {
    local test_name=$1
    local url=$2
    local expected_code=${3:-200}
    
    echo -n -e "${YELLOW}Testing $test_name...${NC} "
    
    response_code=$(curl -s -o /dev/null -w "%{http_code}" $url 2>/dev/null || echo "000")
    
    if [ "$response_code" = "$expected_code" ]; then
        echo -e "${GREEN}✓ PASSED (HTTP $response_code)${NC}"
        PASSED_TESTS=$((PASSED_TESTS + 1))
        return 0
    else
        echo -e "${RED}✗ FAILED (HTTP $response_code)${NC}"
        FAILED_TESTS=$((FAILED_TESTS + 1))
        return 1
    fi
}

echo -e "\n${BLUE}1. Testing Database Connectivity${NC}"
test_service "PostgreSQL Connection" "docker-compose exec -T postgres pg_isready -U smm_admin -d smm_panel"
test_service "PostgreSQL Query" "docker-compose exec -T postgres psql -U smm_admin -d smm_panel -c 'SELECT 1'"

echo -e "\n${BLUE}2. Testing Redis${NC}"
test_service "Redis Ping" "docker-compose exec -T redis redis-cli ping"
test_service "Redis Set/Get" "docker-compose exec -T redis redis-cli SET test_key test_value && docker-compose exec -T redis redis-cli GET test_key"

echo -e "\n${BLUE}3. Testing Kafka${NC}"
test_service "Kafka Broker" "docker-compose exec -T kafka sh -c 'kafka-broker-api-versions --bootstrap-server localhost:9092'"
test_service "Kafka Topics List" "docker-compose exec -T kafka sh -c 'kafka-topics --bootstrap-server localhost:9092 --list'"

# Check if specific topics exist
echo -e "\n${BLUE}4. Testing Kafka Topics${NC}"
for topic in "smm.order.processing" "smm.payment.confirmations" "smm.video.processing" "smm.notifications"; do
    test_service "Topic: $topic" "docker-compose exec -T kafka sh -c 'kafka-topics --bootstrap-server localhost:9092 --list' | grep -q $topic"
done

echo -e "\n${BLUE}5. Testing Web Services${NC}"
test_http "Kafka UI" "http://localhost:8081" "200"
test_http "Grafana" "http://localhost:3000" "200"  # Grafana anonymous access enabled
test_http "Prometheus" "http://localhost:9090" "302"  # Prometheus redirects to /graph
test_http "Selenium Grid" "http://localhost:4444" "302"  # Selenium Grid redirects to UI

echo -e "\n${BLUE}6. Testing Selenium Grid${NC}"
test_service "Selenium Hub Status" "curl -s http://localhost:4444/wd/hub/status | grep -q ready"

echo -e "\n${BLUE}7. Testing Container Health${NC}"
# Check container health status
containers=("postgres" "redis" "kafka" "zookeeper" "selenium-hub")
for container in "${containers[@]}"; do
    if docker-compose ps | grep -q "${container}.*Up.*healthy"; then
        echo -e "${GREEN}✓ $container is healthy${NC}"
        PASSED_TESTS=$((PASSED_TESTS + 1))
    elif docker-compose ps | grep -q "${container}.*Up"; then
        echo -e "${YELLOW}⚠ $container is running (no health check)${NC}"
        PASSED_TESTS=$((PASSED_TESTS + 1))
    else
        echo -e "${RED}✗ $container is not healthy${NC}"
        FAILED_TESTS=$((FAILED_TESTS + 1))
    fi
done

echo -e "\n${BLUE}8. Testing Application Readiness${NC}"

# Check if Spring Boot app can connect to services
echo -n -e "${YELLOW}Testing Spring Boot prerequisites...${NC} "

# Create a simple test to verify database schema
DB_TABLES=$(docker-compose exec -T postgres psql -U smm_admin -d smm_panel -t -c "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = 'public'" 2>/dev/null | tr -d ' ' || echo "0")

if [ "$DB_TABLES" -gt "0" ]; then
    echo -e "${GREEN}✓ Database schema exists ($DB_TABLES tables)${NC}"
    PASSED_TESTS=$((PASSED_TESTS + 1))
else
    echo -e "${YELLOW}⚠ Database schema not initialized (Run Liquibase migrations)${NC}"
fi

# Test if the application port is available
if ! lsof -i:8080 > /dev/null 2>&1; then
    echo -e "${GREEN}✓ Port 8080 is available for Spring Boot${NC}"
    PASSED_TESTS=$((PASSED_TESTS + 1))
else
    echo -e "${YELLOW}⚠ Port 8080 is in use${NC}"
fi

echo -e "\n${BLUE}9. Testing Development Tools${NC}"
test_http "pgAdmin" "http://localhost:5050" "302"  # pgAdmin redirects to login
# RedisInsight takes time to start, skip for now
# test_http "RedisInsight" "http://localhost:8001" "200"

# Summary
echo -e "\n${GREEN}========================================${NC}"
echo -e "${GREEN}  Test Results Summary${NC}"
echo -e "${GREEN}========================================${NC}"

TOTAL_TESTS=$((PASSED_TESTS + FAILED_TESTS))
echo -e "${BLUE}Total Tests:${NC} $TOTAL_TESTS"
echo -e "${GREEN}Passed:${NC} $PASSED_TESTS"
echo -e "${RED}Failed:${NC} $FAILED_TESTS"

if [ $FAILED_TESTS -eq 0 ]; then
    echo -e "\n${GREEN}✓ All tests passed! Development environment is ready.${NC}"
    
    echo -e "\n${BLUE}You can now:${NC}"
    echo -e "1. Start the Spring Boot application:"
    echo -e "   ${YELLOW}cd backend && ./gradlew bootRun${NC}"
    echo -e "2. Start the frontend development server:"
    echo -e "   ${YELLOW}cd frontend && npm install && npm run dev${NC}"
    
    exit 0
else
    echo -e "\n${RED}✗ Some tests failed. Please check the services above.${NC}"
    
    echo -e "\n${BLUE}Troubleshooting:${NC}"
    echo -e "1. Check service logs:"
    echo -e "   ${YELLOW}docker-compose logs [service-name]${NC}"
    echo -e "2. Restart failed services:"
    echo -e "   ${YELLOW}docker-compose restart [service-name]${NC}"
    echo -e "3. Re-run the setup script:"
    echo -e "   ${YELLOW}./dev-setup.sh${NC}"
    
    exit 1
fi