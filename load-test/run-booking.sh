#!/usr/bin/env bash
# 지정석 예매(V7) 부하 실행: 회차를 새로 만들고 시나리오 C를 돌린 뒤 MySQL·카운터 차이와 오버부킹을 기록한다.
#
# 앱은 직접 띄운다. 게이트 ON/OFF는 enabled 값만 바꿔 다시 띄운다.
#   ./gradlew bootRun --args='--booking.gate.enabled=true --booking.queue.capacity=5000 --booking.fake-pg.delay-min-ms=50 --booking.fake-pg.delay-max-ms=150 --booking.fake-pg.decline-rate=0 --booking.fake-pg.slow-rate=0'
#
# 사용법: MYSQL_PWD=<비밀번호> load-test/run-booking.sh <on|off> <users> [runs]
# 환경 변수: MYSQL_USER(root) MYSQL_DB(ticketing) BASE_URL(http://localhost:8080) RAMP_SECONDS(10) SETTLE_SECONDS(11)
# 결과 한 줄 요약: load-test/results/booking-<gate>-<users>-<시각>.txt
set -euo pipefail

GATE="${1:-}"
USERS="${2:-}"
RUNS="${3:-1}"
if [[ "$GATE" != "on" && "$GATE" != "off" ]] || ! [[ "$USERS" =~ ^[0-9]+$ ]] || ! [[ "$RUNS" =~ ^[0-9]+$ ]]; then
  echo "사용법: $0 <on|off> <users> [runs]" >&2
  exit 2
fi

REPO="$(cd "$(dirname "$0")/.." && pwd)"
GATLING_DIR="$REPO/load-test/gatling"
RESULTS_DIR="$REPO/load-test/results"
BASE_URL="${BASE_URL:-http://localhost:8080}"
RAMP_SECONDS="${RAMP_SECONDS:-10}"
SETTLE_SECONDS="${SETTLE_SECONDS:-11}"
MYSQL_USER="${MYSQL_USER:-root}"
MYSQL_DB="${MYSQL_DB:-ticketing}"
: "${MYSQL_PWD:?MYSQL_PWD 환경 변수가 필요하다}"
export MYSQL_PWD

for cmd in curl jq mysql mvn python3; do
  command -v "$cmd" >/dev/null || { echo "$cmd 가 필요하다" >&2; exit 2; }
done
mkdir -p "$RESULTS_DIR"

sql() { mysql -u"$MYSQL_USER" -N -B "$MYSQL_DB" -e "$1"; }

# Com_update, Innodb_row_lock_waits, Innodb_row_lock_time(ms) 순서
mysql_status() {
  sql "SHOW GLOBAL STATUS WHERE Variable_name IN ('Com_update','Innodb_row_lock_waits','Innodb_row_lock_time')" \
    | awk '{v[$1]=$2} END {print v["Com_update"], v["Innodb_row_lock_waits"], v["Innodb_row_lock_time"]}'
}

# 운영 집계의 카운터를 "이름/태그값 수" 줄로 펼친다. 카운터는 서버가 켜진 뒤 누적이라 전후 차이만 쓴다
counters() {
  curl -sf "$BASE_URL/api/ops/performances/$1/summary" \
    | jq -r '.counters | to_entries[] | .key as $n | (.value.values | to_entries[] | "\($n)/\(.key) \(.value)"), "\($n)/_total \(.value.total)"' \
    | LC_ALL=C sort
}

post_json() {
  curl -sf -X POST "$BASE_URL$1" -H 'Content-Type: application/json' -d "$2"
}

curl -sf "$BASE_URL/actuator/health" >/dev/null || { echo "앱이 $BASE_URL 에서 응답하지 않는다" >&2; exit 1; }

STAMP_SHOW="$(date +%Y%m%d-%H%M%S)"
SHOW_ID="$(post_json /api/admin/shows "{\"title\":\"부하 테스트 $STAMP_SHOW\",\"venueName\":\"부하 테스트홀\"}" | jq -r .showId)"
echo ">>> show $SHOW_ID (gate=$GATE users=$USERS runs=$RUNS)"

for run in $(seq 1 "$RUNS"); do
  STAMP="$(date +%Y%m%d-%H%M%S)"
  OPEN_AT="$(sql "SELECT DATE_FORMAT(NOW() - INTERVAL 1 MINUTE, '%Y-%m-%dT%H:%i:%s')")"
  STARTS_AT="$(sql "SELECT DATE_FORMAT(NOW() + INTERVAL 1 DAY, '%Y-%m-%dT%H:%i:%s')")"
  # 4구역 × 10열 × 20석 = 800석. A구역 1열 20석이 인기석이다
  PID="$(post_json /api/admin/performances \
    "{\"showId\":$SHOW_ID,\"startsAt\":\"$STARTS_AT\",\"openAt\":\"$OPEN_AT\",\"sections\":4,\"rows\":10,\"seatsPerRow\":20}" \
    | jq -r .performanceId)"
  echo ">>> [run $run/$RUNS] performance $PID, ${SETTLE_SECONDS}s settle"

  # 회차가 열린 회차 목록과 좌석맵 스냅샷에 들어갈 때까지 기다리며 쉬는 동안의 Com_update도 잰다
  read -r IDLE_U0 _ _ <<<"$(mysql_status)"
  sleep "$SETTLE_SECONDS"
  read -r IDLE_U1 _ _ <<<"$(mysql_status)"

  read -r U0 W0 T0 <<<"$(mysql_status)"
  counters "$PID" >"$RESULTS_DIR/.counters-before-$PID"

  LOG="$RESULTS_DIR/booking-$GATE-$USERS-$STAMP-gatling.log"
  echo ">>> gatling (log: $LOG)"
  START_NS="$(python3 -c 'import time; print(time.time_ns())')"
  set +e
  (cd "$GATLING_DIR" && mvn -B gatling:test -Dgatling.simulationClass=ScenarioCSimulation \
    -DPERFORMANCE_ID="$PID" -DUSERS="$USERS" -DRAMP_SECONDS="$RAMP_SECONDS" -DBASE_URL="$BASE_URL") >"$LOG" 2>&1
  MVN_EXIT=$?
  set -e
  END_NS="$(python3 -c 'import time; print(time.time_ns())')"

  read -r U1 W1 T1 <<<"$(mysql_status)"
  counters "$PID" >"$RESULTS_DIR/.counters-after-$PID"

  awk '/---- Global Information/ {p=1} p {print} p && /^====/ {exit}' "$LOG"
  if [[ $MVN_EXIT -ne 0 ]]; then
    echo "!!! gatling 실패(exit $MVN_EXIT). 로그 끝:" >&2
    tail -n 40 "$LOG" >&2
    exit 1
  fi

  SIM_DIR="$(ls -td "$GATLING_DIR"/target/gatling/scenariocsimulation-* | head -1)"

  # 선점 응답 시간·상태와 단계별 수를 simulation.log에서 직접 센다(백분위는 nearest-rank)
  CLIENT="$(python3 - "$SIM_DIR/simulation.log" <<'PY'
import math, re, sys
reqs = {}
for line in open(sys.argv[1], encoding="utf-8"):
    f = line.rstrip("\n").split("\t")
    if f[0] != "REQUEST":
        continue
    name, start, end, status, msg = f[2], int(f[3]), int(f[4]), f[5], (f[6] if len(f) > 6 else "")
    reqs.setdefault(name, []).append((end - start, status, msg))

def pct(xs, p):
    xs = sorted(xs)
    return xs[max(0, math.ceil(p / 100 * len(xs)) - 1)] if xs else 0

def code(status, msg):
    if status == "OK":
        return "201"
    m = re.search(r"found (\d{3})", msg)
    return m.group(1) if m else "err"

holds = reqs.get("05 hold", []) + reqs.get("07 hold retry", [])
codes = {}
for _, st, msg in holds:
    c = code(st, msg)
    codes[c] = codes.get(c, 0) + 1
out = {
    "hold_req": len(holds),
    "hold_201": codes.pop("201", 0),
    "hold_409": codes.pop("409", 0),
    "hold_other": sum(codes.values()),
    "hold_p50_ms": pct([r[0] for r in holds], 50),
    "hold_p99_ms": pct([r[0] for r in holds], 99),
    "first_p50_ms": pct([r[0] for r in reqs.get("05 hold", [])], 50),
    "first_p99_ms": pct([r[0] for r in reqs.get("05 hold", [])], 99),
    "retry_req": len(reqs.get("07 hold retry", [])),
    "retry_p50_ms": pct([r[0] for r in reqs.get("07 hold retry", [])], 50),
    "retry_p99_ms": pct([r[0] for r in reqs.get("07 hold retry", [])], 99),
    "pay_req": len(reqs.get("08 payment", [])),
    "pay_ok": sum(1 for r in reqs.get("08 payment", []) if r[1] == "OK"),
    "ko_other_steps": sum(1 for n, rs in reqs.items() if "hold" not in n for r in rs if r[1] != "OK"),
}
if codes:
    out["hold_other_codes"] = "+".join(f"{k}:{v}" for k, v in sorted(codes.items()))
starts = [l for l in open(sys.argv[1], encoding="utf-8") if l.startswith("REQUEST")]
spans = [(int(l.split("\t")[3]), int(l.split("\t")[4])) for l in starts]
out["sim_s"] = round((max(e for _, e in spans) - min(s for s, _ in spans)) / 1000, 1) if spans else 0
print(" ".join(f"{k}={v}" for k, v in out.items()))
PY
)"

  # 같은 이름의 전후 값 차이. 이번 실행에서 0이 아닌 것만 남긴다
  COUNTER_DIFF="$(LC_ALL=C join -a2 -e 0 -o '0,1.2,2.2' "$RESULTS_DIR/.counters-before-$PID" "$RESULTS_DIR/.counters-after-$PID" \
    | awk '{d=$3-$2; if (d != 0) printf "%s=%d ", $1, d}')"
  rm -f "$RESULTS_DIR/.counters-before-$PID" "$RESULTS_DIR/.counters-after-$PID"
  cdiff() { awk -v k="$1" '{for (i=1;i<=NF;i++) {split($i,a,"="); if (a[1]==k) {print a[2]; exit}}} END {}' <<<"$COUNTER_DIFF"; }
  DB_HOLD="$(cdiff booking.hold.db/_total)"; DB_HOLD="${DB_HOLD:-0}"
  GATE_OK="$(cdiff booking.hold.gate/ok)"; GATE_OK="${GATE_OK:-0}"
  GATE_TAKEN="$(cdiff booking.hold.gate/seat_taken)"; GATE_TAKEN="${GATE_TAKEN:-0}"
  GATE_SKIPPED="$(cdiff booking.hold.gate/skipped)"; GATE_SKIPPED="${GATE_SKIPPED:-0}"

  # 앱의 실제 게이트 설정과 인자가 맞는지 카운터로 확인한다
  GATE_CHECK=ok
  if [[ "$GATE" == "on" && "$GATE_SKIPPED" -ne 0 ]] || [[ "$GATE" == "off" && $((GATE_OK + GATE_TAKEN)) -ne 0 ]]; then
    GATE_CHECK=MISMATCH
  fi

  OVERBOOKED="$(sql "SELECT COUNT(*) FROM (SELECT bs.seat_id FROM booking_seat bs JOIN booking b ON b.id = bs.booking_id
    WHERE b.status = 'CONFIRMED' AND b.performance_id = $PID GROUP BY bs.seat_id HAVING COUNT(*) > 1) t")"
  CONFIRMED="$(sql "SELECT COUNT(*) FROM booking WHERE performance_id = $PID AND status = 'CONFIRMED'")"
  SEATS="$(sql "SELECT GROUP_CONCAT(CONCAT(status, ':', n) ORDER BY status) FROM (SELECT status, COUNT(*) n FROM seat WHERE performance_id = $PID GROUP BY status) t")"

  WALL_S="$(python3 -c "print(round(($END_NS - $START_NS) / 1e9, 1))")"
  IDLE_RATE="$(python3 -c "print(round(($IDLE_U1 - $IDLE_U0) / $SETTLE_SECONDS, 1))")"
  SUMMARY="gate=$GATE gate_check=$GATE_CHECK users=$USERS pid=$PID $CLIENT confirmed=$CONFIRMED seats=$SEATS overbooked=$OVERBOOKED db_hold=$DB_HOLD com_update=$((U1 - U0)) row_lock_waits=$((W1 - W0)) row_lock_time_ms=$((T1 - T0)) idle_com_update_per_s=$IDLE_RATE wall_s=$WALL_S"

  echo ">>> overbooking rows: $OVERBOOKED (0이어야 한다)"
  echo ">>> counters: $COUNTER_DIFF"
  echo ">>> report: $SIM_DIR/index.html"
  echo "$SUMMARY"
  echo "$SUMMARY counters: $COUNTER_DIFF" >"$RESULTS_DIR/booking-$GATE-$USERS-$STAMP.txt"
  echo ">>> saved $RESULTS_DIR/booking-$GATE-$USERS-$STAMP.txt"
done
