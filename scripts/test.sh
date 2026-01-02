#!/bin/bash
# ═══════════════════════════════════════════════════════════════
# Test Script for Kafka Order Processor Demo
# ═══════════════════════════════════════════════════════════════

BASE_URL="http://localhost:8080/api"

# Function to make API call and display result
call_api() {
    local method=$1
    local endpoint=$2
    local description=$3
    
    echo ""
    echo "Result:"
    
    if [ "$method" == "GET" ]; then
        response=$(curl -s -w "\n%{http_code}" "$BASE_URL$endpoint")
    else
        response=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL$endpoint")
    fi
    
    # Extract HTTP code (last line) and body (everything else)
    http_code=$(echo "$response" | tail -n1)
    body=$(echo "$response" | sed '$d')
    
    if [ "$http_code" == "000" ]; then
        echo "❌ ERROR: Cannot connect to server at $BASE_URL"
        echo "   Make sure the application is running: ./mvnw spring-boot:run"
        return 1
    elif [ "$http_code" -ge 400 ]; then
        echo "❌ HTTP Error: $http_code"
        echo "$body"
        return 1
    else
        echo "✓ HTTP $http_code"
        # Try to pretty print JSON, fallback to raw output
        if command -v python3 &> /dev/null; then
            echo "$body" | python3 -m json.tool 2>/dev/null || echo "$body"
        else
            echo "$body"
        fi
        return 0
    fi
}

echo "╔══════════════════════════════════════════════════════════════╗"
echo "║           Kafka Order Processor - Test Suite                 ║"
echo "╚══════════════════════════════════════════════════════════════╝"
echo ""
echo "Testing against: $BASE_URL"

# Check if server is running first
echo ""
echo "Checking server connectivity..."
if ! curl -s --connect-timeout 2 "$BASE_URL/health" > /dev/null 2>&1; then
    echo "╔══════════════════════════════════════════════════════════════╗"
    echo "║  ❌ ERROR: Server not running!                               ║"
    echo "╠══════════════════════════════════════════════════════════════╣"
    echo "║  Please start the application first:                         ║"
    echo "║                                                              ║"
    echo "║    cd kafka-order-processor                                  ║"
    echo "║    ./mvnw spring-boot:run                                    ║"
    echo "║                                                              ║"
    echo "║  Then run this test script in another terminal.              ║"
    echo "╚══════════════════════════════════════════════════════════════╝"
    exit 1
fi
echo "✓ Server is running!"

# Health check
echo ""
echo "═══════════════════════════════════════════════════════════════"
echo "1. Health Check"
echo "═══════════════════════════════════════════════════════════════"
call_api "GET" "/health"

# Sample order
echo ""
echo "═══════════════════════════════════════════════════════════════"
echo "2. Get Sample Order Structure"
echo "═══════════════════════════════════════════════════════════════"
call_api "GET" "/sample"

# Direct processing test (10 orders)
echo ""
echo "═══════════════════════════════════════════════════════════════"
echo "3. Direct Processing Test (10 orders)"
echo "   This tests batch DB calls + parallel processing"
echo "═══════════════════════════════════════════════════════════════"
call_api "POST" "/process/direct?orderCount=10"

# Direct processing test (50 orders)
echo ""
echo "═══════════════════════════════════════════════════════════════"
echo "4. Direct Processing Test (50 orders)"
echo "   Processing more orders to show scalability"
echo "═══════════════════════════════════════════════════════════════"
call_api "POST" "/process/direct?orderCount=50"

# Benchmark comparison
echo ""
echo "═══════════════════════════════════════════════════════════════"
echo "5. Benchmark: Sequential vs Parallel (20 orders)"
echo "   Compares theoretical sequential time vs actual parallel time"
echo "═══════════════════════════════════════════════════════════════"
call_api "GET" "/benchmark?orderCount=20"

# Benchmark comparison (larger)
echo ""
echo "═══════════════════════════════════════════════════════════════"
echo "6. Benchmark: Sequential vs Parallel (50 orders)"
echo "   Larger batch shows even bigger improvement"
echo "═══════════════════════════════════════════════════════════════"
call_api "GET" "/benchmark?orderCount=50"

echo ""
echo ""
echo "╔══════════════════════════════════════════════════════════════╗"
echo "║                    ✓ Tests Complete!                         ║"
echo "╚══════════════════════════════════════════════════════════════╝"
echo ""
echo "Additional commands to try:"
echo ""
echo "  # Process 100 orders:"
echo "  curl -X POST '$BASE_URL/process/direct?orderCount=100'"
echo ""
echo "  # Send event to Kafka (requires Kafka running):"
echo "  curl -X POST '$BASE_URL/kafka/send?orderCount=50'"
echo ""
echo "  # Access H2 Console:"
echo "  open http://localhost:8080/h2-console"
echo ""
echo ""
