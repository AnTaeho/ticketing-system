#!/usr/bin/env bash
# 지정석 예매(V7) 부하 실행: 회차를 새로 만들고 시나리오를 돌린 뒤, 정리 대기 → 정합성 → MySQL·카운터·Hikari·앱 로그 차이를 회차 폴더에 남긴다.
#
# 앱은 직접 띄운다. 공통 인자: --spring.jpa.show-sql=false --logging.level.org.hibernate.SQL=WARN
#   PG 정상(PG_OK): --ticketing.fake-pg.delay-min=50ms --ticketing.fake-pg.delay-max=150ms --ticketing.fake-pg.decline-rate=0 --ticketing.fake-pg.slow-rate=0
#   S1 오픈 러시     --ticketing.gate.enabled=<true|false> --ticketing.queue.capacity=5000 + PG_OK
#   S2 대기열 정원   --ticketing.gate.enabled=true --ticketing.queue.capacity=200 + PG_OK
#   S3 결제 장애     --ticketing.gate.enabled=true --ticketing.queue.capacity=5000 --ticketing.fake-pg.delay-min=50ms --ticketing.fake-pg.delay-max=150ms
#                    --ticketing.fake-pg.decline-rate=0.1 --ticketing.fake-pg.slow-rate=0.1 --ticketing.fake-pg.slow-delay=5s --ticketing.pg.read-timeout=3s
#   S4 서버 두 대    S1(게이트 ON) 인자로 두 번 띄우고 두 번째에만 --server.port=8081
# 예: ./gradlew bootJar && java -jar build/libs/ticketing-system-0.0.1-SNAPSHOT.jar <인자> >app-8080.log 2>&1 &
#
# 사용법: MYSQL_PWD=<비밀번호> load-test/run-booking.sh <S1|S2|S3|S4> [on|off] [runs]
# 환경 변수: USERS·RAMP_SECONDS(시나리오 기본값을 덮는다) MAX_WAIT_SECONDS(S2 입장 대기 한도, 120)
#           BASE_URL(http://localhost:8080) BASE_URL2(S4, http://localhost:8081) MYSQL_USER(root) MYSQL_DB(ticketing)
#           SETTLE_SECONDS(11) DRAIN_SECONDS(120) MAX_LOAD(3.0) LOAD_WAIT_SECONDS(2400) PERF_OPEN_MINUTES(30)
#           APP_LOGS(앱 로그 경로, 공백 구분. 주면 회차 구간의 WARN·ERROR·커넥션 고갈 건수를 센다) RESULTS_DIR(load-test/results)
# 결과: RESULTS_DIR/<시나리오>-<gate>-<시각>/ 회차 폴더. summary.txt가 결과 한 줄이다.
set -euo pipefail

SCENARIO="${1:-}"
GATE="${2:-on}"
RUNS="${3:-1}"
if ! [[ "$SCENARIO" =~ ^S[1-4]$ ]] || [[ "$GATE" != "on" && "$GATE" != "off" ]] || ! [[ "$RUNS" =~ ^[0-9]+$ ]]; then
  echo "사용법: $0 <S1|S2|S3|S4> [on|off] [runs]" >&2
  exit 2
fi
if [[ "$SCENARIO" != "S1" && "$GATE" == "off" ]]; then
  echo "게이트 OFF는 S1만 비교한다" >&2
  exit 2
fi

REPO="$(cd "$(dirname "$0")/.." && pwd)"
GATLING_DIR="$REPO/load-test/gatling"
SCRIPTS="$REPO/load-test/scripts"
RESULTS_DIR="${RESULTS_DIR:-$REPO/load-test/results}"
BASE_URL="${BASE_URL:-http://localhost:8080}"
BASE_URL2="${BASE_URL2:-http://localhost:8081}"
SETTLE_SECONDS="${SETTLE_SECONDS:-11}"
DRAIN_SECONDS="${DRAIN_SECONDS:-120}"
MAX_LOAD="${MAX_LOAD:-3.0}"
LOAD_WAIT_SECONDS="${LOAD_WAIT_SECONDS:-2400}"
PERF_OPEN_MINUTES="${PERF_OPEN_MINUTES:-30}"
MAX_WAIT_SECONDS="${MAX_WAIT_SECONDS:-120}"
APP_LOGS="${APP_LOGS:-}"
MYSQL_USER="${MYSQL_USER:-root}"
MYSQL_DB="${MYSQL_DB:-ticketing}"
: "${MYSQL_PWD:?MYSQL_PWD 환경 변수가 필요하다}"
export MYSQL_PWD MYSQL_USER MYSQL_DB

case "$SCENARIO" in
  S2) SIMULATION=QueueCapacitySimulation; USERS="${USERS:-5000}"; RAMP_SECONDS="${RAMP_SECONDS:-20}" ;;
  *)  SIMULATION=ScenarioCSimulation;     USERS="${USERS:-2000}"; RAMP_SECONDS="${RAMP_SECONDS:-10}" ;;
esac
# S4만 두 서버를 쓴다. Gatling은 사용자마다 주소를 번갈아 쓴다
APP_URLS=("$BASE_URL")
[[ "$SCENARIO" == "S4" ]] && APP_URLS+=("$BASE_URL2")

for cmd in curl jq mysql mvn python3 redis-cli; do
  command -v "$cmd" >/dev/null || { echo "$cmd 가 필요하다" >&2; exit 2; }
done
mkdir -p "$RESULTS_DIR"

sql() { mysql -u"$MYSQL_USER" -N -B "$MYSQL_DB" -e "$1"; }

post_json() {
  curl -sf -X POST "$BASE_URL$1" -H 'Content-Type: application/json' -d "$2"
}

now_ms() { python3 -c 'import time; print(time.time_ns() // 1_000_000)'; }

load1() { sysctl -n vm.loadavg | awk '{print $2}'; }

# 1분 부하 평균이 MAX_LOAD 밑으로 내려올 때까지 30초 간격으로 기다린다. 기다린 초를 돌려준다
wait_for_quiet() {
  local waited=0 l
  while :; do
    l="$(load1)"
    echo "$(date +%H:%M:%S) load1=$l" >>"$1"
    if python3 -c "import sys; sys.exit(0 if $l < $MAX_LOAD else 1)"; then
      echo "$waited"
      return
    fi
    if (( waited >= LOAD_WAIT_SECONDS )); then
      echo "!!! ${LOAD_WAIT_SECONDS}s 기다려도 부하 평균이 $MAX_LOAD 밑으로 내려오지 않았다" >&2
      exit 1
    fi
    sleep 30
    waited=$((waited + 30))
  done
}

# Com_update Com_select Innodb_row_lock_waits Innodb_row_lock_time(ms) 순서
mysql_status() {
  sql "SHOW GLOBAL STATUS WHERE Variable_name IN ('Com_update','Com_select','Innodb_row_lock_waits','Innodb_row_lock_time')" \
    | awk '{v[$1]=$2} END {print v["Com_update"], v["Com_select"], v["Innodb_row_lock_waits"], v["Innodb_row_lock_time"]}'
}

# 운영 집계의 카운터를 "이름/태그값 수" 줄로 펼치고 서버들의 값을 더한다. 카운터는 서버가 켜진 뒤 누적이라 전후 차이만 쓴다
counters() {
  local url
  for url in "${APP_URLS[@]}"; do
    curl -sf "$url/api/ops/performances/$1/summary" \
      | jq -r '.counters | to_entries[] | .key as $n | (.value.values | to_entries[] | "\($n)/\(.key) \(.value)"), "\($n)/_total \(.value.total)"'
  done | awk '{s[$1]+=$2} END {for (k in s) print k, s[k]}' | LC_ALL=C sort
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

# 서버들의 Hikari 커넥션 대기 시간 초과 누적 수의 합
hikari_timeouts() {
  local url total=0 v
  for url in "${APP_URLS[@]}"; do
    v="$(curl -sf "$url/actuator/metrics/hikaricp.connections.timeout" | jq '.measurements[0].value' 2>/dev/null || echo 0)"
    total="$(python3 -c "print(int($total + ${v:-0}))")"
  done
  echo "$total"
}

# 0.5초마다 서버별 pending·active 커넥션 수를 남긴다
sample_hikari() {
  local url pending active
  while :; do
    for url in "${APP_URLS[@]}"; do
      pending="$(curl -sf -m 1 "$url/actuator/metrics/hikaricp.connections.pending" | jq '.measurements[0].value' 2>/dev/null || echo NA)"
      active="$(curl -sf -m 1 "$url/actuator/metrics/hikaricp.connections.active" | jq '.measurements[0].value' 2>/dev/null || echo NA)"
      echo "$(now_ms) $url ${pending:-NA} ${active:-NA}"
    done
    sleep 0.5
  done
}

# 10초마다 부하 평균과 CPU 상위 프로세스를 남긴다
monitor() {
  while :; do
    echo "== $(date +%H:%M:%S) loadavg $(sysctl -n vm.loadavg)"
    ps -Ao pid=,pcpu=,args= -r | head -6 | cut -c1-400
    sleep 10
  done
}

# 우리 앱·Gatling·MySQL·Redis가 아닌 프로세스가 200%를 넘은 샘플을 찾는다. 있으면 오염이다
contamination() {
  awk '!/^==/ && $2+0 > 200 && !/gatling|plexus-classworlds|ticketing-system|mysqld|redis-server/ {print $1 ":" $2 ":" $3}' "$1" \
    | sort -u | paste -sd, -
}

max_load1() { awk '/^==/ {gsub(/[{}]/, ""); if ($4+0 > m) m = $4+0} END {print m+0}' "$1"; }

# 앱 로그들의 줄 수(공백 구분)
log_lines() {
  local f
  for f in $APP_LOGS; do wc -l <"$f" | tr -d ' '; done | paste -sd' ' -
}

# 회차 구간의 앱 로그에서 WARN·ERROR·커넥션 고갈 건수를 세고 자주 나온 문장을 남긴다
app_log_summary() {
  local -a before=($1) after=($2)
  local i=0 f slice warn=0 error=0 exhausted=0 w e x
  for f in $APP_LOGS; do
    slice="$(sed -n "$((before[i] + 1)),$((after[i]))p" "$f")"
    w="$(grep -c ' WARN ' <<<"$slice" || true)"
    e="$(grep -c ' ERROR ' <<<"$slice" || true)"
    x="$(grep -c 'Connection is not available' <<<"$slice" || true)"
    warn=$((warn + w)); error=$((error + e)); exhausted=$((exhausted + x))
    {
      echo "-- $f lines $((before[i] + 1))..${after[i]} warn=$w error=$e exhausted=$x"
      grep -E ' (WARN|ERROR) ' <<<"$slice" | sed -E 's/^.*(WARN|ERROR) [0-9]+ --- (\[[^]]*\] *)*//; s/[0-9]+/N/g' \
        | cut -c1-200 | sort | uniq -c | sort -rn | head -8 || true
    } >>"$3"
    i=$((i + 1))
  done
  echo "app_warn=$warn app_error=$error hikari_exhausted_log=$exhausted"
}

create_show() {
  local stamp
  stamp="$(date +%Y%m%d-%H%M%S)"
  post_json /api/admin/shows "{\"title\":\"부하 테스트 $SCENARIO $stamp\",\"venueName\":\"부하 테스트홀\"}" | jq -r .showId
}

# 4구역 × 10열 × 20석 = 800석. A구역 1열 20석이 인기석이다. 지난 회차가 스케줄러에 계속 잡히지 않게 PERF_OPEN_MINUTES 뒤에 닫는다
create_performance() {
  local open_at starts_at
  open_at="$(sql "SELECT DATE_FORMAT(NOW() - INTERVAL 1 MINUTE, '%Y-%m-%dT%H:%i:%s')")"
  starts_at="$(sql "SELECT DATE_FORMAT(NOW() + INTERVAL $PERF_OPEN_MINUTES MINUTE, '%Y-%m-%dT%H:%i:%s')")"
  post_json /api/admin/performances \
    "{\"showId\":$SHOW_ID,\"startsAt\":\"$starts_at\",\"openAt\":\"$open_at\",\"sections\":4,\"rows\":10,\"seatsPerRow\":20}" \
    | jq -r .performanceId
}

# 결제·hold·Outbox가 끝날 때까지 남은 일(열린 결제 + PAYING·REFUNDING hold + 대기·처리 중 Outbox)
open_work() {
  sql "SELECT
    (SELECT COUNT(*) FROM booking_payment bp JOIN seat_hold h ON h.id = bp.hold_id
      WHERE h.performance_id = $1 AND bp.status IN ('REQUESTED','UNKNOWN'))
  + (SELECT COUNT(*) FROM seat_hold WHERE performance_id = $1 AND status IN ('PAYING','REFUNDING'))
  + (SELECT COUNT(*) FROM outbox_task o WHERE o.status IN ('PENDING','PROCESSING') AND (
       (o.task_type = 'TICKET_ISSUED' AND o.aggregate_id IN (SELECT id FROM booking WHERE performance_id = $1))
    OR (o.task_type = 'REFUND_REQUESTED' AND o.aggregate_id IN (SELECT bp.id FROM booking_payment bp
          JOIN seat_hold h ON h.id = bp.hold_id WHERE h.performance_id = $1))))"
}

# 남은 일이 0이 될 때까지 2초 간격으로 DRAIN_SECONDS까지 기다린다. "걸린 초 남은 일" 을 돌려준다
drain() {
  local start now left
  start="$(now_ms)"
  while :; do
    left="$(open_work "$1")"
    now="$(now_ms)"
    if (( left == 0 )) || (( now - start >= DRAIN_SECONDS * 1000 )); then
      python3 -c "print(round(($now - $start) / 1000, 1), $left)"
      return
    fi
    sleep 2
  done
}

run_gatling() {
  local urls
  urls="$(IFS=,; echo "${APP_URLS[*]}")"
  (cd "$GATLING_DIR" && mvn -B gatling:test -Dgatling.simulationClass="$SIMULATION" \
    -DPERFORMANCE_ID="$1" -DUSERS="$USERS" -DRAMP_SECONDS="$RAMP_SECONDS" -DBASE_URLS="$urls" \
    -DMAX_WAIT_SECONDS="$MAX_WAIT_SECONDS" -DUSER_LOG="$2/users.csv") >"$2/gatling.log" 2>&1
}

latest_simulation_dir() {
  local prefix
  prefix="$(tr '[:upper:]' '[:lower:]' <<<"$SIMULATION")"
  ls -td "$GATLING_DIR"/target/gatling/"$prefix"-* | head -1
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

# 회차 하나: 부하 확인 → 회차 생성 → 쉬는 동안 쓰기 → Gatling → 정리 대기 → 정합성 → 결과 한 줄
run_once() {
  local run="$1" stamp dir pid waited start_load idle u0 s0 w0 t0 u1 s1 w1 t1 su0 ss0 sw0 st0
  local start_ms end_ms gatling_exit sim_dir client drain_out drain_s drain_left before after counters_diff
  local db_hold check inv hik0 hik1 lines0 lines1 applog mon_pid hik_pid contaminated hik_max summary

  stamp="$(date +%Y%m%d-%H%M%S)"
  dir="$RESULTS_DIR/$SCENARIO-$GATE-$run-$stamp"
  mkdir -p "$dir"
  waited="$(wait_for_quiet "$dir/load-wait.txt")"
  start_load="$(load1)"

  pid="$(create_performance)"
  echo ">>> [$SCENARIO $GATE run $run/$RUNS] performance $pid, ${SETTLE_SECONDS}s settle (dir: $dir)"
  read -r su0 ss0 sw0 st0 <<<"$(mysql_status)"
  sleep "$SETTLE_SECONDS"
  read -r u0 s0 w0 t0 <<<"$(mysql_status)"
  idle="idle_com_update_per_s=$(python3 -c "print(round(($u0 - $su0) / $SETTLE_SECONDS, 1))") idle_com_select_per_s=$(python3 -c "print(round(($s0 - $ss0) / $SETTLE_SECONDS, 1))")"

  before="$dir/counters-before.txt"
  after="$dir/counters-after.txt"
  counters "$pid" >"$before"
  hik0="$(hikari_timeouts)"
  lines0="$(log_lines)"

  monitor >"$dir/monitor.txt" 2>&1 &
  mon_pid=$!
  sample_hikari >"$dir/hikari.txt" 2>&1 &
  hik_pid=$!

  echo ">>> gatling $SIMULATION users=$USERS ramp=${RAMP_SECONDS}s"
  start_ms="$(now_ms)"
  gatling_exit=0
  run_gatling "$pid" "$dir" || gatling_exit=$?
  end_ms="$(now_ms)"
  read -r u1 s1 w1 t1 <<<"$(mysql_status)"

  drain_out="$(drain "$pid")"
  read -r drain_s drain_left <<<"$drain_out"
  kill "$mon_pid" "$hik_pid" 2>/dev/null || true
  wait "$mon_pid" "$hik_pid" 2>/dev/null || true

  awk '/---- Global Information/ {p=1} p {print} p && /^====/ {exit}' "$dir/gatling.log" >"$dir/gatling-summary.txt"
  if [[ $gatling_exit -ne 0 ]]; then
    echo "!!! gatling 실패(exit $gatling_exit). 로그 끝:" >&2
    tail -n 40 "$dir/gatling.log" >&2
    exit 1
  fi
  sim_dir="$(latest_simulation_dir)"
  client="$(python3 "$SCRIPTS/summarize_simulation.py" "$sim_dir/simulation.log" --users "$dir/users.csv" --json "$dir/simulation.json")"

  counters "$pid" >"$after"
  counters_diff="$(counter_diff "$before" "$after")"
  echo "$counters_diff" >"$dir/counters-diff.txt"
  db_hold="$(counter_value "$counters_diff" ticketing.hold.db/_total)"
  check="$(gate_check "$counters_diff")"
  hik1="$(hikari_timeouts)"
  lines1="$(log_lines)"
  applog=""
  if [[ -n "$APP_LOGS" ]]; then
    applog="$(app_log_summary "$lines0" "$lines1" "$dir/applog.txt")"
  fi

  local ops_args=()
  for url in "${APP_URLS[@]}"; do ops_args+=(--ops-url "$url"); done
  inv="$(python3 "$SCRIPTS/invariants.py" "$pid" --gate "$GATE" "${ops_args[@]}" --json "$dir/invariants.json")"
  echo "$inv" >"$dir/invariants.txt"

  contaminated="$(contamination "$dir/monitor.txt")"
  hik_max="$(awk '$3 != "NA" && $3+0 > m {m = $3+0} END {print m+0}' "$dir/hikari.txt")"

  summary="scenario=$SCENARIO gate=$GATE gate_check=$check users=$USERS pid=$pid $client"
  summary+=" confirmed=$(sed -nE 's/.*confirmed_bookings=([0-9]+).*/\1/p' <<<"$inv")"
  summary+=" overbooked=$(sed -nE 's/.*overbooked_seats=([0-9]+).*/\1/p' <<<"$inv")"
  summary+=" violations=$(sed -nE 's/.*violations=([^ ]+).*/\1/p' <<<"$inv")"
  summary+=" db_hold=$db_hold com_update=$((u1 - u0)) com_select=$((s1 - s0)) row_lock_waits=$((w1 - w0)) row_lock_time_ms=$((t1 - t0))"
  summary+=" $idle hikari_pending_max=$hik_max hikari_timeout=$(python3 -c "print(int($hik1 - $hik0))") $applog"
  summary+=" drain_s=$drain_s drain_left=$drain_left wall_s=$(python3 -c "print(round(($end_ms - $start_ms) / 1000, 1))")"
  summary+=" start_load1=$start_load max_load1=$(max_load1 "$dir/monitor.txt") load_waited_s=$waited contaminated=${contaminated:-none}"
  {
    echo "start_ms=$start_ms"
    echo "end_ms=$end_ms"
    echo "simulation_dir=$sim_dir"
    echo "app_urls=${APP_URLS[*]}"
  } >"$dir/meta.txt"

  echo ">>> invariants: $inv"
  echo ">>> counters: $counters_diff"
  echo "$summary"
  echo "$summary" >"$dir/summary.txt"
  echo ">>> saved $dir"
}

for url in "${APP_URLS[@]}"; do
  curl -sf "$url/actuator/health" >/dev/null || { echo "앱이 $url 에서 응답하지 않는다" >&2; exit 1; }
done

SHOW_ID="$(create_show)"
echo ">>> show $SHOW_ID ($SCENARIO gate=$GATE users=$USERS runs=$RUNS)"

for run in $(seq 1 "$RUNS"); do
  run_once "$run"
done
