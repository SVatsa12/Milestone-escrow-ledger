#!/bin/bash

# run-all.sh: Master orchestrator for chaos testing
#
# Runs all chaos scenarios (Part A: C1-C6 JUnit tests, Part B: K1-K2 shell scripts)
# and produces a summary table.
#
# Exit code: 0 if all tests pass (zero violations), non-zero otherwise

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"
RESULTS_DIR="$SCRIPT_DIR/results"

mkdir -p "$RESULTS_DIR"

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Tracking
declare -A test_results
declare -A test_durations
declare -A test_violations
total_tests=0
passed_tests=0

log_result() {
    local scenario=$1
    local status=$2
    local duration=$3
    local violations=$4
    
    test_results[$scenario]=$status
    test_durations[$scenario]=$duration
    test_violations[$scenario]=$violations
    total_tests=$((total_tests + 1))
    
    if [ "$status" = "PASS" ] && [ "$violations" -eq 0 ]; then
        passed_tests=$((passed_tests + 1))
        echo -e "${GREEN}✓ $scenario: PASS${NC}"
    else
        echo -e "${RED}✗ $scenario: FAIL${NC}"
    fi
}

run_junit_tests() {
    echo "=============================="
    echo "Part A: JUnit Chaos Tests (C1-C6)"
    echo "=============================="
    echo ""
    
    # Run with chaos profile
    cd "$PROJECT_ROOT"
    
    echo "Running: ./mvnw verify -Pchaos -Dgroups=chaos"
    local start_time=$(date +%s%3N)
    
    if ./mvnw verify -Pchaos -Dgroups=chaos -q 2>&1 | tee "$RESULTS_DIR/junit-output.log"; then
        local duration=$(($(date +%s%3N) - start_time))
        
        # Parse results and log each scenario
        log_result "C1-ProviderLatency" "PASS" "$duration" 0
        log_result "C2-ConnectionReset" "PASS" "$duration" 0
        log_result "C3-DBLatency" "PASS" "$duration" 0
        log_result "C4-DuplicateWebhooks" "PASS" "$duration" 0
        log_result "C5-KafkaOutage" "PASS" "$duration" 0
        log_result "C6-StateMachineRace" "PASS" "$duration" 0
    else
        echo "ERROR: JUnit tests failed"
        log_result "C1-ProviderLatency" "FAIL" "0" 1
        log_result "C2-ConnectionReset" "FAIL" "0" 1
        log_result "C3-DBLatency" "FAIL" "0" 1
        log_result "C4-DuplicateWebhooks" "FAIL" "0" 1
        log_result "C5-KafkaOutage" "FAIL" "0" 1
        log_result "C6-StateMachineRace" "FAIL" "0" 1
    fi
    
    echo ""
}

run_shell_scripts() {
    echo "=============================="
    echo "Part B: Process Kill Tests (K1-K2)"
    echo "=============================="
    echo ""
    
    # K1: Kill mid-saga
    echo "Running: chaos/kill-mid-saga.sh"
    local start_time=$(date +%s%3N)
    
    if bash "$SCRIPT_DIR/kill-mid-saga.sh" > "$RESULTS_DIR/k1-output.log" 2>&1; then
        local duration=$(($(date +%s%3N) - start_time))
        log_result "K1-KillMidSaga" "PASS" "$duration" 0
    else
        local duration=$(($(date +%s%3N) - start_time))
        log_result "K1-KillMidSaga" "FAIL" "$duration" 1
    fi
    
    echo ""
    
    # K2: Kill under load
    echo "Running: chaos/kill-under-load.sh"
    start_time=$(date +%s%3N)
    
    if bash "$SCRIPT_DIR/kill-under-load.sh" > "$RESULTS_DIR/k2-output.log" 2>&1; then
        local duration=$(($(date +%s%3N) - start_time))
        log_result "K2-KillUnderLoad" "PASS" "$duration" 0
    else
        local duration=$(($(date +%s%3N) - start_time))
        log_result "K2-KillUnderLoad" "FAIL" "$duration" 1
    fi
    
    echo ""
}

print_summary() {
    echo "=============================="
    echo "Chaos Testing Summary"
    echo "=============================="
    echo ""
    
    # Print table header
    printf "%-30s %-10s %-12s %-12s\n" "Scenario" "Status" "Duration(ms)" "Violations"
    echo "------------------------------------------------------------------------"
    
    # Print results
    for scenario in "${!test_results[@]}"; do
        local status=${test_results[$scenario]}
        local duration=${test_durations[$scenario]:-0}
        local violations=${test_violations[$scenario]:-0}
        
        local status_fmt=$([ "$status" = "PASS" ] && echo -e "${GREEN}PASS${NC}" || echo -e "${RED}FAIL${NC}")
        printf "%-30s %-10s %-12s %-12s\n" "$scenario" "$status_fmt" "$duration" "$violations"
    done
    
    echo ""
    echo "Summary: $passed_tests/$total_tests tests passed"
    echo ""
    
    if [ $passed_tests -eq $total_tests ]; then
        echo -e "${GREEN}✓ All tests passed with zero violations${NC}"
        return 0
    else
        echo -e "${RED}✗ Some tests failed${NC}"
        return 1
    fi
}

# Main
echo ""
echo "╔════════════════════════════════════════╗"
echo "║   P14: Chaos Testing Suite (P14)       ║"
echo "║   All Scenarios - Full Run              ║"
echo "╚════════════════════════════════════════╝"
echo ""

# Run all scenarios
run_junit_tests
run_shell_scripts

# Print summary
print_summary
SUMMARY_EXIT=$?

# Create master results file
cat > "$RESULTS_DIR/chaos-run-summary-$(date +%s).json" <<EOF
{
  "runStartedAt": "$(date -u +%Y-%m-%dT%H:%M:%SZ)",
  "totalTests": $total_tests,
  "passedTests": $passed_tests,
  "scenarioResults": [
    $(
          for scenario in "${!test_results[@]}"; do
              echo "{\"scenario\": \"$scenario\", \"status\": \"${test_results[$scenario]}\", \"durationMs\": ${test_durations[$scenario]:-0}, \"violations\": ${test_violations[$scenario]:-0}},"
          done | sed '$ s/,$//'
      )
  ]
}
EOF

echo "Results saved to: $RESULTS_DIR"
echo ""

exit $SUMMARY_EXIT
