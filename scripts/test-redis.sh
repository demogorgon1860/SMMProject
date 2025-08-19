#!/bin/bash

# Redis Health Check and Testing Script
# This script tests Redis connectivity and basic operations

REDIS_HOST=${1:-localhost}
REDIS_PORT=${2:-6379}
REDIS_PASSWORD=${3:-}

echo "Testing Redis connection at $REDIS_HOST:$REDIS_PORT..."
echo ""

# Function to execute Redis commands
redis_exec() {
    if [ -z "$REDIS_PASSWORD" ]; then
        redis-cli -h $REDIS_HOST -p $REDIS_PORT "$@"
    else
        redis-cli -h $REDIS_HOST -p $REDIS_PORT -a $REDIS_PASSWORD "$@"
    fi
}

# Test 1: Ping
echo "1. Testing PING..."
PING_RESULT=$(redis_exec PING)
if [ "$PING_RESULT" = "PONG" ]; then
    echo "✓ Redis is responding to PING"
else
    echo "✗ Redis PING failed"
    exit 1
fi
echo ""

# Test 2: Get Redis Info
echo "2. Getting Redis Info..."
redis_exec INFO server | grep -E "redis_version|redis_mode|tcp_port|uptime_in_days"
echo ""

# Test 3: Memory Info
echo "3. Memory Usage..."
redis_exec INFO memory | grep -E "used_memory_human|used_memory_peak_human|maxmemory_human"
echo ""

# Test 4: Test Cache Operations
echo "4. Testing Cache Operations..."

# Set a test key
redis_exec SET "smm:cache:test:key" "test_value" EX 60
echo "✓ SET operation successful"

# Get the test key
TEST_VALUE=$(redis_exec GET "smm:cache:test:key")
if [ "$TEST_VALUE" = "test_value" ]; then
    echo "✓ GET operation successful"
else
    echo "✗ GET operation failed"
fi

# Delete the test key
redis_exec DEL "smm:cache:test:key"
echo "✓ DEL operation successful"
echo ""

# Test 5: List all SMM cache keys
echo "5. SMM Cache Keys..."
KEY_COUNT=$(redis_exec --scan --pattern "smm:cache:*" | wc -l)
echo "Found $KEY_COUNT SMM cache keys"

if [ $KEY_COUNT -gt 0 ]; then
    echo "Sample keys:"
    redis_exec --scan --pattern "smm:cache:*" | head -10
fi
echo ""

# Test 6: Database Statistics
echo "6. Database Statistics..."
DB_SIZE=$(redis_exec DBSIZE | awk '{print $2}')
echo "Total keys in database: $DB_SIZE"
echo ""

# Test 7: Cache Hit/Miss Ratio
echo "7. Cache Performance..."
redis_exec INFO stats | grep -E "keyspace_hits|keyspace_misses|evicted_keys|expired_keys"
echo ""

# Test 8: Connected Clients
echo "8. Connected Clients..."
redis_exec INFO clients | grep -E "connected_clients|blocked_clients"
echo ""

# Test 9: Check Persistence
echo "9. Persistence Configuration..."
redis_exec CONFIG GET "save"
redis_exec CONFIG GET "appendonly"
echo ""

# Test 10: Cache TTL verification
echo "10. Testing TTL Configuration..."
redis_exec SET "smm:cache:test:ttl" "ttl_test" EX 10
TTL_VALUE=$(redis_exec TTL "smm:cache:test:ttl")
echo "TTL for test key: $TTL_VALUE seconds"
redis_exec DEL "smm:cache:test:ttl"
echo ""

echo "============================================"
echo "Redis health check completed successfully!"
echo "============================================"
echo ""
echo "Recommendations:"
echo "1. Monitor evicted_keys - high values indicate memory pressure"
echo "2. Monitor keyspace hit/miss ratio - low hit rate indicates ineffective caching"
echo "3. Ensure persistence is configured for production (save or appendonly)"
echo "4. Set appropriate maxmemory and eviction policy for production"