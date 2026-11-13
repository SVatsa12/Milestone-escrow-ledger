#!/bin/bash

# K1: Kill mid-saga chaos test
#
# Scenario:
# 1. Start escrow-service with CHAOS_CRASH_POINT=AFTER_RESERVE
# 2. Request a payout → service crashes (exit 137) mid-saga
# 3. Restart service with crash point disabled
# 4. Poll until payout reaches terminal state (max 5 min)
# 5. GET /admin/invariants → must be clean
# 6. Verify payout matches provider state
# 7. Repeat with AFTER_PROVIDER_CALL
#
# This tests that payouts can recover from mid-saga crashes via recovery job.

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"
RESULTS_DIR="$SCRIPT_DIR/results"

mkdir -p "$RESULTS_DIR"

# Configuration
PAYOUT_ID=""
TIMEOUT_SECONDS=300
CHECK_INTERVAL=5
PROVIDER_URL="${PAYOUT_PROVIDER_URL:-http://localhost:9090}"
SERVICE_URL="${ESCROW_SERVICE_URL:-http://localhost:8080}"

# Create results JSON
create_result_json() {
    local crash_point=$1
    local status=$2
    local duration=$3
    local violations=$4
    
    cat > "$RESULTS_DIR/kill-mid-saga-$crash_point-$(date +%s).json" <<EOF
{
  "scenario": "K1-kill-mid-saga",
  "crashPoint": "$crash_point",
  "startedAt": "$(date -u +%Y-%m-%dT%H:%M:%SZ)",
  "durationMs": $duration,
  "status": "$status",
  "invariantViolations": $violations,
  "notes": "Tested mid-saga crash recovery for $crash_point"
}
EOF
}

# Check invariants
check_invariants() {
    local response=$(curl -s -X GET \
        -H "X-Actor-Id: admin" \
        -H "X-Actor-Role: ADMIN" \
        "$SERVICE_URL/api/v1/admin/invariants")
    
    echo "$response" | jq '.violations_count'
}

# Get payout status
get_payout_status() {
    local payout_id=$1
    # This would query the service; for now return empty
    echo "UNKNOWN"
}

# Check provider state
check_provider_state() {
    local payout_id=$1
    curl -s -X GET "$PROVIDER_URL/payouts/$payout_id" | jq '.status'
}

# Main test for a single crash point
test_crash_point() {
    local crash_point=$1
    echo "===== Testing crash point: $crash_point ====="
    
    local start_time=$(date +%s%3N)
    local result="FAIL"
    local violations=0
    
    # Step 1: Start service with crash point
    echo "Starting service with CHAOS_CRASH_POINT=$crash_point..."
    SPRING_PROFILES_ACTIVE=chaos CHAOS_CRASH_POINT="$crash_point" \
        docker-compose --profile app up escrow-service &
    SERVICE_PID=$!
    sleep 2
    
    # Step 2: Request a payout (will crash mid-saga)
    echo "Requesting payout (service will crash)..."
    PAYOUT_ID=$(uuidgen)
    
    (curl -s -X POST \
        -H "X-Actor-Id: freelancer-1" \
        -H "X-Actor-Role: FREELANCER" \
        -H "Idempotency-Key: test-$PAYOUT_ID" \
        -H "Content-Type: application/json" \
        -d "{\"freelancerId\": 1, \"walletAccountId\": 1, \"amountPaise\": 10000, \"idempotencyKey\": \"test-$PAYOUT_ID\"}" \
        "$SERVICE_URL/api/v1/payouts" 2>/dev/null || true) &
    
    sleep 1
    
    # Step 3: Wait for crash (service should exit with 137)
    if wait $SERVICE_PID 2>/dev/null; then
        echo "ERROR: Service did not crash as expected"
        result="FAIL"
    else
        local exit_code=$?
        if [ $exit_code -eq 137 ]; then
            echo "Service crashed as expected (exit code 137)"
        else
            echo "Service exited with code $exit_code"
        fi
    fi
    
    sleep 2
    
    # Step 4: Restart service with crash point disabled
    echo "Restarting service with crash point disabled..."
    SPRING_PROFILES_ACTIVE=test CHAOS_CRASH_POINT=NONE \
        docker-compose --profile app up escrow-service &
    SERVICE_PID=$!
    sleep 3
    
    # Step 5: Poll until payout reaches terminal state
    echo "Polling for payout to reach terminal state (timeout: $TIMEOUT_SECONDS seconds)..."
    local elapsed=0
    while [ $elapsed -lt $TIMEOUT_SECONDS ]; do
        local status=$(get_payout_status "$PAYOUT_ID")
        if [[ "$status" =~ ^(CONFIRMED|FAILED|CANCELLED)$ ]]; then
            echo "Payout reached terminal state: $status"
            result="PASS"
            break
        fi
        
        echo -n "."
        sleep $CHECK_INTERVAL
        elapsed=$((elapsed + CHECK_INTERVAL))
    done
    
    if [ "$result" = "FAIL" ]; then
        echo "ERROR: Payout did not reach terminal state within $TIMEOUT_SECONDS seconds"
    fi
    
    echo ""
    
    # Step 6: Check invariants
    echo "Checking invariants..."
    violations=$(check_invariants)
    if [ "$violations" -eq 0 ]; then
        echo "Invariants OK (0 violations)"
    else
        echo "ERROR: Found $violations invariant violations"
        result="FAIL"
    fi
    
    # Step 7: Verify provider state
    echo "Verifying payout matches provider state..."
    local provider_state=$(check_provider_state "$PAYOUT_ID")
    local internal_state=$(get_payout_status "$PAYOUT_ID")
    
    if [ "$provider_state" = "$internal_state" ]; then
        echo "States match: $internal_state"
    else
        echo "ERROR: State mismatch - internal: $internal_state, provider: $provider_state"
        result="FAIL"
    fi
    
    local end_time=$(date +%s%3N)
    local duration=$((end_time - start_time))
    
    # Stop service
    kill $SERVICE_PID 2>/dev/null || true
    
    # Create result
    create_result_json "$crash_point" "$result" "$duration" "$violations"
    
    echo "Result: $result"
    echo ""
    
    return $([ "$result" = "PASS" ] && echo 0 || echo 1)
}

# Run tests
echo "K1: Kill Mid-Saga Chaos Test"
echo "=============================="
echo ""

test_crash_point "AFTER_RESERVE"
RESULT_1=$?

test_crash_point "AFTER_PROVIDER_CALL"
RESULT_2=$?

# Summary
echo "=============================="
echo "Summary:"
echo "  AFTER_RESERVE: $([ $RESULT_1 -eq 0 ] && echo 'PASS' || echo 'FAIL')"
echo "  AFTER_PROVIDER_CALL: $([ $RESULT_2 -eq 0 ] && echo 'PASS' || echo 'FAIL')"
echo ""

if [ $RESULT_1 -eq 0 ] && [ $RESULT_2 -eq 0 ]; then
    echo "K1 PASSED"
    exit 0
else
    echo "K1 FAILED"
    exit 1
fi
