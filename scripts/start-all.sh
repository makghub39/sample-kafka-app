#!/bin/bash
# ═══════════════════════════════════════════════════════════════
# Start All: Docker Services + Spring Boot Application
# ═══════════════════════════════════════════════════════════════

cd "$(dirname "$0")/.."

echo "╔══════════════════════════════════════════════════════════════╗"
echo "║          Starting Full Stack (Docker + Spring Boot)          ║"
echo "╚══════════════════════════════════════════════════════════════╝"

# Kill any existing Spring Boot process
echo ""
echo "Checking for existing Spring Boot process..."
pkill -f "kafka-order-processor" 2>/dev/null
PID=$(lsof -ti :8080 2>/dev/null)
if [ -n "$PID" ]; then
    echo "  Killing process on port 8080 (PID: $PID)"
    kill -9 $PID 2>/dev/null
fi
echo "✓ Spring Boot port available"

# Step 1: Start Docker services
echo ""
echo "Step 1: Starting Docker services..."
./scripts/docker-start.sh

if [ $? -ne 0 ]; then
    echo "❌ Failed to start Docker services"
    exit 1
fi

# Step 2: Start Spring Boot
echo ""
echo "Step 2: Starting Spring Boot application..."
echo ""

mvn spring-boot:run -Dspring-boot.run.profiles=docker
