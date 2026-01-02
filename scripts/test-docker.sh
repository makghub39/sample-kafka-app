#!/bin/bash
# ═══════════════════════════════════════════════════════════════
# Test Script for Docker Environment (Kafka + IBM MQ)
# ═══════════════════════════════════════════════════════════════

BASE_URL="http://localhost:8080/api"

# Function to make API call and display result
call_api() {
    local method=$1
    local endpoint=$2
    
    echo ""
    echo "Result:"
    
    if [ "$method" == "GET" ]; then
        response=$(curl -s -w "\n%{http_code}" "$BASE_URL$endpoint")
    else
        response=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL$endpoint")
    fi
    
    http_code=$(echo "$response" | tail -n1)
    body=$(echo "$response" | sed '$d')
    
    if [ "$http_code" == "000" ]; then
        echo "❌ ERROR: Cannot connect to server at $BASE_URL"
        echo "   Make sure the application is running with docker profile:"
        echo "   ./mvnw spring-boot:run -Dspring-boot.run.profiles=docker"
        return 1
    elif [ "$http_code" -ge 400 ]; then
        echo "❌ HTTP Error: $http_code"
        echo "$body"
        return 1
    else
        echo "✓ HTTP $http_code"
        if command -v python3 &> /dev/null; then
            echo "$body" | python3 -m json.tool 2>/dev/null || echo "$body"
        else
            echo "$body"
        fi
        return 0
    fi
}

echo "╔══════════════════════════════════════════════════════════════╗"
echo "║      Kafka Order Processor - Docker Integration Test         ║"
echo "╚══════════════════════════════════════════════════════════════╝"
echo ""
echo "Testing against: $BASE_URL"

# Check prerequisites
echo ""
echo "Checking prerequisites..."

# Check Docker containers
echo -n "  Docker containers: "
if docker ps | grep -q kafka && docker ps | grep -q ibm-mq && docker ps | grep -q mongodb; then
    echo "✓ Running"
else
    echo "❌ Not running"
    echo ""
    echo "  Please start Docker containers first:"
    echo "    ./docker-start.sh"
    exit 1
fi

# Check server
echo -n "  Spring Boot app: "
if curl -s --connect-timeout 2 "$BASE_URL/health" > /dev/null 2>&1; then
    echo "✓ Running"
else
    echo "❌ Not running"
    echo ""
    echo "  Please start the application with docker profile:"
    echo "    ./mvnw spring-boot:run -Dspring-boot.run.profiles=docker"
    exit 1
fi

echo ""
echo "All prerequisites met!"

# Health check
echo ""
echo "═══════════════════════════════════════════════════════════════"
echo "1. Health Check"
echo "═══════════════════════════════════════════════════════════════"
call_api "GET" "/health"

# MongoDB Status
echo ""
echo "═══════════════════════════════════════════════════════════════"
echo "2. MongoDB Status"
echo "═══════════════════════════════════════════════════════════════"
call_api "GET" "/mongo/status"

# Fetch from MongoDB
echo ""
echo "═══════════════════════════════════════════════════════════════"
echo "3. Fetch Orders from MongoDB (Customer CUST-001)"
echo "═══════════════════════════════════════════════════════════════"
call_api "GET" "/mongo/orders?customerId=CUST-001"

# Full MongoDB → H2 → Process flow
echo ""
echo "═══════════════════════════════════════════════════════════════"
echo "4. Full Pipeline: MongoDB → H2 Batch → Process (10 orders)"
echo "═══════════════════════════════════════════════════════════════"
call_api "POST" "/mongo/process?orderCount=10"

# Direct processing (bypasses Kafka)
echo ""
echo "═══════════════════════════════════════════════════════════════"
echo "5. Direct Processing (10 orders) - Bypasses Kafka"
echo "═══════════════════════════════════════════════════════════════"
call_api "POST" "/process/direct?orderCount=10"

# Send to Kafka (full flow)
echo ""
echo "═══════════════════════════════════════════════════════════════"
echo "6. Send to Kafka (20 orders) - Full Kafka → MongoDB → H2 → MQ Flow"
echo "═══════════════════════════════════════════════════════════════"
echo ""
echo "Sending event to Kafka topic 'order-events'..."
call_api "POST" "/kafka/send?orderCount=20"

echo ""
echo "Waiting 5 seconds for Kafka processing..."
sleep 5

# Send batch to Kafka
echo ""
echo "═══════════════════════════════════════════════════════════════"
echo "7. Send Batch to Kafka (3 events × 20 orders each)"
echo "═══════════════════════════════════════════════════════════════"
echo ""
echo "Sending batch events to Kafka..."
call_api "POST" "/kafka/send-batch?eventCount=3&ordersPerEvent=20"

echo ""
echo "Waiting 10 seconds for batch processing..."
sleep 10

# Check Kafka UI
echo ""
echo "═══════════════════════════════════════════════════════════════"
echo "8. Kafka Topic Status"
echo "═══════════════════════════════════════════════════════════════"
echo ""
echo "Checking Kafka topics..."
docker exec kafka kafka-topics.sh --bootstrap-server localhost:9092 --describe --topic order-events 2>/dev/null || echo "Could not get topic info"

# Check MQ
echo ""
echo "═══════════════════════════════════════════════════════════════"
echo "9. IBM MQ Queue Status"
echo "═══════════════════════════════════════════════════════════════"
echo ""
echo "Checking IBM MQ queue depth..."
docker exec ibm-mq bash -c 'echo "DISPLAY QLOCAL(DEV.QUEUE.1) CURDEPTH" | runmqsc QM1' 2>/dev/null | grep -E "QUEUE|CURDEPTH" || echo "Could not get queue info"

# Check MongoDB order count
echo ""
echo "═══════════════════════════════════════════════════════════════"
echo "10. MongoDB Order Count"
echo "═══════════════════════════════════════════════════════════════"
echo ""
echo "Checking MongoDB order count and statuses..."
docker exec mongodb mongosh ordersdb --quiet --eval 'db.orders.countDocuments()' --authenticationDatabase admin -u admin -p password 2>/dev/null || echo "Could not get MongoDB info"
docker exec mongodb mongosh ordersdb --quiet --eval 'db.orders.aggregate([{$group:{_id:"$status",count:{$sum:1}}}])' --authenticationDatabase admin -u admin -p password 2>/dev/null || echo ""

echo ""
echo ""
echo "╔══════════════════════════════════════════════════════════════╗"
echo "║                    ✓ Tests Complete!                         ║"
echo "╚══════════════════════════════════════════════════════════════╝"
echo ""
echo "Useful URLs:"
echo "  Kafka UI:        http://localhost:8081"
echo "  IBM MQ Console:  https://localhost:9443/ibmmq/console"
echo "  Mongo Express:   http://localhost:8082"
echo "  H2 Console:      http://localhost:8080/h2-console"
echo ""
echo "Check the Spring Boot logs for detailed processing output!"
echo ""
