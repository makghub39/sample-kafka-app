#!/bin/bash
# ═══════════════════════════════════════════════════════════════
# Stop All: Spring Boot Application + Docker Services
# ═══════════════════════════════════════════════════════════════

cd "$(dirname "$0")/.."

echo "╔══════════════════════════════════════════════════════════════╗"
echo "║          Stopping Full Stack (Spring Boot + Docker)          ║"
echo "╚══════════════════════════════════════════════════════════════╝"

# Step 1: Stop Spring Boot
echo ""
echo "Step 1: Stopping Spring Boot application..."
pkill -f "kafka-order-processor" 2>/dev/null && echo "✓ Spring Boot stopped" || echo "✓ Spring Boot was not running"

# Step 2: Stop Docker services
echo ""
echo "Step 2: Stopping Docker services..."
docker-compose down

# Step 3: Clean up any lingering processes on ports
echo ""
echo "Step 3: Cleaning up ports..."
PORTS="8080 9092 9094 27017 8081 8082 1414 9443"
for port in $PORTS; do
    PID=$(lsof -ti :$port 2>/dev/null)
    if [ -n "$PID" ]; then
        echo "  Killing lingering process on port $port (PID: $PID)"
        kill -9 $PID 2>/dev/null
    fi
done
echo "✓ All ports released"

echo ""
echo "╔══════════════════════════════════════════════════════════════╗"
echo "║                    ✓ All services stopped                    ║"
echo "╚══════════════════════════════════════════════════════════════╝"
