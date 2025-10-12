#!/bin/bash

echo "==================================="
echo "Kafka Diagnostic and Fix Script"
echo "Date: $(date)"
echo "==================================="

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

echo ""
echo "1. Checking Kafka Broker Health..."
echo "-----------------------------------"
BROKER_STATUS=$(docker exec smm_kafka kafka-broker-api-versions --bootstrap-server localhost:9092 2>&1)
if [ $? -eq 0 ]; then
    echo -e "${GREEN}✅ Kafka broker is responsive${NC}"
else
    echo -e "${RED}❌ Kafka broker is not responsive${NC}"
    echo "Attempting to restart Kafka..."
    docker-compose restart kafka
    sleep 10
fi

echo ""
echo "2. Checking Topic Health..."
echo "-----------------------------------"
ORDER_TOPIC=$(docker exec smm_kafka kafka-topics --bootstrap-server localhost:9092 --describe --topic smm.order.processing 2>&1)
if [ $? -eq 0 ]; then
    echo -e "${GREEN}✅ Order processing topic exists${NC}"
    echo "$ORDER_TOPIC" | grep "PartitionCount"
else
    echo -e "${RED}❌ Order processing topic missing${NC}"
    echo "Creating missing topic..."
    docker exec smm_kafka kafka-topics --bootstrap-server localhost:9092 \
        --create --topic smm.order.processing \
        --partitions 3 --replication-factor 1
fi

echo ""
echo "3. Checking Consumer Groups..."
echo "-----------------------------------"
GROUPS=$(docker exec smm_kafka kafka-consumer-groups --bootstrap-server localhost:9092 --list)
echo "Active consumer groups:"
echo "$GROUPS" | head -10

echo ""
echo "4. Checking Consumer Lag..."
echo "-----------------------------------"
echo "Order Processing Group:"
docker exec smm_kafka kafka-consumer-groups --bootstrap-server localhost:9092 \
    --group smm-order-processing-group --describe | head -5

echo ""
echo "5. Checking for Stuck Messages..."
echo "-----------------------------------"
for topic in smm.order.processing smm.order.state.updates smm.video.processing; do
    OFFSET=$(docker exec smm_kafka kafka-run-class kafka.tools.GetOffsetShell \
        --broker-list localhost:9092 --topic $topic --time -1 2>/dev/null | head -1)
    echo "$topic: $OFFSET"
done

echo ""
echo "6. Testing Kafka Producer..."
echo "-----------------------------------"
# Create a test message
TEST_MSG='{"test":"message","timestamp":"'$(date -Iseconds)'"}'
echo "Sending test message to test-topic..."
echo "$TEST_MSG" | docker exec -i smm_kafka kafka-console-producer \
    --broker-list localhost:9092 \
    --topic test-topic \
    --property "parse.key=true" \
    --property "key.separator=:" \
    > /dev/null 2>&1

if [ $? -eq 0 ]; then
    echo -e "${GREEN}✅ Producer test successful${NC}"
else
    echo -e "${RED}❌ Producer test failed${NC}"
fi

echo ""
echo "7. Checking Application Kafka Configuration..."
echo "-----------------------------------"
APP_CONFIG=$(docker-compose logs spring-boot-app 2>&1 | grep -i "kafka.*bootstrap" | tail -1)
if [ -n "$APP_CONFIG" ]; then
    echo "Application Kafka config found:"
    echo "$APP_CONFIG"
else
    echo -e "${YELLOW}⚠️ No recent Kafka bootstrap logs found${NC}"
fi

echo ""
echo "8. Checking for Recent Order Processing..."
echo "-----------------------------------"
RECENT_ORDERS=$(docker-compose exec postgres psql -U smm_admin -d smm_panel -t -c \
    "SELECT COUNT(*) FROM orders WHERE created_at > NOW() - INTERVAL '1 hour';")
echo "Orders created in last hour: $RECENT_ORDERS"

PENDING_ORDERS=$(docker-compose exec postgres psql -U smm_admin -d smm_panel -t -c \
    "SELECT COUNT(*) FROM orders WHERE status IN ('PENDING', 'ACTIVE') AND error_message IS NOT NULL;")
echo "Orders with errors: $PENDING_ORDERS"

echo ""
echo "9. Attempting to Fix Common Issues..."
echo "-----------------------------------"

# Reset consumer group offsets if needed
echo "Resetting consumer group offsets to latest..."
docker exec smm_kafka kafka-consumer-groups --bootstrap-server localhost:9092 \
    --group smm-order-processing-group \
    --reset-offsets --to-latest \
    --topic smm.order.processing \
    --execute > /dev/null 2>&1

# Clear any locks
echo "Clearing database locks..."
docker-compose exec postgres psql -U smm_admin -d smm_panel -c \
    "UPDATE databasechangeloglock SET locked = false WHERE locked = true;" > /dev/null 2>&1

echo ""
echo "10. Restarting Kafka Consumers..."
echo "-----------------------------------"
echo "Sending SIGHUP to application to reload Kafka listeners..."
docker-compose exec spring-boot-app kill -HUP 1 2>/dev/null || echo "Application restart may be needed"

echo ""
echo "==================================="
echo "DIAGNOSTIC SUMMARY"
echo "==================================="

# Final health check
FINAL_HEALTH=$(curl -s http://localhost:8080/actuator/health | python -c "import sys, json; print(json.load(sys.stdin).get('status', 'UNKNOWN'))" 2>/dev/null)

if [ "$FINAL_HEALTH" == "UP" ]; then
    echo -e "${GREEN}✅ Application Health: UP${NC}"
else
    echo -e "${RED}❌ Application Health: $FINAL_HEALTH${NC}"
fi

# Check if Kafka producer is ready
PRODUCER_READY=$(curl -s http://localhost:8080/actuator/health | python -c "
import sys, json
health = json.load(sys.stdin)
kafka = health.get('components', {}).get('kafka', {}).get('details', {})
print(kafka.get('producer_ready', False))
" 2>/dev/null)

if [ "$PRODUCER_READY" == "True" ]; then
    echo -e "${GREEN}✅ Kafka Producer: Ready${NC}"
else
    echo -e "${RED}❌ Kafka Producer: Not Ready${NC}"
    echo ""
    echo "RECOMMENDED ACTION:"
    echo "1. Restart the application container:"
    echo "   docker-compose restart spring-boot-app"
    echo ""
    echo "2. Check application logs:"
    echo "   docker-compose logs --tail=100 spring-boot-app"
fi

echo ""
echo "Script completed. Check above for any issues."