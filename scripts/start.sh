#!/bin/bash
# ═══════════════════════════════════════════════════════════════
# Quick Start Script for Kafka Order Processor Demo
# ═══════════════════════════════════════════════════════════════

echo "╔══════════════════════════════════════════════════════════════╗"
echo "║           Kafka Order Processor - Quick Start                ║"
echo "╚══════════════════════════════════════════════════════════════╝"

# Navigate to project directory
cd "$(dirname "$0")/.."

# Check Java version
JAVA_VERSION=$(java -version 2>&1 | head -1 | cut -d'"' -f2 | cut -d'.' -f1)
if [ "$JAVA_VERSION" -lt 21 ]; then
    echo "❌ Error: Java 21+ is required. Current version: $JAVA_VERSION"
    echo "   Please install Java 21 or higher."
    exit 1
fi
echo "✓ Java version: $JAVA_VERSION"

# Build the project
echo ""
echo "Building project..."
./mvnw clean compile -q

if [ $? -ne 0 ]; then
    echo "❌ Build failed"
    exit 1
fi
echo "✓ Build successful"

# Start the application
echo ""
echo "Starting application..."
echo "═══════════════════════════════════════════════════════════════"
./mvnw spring-boot:run
