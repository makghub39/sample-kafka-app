#!/bin/bash

# ═══════════════════════════════════════════════════════════════
# Clear IBM MQ Queue Script
# ═══════════════════════════════════════════════════════════════

QUEUE_NAME="${1:-DEV.QUEUE.1}"
QUEUE_MANAGER="QM1"
CONTAINER="ibm-mq"
MAX_DEPTH=50000

echo "╔══════════════════════════════════════════════════════════════╗"
echo "║              Clearing IBM MQ Queue                           ║"
echo "╚══════════════════════════════════════════════════════════════╝"
echo ""

# Check if container is running
if ! docker ps --format '{{.Names}}' | grep -q "^${CONTAINER}$"; then
    echo "❌ Error: IBM MQ container '${CONTAINER}' is not running"
    exit 1
fi

# Check current queue depth
echo "📊 Current queue status:"
docker exec ${CONTAINER} bash -c "echo 'DISPLAY QLOCAL(${QUEUE_NAME}) CURDEPTH MAXDEPTH' | runmqsc ${QUEUE_MANAGER}" 2>/dev/null | grep -E "CURDEPTH|MAXDEPTH"
echo ""

# Set max depth to 50k
echo "📈 Setting max depth to ${MAX_DEPTH}..."
docker exec ${CONTAINER} bash -c "echo 'ALTER QLOCAL(${QUEUE_NAME}) MAXDEPTH(${MAX_DEPTH})' | runmqsc ${QUEUE_MANAGER}" 2>/dev/null | grep -E "AMQ|MAXDEPTH"

# Try to clear the queue
echo ""
echo "🧹 Clearing queue ${QUEUE_NAME}..."
RESULT=$(docker exec ${CONTAINER} bash -c "echo 'CLEAR QLOCAL(${QUEUE_NAME})' | runmqsc ${QUEUE_MANAGER}" 2>&1)

if echo "$RESULT" | grep -q "AMQ8022I"; then
    echo "✅ Queue ${QUEUE_NAME} cleared successfully!"
elif echo "$RESULT" | grep -q "AMQ8148E"; then
    echo "⚠️  Queue is in use. Attempting force clear..."
    
    # Disable GET and PUT, then clear
    docker exec ${CONTAINER} bash -c "echo 'ALTER QLOCAL(${QUEUE_NAME}) GET(DISABLED) PUT(DISABLED)' | runmqsc ${QUEUE_MANAGER}" 2>/dev/null
    sleep 1
    docker exec ${CONTAINER} bash -c "echo 'CLEAR QLOCAL(${QUEUE_NAME})' | runmqsc ${QUEUE_MANAGER}" 2>/dev/null
    docker exec ${CONTAINER} bash -c "echo 'ALTER QLOCAL(${QUEUE_NAME}) GET(ENABLED) PUT(ENABLED)' | runmqsc ${QUEUE_MANAGER}" 2>/dev/null
    
    echo "✅ Queue ${QUEUE_NAME} force cleared and re-enabled!"
else
    echo "❌ Failed to clear queue. Output:"
    echo "$RESULT"
    exit 1
fi

# Verify queue is empty and max depth is set
echo ""
echo "📊 Queue status after clear:"
docker exec ${CONTAINER} bash -c "echo 'DISPLAY QLOCAL(${QUEUE_NAME}) CURDEPTH MAXDEPTH' | runmqsc ${QUEUE_MANAGER}" 2>/dev/null | grep -E "CURDEPTH|MAXDEPTH"

echo ""
echo "╔══════════════════════════════════════════════════════════════╗"
echo "║                    ✓ Done                                    ║"
echo "║           Max Depth: ${MAX_DEPTH}                               ║"
echo "╚══════════════════════════════════════════════════════════════╝"
