"""Gatling simulation.log(과 사용자 기록 CSV)에서 단계별 응답 시간·상태 코드·초당 처리량을 센다.

사용법: python3 load-test/scripts/summarize_simulation.py <simulation.log> [--users users.csv] [--json out.json]
출력: "키=값" 한 줄. 백분위는 nearest-rank다. --json을 주면 단계별 분포와 초당 시계열을 파일로 남긴다.
pay_ok는 Gatling이 OK로 센 결제(200 또는 202)다. 확정 200과 결과 대기 202는 사용자 기록이 있을 때 pay_200·pay_202로 가른다.
heartbeat(생존 신호, 200 기대)와 leave(떠남, 204 기대)도 단계별 응답 시간과 상태 코드에 들어간다.
"""
import argparse
import csv
import json
import math
import re
import sys
from pathlib import Path

NAMES_FILE = Path(__file__).resolve().parent.parent / "gatling/src/test/resources/request-names.properties"

# 입장 대기 히스토그램 구간(초). 마지막 구간은 끝이 열려 있다
WAIT_BUCKETS_S = [0, 1, 2, 5, 10, 20, 30, 60, 90, 120]


def load_names(path):
    names = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        key, _, value = line.partition("=")
        names[key.strip()] = value.strip()
    return names


def read_log(log_path):
    """요청 이름별 (시작, 끝, 상태, 메시지) 목록과 사용자 시작·끝 시각 목록."""
    reqs, user_events = {}, []
    with open(log_path, encoding="utf-8") as f:
        for line in f:
            fields = line.rstrip("\n").split("\t")
            if fields[0] == "REQUEST":
                msg = fields[6].strip() if len(fields) > 6 else ""
                reqs.setdefault(fields[2], []).append((int(fields[3]), int(fields[4]), fields[5], msg))
            elif fields[0] == "USER":
                user_events.append((int(fields[3]), 1 if fields[2] == "START" else -1))
    return reqs, user_events


def check_names(reqs, names):
    # Scala와 이 파일이 다른 이름을 쓰면 집계가 조용히 0이 되므로 멈춘다
    unknown = sorted(set(reqs) - set(names.values()))
    if unknown:
        sys.exit(f"request-names.properties에 없는 요청 이름: {unknown}")


def pct(xs, p):
    xs = sorted(xs)
    return xs[max(0, math.ceil(p / 100 * len(xs)) - 1)] if xs else 0


def code_of(status, msg, ok_code):
    # OK는 체크가 기대한 코드로 보고, KO는 Gatling 메시지에서 실제 상태 코드를 꺼낸다
    if status == "OK":
        return ok_code
    m = re.search(r"found (\d{3})", msg)
    return m.group(1) if m else "err"


def count_codes(rows, ok_code):
    codes = {}
    for _, _, status, msg in rows:
        c = code_of(status, msg, ok_code)
        codes[c] = codes.get(c, 0) + 1
    return codes


def step_stats(rows):
    times = [e - s for s, e, _, _ in rows]
    return {
        "n": len(rows),
        "ko": sum(1 for r in rows if r[2] != "OK"),
        "p50_ms": pct(times, 50),
        "p95_ms": pct(times, 95),
        "p99_ms": pct(times, 99),
        "max_ms": max(times) if times else 0,
    }


def read_users(path):
    with open(path, encoding="utf-8") as f:
        return list(csv.DictReader(f))


def admission_waits(users):
    """진입부터 PROCESSING을 본 시각까지(ms). 입장하지 못한 사용자는 빼고 수만 센다."""
    waits = [int(u["admitted_at_ms"]) - int(u["enter_at_ms"])
             for u in users if u["admitted_at_ms"] and u["enter_at_ms"]]
    hist = []
    for i, lo in enumerate(WAIT_BUCKETS_S):
        hi = WAIT_BUCKETS_S[i + 1] if i + 1 < len(WAIT_BUCKETS_S) else None
        n = sum(1 for w in waits if w >= lo * 1000 and (hi is None or w < hi * 1000))
        hist.append({"lo_s": lo, "hi_s": hi, "n": n})
    return {
        "admitted": len(waits),
        "not_admitted": sum(1 for u in users if u["enter_at_ms"] and not u["admitted_at_ms"]),
        "p50_ms": pct(waits, 50),
        "p95_ms": pct(waits, 95),
        "max_ms": max(waits) if waits else 0,
        "histogram": hist,
    }


def timeseries(reqs, user_events, names, t0):
    """첫 요청 시작부터 초 단위. 요청은 끝난 초에 센다. 활성 사용자는 그 초가 끝날 때의 수다."""
    holds = reqs.get(names["hold"], []) + reqs.get(names["hold_retry"], [])
    pays = reqs.get(names["payment"], [])
    last = max([e for rs in reqs.values() for _, e, _, _ in rs] + [t for t, _ in user_events] + [t0])
    n = (last - t0) // 1000 + 1
    series = {"t_s": list(range(n)), "hold_201": [0] * n, "hold_409": [0] * n, "pay": [0] * n,
              "active_users": [0] * n}
    for _, e, status, msg in holds:
        c = code_of(status, msg, "201")
        if c in ("201", "409"):
            series[f"hold_{c}"][(e - t0) // 1000] += 1
    for _, e, _, _ in pays:
        series["pay"][(e - t0) // 1000] += 1
    delta = [0] * n
    for t, d in user_events:
        delta[max(0, (t - t0) // 1000)] += d
    active = 0
    for i in range(n):
        active += delta[i]
        series["active_users"][i] = active
    return series


def summarize(reqs, user_events, names, users):
    first = reqs.get(names["hold"], [])
    retry = reqs.get(names["hold_retry"], [])
    payment = reqs.get(names["payment"], [])
    holds = first + retry
    hold_names = {names["hold"], names["hold_retry"]}
    starts = [s for rs in reqs.values() for s, _, _, _ in rs]
    ends = [e for rs in reqs.values() for _, e, _, _ in rs]

    codes = count_codes(holds, "201")
    out = {
        "hold_req": len(holds),
        "hold_201": codes.pop("201", 0),
        "hold_409": codes.pop("409", 0),
        "hold_other": sum(codes.values()),
        "hold_p50_ms": pct([e - s for s, e, _, _ in holds], 50),
        "hold_p99_ms": pct([e - s for s, e, _, _ in holds], 99),
        "first_p50_ms": pct([e - s for s, e, _, _ in first], 50),
        "first_p99_ms": pct([e - s for s, e, _, _ in first], 99),
        "retry_req": len(retry),
        "retry_p50_ms": pct([e - s for s, e, _, _ in retry], 50),
        "retry_p99_ms": pct([e - s for s, e, _, _ in retry], 99),
        "pay_req": len(payment),
        "pay_ok": sum(1 for r in payment if r[2] == "OK"),
        "ko_other_steps": sum(1 for n, rs in reqs.items() if n not in hold_names for r in rs if r[2] != "OK"),
    }
    if codes:
        out["hold_other_codes"] = "+".join(f"{k}:{v}" for k, v in sorted(codes.items()))
    out["sim_s"] = round((max(ends) - min(starts)) / 1000, 1) if starts else 0

    out["hold_403"] = codes.get("403", 0)
    out["hold_503"] = codes.get("503", 0)
    pay_codes = count_codes(payment, "2xx")
    if users is not None:
        statuses = [u["pay_status"] for u in users if u["pay_status"]]
        out["pay_200"] = statuses.count("200")
        out["pay_202"] = statuses.count("202")
    else:
        out["pay_200"] = out["pay_202"] = None
    out["pay_402"] = pay_codes.get("402", 0)
    out["pay_409"] = pay_codes.get("409", 0)
    out["pay_other"] = sum(v for k, v in pay_codes.items() if k not in ("2xx", "402", "409"))
    heartbeats = reqs.get(names["heartbeat"], [])
    leaves = reqs.get(names["leave"], [])
    out["heartbeat_req"] = len(heartbeats)
    out["heartbeat_ko"] = sum(1 for r in heartbeats if r[2] != "OK")
    out["heartbeat_p99_ms"] = pct([e - s for s, e, _, _ in heartbeats], 99)
    out["leave_req"] = len(leaves)
    out["leave_ko"] = sum(1 for r in leaves if r[2] != "OK")
    out["leave_p99_ms"] = pct([e - s for s, e, _, _ in leaves], 99)
    if users is not None and users and "sold_out_at_ms" in users[0]:
        out["enter_sold_out"] = sum(1 for u in users if u["outcome"] == "enter_sold_out")
        out["poll_sold_out"] = sum(1 for u in users if u["outcome"] == "queue_sold_out")

    detail = {
        "steps": {key: step_stats(reqs.get(names[key], [])) for key in
                  ("login", "queue_enter", "queue_poll", "heartbeat", "seatmap", "seatmap_retry", "hold",
                   "hold_retry", "leave", "payment")},
        "hold_codes": count_codes(holds, "201"),
        "pay_codes": pay_codes,
        "heartbeat_codes": count_codes(heartbeats, "200"),
        "leave_codes": count_codes(leaves, "204"),
        "first_request_ms": min(starts) if starts else None,
        "last_response_ms": max(ends) if ends else None,
        "timeseries": timeseries(reqs, user_events, names, min(starts)) if starts else None,
    }
    if users is not None:
        outcomes = {}
        for u in users:
            outcomes[u["outcome"]] = outcomes.get(u["outcome"], 0) + 1
        detail["users"] = len(users)
        detail["outcomes"] = dict(sorted(outcomes.items()))
        detail["admission_wait"] = admission_waits(users)
        if users and "heartbeats" in users[0]:
            per_user = {}
            for u in users:
                per_user[u["heartbeats"]] = per_user.get(u["heartbeats"], 0) + 1
            detail["heartbeats_per_user"] = dict(sorted(per_user.items(), key=lambda kv: int(kv[0])))
    return out, detail


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("simulation_log")
    parser.add_argument("--users", help="UserLog가 남긴 사용자 기록 CSV")
    parser.add_argument("--json", help="단계별 분포와 시계열을 쓸 파일")
    args = parser.parse_args()

    names = load_names(NAMES_FILE)
    reqs, user_events = read_log(args.simulation_log)
    check_names(reqs, names)
    users = read_users(args.users) if args.users else None
    out, detail = summarize(reqs, user_events, names, users)
    print(" ".join(f"{k}={'null' if v is None else v}" for k, v in out.items()))
    if args.json:
        Path(args.json).write_text(json.dumps({"summary": out, **detail}, ensure_ascii=False, indent=1),
                                   encoding="utf-8")


if __name__ == "__main__":
    main()
