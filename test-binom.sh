#!/bin/bash

echo "============================="
echo "BINOM INTEGRATION TEST REPORT"
echo "============================="
echo ""

# 1. Check database campaigns
echo "1. DATABASE CAMPAIGNS:"
echo "----------------------"
docker exec smm_postgres psql -U smm_admin -d smm_panel -t -c "SELECT campaign_id, campaign_name, geo_targeting, active FROM fixed_binom_campaigns;" 2>/dev/null
echo ""

# 2. Check environment variables
echo "2. BINOM CONFIGURATION:"
echo "-----------------------"
docker exec smm_backend sh -c 'echo "API URL: $BINOM_API_URL"' 2>/dev/null
docker exec smm_backend sh -c 'echo "API KEY: ${BINOM_API_KEY:0:20}..."' 2>/dev/null
docker exec smm_backend sh -c 'echo "ENABLED: $BINOM_INTEGRATION_ENABLED"' 2>/dev/null
echo ""

# 3. Check if backend is running
echo "3. BACKEND STATUS:"
echo "------------------"
CONTAINER_STATUS=$(docker ps --filter "name=smm_backend" --format "table {{.Status}}" | tail -1)
echo "Container: $CONTAINER_STATUS"

# Check if app is responding
if curl -s -o /dev/null -w "%{http_code}" http://localhost:8080/api/actuator/health 2>/dev/null | grep -q "200"; then
    echo "Application: RUNNING (HTTP 200)"
else
    echo "Application: NOT READY"
fi
echo ""

# 4. Test Binom endpoints (if available)
echo "4. BINOM TEST ENDPOINTS:"
echo "------------------------"
echo "Testing /api/test/binom/campaigns..."
RESPONSE=$(curl -s http://localhost:8080/api/test/binom/campaigns 2>/dev/null)
if [ ! -z "$RESPONSE" ]; then
    echo "$RESPONSE" | python -m json.tool 2>/dev/null || echo "$RESPONSE"
else
    echo "Endpoint not available yet"
fi
echo ""

echo "============================="
echo "END OF REPORT"
echo "============================="