#!/bin/bash
# ======================================================
# Redis Configuration Validation Script
# ======================================================
# Purpose: Validate Redis configuration, security, and performance
# Usage: ./redis-validation.sh [redis_host] [redis_port]
# ======================================================

set -euo pipefail

# Configuration
REDIS_HOST="${1:-localhost}"
REDIS_PORT="${2:-6379}"
REDIS_PASSWORD="${REDIS_PASSWORD:-}"

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Function to execute Redis commands
redis_exec() {
    if [ -n "$REDIS_PASSWORD" ]; then
        redis-cli -h "$REDIS_HOST" -p "$REDIS_PORT" -a "$REDIS_PASSWORD" --no-auth-warning "$@"
    else
        redis-cli -h "$REDIS_HOST" -p "$REDIS_PORT" "$@"
    fi
}

echo -e "${BLUE}======================================${NC}"
echo -e "${BLUE}Redis Configuration Validation${NC}"
echo -e "${BLUE}======================================${NC}"
echo "Host: $REDIS_HOST:$REDIS_PORT"
echo ""

# 1. Test connectivity
echo -e "${YELLOW}1. Testing connectivity...${NC}"
if redis_exec ping > /dev/null 2>&1; then
    echo -e "${GREEN}✓ Redis is accessible${NC}"
else
    echo -e "${RED}✗ Cannot connect to Redis${NC}"
    exit 1
fi

# 2. Check Redis version
echo -e "\n${YELLOW}2. Checking Redis version...${NC}"
VERSION=$(redis_exec info server | grep redis_version | cut -d: -f2 | tr -d '\r')
MAJOR_VERSION=$(echo "$VERSION" | cut -d. -f1)
if [ "$MAJOR_VERSION" -ge 7 ]; then
    echo -e "${GREEN}✓ Redis version: $VERSION (Redis 7+ detected)${NC}"
else
    echo -e "${YELLOW}⚠ Redis version: $VERSION (Consider upgrading to Redis 7+)${NC}"
fi

# 3. Check authentication
echo -e "\n${YELLOW}3. Checking authentication...${NC}"
if redis-cli -h "$REDIS_HOST" -p "$REDIS_PORT" ping 2>/dev/null | grep -q PONG; then
    echo -e "${RED}✗ Redis accessible without password (SECURITY RISK!)${NC}"
else
    echo -e "${GREEN}✓ Password authentication required${NC}"
fi

# 4. Check ACL users
echo -e "\n${YELLOW}4. Checking ACL configuration...${NC}"
ACL_USERS=$(redis_exec ACL LIST 2>/dev/null | wc -l || echo "0")
if [ "$ACL_USERS" -gt 1 ]; then
    echo -e "${GREEN}✓ ACL configured with $ACL_USERS users${NC}"
    echo "ACL Users:"
    redis_exec ACL LIST | grep -oE 'user [^ ]+' | head -5
else
    echo -e "${YELLOW}⚠ No ACL users configured (using default user only)${NC}"
fi

# 5. Check dangerous commands
echo -e "\n${YELLOW}5. Checking dangerous commands...${NC}"
DANGEROUS_CMDS=("FLUSHDB" "FLUSHALL" "KEYS" "CONFIG" "SHUTDOWN")
DISABLED_COUNT=0
for cmd in "${DANGEROUS_CMDS[@]}"; do
    if ! redis_exec COMMAND INFO "$cmd" 2>/dev/null | grep -q "$cmd"; then
        DISABLED_COUNT=$((DISABLED_COUNT + 1))
    fi
done
if [ "$DISABLED_COUNT" -gt 0 ]; then
    echo -e "${GREEN}✓ $DISABLED_COUNT dangerous commands disabled/renamed${NC}"
else
    echo -e "${YELLOW}⚠ All dangerous commands are enabled${NC}"
fi

# 6. Check persistence configuration
echo -e "\n${YELLOW}6. Checking persistence...${NC}"
# RDB persistence
RDB_ENABLED=$(redis_exec CONFIG GET save | tail -1 | tr -d '\r')
if [ -n "$RDB_ENABLED" ] && [ "$RDB_ENABLED" != '""' ]; then
    echo -e "${GREEN}✓ RDB persistence enabled: $RDB_ENABLED${NC}"
else
    echo -e "${YELLOW}⚠ RDB persistence disabled${NC}"
fi

# AOF persistence
AOF_ENABLED=$(redis_exec CONFIG GET appendonly | tail -1 | tr -d '\r')
if [ "$AOF_ENABLED" = "yes" ]; then
    echo -e "${GREEN}✓ AOF persistence enabled${NC}"
    AOF_POLICY=$(redis_exec CONFIG GET appendfsync | tail -1 | tr -d '\r')
    echo "  AOF fsync policy: $AOF_POLICY"
else
    echo -e "${YELLOW}⚠ AOF persistence disabled${NC}"
fi

# 7. Check memory configuration
echo -e "\n${YELLOW}7. Checking memory configuration...${NC}"
MAX_MEMORY=$(redis_exec CONFIG GET maxmemory | tail -1 | tr -d '\r')
if [ "$MAX_MEMORY" != "0" ]; then
    MAX_MEMORY_HUMAN=$(redis_exec CONFIG GET maxmemory-human | tail -1 | tr -d '\r' 2>/dev/null || echo "N/A")
    echo -e "${GREEN}✓ Max memory limit set: $MAX_MEMORY_HUMAN${NC}"
    
    EVICTION_POLICY=$(redis_exec CONFIG GET maxmemory-policy | tail -1 | tr -d '\r')
    echo "  Eviction policy: $EVICTION_POLICY"
else
    echo -e "${YELLOW}⚠ No memory limit set (unlimited)${NC}"
fi

# 8. Check current memory usage
echo -e "\n${YELLOW}8. Current memory usage...${NC}"
MEMORY_INFO=$(redis_exec INFO memory | grep used_memory_human | cut -d: -f2 | tr -d '\r')
MEMORY_PEAK=$(redis_exec INFO memory | grep used_memory_peak_human | cut -d: -f2 | tr -d '\r')
echo "Current usage: $MEMORY_INFO"
echo "Peak usage: $MEMORY_PEAK"

# 9. Check slow log
echo -e "\n${YELLOW}9. Checking slow queries...${NC}"
SLOW_COUNT=$(redis_exec SLOWLOG LEN | tr -d '\r')
if [ "$SLOW_COUNT" -gt 0 ]; then
    echo -e "${YELLOW}⚠ Found $SLOW_COUNT slow queries${NC}"
    echo "Latest slow queries:"
    redis_exec SLOWLOG GET 3 | head -20
else
    echo -e "${GREEN}✓ No slow queries detected${NC}"
fi

# 10. Check replication
echo -e "\n${YELLOW}10. Checking replication...${NC}"
ROLE=$(redis_exec INFO replication | grep role | cut -d: -f2 | tr -d '\r')
echo "Role: $ROLE"
if [ "$ROLE" = "master" ]; then
    SLAVES=$(redis_exec INFO replication | grep connected_slaves | cut -d: -f2 | tr -d '\r')
    if [ "$SLAVES" != "0" ] && [ -n "$SLAVES" ]; then
        echo -e "${GREEN}✓ Master with $SLAVES connected slaves${NC}"
    else
        echo "No slaves connected"
    fi
fi

# 11. Performance metrics
echo -e "\n${YELLOW}11. Performance metrics...${NC}"
OPS_PER_SEC=$(redis_exec INFO stats | grep instantaneous_ops_per_sec | cut -d: -f2 | tr -d '\r')
CONNECTED_CLIENTS=$(redis_exec INFO clients | grep connected_clients | cut -d: -f2 | tr -d '\r')
USED_CPU=$(redis_exec INFO cpu | grep used_cpu_sys | cut -d: -f2 | tr -d '\r')

echo "Operations/sec: $OPS_PER_SEC"
echo "Connected clients: $CONNECTED_CLIENTS"
echo "CPU time used: $USED_CPU seconds"

# 12. Check for security issues
echo -e "\n${YELLOW}12. Security assessment...${NC}"
SECURITY_SCORE=100

# Check protected mode
PROTECTED_MODE=$(redis_exec CONFIG GET protected-mode | tail -1 | tr -d '\r')
if [ "$PROTECTED_MODE" = "no" ]; then
    echo -e "${YELLOW}⚠ Protected mode disabled (-10 points)${NC}"
    SECURITY_SCORE=$((SECURITY_SCORE - 10))
fi

# Check bind address
BIND_ADDR=$(redis_exec CONFIG GET bind | tail -1 | tr -d '\r')
if echo "$BIND_ADDR" | grep -q "0.0.0.0"; then
    echo -e "${YELLOW}⚠ Listening on all interfaces (-5 points)${NC}"
    SECURITY_SCORE=$((SECURITY_SCORE - 5))
fi

# Check password strength
if [ -z "$REDIS_PASSWORD" ]; then
    echo -e "${RED}✗ No password set (-30 points)${NC}"
    SECURITY_SCORE=$((SECURITY_SCORE - 30))
elif [ ${#REDIS_PASSWORD} -lt 16 ]; then
    echo -e "${YELLOW}⚠ Weak password (<16 chars) (-15 points)${NC}"
    SECURITY_SCORE=$((SECURITY_SCORE - 15))
fi

echo -e "\n${BLUE}Security Score: $SECURITY_SCORE/100${NC}"

# Summary
echo -e "\n${BLUE}======================================${NC}"
echo -e "${BLUE}Validation Summary${NC}"
echo -e "${BLUE}======================================${NC}"

if [ "$SECURITY_SCORE" -ge 80 ]; then
    echo -e "${GREEN}✓ Redis configuration is secure and optimized${NC}"
elif [ "$SECURITY_SCORE" -ge 60 ]; then
    echo -e "${YELLOW}⚠ Redis configuration needs improvement${NC}"
else
    echo -e "${RED}✗ Critical security issues detected${NC}"
fi

echo -e "\nRecommendations:"
[ "$SECURITY_SCORE" -lt 100 ] && echo "- Review security settings above"
[ "$ACL_USERS" -le 1 ] && echo "- Configure ACL users for better access control"
[ "$SLOW_COUNT" -gt 0 ] && echo "- Optimize slow queries"
[ "$MAX_MEMORY" = "0" ] && echo "- Set maxmemory limit to prevent OOM"
[ "$AOF_ENABLED" != "yes" ] && echo "- Enable AOF for better durability"

echo -e "\n${GREEN}Validation complete!${NC}"