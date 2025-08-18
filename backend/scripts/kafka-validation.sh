#!/bin/bash
# ======================================================
# Kafka Configuration Validation Script
# ======================================================
# Purpose: Validate Kafka configuration, connectivity, and health
# Usage: ./kafka-validation.sh [broker_host] [broker_port]
# ======================================================

set -euo pipefail

# Configuration
KAFKA_HOST="${1:-localhost}"
KAFKA_PORT="${2:-9092}"
KAFKA_BOOTSTRAP="${KAFKA_HOST}:${KAFKA_PORT}"
KAFKA_CONTAINER="${3:-smm_kafka}"

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

echo -e "${BLUE}======================================${NC}"
echo -e "${BLUE}Kafka Configuration Validation${NC}"
echo -e "${BLUE}======================================${NC}"
echo "Bootstrap Server: $KAFKA_BOOTSTRAP"
echo "Container: $KAFKA_CONTAINER"
echo ""

# Function to run Kafka commands
kafka_exec() {
    if command -v kafka-topics >/dev/null 2>&1; then
        "$@"
    else
        docker exec "$KAFKA_CONTAINER" "$@" 2>/dev/null || {
            echo -e "${RED}Failed to execute: $*${NC}"
            return 1
        }
    fi
}

# 1. Test broker connectivity
echo -e "${YELLOW}1. Testing broker connectivity...${NC}"
if kafka_exec kafka-broker-api-versions --bootstrap-server "$KAFKA_BOOTSTRAP" >/dev/null 2>&1; then
    echo -e "${GREEN}✓ Kafka broker is accessible${NC}"
else
    echo -e "${RED}✗ Cannot connect to Kafka broker${NC}"
    exit 1
fi

# 2. Check Kafka version
echo -e "\n${YELLOW}2. Checking Kafka version...${NC}"
VERSION=$(kafka_exec kafka-broker-api-versions --bootstrap-server "$KAFKA_BOOTSTRAP" 2>/dev/null | head -1 || echo "Unknown")
echo "Broker API versions: $VERSION"

# 3. List topics
echo -e "\n${YELLOW}3. Listing topics...${NC}"
TOPICS=$(kafka_exec kafka-topics --bootstrap-server "$KAFKA_BOOTSTRAP" --list 2>/dev/null || echo "")
if [ -z "$TOPICS" ]; then
    echo -e "${YELLOW}⚠ No topics found${NC}"
else
    echo "Topics found:"
    echo "$TOPICS" | while read -r topic; do
        echo "  - $topic"
    done
fi

# 4. Check required topics
echo -e "\n${YELLOW}4. Checking required topics...${NC}"
REQUIRED_TOPICS=("order-events" "video-processing-events" "offer-assignment-events" "payment-events")
MISSING_TOPICS=()

for topic in "${REQUIRED_TOPICS[@]}"; do
    if echo "$TOPICS" | grep -q "^$topic$"; then
        echo -e "${GREEN}✓ Topic exists: $topic${NC}"
    else
        echo -e "${RED}✗ Topic missing: $topic${NC}"
        MISSING_TOPICS+=("$topic")
    fi
done

# 5. Describe cluster
echo -e "\n${YELLOW}5. Cluster information...${NC}"
kafka_exec kafka-metadata --bootstrap-server "$KAFKA_BOOTSTRAP" --list 2>/dev/null || {
    # Fallback to broker list
    kafka_exec kafka-broker-api-versions --bootstrap-server "$KAFKA_BOOTSTRAP" 2>/dev/null | grep -E "id:|version:" | head -5 || echo "Unable to get cluster info"
}

# 6. Check consumer groups
echo -e "\n${YELLOW}6. Consumer groups...${NC}"
GROUPS=$(kafka_exec kafka-consumer-groups --bootstrap-server "$KAFKA_BOOTSTRAP" --list 2>/dev/null || echo "")
if [ -z "$GROUPS" ]; then
    echo "No consumer groups found"
else
    echo "Consumer groups:"
    echo "$GROUPS" | while read -r group; do
        echo "  - $group"
        # Get group details
        kafka_exec kafka-consumer-groups --bootstrap-server "$KAFKA_BOOTSTRAP" --describe --group "$group" 2>/dev/null | head -3 || true
    done
fi

# 7. Check broker configuration
echo -e "\n${YELLOW}7. Broker configuration...${NC}"
echo "Key configurations:"

# Check auto.create.topics.enable
AUTO_CREATE=$(kafka_exec kafka-configs --bootstrap-server "$KAFKA_BOOTSTRAP" --entity-type brokers --entity-default --describe 2>/dev/null | grep "auto.create.topics.enable" || echo "")
if [ -z "$AUTO_CREATE" ]; then
    echo "  auto.create.topics.enable: default (true)"
    echo -e "  ${YELLOW}⚠ Auto topic creation is likely enabled${NC}"
else
    echo "  $AUTO_CREATE"
fi

# Check compression
COMPRESSION=$(kafka_exec kafka-configs --bootstrap-server "$KAFKA_BOOTSTRAP" --entity-type brokers --entity-default --describe 2>/dev/null | grep "compression.type" || echo "compression.type: producer")
echo "  $COMPRESSION"

# 8. Test producer/consumer
echo -e "\n${YELLOW}8. Testing producer/consumer...${NC}"
TEST_TOPIC="test-validation-topic"
TEST_MESSAGE="test-message-$(date +%s)"

# Create test topic
kafka_exec kafka-topics --bootstrap-server "$KAFKA_BOOTSTRAP" --create --topic "$TEST_TOPIC" --partitions 1 --replication-factor 1 2>/dev/null || true

# Produce a message
echo "$TEST_MESSAGE" | kafka_exec kafka-console-producer --bootstrap-server "$KAFKA_BOOTSTRAP" --topic "$TEST_TOPIC" 2>/dev/null && {
    echo -e "${GREEN}✓ Message produced successfully${NC}"
} || {
    echo -e "${RED}✗ Failed to produce message${NC}"
}

# Consume the message
CONSUMED=$(timeout 5 kafka_exec kafka-console-consumer --bootstrap-server "$KAFKA_BOOTSTRAP" --topic "$TEST_TOPIC" --from-beginning --max-messages 1 2>/dev/null || echo "")
if echo "$CONSUMED" | grep -q "$TEST_MESSAGE"; then
    echo -e "${GREEN}✓ Message consumed successfully${NC}"
else
    echo -e "${YELLOW}⚠ Could not verify message consumption${NC}"
fi

# Clean up test topic
kafka_exec kafka-topics --bootstrap-server "$KAFKA_BOOTSTRAP" --delete --topic "$TEST_TOPIC" 2>/dev/null || true

# 9. Check listener configuration
echo -e "\n${YELLOW}9. Listener configuration...${NC}"
docker exec "$KAFKA_CONTAINER" cat /etc/kafka/server.properties 2>/dev/null | grep -E "listeners=|advertised.listeners=" | head -2 || {
    echo "Unable to check listener configuration"
}

# 10. Performance metrics
echo -e "\n${YELLOW}10. Performance metrics...${NC}"
if docker exec "$KAFKA_CONTAINER" test -f /proc/1/status 2>/dev/null; then
    MEM_USAGE=$(docker exec "$KAFKA_CONTAINER" cat /proc/1/status | grep VmRSS | awk '{print $2/1024 " MB"}')
    echo "Memory usage: $MEM_USAGE"
fi

# JMX metrics if available
if nc -z "$KAFKA_HOST" 9101 2>/dev/null; then
    echo -e "${GREEN}✓ JMX port 9101 is accessible${NC}"
else
    echo "JMX metrics not accessible on port 9101"
fi

# 11. Check for KRaft mode
echo -e "\n${YELLOW}11. Consensus mode...${NC}"
if docker exec "$KAFKA_CONTAINER" test -d /var/lib/kafka/metadata 2>/dev/null; then
    echo -e "${GREEN}✓ Running in KRaft mode (no Zookeeper)${NC}"
else
    # Check Zookeeper connection
    ZK_STATUS=$(kafka_exec kafka-broker-api-versions --bootstrap-server "$KAFKA_BOOTSTRAP" 2>&1 | grep -i zookeeper || echo "")
    if [ -n "$ZK_STATUS" ]; then
        echo "Running in Zookeeper mode"
        # Check Zookeeper health
        if docker exec smm_zookeeper echo ruok | nc localhost 2181 2>/dev/null | grep -q imok; then
            echo -e "${GREEN}✓ Zookeeper is healthy${NC}"
        else
            echo -e "${YELLOW}⚠ Cannot verify Zookeeper health${NC}"
        fi
    else
        echo "Consensus mode: Unknown"
    fi
fi

# 12. Security assessment
echo -e "\n${YELLOW}12. Security assessment...${NC}"
SECURITY_SCORE=100

# Check auto topic creation
if [ -z "$AUTO_CREATE" ] || echo "$AUTO_CREATE" | grep -q "true"; then
    echo -e "${YELLOW}⚠ Auto topic creation enabled (-20 points)${NC}"
    SECURITY_SCORE=$((SECURITY_SCORE - 20))
fi

# Check listeners
PLAINTEXT_COUNT=$(docker exec "$KAFKA_CONTAINER" cat /etc/kafka/server.properties 2>/dev/null | grep -c "PLAINTEXT" || echo "0")
if [ "$PLAINTEXT_COUNT" -gt 0 ]; then
    echo -e "${YELLOW}⚠ Using PLAINTEXT listeners (-15 points)${NC}"
    SECURITY_SCORE=$((SECURITY_SCORE - 15))
fi

# Check ACLs
ACL_ENABLED=$(kafka_exec kafka-acls --bootstrap-server "$KAFKA_BOOTSTRAP" --list 2>&1 | grep -c "ACLs" || echo "0")
if [ "$ACL_ENABLED" -eq 0 ]; then
    echo -e "${YELLOW}⚠ ACLs not configured (-25 points)${NC}"
    SECURITY_SCORE=$((SECURITY_SCORE - 25))
fi

echo -e "\n${BLUE}Security Score: $SECURITY_SCORE/100${NC}"

# Summary
echo -e "\n${BLUE}======================================${NC}"
echo -e "${BLUE}Validation Summary${NC}"
echo -e "${BLUE}======================================${NC}"

if [ "$SECURITY_SCORE" -ge 80 ] && [ ${#MISSING_TOPICS[@]} -eq 0 ]; then
    echo -e "${GREEN}✓ Kafka is properly configured and healthy${NC}"
elif [ "$SECURITY_SCORE" -ge 60 ]; then
    echo -e "${YELLOW}⚠ Kafka is functional but needs security improvements${NC}"
else
    echo -e "${RED}✗ Critical configuration issues detected${NC}"
fi

echo -e "\nRecommendations:"
[ ${#MISSING_TOPICS[@]} -gt 0 ] && echo "- Create missing topics: ${MISSING_TOPICS[*]}"
[ "$SECURITY_SCORE" -lt 100 ] && echo "- Review security settings above"
[ "$PLAINTEXT_COUNT" -gt 0 ] && echo "- Consider using SSL/SASL for production"
[ -z "$GROUPS" ] && echo "- No consumer groups found - verify application connectivity"

echo -e "\n${GREEN}Validation complete!${NC}"