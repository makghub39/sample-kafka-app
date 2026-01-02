#!/bin/bash
# ═══════════════════════════════════════════════════════════════
# Docker Stop Script
# ═══════════════════════════════════════════════════════════════

cd "$(dirname "$0")/.."

echo "Stopping Docker containers..."
docker-compose down

# Clean up any lingering processes on Docker ports
echo ""
echo "Cleaning up ports..."
PORTS="9092 9094 27017 8081 8082 1414 9443"
for port in $PORTS; do
    PID=$(lsof -ti :$port 2>/dev/null)
    if [ -n "$PID" ]; then
        echo "  Killing lingering process on port $port (PID: $PID)"
        kill -9 $PID 2>/dev/null
    fi
done

echo "✓ All containers stopped and ports released"
