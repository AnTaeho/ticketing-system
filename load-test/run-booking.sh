#!/usr/bin/env bash
# 지정석 예매(V7) 부하 실행: 회차를 새로 만들고 시나리오 C를 돌린 뒤 MySQL·카운터 차이와 오버부킹을 기록한다.
#
# 앱은 직접 띄운다. 게이트 ON/OFF는 enabled 값만 바꿔 다시 띄운다.
#   ./gradlew bootRun --args='--ticketing.gate.enabled=true --ticketing.queue.capacity=5000 --ticketing.fake-pg.delay-min=50ms --ticketing.fake-pg.delay-max=150ms --ticketing.fake-pg.decline-rate=0 --ticketing.fake-pg.slow-rate=0'
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
SUMMARIZE="$REPO/load-test/scripts/summarize_simulation.py"
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

post_json() {
  curl -sf -X POST "$BASE_URL$1" -H 'Content-Type: application/json' -d "$2"
}

now_ns() { python3 -c 'import time; print(time.time_ns())'; }

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

# 같은 이름의 전후 값 차이를 "이름=차이 "로 잇는다. 이번 실행에서 0이 아닌 것만 남긴다
counter_diff() {
  LC_ALL=C join -a2 -e 0 -o '0,1.2,2.2' "$1" "$2" \
    | awk '{d=$3-$2; if (d != 0) printf "%s=%d ", $1, d}'
}

# counter_diff 결과에서 이름 하나의 값. 없으면 0
counter_value() {
  local v
  v="$(awk -v k="$2" '{for (i=1;i<=NF;i++) {split($i,a,"="); if (a[1]==k) {print a[2]; exit}}}' <<<"$1")"
  echo "${v:-0}"
}

create_show() {
  local stamp
  stamp="$(date +%Y%m%d-%H%M%S)"
  post_json /api/admin/shows "{\"title\":\"부하 테스트 $stamp\",\"venueName\":\"부하 테스트홀\"}" | jq -r .showId
}

# 4구역 × 10열 × 20석 = 800석. A구역 1열 20석이 인기석이다
create_performance() {
  local open_at starts_at
  open_at="$(sql "SELECT DATE_FORMAT(NOW() - INTERVAL 1 MINUTE, '%Y-%m-%dT%H:%i:%s')")"
  starts_at="$(sql "SELECT DATE_FORMAT(NOW() + INTERVAL 1 DAY, '%Y-%m-%dT%H:%i:%s')")"
  post_json /api/admin/performances \
    "{\"showId\":$SHOW_ID,\"startsAt\":\"$starts_at\",\"openAt\":\"$open_at\",\"sections\":4,\"rows\":10,\"seatsPerRow\":20}" \
    | jq -r .performanceId
}

# 회차가 열린 회차 목록과 좌석맵 스냅샷에 들어갈 때까지 기다리며 쉬는 동안의 초당 Com_update를 잰다
measure_idle_updates() {
  local u0 u1
  read -r u0 _ _ <<<"$(mysql_status)"
  sleep "$SETTLE_SECONDS"
  read -r u1 _ _ <<<"$(mysql_status)"
  python3 -c "print(round(($u1 - $u0) / $SETTLE_SECONDS, 1))"
}

run_gatling() {
  (cd "$GATLING_DIR" && mvn -B gatling:test -Dgatling.simulationClass=ScenarioCSimulation \
    -DPERFORMANCE_ID="$1" -DUSERS="$USERS" -DRAMP_SECONDS="$RAMP_SECONDS" -DBASE_URL="$BASE_URL") >"$2" 2>&1
}

# Global Information을 보여 준다. Gatling이 실패했으면 로그 끝을 보이고 끝낸다
show_gatling_result() {
  local log="$1" exit_code="$2"
  awk '/---- Global Information/ {p=1} p {print} p && /^====/ {exit}' "$log"
  if [[ $exit_code -ne 0 ]]; then
    echo "!!! gatling 실패(exit $exit_code). 로그 끝:" >&2
    tail -n 40 "$log" >&2
    exit 1
  fi
}

latest_simulation_dir() {
  ls -td "$GATLING_DIR"/target/gatling/scenariocsimulation-* | head -1
}

# 앱의 실제 게이트 설정과 인자가 맞는지 카운터로 확인한다
gate_check() {
  local counters_diff="$1" ok taken skipped
  ok="$(counter_value "$counters_diff" ticketing.hold.gate/ok)"
  taken="$(counter_value "$counters_diff" ticketing.hold.gate/seat_taken)"
  skipped="$(counter_value "$counters_diff" ticketing.hold.gate/skipped)"
  if [[ "$GATE" == "on" && "$skipped" -ne 0 ]] || [[ "$GATE" == "off" && $((ok + taken)) -ne 0 ]]; then
    echo MISMATCH
  else
    echo ok
  fi
}

# 같은 좌석이 확정 예매 두 건 이상에 들어간 좌석 수. 0이어야 한다
count_overbooked() {
  sql "SELECT COUNT(*) FROM (SELECT bs.seat_id FROM booking_seat bs JOIN booking b ON b.id = bs.booking_id
    WHERE b.status = 'CONFIRMED' AND b.performance_id = $1 GROUP BY bs.seat_id HAVING COUNT(*) > 1) t"
}

count_confirmed() {
  sql "SELECT COUNT(*) FROM booking WHERE performance_id = $1 AND status = 'CONFIRMED'"
}

seat_status_counts() {
  sql "SELECT GROUP_CONCAT(CONCAT(status, ':', n) ORDER BY status) FROM (SELECT status, COUNT(*) n FROM seat WHERE performance_id = $1 GROUP BY status) t"
}

# 한 회차를 만들고 부하를 건 뒤 결과 한 줄을 남긴다
run_once() {
  local run="$1"
  local stamp pid idle_rate u0 w0 t0 u1 w1 t1 log start_ns end_ns gatling_exit wall_s sim_dir client
  local before after counters_diff db_hold check overbooked confirmed seats summary result_file

  stamp="$(date +%Y%m%d-%H%M%S)"
  pid="$(create_performance)"
  echo ">>> [run $run/$RUNS] performance $pid, ${SETTLE_SECONDS}s settle"
  idle_rate="$(measure_idle_updates)"

  before="$RESULTS_DIR/.counters-before-$pid"
  after="$RESULTS_DIR/.counters-after-$pid"
  read -r u0 w0 t0 <<<"$(mysql_status)"
  counters "$pid" >"$before"

  log="$RESULTS_DIR/booking-$GATE-$USERS-$stamp-gatling.log"
  echo ">>> gatling (log: $log)"
  start_ns="$(now_ns)"
  gatling_exit=0
  run_gatling "$pid" "$log" || gatling_exit=$?
  end_ns="$(now_ns)"

  read -r u1 w1 t1 <<<"$(mysql_status)"
  counters "$pid" >"$after"
  show_gatling_result "$log" "$gatling_exit"

  sim_dir="$(latest_simulation_dir)"
  client="$(python3 "$SUMMARIZE" "$sim_dir/simulation.log")"

  counters_diff="$(counter_diff "$before" "$after")"
  rm -f "$before" "$after"
  db_hold="$(counter_value "$counters_diff" ticketing.hold.db/_total)"
  check="$(gate_check "$counters_diff")"

  overbooked="$(count_overbooked "$pid")"
  confirmed="$(count_confirmed "$pid")"
  seats="$(seat_status_counts "$pid")"

  wall_s="$(python3 -c "print(round(($end_ns - $start_ns) / 1e9, 1))")"
  summary="gate=$GATE gate_check=$check users=$USERS pid=$pid $client confirmed=$confirmed seats=$seats overbooked=$overbooked db_hold=$db_hold com_update=$((u1 - u0)) row_lock_waits=$((w1 - w0)) row_lock_time_ms=$((t1 - t0)) idle_com_update_per_s=$idle_rate wall_s=$wall_s"
  result_file="$RESULTS_DIR/booking-$GATE-$USERS-$stamp.txt"

  echo ">>> overbooking rows: $overbooked (0이어야 한다)"
  echo ">>> counters: $counters_diff"
  echo ">>> report: $sim_dir/index.html"
  echo "$summary"
  echo "$summary counters: $counters_diff" >"$result_file"
  echo ">>> saved $result_file"
}

curl -sf "$BASE_URL/actuator/health" >/dev/null || { echo "앱이 $BASE_URL 에서 응답하지 않는다" >&2; exit 1; }

SHOW_ID="$(create_show)"
echo ">>> show $SHOW_ID (gate=$GATE users=$USERS runs=$RUNS)"

for run in $(seq 1 "$RUNS"); do
  run_once "$run"
done
