#!/bin/bash

# ═══════════════════════════════════════════════════════════════
# Kafka Order Processor - Metrics Dashboard
# ═══════════════════════════════════════════════════════════════

BASE_URL="${1:-http://localhost:8080}"

# Colors for output
GREEN='\033[0;32m'
BLUE='\033[0;34m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
NC='\033[0m' # No Color

echo ""
echo -e "${CYAN}═══════════════════════════════════════════════════════════════${NC}"
echo -e "${CYAN}          KAFKA ORDER PROCESSOR - METRICS DASHBOARD            ${NC}"
echo -e "${CYAN}═══════════════════════════════════════════════════════════════${NC}"
echo -e "${YELLOW}Endpoint: ${BASE_URL}${NC}"
echo -e "${CYAN}═══════════════════════════════════════════════════════════════${NC}"
echo ""

# Function to get metric value
get_metric() {
    local metric_name=$1
    local stat=${2:-COUNT}
    curl -s "${BASE_URL}/actuator/metrics/${metric_name}" 2>/dev/null | \
        grep -o "\"statistic\":\"${stat}\",\"value\":[0-9.]*" | \
        grep -o "[0-9.]*$" || echo "N/A"
}

# Function to format time (seconds to ms)
format_time_ms() {
    local seconds=$1
    if [[ "$seconds" == "N/A" ]]; then
        echo "N/A"
    else
        printf "%.2f ms" $(echo "$seconds * 1000" | bc 2>/dev/null || echo "0")
    fi
}

# ═══════════════════════════════════════════════════════════════
# ORDER COUNTS
# ═══════════════════════════════════════════════════════════════
echo -e "${GREEN}📊 ORDER COUNTS${NC}"
echo "────────────────────────────────────────────────────────────"

TOTAL_ORDERS=$(get_metric "order.total.count")
SUCCESS_ORDERS=$(get_metric "order.success")
FAILED_ORDERS=$(get_metric "order.failed")
EVENTS_RECEIVED=$(get_metric "event.received")

printf "  %-25s %s\n" "Total Orders Processed:" "${TOTAL_ORDERS}"
printf "  %-25s %s\n" "Successful Orders:" "${SUCCESS_ORDERS}"
printf "  %-25s %s\n" "Failed Orders:" "${FAILED_ORDERS}"
printf "  %-25s %s\n" "Events Received:" "${EVENTS_RECEIVED}"

# Calculate success rate
if [[ "$TOTAL_ORDERS" != "N/A" && "$TOTAL_ORDERS" != "0" && "$SUCCESS_ORDERS" != "N/A" ]]; then
    SUCCESS_RATE=$(echo "scale=2; $SUCCESS_ORDERS / $TOTAL_ORDERS * 100" | bc 2>/dev/null || echo "N/A")
    printf "  %-25s %s%%\n" "Success Rate:" "${SUCCESS_RATE}"
fi

echo ""

# ═══════════════════════════════════════════════════════════════
# TIMING METRICS
# ═══════════════════════════════════════════════════════════════
echo -e "${GREEN}⏱️  TIMING METRICS (Averages)${NC}"
echo "────────────────────────────────────────────────────────────"

# Event total time
EVENT_TOTAL_MEAN=$(get_metric "order.event.total" "MEAN")
EVENT_TOTAL_MAX=$(get_metric "order.event.total" "MAX")
EVENT_TOTAL_COUNT=$(get_metric "order.event.total" "COUNT")

printf "  %-25s %s (max: %s) [%s events]\n" \
    "Total Event Processing:" \
    "$(format_time_ms $EVENT_TOTAL_MEAN)" \
    "$(format_time_ms $EVENT_TOTAL_MAX)" \
    "${EVENT_TOTAL_COUNT}"

# MongoDB fetch time
MONGO_MEAN=$(get_metric "order.mongodb.fetch" "MEAN")
MONGO_MAX=$(get_metric "order.mongodb.fetch" "MAX")
MONGO_COUNT=$(get_metric "order.mongodb.fetch" "COUNT")

printf "  %-25s %s (max: %s) [%s calls]\n" \
    "MongoDB Fetch (Avg):" \
    "$(format_time_ms $MONGO_MEAN)" \
    "$(format_time_ms $MONGO_MAX)" \
    "${MONGO_COUNT}"

# H2 DB fetch time
DB_MEAN=$(get_metric "order.db.fetch" "MEAN")
DB_MAX=$(get_metric "order.db.fetch" "MAX")
DB_COUNT=$(get_metric "order.db.fetch" "COUNT")

printf "  %-25s %s (max: %s) [%s calls]\n" \
    "H2 DB Fetch (Avg):" \
    "$(format_time_ms $DB_MEAN)" \
    "$(format_time_ms $DB_MAX)" \
    "${DB_COUNT}"

# Processing time
PROC_MEAN=$(get_metric "order.processing.time" "MEAN")
PROC_MAX=$(get_metric "order.processing.time" "MAX")
PROC_COUNT=$(get_metric "order.processing.time" "COUNT")

printf "  %-25s %s (max: %s) [%s batches]\n" \
    "Order Processing (Avg):" \
    "$(format_time_ms $PROC_MEAN)" \
    "$(format_time_ms $PROC_MAX)" \
    "${PROC_COUNT}"

# WMQ publish time
WMQ_MEAN=$(get_metric "order.wmq.publish" "MEAN")
WMQ_MAX=$(get_metric "order.wmq.publish" "MAX")
WMQ_COUNT=$(get_metric "order.wmq.publish" "COUNT")

printf "  %-25s %s (max: %s) [%s batches]\n" \
    "WMQ Publish (Avg):" \
    "$(format_time_ms $WMQ_MEAN)" \
    "$(format_time_ms $WMQ_MAX)" \
    "${WMQ_COUNT}"

echo ""

# ═══════════════════════════════════════════════════════════════
# WMQ MESSAGES
# ═══════════════════════════════════════════════════════════════
echo -e "${GREEN}📤 WMQ MESSAGES${NC}"
echo "────────────────────────────────────────────────────────────"

WMQ_MESSAGES=$(get_metric "wmq.messages.sent")
printf "  %-25s %s\n" "Messages Sent to WMQ:" "${WMQ_MESSAGES}"

echo ""

# ═══════════════════════════════════════════════════════════════
# SYSTEM METRICS
# ═══════════════════════════════════════════════════════════════
echo -e "${GREEN}💻 SYSTEM METRICS${NC}"
echo "────────────────────────────────────────────────────────────"

# JVM Memory
JVM_USED=$(get_metric "jvm.memory.used" "VALUE")
JVM_MAX=$(get_metric "jvm.memory.max" "VALUE")

if [[ "$JVM_USED" != "N/A" ]]; then
    JVM_USED_MB=$(echo "scale=0; $JVM_USED / 1048576" | bc 2>/dev/null || echo "N/A")
    printf "  %-25s %s MB\n" "JVM Memory Used:" "${JVM_USED_MB}"
fi

# HikariCP connections
HIKARI_ACTIVE=$(get_metric "hikaricp.connections.active" "VALUE")
HIKARI_IDLE=$(get_metric "hikaricp.connections.idle" "VALUE")
HIKARI_PENDING=$(get_metric "hikaricp.connections.pending" "VALUE")

printf "  %-25s Active: %s, Idle: %s, Pending: %s\n" \
    "HikariCP Connections:" \
    "${HIKARI_ACTIVE}" \
    "${HIKARI_IDLE}" \
    "${HIKARI_PENDING}"

echo ""
echo -e "${CYAN}═══════════════════════════════════════════════════════════════${NC}"
echo -e "${YELLOW}Timestamp: $(date '+%Y-%m-%d %H:%M:%S')${NC}"
echo -e "${CYAN}═══════════════════════════════════════════════════════════════${NC}"
echo ""

# ═══════════════════════════════════════════════════════════════
# RAW JSON OUTPUT (optional with -v flag)
# ═══════════════════════════════════════════════════════════════
if [[ "$2" == "-v" || "$2" == "--verbose" ]]; then
    echo -e "${BLUE}📋 RAW METRIC DATA${NC}"
    echo "────────────────────────────────────────────────────────────"
    echo ""
    echo "order.total.count:"
    curl -s "${BASE_URL}/actuator/metrics/order.total.count" | python3 -m json.tool 2>/dev/null || echo "N/A"
    echo ""
    echo "order.event.total:"
    curl -s "${BASE_URL}/actuator/metrics/order.event.total" | python3 -m json.tool 2>/dev/null || echo "N/A"
    echo ""
fi
