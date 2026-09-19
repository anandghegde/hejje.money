"""Strategy bake-off under the pre-registered protocol (plan M6.5, docs/strategies/bakeoff.md).

Standard library only. Talks to a running Hejje server:

    export HEJJE_URL=https://hejje.malgudi.app HEJJE_API_KEY=...   # key with strategies:read/write and market:read
    python3 research/tools/bakeoff.py run cpr_breakout nifty_orb ...        # protocol runs, results table
    python3 research/tools/bakeoff.py holdout nifty_orb ...                 # one run each on the 6-month holdout

For every slug it resolves the latest version, the series its universe trades (continuous futures for NIFTY/BANKNIFTY,
the symbol otherwise), the common coverage of those series and from it the protocol range (ending 6 months before the
latest session every series covers). It then submits the backtest (WALK_FORWARD 12/3, NEXT_OPEN, slippage 5 bps for
futures and indices, 10 for stocks, default capital and risk), polls until it finishes, recomputes the Hejje Score (whose
slippage-sensitivity component is the 2x slippage run), pulls the by-regime breakdown, applies the pass rule and writes
the table between the markers in docs/strategies/bakeoff.md. Every row carries the backtest id and resultHash, so any
number can be traced back. Nothing here changes a strategy or its parameters.
"""
import argparse
import datetime as dt
import json
import os
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from pathlib import Path

REPORT = Path(__file__).resolve().parents[2] / "docs/strategies/bakeoff.md"
HOLDOUT_MONTHS = 6
FUTURES_UNDERLYINGS = {"NIFTY", "BANKNIFTY", "FINNIFTY"}
INDEX_PROXY = {"NIFTY": "INDEX:NIFTY 50", "BANKNIFTY": "INDEX:NIFTY BANK"}
MIN_FUTURES_YEARS = 2
VOLUME_CALLS = ("vwap", "relative_volume")

# pass rule (net of costs, out-of-sample slice)
MIN_OOS_TRADES = 30
MIN_OOS_EXPECTANCY_R = 0.10
MIN_OOS_PROFIT_FACTOR = 1.2
MIN_POSITIVE_WINDOW_SHARE = 0.60


class Api:
    def __init__(self, base: str, key: str):
        self.base = base.rstrip("/") + ("" if base.rstrip("/").endswith("/api/v1") else "/api/v1")
        self.key = key

    def call(self, method: str, path: str, body=None, query=None):
        url = self.base + path + ("?" + urllib.parse.urlencode(query) if query else "")
        data = None if body is None else json.dumps(body).encode()
        req = urllib.request.Request(url, data=data, method=method)
        req.add_header("Authorization", "Bearer " + self.key)
        req.add_header("Accept", "application/json")
        if data is not None:
            req.add_header("Content-Type", "application/json")
        if method != "GET":
            req.add_header("Idempotency-Key", str(uuid.uuid4()))
        try:
            with urllib.request.urlopen(req, timeout=600) as resp:
                text = resp.read().decode()
                return json.loads(text) if text else None
        except urllib.error.HTTPError as e:
            raise RuntimeError(f"{method} {path} -> {e.code}: {e.read().decode()[:500]}") from None

    def get(self, path, **query):
        return self.call("GET", path, query=query or None)

    def post(self, path, body=None, **query):
        return self.call("POST", path, body=body, query=query or None)


def months_before(day: dt.date, months: int) -> dt.date:
    y, m = divmod(day.year * 12 + day.month - 1 - months, 12)
    m += 1
    last = [31, 29 if y % 4 == 0 and (y % 100 != 0 or y % 400 == 0) else 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31][m - 1]
    return dt.date(y, m, min(day.day, last))


def strategy_by_slug(api: Api, slug: str) -> dict:
    for s in api.get("/strategies"):
        if s["slug"] == slug:
            return s
    raise RuntimeError(f"no strategy '{slug}' on the server")


def universe(version: dict) -> list[str]:
    """Universe lines from the version's YAML (aliases, symbols, selectors) as plain values."""
    yaml = version["definitionYaml"]
    block = re.search(r"^universe:\s*\n((?:\s+-.*\n)+)", yaml, re.M)
    if block:
        items = [line.strip()[1:].strip() for line in block.group(1).splitlines() if line.strip().startswith("-")]
    else:
        inline = re.search(r"^universe:\s*\[(.*)\]", yaml, re.M)
        items = [x.strip() for x in inline.group(1).split(",")] if inline else []
    out = []
    for item in items:
        item = item.strip().strip('"').strip("'")
        if ":" in item and item.split(":", 1)[0] in ("nearest_future", "index"):
            kind, value = (x.strip().strip('"') for x in item.split(":", 1))
            out.append(value if kind == "nearest_future" else "INDEX:" + value)
        else:
            out.append(item)
    return out


def uses_volume(version: dict) -> bool:
    yaml = version["definitionYaml"]
    return any(re.search(r"\b" + call + r"\b", yaml) for call in VOLUME_CALLS)


def series_for(api: Api, item: str) -> dict:
    """{symbol, instrumentId, kind} for one universe value; futures aliases map to their continuous series."""
    if item in FUTURES_UNDERLYINGS:
        built = api.get("/market/history/continuous", underlying=item)
        built = built if isinstance(built, list) else [built]
        if not built or not built[0]:
            raise RuntimeError(f"no continuous series for {item}: build it first (docs/data.md)")
        return {"symbol": built[0]["symbol"], "instrumentId": built[0]["id"], "kind": "future", "underlying": item}
    inst = api.get("/instruments/resolve", symbol=item)
    kind = "index" if item.startswith("INDEX:") else "stock"
    return {"symbol": item, "instrumentId": inst["id"], "kind": kind}


def coverage(api: Api, series: dict, timeframe: str) -> tuple[dt.date, dt.date] | None:
    c = api.get("/market/history/coverage", instrumentId=series["instrumentId"], timeframe=timeframe)
    if not c or not c.get("candleCount"):
        return None
    return dt.date.fromisoformat(c["from"][:10]), dt.date.fromisoformat(c["to"][:10])


def plan(api: Api, slug: str, latest_session: dt.date | None = None) -> dict:
    """The protocol inputs of one strategy: version, series, range, holdout, slippage. `latest_session` pins the
    latest session (so the holdout run uses the same boundary as the protocol run after more data has arrived)."""
    s = strategy_by_slug(api, slug)
    version = api.get(f"/strategies/{s['id']}/versions/{s['latestVersion']}")
    timeframe = version["definition"]["timeframe"]
    series = [series_for(api, item) for item in universe(version)]
    notes = []
    spans = {x["symbol"]: coverage(api, x, timeframe) for x in series}
    futures = [x for x in series if x["kind"] == "future"]
    if futures and not uses_volume(version):
        short = [x for x in futures if spans[x["symbol"]] is None
                 or (spans[x["symbol"]][1] - spans[x["symbol"]][0]).days < MIN_FUTURES_YEARS * 365]
        if short and all(x["underlying"] in INDEX_PROXY for x in short):
            for x in short:
                proxy = series_for(api, INDEX_PROXY[x["underlying"]])
                notes.append(f"{x['symbol']} covers under {MIN_FUTURES_YEARS} years: price-only rules run on {proxy['symbol']}")
                series[series.index(x)] = proxy
                spans[proxy["symbol"]] = coverage(api, proxy, timeframe)
    missing = [sym for sym, span in spans.items() if span is None and sym in {x["symbol"] for x in series}]
    if missing:
        raise RuntimeError(f"{slug}: no {timeframe} history for {', '.join(missing)}")
    used = [spans[x["symbol"]] for x in series]
    start = max(a for a, _ in used)
    latest = min(b for _, b in used)
    if latest_session is not None:
        latest = min(latest, latest_session)
    end = months_before(latest, HOLDOUT_MONTHS)
    if end <= start:
        raise RuntimeError(f"{slug}: common coverage {start}..{latest} is shorter than the {HOLDOUT_MONTHS}-month holdout")
    stocks = all(x["kind"] == "stock" for x in series)
    return {"slug": slug, "strategyId": s["id"], "version": s["latestVersion"], "versionId": version["id"], "timeframe": timeframe,
            "instruments": [x["symbol"] for x in series], "from": start.isoformat(), "to": end.isoformat(),
            "holdoutFrom": (end + dt.timedelta(days=1)).isoformat(), "holdoutTo": latest.isoformat(),
            "slippageBps": 10 if stocks else 5, "notes": notes}


def submit(api: Api, p: dict, holdout: bool) -> dict:
    body = {"versionId": p["versionId"], "instruments": p["instruments"], "fillModel": "NEXT_OPEN", "slippageBps": p["slippageBps"],
            "from": p["holdoutFrom"] if holdout else p["from"], "to": p["holdoutTo"] if holdout else p["to"],
            "splits": {"type": "NONE"} if holdout else {"type": "WALK_FORWARD", "trainMonths": 12, "testMonths": 3, "anchored": False}}
    return api.post("/backtests", body)


def wait(api: Api, ids: list[str], poll: float) -> dict:
    done = {}
    while len(done) < len(ids):
        for i in ids:
            if i in done:
                continue
            b = api.get(f"/backtests/{i}")
            if b["status"] in ("DONE", "FAILED", "CANCELLED"):
                done[i] = b
                print(f"  {i} {b['status']}", file=sys.stderr)
        if len(done) < len(ids):
            time.sleep(poll)
    return done


def slippage_evidence(api: Api, p: dict, backtest_id: str) -> tuple[float | None, int | None, str | None]:
    """(expectancy at 2x slippage, final score, note) from the Hejje Score breakdown built on this backtest."""
    api.post(f"/strategies/{p['strategyId']}/score/recompute", version=p["version"])
    score = api.get(f"/strategies/{p['strategyId']}/score", version=p["version"])
    b = score.get("breakdown")
    if not b:
        return None, None, "no score"
    if b.get("baseBacktestId") != backtest_id:
        return None, b.get("finalScore"), f"score is based on backtest {b.get('baseBacktestId')}, not this run"
    for c in b.get("components", []):
        if c["name"] == "Slippage sensitivity":
            return c.get("evidence", {}).get("doubledSlippageExpectancyR"), b.get("finalScore"), None
    return None, b.get("finalScore"), "no slippage component"


def verdict(b: dict, doubled: float | None) -> tuple[bool, list[str], dict]:
    oos = (b.get("bySplit") or {}).get("OUT_OF_SAMPLE") or {}
    trades = oos.get("totalTrades", 0)
    exp_r = oos.get("expectancyR", 0.0)
    pf = oos.get("profitFactor")
    windows = [w for w in b.get("windows") or [] if w.get("trades", 0) > 0]
    positive = sum(1 for w in windows if w["expectancyR"] > 0)
    share = positive / len(windows) if windows else 0.0
    fails = []
    if trades < MIN_OOS_TRADES:
        fails.append(f"OOS trades {trades} < {MIN_OOS_TRADES}")
    if exp_r < MIN_OOS_EXPECTANCY_R:
        fails.append(f"OOS expectancy {exp_r:.3f}R < {MIN_OOS_EXPECTANCY_R}R")
    if pf is not None and pf < MIN_OOS_PROFIT_FACTOR:
        fails.append(f"OOS profit factor {pf:.2f} < {MIN_OOS_PROFIT_FACTOR}")
    if share < MIN_POSITIVE_WINDOW_SHARE:
        fails.append(f"positive windows {positive}/{len(windows)} < {MIN_POSITIVE_WINDOW_SHARE:.0%}")
    if doubled is None:
        fails.append("2x slippage expectancy unavailable")
    elif doubled <= 0:
        fails.append(f"2x slippage expectancy {doubled:.3f}R <= 0")
    stats = {"trades": trades, "expectancyR": exp_r, "profitFactor": pf, "windows": f"{positive}/{len(windows)}", "doubled": doubled}
    return not fails, fails, stats


def regimes(api: Api, backtest_id: str) -> str:
    r = api.get(f"/backtests/{backtest_id}/regimes", dims="trend,volatility")
    rows = sorted(r.get("byRegime") or [], key=lambda x: -x["trades"])
    return "; ".join(f"{x['key']} {x['trades']}t {x['expectancyR']:+.2f}R" for x in rows[:4]) or "—"


def fmt(v, spec):
    return "—" if v is None else format(v, spec)


def replace_section(marker: str, content: str) -> None:
    text = REPORT.read_text()
    start, end = f"<!-- bakeoff:{marker}:start -->", f"<!-- bakeoff:{marker}:end -->"
    if start not in text or end not in text:
        raise RuntimeError(f"{REPORT} has no {marker} markers")
    head, rest = text.split(start, 1)
    _, tail = rest.split(end, 1)
    REPORT.write_text(head + start + "\n" + content.rstrip() + "\n" + end + tail)


def run(api: Api, slugs: list[str], poll: float, write: bool, latest: dt.date | None) -> None:
    plans = [plan(api, s, latest) for s in slugs]
    submitted = {}
    for p in plans:
        b = submit(api, p, holdout=False)
        submitted[b["id"]] = p
        print(f"{p['slug']}: backtest {b['id']} {p['from']}..{p['to']} on {', '.join(p['instruments'][:3])}"
              f"{' …' if len(p['instruments']) > 3 else ''}", file=sys.stderr)
    finished = wait(api, list(submitted), poll)
    lines = ["| Strategy | Series | Range | OOS trades | OOS exp. (R) | OOS PF | + windows | 2x slip (R) | Score | Pass | Failures / notes | By regime (trend × vol) | Backtest / resultHash |",
             "|---|---|---|---:|---:|---:|---:|---:|---:|---|---|---|---|"]
    for bid, p in submitted.items():
        b = finished[bid]
        series = ", ".join(p["instruments"]) if len(p["instruments"]) <= 2 else f"{len(p['instruments'])} stocks"
        if b["status"] != "DONE":
            lines.append(f"| `{p['slug']}` | {series} | {p['from']}..{p['to']} | | | | | | | FAIL | backtest {b['status']}: {b.get('error') or ''} | | `{bid}` |")
            continue
        doubled, score, note = slippage_evidence(api, p, bid)
        ok, fails, st = verdict(b, doubled)
        if note:
            fails.append(note)
        why = "; ".join(fails + p["notes"]) or "—"
        lines.append(f"| `{p['slug']}` | {series} | {p['from']}..{p['to']} | {st['trades']} | {st['expectancyR']:+.3f} | {fmt(st['profitFactor'], '.2f')} | "
                     f"{st['windows']} | {fmt(doubled, '+.3f')} | {fmt(score, 'd')} | {'PASS' if ok else 'FAIL'} | {why} | {regimes(api, bid)} | "
                     f"`{bid}` / `{(b.get('resultHash') or '')[:12]}` |")
    table = "\n".join(lines)
    stamp = f"Run {dt.datetime.now(dt.timezone.utc).strftime('%Y-%m-%d %H:%M UTC')} against {api.base}.\n\n"
    print(table)
    if write:
        replace_section("results", stamp + table)


def holdout(api: Api, slugs: list[str], poll: float, write: bool, latest: dt.date | None) -> None:
    plans = [plan(api, s, latest) for s in slugs]
    submitted = {submit(api, p, holdout=True)["id"]: p for p in plans}
    finished = wait(api, list(submitted), poll)
    lines = ["| Strategy | Holdout | Trades | Expectancy (R) | Profit factor | Net P&L (₹) | Advances | Backtest / resultHash |",
             "|---|---|---:|---:|---:|---:|---|---|"]
    for bid, p in submitted.items():
        b = finished[bid]
        m = b.get("metrics") or {}
        exp_r = m.get("expectancyR")
        net = (m.get("netPnl") or {}).get("paise")
        advances = b["status"] == "DONE" and m.get("totalTrades", 0) > 0 and exp_r is not None and exp_r > 0
        lines.append(f"| `{p['slug']}` | {p['holdoutFrom']}..{p['holdoutTo']} | {m.get('totalTrades', 0)} | {fmt(exp_r, '+.3f')} | "
                     f"{fmt(m.get('profitFactor'), '.2f')} | {fmt(None if net is None else net / 100, ',.0f')} | {'yes' if advances else 'no'} | "
                     f"`{bid}` / `{(b.get('resultHash') or '')[:12]}` |")
    table = "\n".join(lines)
    print(table)
    if write:
        replace_section("holdout", f"Run {dt.datetime.now(dt.timezone.utc).strftime('%Y-%m-%d %H:%M UTC')}.\n\n" + table)


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("mode", choices=["run", "holdout", "plan"], help="run: protocol runs; holdout: one run each on the holdout; plan: print ranges only")
    ap.add_argument("slugs", nargs="+")
    ap.add_argument("--poll", type=float, default=10.0, help="seconds between status polls")
    ap.add_argument("--no-write", action="store_true", help="print the table without editing bakeoff.md")
    ap.add_argument("--latest", type=dt.date.fromisoformat, help="pin the latest session (YYYY-MM-DD); use the protocol run's value for the holdout")
    args = ap.parse_args()
    base, key = os.environ.get("HEJJE_URL"), os.environ.get("HEJJE_API_KEY")
    if not base or not key:
        sys.exit("set HEJJE_URL and HEJJE_API_KEY")
    api = Api(base, key)
    if args.mode == "plan":
        for s in args.slugs:
            print(json.dumps(plan(api, s, args.latest)))
    elif args.mode == "run":
        run(api, args.slugs, args.poll, not args.no_write, args.latest)
    else:
        holdout(api, args.slugs, args.poll, not args.no_write, args.latest)


if __name__ == "__main__":
    main()
