#!/bin/bash
# ═══════════════════════════════════════════════════════════════
# Docker Startup Script for Kafka + IBM MQ + MongoDB
# ═══════════════════════════════════════════════════════════════

cd "$(dirname "$0")/.."

echo "╔══════════════════════════════════════════════════════════════╗"
echo "║     Starting Kafka + IBM MQ + MongoDB with Docker            ║"
echo "╚══════════════════════════════════════════════════════════════╝"

# Check if Docker is running
if ! docker info > /dev/null 2>&1; then
    echo "❌ Error: Docker is not running. Please start Docker first."
    exit 1
fi

# ═══════════════════════════════════════════════════════════════
# Kill any processes using required ports
# ═══════════════════════════════════════════════════════════════
echo ""
echo "Checking for port conflicts..."

PORTS="9092 9094 27017 8081 8082 1414 9443"
for port in $PORTS; do
    PID=$(lsof -ti :$port 2>/dev/null)
    if [ -n "$PID" ]; then
        echo "  Killing process on port $port (PID: $PID)"
        kill -9 $PID 2>/dev/null
    fi
done
echo "✓ Ports are available"

# Stop any existing containers first
echo ""
echo "Stopping any existing containers..."
docker-compose down 2>/dev/null

echo ""
echo "Starting containers..."
docker-compose up -d

echo ""
echo "Waiting for services to be healthy..."

# Wait for Kafka (Apache Kafka image has tools in /opt/kafka/bin/)
echo -n "Kafka: "
KAFKA_TIMEOUT=60
KAFKA_COUNT=0
until docker exec kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --list > /dev/null 2>&1; do
    echo -n "."
    sleep 2
    KAFKA_COUNT=$((KAFKA_COUNT + 2))
    if [ $KAFKA_COUNT -ge $KAFKA_TIMEOUT ]; then
        echo " ⚠ Timeout (continuing anyway)"
        break
    fi
done
if [ $KAFKA_COUNT -lt $KAFKA_TIMEOUT ]; then
    echo " ✓ Ready"
fi

# Wait for IBM MQ (with timeout)
echo -n "IBM MQ: "
MQ_TIMEOUT=90
MQ_COUNT=0
until docker exec ibm-mq chkmqhealthy > /dev/null 2>&1; do
    echo -n "."
    sleep 3
    MQ_COUNT=$((MQ_COUNT + 3))
    if [ $MQ_COUNT -ge $MQ_TIMEOUT ]; then
        echo " ⚠ Timeout (MQ may still be starting)"
        break
    fi
done
if [ $MQ_COUNT -lt $MQ_TIMEOUT ]; then
    echo " ✓ Ready"
fi

# Wait for MongoDB (with timeout)
echo -n "MongoDB: "
MONGO_TIMEOUT=60
MONGO_COUNT=0
until docker exec mongodb mongosh --quiet --eval "db.adminCommand('ping')" > /dev/null 2>&1; do
    echo -n "."
    sleep 2
    MONGO_COUNT=$((MONGO_COUNT + 2))
    if [ $MONGO_COUNT -ge $MONGO_TIMEOUT ]; then
        echo " ⚠ Timeout (continuing anyway)"
        break
    fi
done
if [ $MONGO_COUNT -lt $MONGO_TIMEOUT ]; then
    echo " ✓ Ready"
fi

# Wait for Kafka topic creation (kafka-init container)
echo -n "Kafka Topics: "
sleep 5
docker exec kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --list 2>/dev/null | grep -q "order-events" && echo "✓ Created" || echo "✓ Will be auto-created"

echo ""
echo "═══════════════════════════════════════════════════════════════"
echo "Services are ready!"
echo "═══════════════════════════════════════════════════════════════"
echo ""
echo "  Kafka Bootstrap:   localhost:9094"
echo "  Kafka UI:          http://localhost:8081"
echo ""
echo "  IBM MQ Host:       localhost:1414"
echo "  IBM MQ Console:    https://localhost:9443/ibmmq/console"
echo "                     Username: admin"
echo "                     Password: passw0rd"
echo ""
echo "  MongoDB:           localhost:27017"
echo "  Mongo Express:     http://localhost:8082"
echo "                     Username: admin"
echo "                     Password: admin"
echo ""
echo "═══════════════════════════════════════════════════════════════"
echo ""
echo "To start the Spring Boot app with Docker profile:"
echo ""
echo "  ./mvnw spring-boot:run -Dspring-boot.run.profiles=docker"
echo ""
echo "Or run tests:"
echo ""
echo "  ./test-docker.sh"
echo ""
