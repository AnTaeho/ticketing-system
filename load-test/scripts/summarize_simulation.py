"""시나리오 C의 Gatling simulation.log에서 선점 응답 시간·상태와 단계별 수를 센다.

사용법: python3 load-test/scripts/summarize_simulation.py <simulation.log>
출력: "키=값" 한 줄. 백분위는 nearest-rank다.
"""
import math
import re
import sys
from pathlib import Path

NAMES_FILE = Path(__file__).resolve().parent.parent / "gatling/src/test/resources/request-names.properties"


def load_names(path):
    names = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        key, _, value = line.partition("=")
        names[key.strip()] = value.strip()
    return names


def read_requests(log_path):
    """요청 이름별 (응답 ms, 상태, 메시지) 목록과 전체 요청의 (시작, 끝) 목록."""
    reqs, spans = {}, []
    with open(log_path, encoding="utf-8") as f:
        for line in f:
            fields = line.rstrip("\n").split("\t")
            if fields[0] != "REQUEST":
                continue
            name, start, end, status = fields[2], int(fields[3]), int(fields[4]), fields[5]
            msg = fields[6] if len(fields) > 6 else ""
            reqs.setdefault(name, []).append((end - start, status, msg))
            spans.append((start, end))
    return reqs, spans


def check_names(reqs, names):
    # Scala와 이 파일이 다른 이름을 쓰면 집계가 조용히 0이 되므로 멈춘다
    unknown = sorted(set(reqs) - set(names.values()))
    if unknown:
        sys.exit(f"request-names.properties에 없는 요청 이름: {unknown}")


def pct(xs, p):
    xs = sorted(xs)
    return xs[max(0, math.ceil(p / 100 * len(xs)) - 1)] if xs else 0


def hold_code(status, msg):
    # 선점은 201만 OK로 센다. KO는 Gatling 메시지에서 실제 상태 코드를 꺼낸다
    if status == "OK":
        return "201"
    m = re.search(r"found (\d{3})", msg)
    return m.group(1) if m else "err"


def summarize(reqs, spans, names):
    first = reqs.get(names["hold"], [])
    retry = reqs.get(names["hold_retry"], [])
    payment = reqs.get(names["payment"], [])
    holds = first + retry
    hold_names = {names["hold"], names["hold_retry"]}

    codes = {}
    for _, status, msg in holds:
        c = hold_code(status, msg)
        codes[c] = codes.get(c, 0) + 1

    out = {
        "hold_req": len(holds),
        "hold_201": codes.pop("201", 0),
        "hold_409": codes.pop("409", 0),
        "hold_other": sum(codes.values()),
        "hold_p50_ms": pct([r[0] for r in holds], 50),
        "hold_p99_ms": pct([r[0] for r in holds], 99),
        "first_p50_ms": pct([r[0] for r in first], 50),
        "first_p99_ms": pct([r[0] for r in first], 99),
        "retry_req": len(retry),
        "retry_p50_ms": pct([r[0] for r in retry], 50),
        "retry_p99_ms": pct([r[0] for r in retry], 99),
        "pay_req": len(payment),
        "pay_ok": sum(1 for r in payment if r[1] == "OK"),
        "ko_other_steps": sum(1 for n, rs in reqs.items() if n not in hold_names for r in rs if r[1] != "OK"),
    }
    if codes:
        out["hold_other_codes"] = "+".join(f"{k}:{v}" for k, v in sorted(codes.items()))
    out["sim_s"] = round((max(e for _, e in spans) - min(s for s, _ in spans)) / 1000, 1) if spans else 0
    return out


def main():
    if len(sys.argv) != 2:
        sys.exit(f"사용법: python3 {sys.argv[0]} <simulation.log>")
    names = load_names(NAMES_FILE)
    reqs, spans = read_requests(sys.argv[1])
    check_names(reqs, names)
    out = summarize(reqs, spans, names)
    print(" ".join(f"{k}={v}" for k, v in out.items()))


if __name__ == "__main__":
    main()
