"""부하 회차 하나의 정합성을 DB(invariants.sql)와 Redis 게이트 키로 확인한다.

사용법: MYSQL_PWD=<비밀번호> python3 load-test/scripts/invariants.py <회차 ID> [--gate on|off] [--ops-url URL] [--json out.json]
출력: "키=값" 한 줄. 끝의 violations는 어긋난 검사 이름이다(없으면 none).
환경 변수: MYSQL_USER(root) MYSQL_DB(ticketing) REDIS_DB(0)
"""
import argparse
import json
import os
import subprocess
import sys
import urllib.request
from pathlib import Path

SQL_FILE = Path(__file__).resolve().parent / "invariants.sql"


def run(cmd, stdin=None):
    return subprocess.run(cmd, input=stdin, capture_output=True, text=True, check=True).stdout


def mysql(sql):
    user = os.environ.get("MYSQL_USER", "root")
    db = os.environ.get("MYSQL_DB", "ticketing")
    return run(["mysql", f"-u{user}", "-N", "-B", db], stdin=sql)


def db_counts(pid):
    values = {}
    for line in mysql(f"SET @pid = {pid};\n" + SQL_FILE.read_text(encoding="utf-8")).splitlines():
        key, _, value = line.partition("\t")
        values[key] = None if value == "null" else int(float(value))
    return values


def redis(*args):
    return run(["redis-cli", "-n", os.environ.get("REDIS_DB", "0"), *args])


def redis_values(pattern):
    """패턴에 맞는 키와 값(holdToken)."""
    keys = [k for k in redis("--scan", "--pattern", pattern).splitlines() if k]
    values = {}
    for i in range(0, len(keys), 200):
        chunk = keys[i:i + 200]
        for key, value in zip(chunk, redis("MGET", *chunk).splitlines()):
            values[key] = value
    return values


def gate_mismatch(pid):
    """게이트 키와 DB가 어긋난 수. 살아 있는 좌석(HELD·SOLD)은 그 hold 토큰을 값으로 가진 키가 있어야 한다."""
    live_seats = {}
    for line in mysql(f"""SELECT s.id, h.hold_token FROM seat s JOIN seat_hold h ON h.id = s.hold_id
                           WHERE s.performance_id = {pid} AND s.status IN ('HELD', 'SOLD')""").splitlines():
        seat_id, token = line.split("\t")
        live_seats[f"seat:{{p:{pid}}}:{seat_id}"] = token
    live_user_tokens = set(mysql(f"""SELECT hold_token FROM seat_hold
                                      WHERE performance_id = {pid} AND status IN ('HELD', 'PAYING')""").split())
    seat_keys = redis_values(f"seat:{{p:{pid}}}:*")
    user_keys = redis_values(f"userhold:{{p:{pid}}}:*")
    return {
        "gate_seat_keys": len(seat_keys),
        "gate_userhold_keys": len(user_keys),
        "gate_live_seats": len(live_seats),
        # 키는 있는데 같은 토큰의 살아 있는 좌석이 없다(고아 키 정리 대상)
        "gate_orphan_seat_keys": sum(1 for k, v in seat_keys.items() if live_seats.get(k) != v),
        "gate_orphan_userhold_keys": sum(1 for v in user_keys.values() if v not in live_user_tokens),
        # 살아 있는 좌석인데 키가 없거나 다른 토큰이다(게이트가 막지 못하는 자리)
        "gate_seats_without_key": sum(1 for k, v in live_seats.items() if seat_keys.get(k) != v),
    }


def ops_gate_keys(urls, pid):
    """운영 집계 API의 게이트 키 수. 여러 서버여도 Redis는 하나라 첫 응답만 쓴다."""
    for url in urls:
        try:
            with urllib.request.urlopen(f"{url}/api/ops/performances/{pid}/summary", timeout=5) as resp:
                body = json.load(resp)
            return {"ops_gate_seat_keys": body["gateKeys"]["seat"],
                    "ops_gate_userhold_keys": body["gateKeys"]["userHold"]}
        except OSError:
            continue
    return {"ops_gate_seat_keys": None, "ops_gate_userhold_keys": None}


def violations(v, gate):
    checks = {
        "overbooked": v["overbooked_seats"] == 0,
        "sold_vs_booking_seats": v["sold_seats"] == v["confirmed_booking_seats"],
        "open_payments": v["open_payments"] == 0,
        "paying_holds": v["paying_holds"] == 0,
        "refunding_holds": v["refunding_holds"] == 0,
        "held_past_expiry": v["held_seats_past_expiry"] == 0,
        "outbox_failed": v["outbox_failed"] == 0,
        "outbox_pending": v["outbox_pending"] == 0,
        "booking_seats_le_total": v["confirmed_booking_seats"] <= v["total_seats"],
    }
    # 게이트를 끄면 키를 만들지 않으므로 키 비교는 켰을 때만 판정한다
    if gate == "on":
        checks["gate_orphan_keys"] = v["gate_orphan_seat_keys"] + v["gate_orphan_userhold_keys"] == 0
        checks["gate_seats_without_key"] = v["gate_seats_without_key"] == 0
    return [name for name, ok in checks.items() if not ok]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("pid", type=int)
    parser.add_argument("--gate", choices=["on", "off"], default="on")
    parser.add_argument("--ops-url", action="append", default=[])
    parser.add_argument("--json")
    args = parser.parse_args()
    if "MYSQL_PWD" not in os.environ:
        sys.exit("MYSQL_PWD 환경 변수가 필요하다")

    v = db_counts(args.pid)
    v["outbox_failed"] = sum(n for k, n in v.items() if k.startswith("outbox_") and k.endswith("_FAILED"))
    v["outbox_pending"] = sum(n for k, n in v.items()
                              if k.startswith("outbox_") and (k.endswith("_PENDING") or k.endswith("_PROCESSING")))
    v["sold_out"] = int(v["sold_seats"] == v["total_seats"])
    v.update(gate_mismatch(args.pid))
    v.update(ops_gate_keys(args.ops_url, args.pid))
    bad = violations(v, args.gate)
    v["violations"] = bad

    print(" ".join(f"{k}={'null' if x is None else x}" for k, x in v.items() if k != "violations")
          + f" violations={'+'.join(bad) if bad else 'none'}")
    if args.json:
        Path(args.json).write_text(json.dumps(v, ensure_ascii=False, indent=1), encoding="utf-8")


if __name__ == "__main__":
    main()
