# Connection Pool Load Test
# This script simulates various load patterns on the database connection pool

#!/bin/bash

# Configuration
BASE_URL="http://localhost:8080"
CONCURRENT_USERS=(10 25 50 100)
TEST_DURATION="300s"  # 5 minutes per test
RAMP_UP_TIME="60s"   # 1 minute ramp-up
THINK_TIME="1"       # 1 second between requests

# Test Scenarios
declare -a ENDPOINTS=(
    "/api/orders"
    "/api/services"
    "/api/balance/transactions"
    "/api/video/processing/status"
)

# Functions
run_load_test() {
    local users=$1
    local duration=$2
    local ramp_up=$3
    
    echo "Running load test with $users concurrent users for $duration"
    echo "==============================================="
    
    k6 run --vus $users --duration $duration --ramp-up $ramp_up - <<EOF
    import http from 'k6/http';
    import { sleep, check } from 'k6';
    
    export let options = {
        thresholds: {
            http_req_duration: ['p(95)<500'], // 95% of requests should complete within 500ms
            http_req_failed: ['rate<0.01'],   // Less than 1% error rate
        },
    };
    
    export default function() {
        // Random selection of endpoints
        const endpoints = ${ENDPOINTS[@]/#/\'}
        const endpoint = endpoints[Math.floor(Math.random() * endpoints.length)];
        
        // Send request
        let response = http.get('${BASE_URL}' + endpoint);
        
        // Verify response
        check(response, {
            'is status 200': (r) => r.status === 200,
            'transaction time OK': (r) => r.timings.duration < 500
        });
        
        // Simulate user think time
        sleep(${THINK_TIME});
    }
EOF
}

# Main test execution
echo "Starting Connection Pool Load Test"
echo "================================="

for users in "${CONCURRENT_USERS[@]}"; do
    # Run test with current user load
    run_load_test $users $TEST_DURATION $RAMP_UP_TIME
    
    # Brief pause between tests
    echo "Cooling down for 30 seconds..."
    sleep 30
done

# Generate report
echo "Test Complete - Generating Report"
echo "==============================="

# Parse metrics from HikariCP JMX
echo "Connection Pool Metrics:"
echo "----------------------"
jcmd | grep "SMMPanel" | cut -d" " -f1 | xargs -I{} jcmd {} JFR.dump filename=pool_metrics.jfr

# Parse and display results
echo "Average Response Times:"
echo "--------------------"
grep "http_req_duration" k6_results.json | jq -r '.metrics.http_req_duration.values.avg'

echo "Connection Pool Usage:"
echo "-------------------"
grep "hikaricp_connections" pool_metrics.jfr | jq -r '.data.poolStats'

echo "Error Rates:"
echo "-----------"
grep "http_req_failed" k6_results.json | jq -r '.metrics.http_req_failed.values.rate'

# Cleanup
rm k6_results.json pool_metrics.jfr

echo "Load Test Complete"
