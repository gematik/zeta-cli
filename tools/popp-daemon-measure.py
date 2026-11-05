#!/usr/bin/env python3
"""Measure the PoPP path of a running `zeta serve` started with --trace-file.

  run     wait for warm-up, then call /api/popp/token N times
  report  turn one trace file per setup into markdown tables

Response bodies and headers are discarded unread: they hold the PoPP token and the KVNR.
"""
import argparse
import json
import statistics
import sys
import time
import urllib.error
import urllib.request
from collections import defaultdict


def get_json(url):
    with urllib.request.urlopen(url, timeout=10) as response:
        return json.load(response)


def wait_for_warmup(base_url, timeout_s):
    deadline = time.monotonic() + timeout_s
    while time.monotonic() < deadline:
        try:
            health = get_json(f"{base_url}/api/health")
        except OSError:
            health = None
        if health and health["warmup"]["complete"]:
            return health
        time.sleep(1)
    sys.exit(f"warm-up not complete after {timeout_s}s")


def run(args):
    health = wait_for_warmup(args.base_url, args.warmup_timeout)
    warmup = health["warmup"]
    print(f"warm-up complete: {warmup['warmed']}/{warmup['total']}, card transport {health.get('poppCard')}")
    # warmupComplete is also set after failed logins, and the first mint would then pay for one.
    if not any("popp" in s["scopes"] for s in health["sessions"]):
        sys.exit("no warm popp session: the PoPP login failed during warm-up, see the daemon log")

    headers = {"middleware-egk": args.egk_handle} if args.egk_handle else {}
    failures = 0
    for i in range(args.n):
        if i and args.pause:
            time.sleep(args.pause)
        request = urllib.request.Request(f"{args.base_url}/api/popp/token", headers=headers)
        started = time.monotonic()
        try:
            with urllib.request.urlopen(request, timeout=args.timeout) as response:
                response.read()
                status = response.status
        except urllib.error.HTTPError as e:
            status = e.code
        except OSError as e:
            status = type(e).__name__
        failures += status != 200
        print(f"{i + 1}/{args.n} status={status} {(time.monotonic() - started) * 1000:.0f} ms", flush=True)
    return 1 if failures else 0


def load_events(path):
    with open(path) as f:
        return [json.loads(line) for line in f if line.strip()]


def duration(span):
    return span["durationMs"]


def find(span, name):
    found = []
    for child in span["children"]:
        if child["name"] == name:
            found.append(child)
        found += find(child, name)
    return found


def total(span, name):
    spans = find(span, name)
    return sum(map(duration, spans)) if spans else None


def table(title, header, rows):
    if not rows:
        return
    print(f"\n### {title}\n")
    print("| " + " | ".join(header) + " |")
    print("|" + "---|" * len(header))
    for row in rows:
        print("| " + " | ".join(str(c) for c in row) + " |")


def summary(values):
    values = [v for v in values if v is not None]
    if not values:
        return None
    return len(values), statistics.median(values), min(values), max(values)


def summary_row(label, values):
    s = summary(values)
    return s and [label, s[0], f"{s[1]:.0f}", f"{s[2]:.0f}", f"{s[3]:.0f}"]


def rounds_of(mint):
    """Pair every scenario with the service frame right before it, the last frame being the token."""
    flow = find(mint, "popp.flow")
    frames = flow[0]["children"] if flow else []
    service, card, token = {}, {}, None
    last_recv = None
    for span in frames:
        if span["name"] == "popp.ws.recv":
            last_recv = span
            if span["attrs"].get("type") == "TokenMessage":
                token = duration(span)
        elif span["name"] == "scenario":
            n = span["attrs"]["round"]
            card[n] = duration(span)
            service[n] = duration(last_recv) if last_recv else None
    return service, card, token


MAIN_PATH = [
    ("mint", None),
    ("lock.wait", "lock.wait"),
    ("session.warm", "session.warm"),
    ("login inside mint", "login"),
    ("SDS (`connector.connect`)", "connector.connect"),
    ("card readable: `connector.getCards`", "connector.getCards"),
    ("card readable: `card.ready`", "card.ready"),
    ("`connector.startCardSession`", "connector.startCardSession"),
    ("`ws.open`", "ws.open"),
    ("APDUs: all `scenario`", "scenario"),
    ("service and network: all `popp.ws.recv`", "popp.ws.recv"),
    ("`connector.stopCardSession`", "connector.stopCardSession"),
]


def report_mints(mints, handle_given):
    ok = [m for m in mints if m["ok"]]
    failed = [m for m in mints if not m["ok"]]
    print(f"\n## Mints, eGK handle {'given' if handle_given else 'not given'}\n")
    print(f"{len(ok)} ok, {len(failed)} failed", end="")
    print(f" (status {sorted({m['attrs'].get('status') for m in failed}, key=str)})" if failed else "")
    if not ok:
        return

    # The first mint can carry the lazy SDS load and a cold code path, so it is reported apart.
    first, rest = ok[0], ok[1:] or ok
    rows = []
    for label, name in MAIN_PATH:
        pick = (lambda m: duration(m)) if name is None else (lambda m, n=name: total(m, n))
        first_ms = pick(first)
        row = summary_row(label, [pick(m) for m in rest])
        # A lazy SDS load exists in the first mint only, and that is the row worth seeing.
        if row or first_ms is not None:
            rows.append((row or [label, 0, "–", "–", "–"]) + [f"{first_ms:.0f}" if first_ms is not None else "–"])
    table("Main path (ms, without the first mint)", ["Step", "n", "median", "min", "max", "first mint"], rows)

    # A 4288 here only means PoPP closed the card session first, so it does not fail a mint.
    stops = [s for m in ok for s in find(m, "connector.stopCardSession")]
    if stops:
        print(f"\n`stopCardSession` reported an error on {sum(not s['ok'] for s in stops)} of {len(stops)} mints.")

    per_round = defaultdict(lambda: ([], [], [], []))
    tokens = []
    for mint in rest:
        service, card, token = rounds_of(mint)
        tokens.append(token)
        for s in find(mint, "scenario"):
            n = s["attrs"]["round"]
            per_round[n][0].append(service.get(n))
            per_round[n][1].append(card.get(n))
            per_round[n][2].append(s["attrs"].get("apdus"))
            per_round[n][3].append(s["attrs"].get("respBytes"))
    rows = [
        [n, f"{statistics.median(a):.0f}", f"{statistics.median(b):.0f}", f"{statistics.median(c):.0f}",
         f"{statistics.median(d):.0f}"]
        for n, (svc, crd, apd, rsp) in sorted(per_round.items())
        for a, b, c, d in [([v for v in svc if v is not None] or [0], crd, apd, rsp)]
    ]
    if summary(tokens):
        rows.append(["token", f"{summary(tokens)[1]:.0f}", "–", "–", "–"])
    table("Per scenario (median)", ["Round", "Service and network ms", "Card or Konnektor ms", "APDUs", "Response bytes"], rows)

    apdus = defaultdict(list)
    bytes_of = {}
    for mint in rest:
        for span in find(mint, "card.apdu"):
            key = span["attrs"]["hdr"]
            apdus[key].append(duration(span))
            bytes_of[key] = span["attrs"]["cmdBytes"] + span["attrs"]["respBytes"]
    rows = []
    for hdr, values in apdus.items():
        ms = statistics.median(values)
        rows.append([f"`{hdr}`", bytes_of[hdr], len(values), f"{ms:.1f}", f"{ms / bytes_of[hdr]:.2f}"])
    table("APDUs at the local card (first APDU of a kartos mint includes its image load)",
          ["Header", "Bytes", "n", "median ms", "ms per byte"], rows)
    if apdus:
        total_ms = sum(statistics.median(v) for v in apdus.values())
        total_bytes = sum(bytes_of.values())
        print(f"\nTogether: {total_ms:.0f} ms for {total_bytes} bytes, {total_ms / total_bytes:.2f} ms per byte.")


def report(args):
    for path in args.files:
        events = load_events(path)
        by_name = defaultdict(list)
        for event in events:
            by_name[event["name"]].append(event)
        print(f"\n# {path}")

        # An endpoint that failed early would pull the login medians towards its time to fail.
        warmed = [w for w in by_name["warm"] if w["ok"]]
        rows = [r for r in [summary_row("`prep.catalog`", map(duration, by_name["prep.catalog"]))] if r]
        for label, name in [("`sdk.init`", "sdk.init"), ("`sdk.discover`", "sdk.discover"),
                            ("`sdk.register`", "sdk.register"), ("`login`", "login"),
                            ("`sdk.authenticate`", "sdk.authenticate"), ("SDS (`connector.connect`)", "connector.connect")]:
            row = summary_row(label, [total(w, name) for w in warmed])
            if row:
                rows.append(row)
        row = summary_row("`warm`, whole endpoint", map(duration, warmed))
        if row:
            rows.append(row)
        table("Preparation and login, per endpoint in parallel (ms)", ["Step", "n", "median", "min", "max"], rows)
        failed = [w["attrs"].get("resource") for w in by_name["warm"] if not w["ok"]]
        if failed:
            print(f"\nFailed warm-ups: {', '.join(map(str, failed))}")

        rows = []
        for label, pick in [("`refresh`", duration),
                            ("`lock.wait`", lambda r: total(r, "lock.wait")),
                            ("`sdk.authenticate`", lambda r: total(r, "sdk.authenticate"))]:
            row = summary_row(label, [pick(r) for r in by_name["refresh"]])
            if row:
                rows.append(row)
        table("Token refresh (ms)", ["Step", "n", "median", "min", "max"], rows)
        failed_refreshes = sum(not r["ok"] for r in by_name["refresh"])
        if failed_refreshes:
            print(f"\nFailed refreshes: {failed_refreshes}")

        for handle_given in (True, False):
            mints = [m for m in by_name["mint"] if m["attrs"].get("egkHandle") == handle_given]
            if mints:
                report_mints(mints, handle_given)
    return 0


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    commands = parser.add_subparsers(dest="command", required=True)

    run_parser = commands.add_parser("run")
    run_parser.add_argument("--base-url", default="http://127.0.0.1:8080", help="the daemon's --port address")
    run_parser.add_argument("-n", type=int, default=20)
    run_parser.add_argument("--pause", type=float, default=0, help="seconds between mints")
    run_parser.add_argument("--egk-handle", help="sent as middleware-egk; without it the daemon runs getCards per mint")
    run_parser.add_argument("--warmup-timeout", type=int, default=300)
    run_parser.add_argument("--timeout", type=int, default=120)
    run_parser.set_defaults(func=run)

    report_parser = commands.add_parser("report")
    report_parser.add_argument("files", nargs="+", help="trace files, one per setup")
    report_parser.set_defaults(func=report)

    args = parser.parse_args()
    return args.func(args)


if __name__ == "__main__":
    sys.exit(main())
