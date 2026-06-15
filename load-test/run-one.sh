#!/bin/bash
# Usage: ./run-one.sh <SCENARIO: A|B> <VERSION: v1..v6> <USERS> <STOCK>
set -e

SCENARIO=$1   # A or B
VERSION=$2    # v1..v6
USERS=$3
STOCK=$4

MYSQL="mysql -uroot -pan902318 ticketing -N"
BASE=/Users/antaeho/Desktop/ticketing-system

VERSION_UPPER=$(echo "$VERSION" | tr 'a-z' 'A-Z')

case "$VERSION" in
  v1) LOCK_TYPE=NO_LOCK ;;
  v2) LOCK_TYPE=PESSIMISTIC ;;
  v3) LOCK_TYPE=OPTIMISTIC ;;
  v4) LOCK_TYPE=LETTUCE_SPIN ;;
  v5) LOCK_TYPE=REDISSON_PUBSUB ;;
  v6) LOCK_TYPE=KAFKA_QUEUE ;;
  *) echo "unknown version $VERSION"; exit 1 ;;
esac

echo ">>> [$SCENARIO-$VERSION-${USERS}u] reset stock=$STOCK"
curl -s -X POST "http://localhost:8080/api/concerts/1/reset?stock=$STOCK" > /dev/null

$MYSQL -e "DELETE FROM reservation WHERE concert_id=1; DELETE FROM outbox_event;" 2>/dev/null || true

cd "$BASE/load-test/gatling"
SIM="ScenarioA"
[ "$SCENARIO" = "B" ] && SIM="ScenarioB"

echo ">>> running gatling: $SIM $VERSION $USERS users"
mvn -q gatling:test -Dgatling.simulationClass=${SIM}Simulation -DVERSION=$VERSION -DUSERS=$USERS > /tmp/gatling-$SCENARIO-$VERSION.log 2>&1

# V6 is async (Kafka) - wait for consumer to catch up
if [ "$VERSION" = "v6" ]; then
  echo ">>> waiting 10s for V6 async consumer"
  sleep 10
fi

# find latest result dir for this simulation
SIM_LOWER=$(echo "$SIM" | tr 'A-Z' 'a-z')
RESULT_DIR=$(ls -dt target/gatling/${SIM_LOWER}simulation-* 2>/dev/null | head -1)
STATS="$RESULT_DIR/js/stats.json"

if [ ! -f "$STATS" ]; then
  echo "!!! stats.json not found at $STATS"
  exit 1
fi

TOTAL=$(python3 -c "import json;d=json.load(open('$STATS'));print(d['stats']['numberOfRequests']['total'])")
OK=$(python3 -c "import json;d=json.load(open('$STATS'));print(d['stats']['numberOfRequests']['ok'])")
KO=$(python3 -c "import json;d=json.load(open('$STATS'));print(d['stats']['numberOfRequests']['ko'])")
P99=$(python3 -c "import json;d=json.load(open('$STATS'));print(d['stats']['percentiles4']['total'])")
TPS=$(python3 -c "import json;d=json.load(open('$STATS'));print(round(d['stats']['meanNumberOfRequestsPerSecond']['total'],1))")
ERRRATE=$(python3 -c "print(round($KO/$TOTAL*100,2)) if $TOTAL>0 else print(0)")

SUCCESS_COUNT=$($MYSQL -e "SELECT COUNT(*) FROM reservation WHERE concert_id=1 AND status='SUCCESS';" 2>/dev/null)

OVERBOOKING=$((SUCCESS_COUNT - STOCK))
[ $OVERBOOKING -lt 0 ] && OVERBOOKING=0

echo ">>> total=$TOTAL ok=$OK ko=$KO p99=$P99 tps=$TPS errRate=$ERRRATE successCount=$SUCCESS_COUNT overBooking=$OVERBOOKING"

PAYLOAD=$(cat <<EOF
{
  "version": "$VERSION_UPPER",
  "lockType": "$LOCK_TYPE",
  "scenarioType": "SCENARIO_$SCENARIO",
  "concurrentUsers": $USERS,
  "initialStock": $STOCK,
  "totalRequests": $TOTAL,
  "successCount": $SUCCESS_COUNT,
  "overBookingCount": $OVERBOOKING,
  "tps": $TPS,
  "p99ResponseMs": $P99,
  "errorRate": $ERRRATE,
  "memo": "$SCENARIO-$VERSION-${USERS}u"
}
EOF
)

echo ">>> posting result"
curl -s -X POST "http://localhost:8080/api/test-results" \
  -H "Content-Type: application/json" \
  -d "$PAYLOAD" | python3 -m json.tool

echo ">>> done [$SCENARIO-$VERSION-${USERS}u]"
