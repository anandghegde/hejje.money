#!/usr/bin/env python3
"""Pre-registered validation of the Phase 8 daily context (plan M8.8, docs/strategies/context-validation.md).

Standard library only, like bakeoff.py (whose API client and helpers it reuses).

    export HEJJE_URL=https://hejje.malgudi.app HEJJE_API_KEY=...   # admin, market:read, strategies:read, strategies:write
    python3 research/tools/context_validation.py plan   --latest 2026-09-18 [slugs...]
    python3 research/tools/context_validation.py run    --latest 2026-09-18 [--only h1,h5] [slugs...]
    python3 research/tools/context_validation.py report

`plan` prints the evaluation range, the sample dates and how many requests a run makes; it writes nothing. `run`
computes what is missing on the server (analog summaries of the sample dates; the server skips what it has), measures
the five hypotheses and saves everything to research/out/context-validation.json. `report` applies the pass marks of
the protocol and writes the results table and the verdicts between the markers of the protocol document. The pass
marks live here as constants and in the document as prose; they were fixed before the first run and must not change.
"""
import argparse
import datetime as dt
import json
import math
import os
import re
import statistics
import sys
import time
import urllib.parse
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from bakeoff import Api, months_before, strategy_by_slug, universe, series_for, wait, fmt  # noqa: E402

ROOT = Path(__file__).resolve().parents[2]
REPORT = ROOT / "docs/strategies/context-validation.md"
OUT = ROOT / "research/out/context-validation.json"
NIFTY50 = ROOT / "config/universe/nifty50.yaml"

# --- the pre-registered protocol (docs/strategies/context-validation.md); do not change after the first run ---
RANGE_MONTHS = 24
SAMPLE_EVERY = 5
H1_LOOKBACKS = (15, 30)
H1_FORWARDS = (5, 10)
H1_MIN_SPREAD_PCT = {5: 0.40, 10: 0.60}
H1_MIN_GROUP = 300
H1_MIN_PER_DATE = 5
H1_MIN_POSITIVE_QUARTERS = 0.60
H1_MIN_QUARTER_GROUP = 30
H1_CELLS_TO_PASS = 3
H2_CHECKPOINTS = ("09:45", "10:15", "11:15", "13:00")
H2_EXIT = "15:10"
H2_MIN_SPREAD_PCT = 0.10
H2_MIN_GROUP = 200
H2_MIN_PER_DATE = 3
MIN_T = 2.0
RELIABLE = ("MEDIUM", "HIGH")
H3_TOP_N = 10
MIN_DELTA_R = 0.05
MIN_FILTERED_TRADES = 30
STOCK_SLIPPAGE_BPS = 10
OTHER_SLIPPAGE_BPS = 5
LONG_CONDITIONS = ("CONFIRMED_UPTREND", "UPTREND_UNDER_PRESSURE")
H5_MIN_TRIGGERED = 50
H5_MIN_MEAN_R = 0.25
IST = dt.timezone(dt.timedelta(hours=5, minutes=30))


def log(*args):
    print(*args, file=sys.stderr, flush=True)


class PoliteApi(Api):
    """The bake-off client, waiting out the API's per-principal rate limit (20 requests a second, HTTP 429) instead of failing:
    a validation run makes tens of thousands of small reads."""

    def call(self, method: str, path: str, body=None, query=None):
        for attempt in range(60):
            try:
                return super().call(method, path, body=body, query=query)
            except RuntimeError as e:
                if "-> 429:" not in str(e):
                    raise
                time.sleep(min(5.0, 0.5 * (attempt + 1)))
        raise RuntimeError(f"{method} {path}: still rate limited after a minute of retries")


# ---------------------------------------------------------------------------------------------------------------------
# inputs


def nifty50_symbols() -> list[str]:
    return [m.group(1) for m in re.finditer(r"^\s+-\s+(\S+)\s*$", NIFTY50.read_text(), re.M)]


def sessions(api: Api, start: dt.date, end: dt.date, calendar_symbol: str) -> list[str]:
    """Session dates of the range: the dates on which the calendar symbol has a rating row."""
    rows = api.get(f"/ratings/{urllib.parse.quote(calendar_symbol)}/history", **{"from": start.isoformat(), "to": end.isoformat()})
    return [r["sessionDate"] for r in rows]


def evaluation(api: Api, latest: dt.date, calendar_symbol: str) -> dict:
    start = months_before(latest, RANGE_MONTHS)
    days = sessions(api, start, latest, calendar_symbol)
    if len(days) < 100:
        raise RuntimeError(f"only {len(days)} rated sessions of {calendar_symbol} in {start}..{latest}: compute ratings over the range first")
    h1_dates = days[: len(days) - max(H1_FORWARDS)][::SAMPLE_EVERY]
    return {"from": start.isoformat(), "to": latest.isoformat(), "sessions": days, "h1Dates": h1_dates, "h2Dates": days[::SAMPLE_EVERY]}


def chunks(items: list, size: int):
    for i in range(0, len(items), size):
        yield items[i:i + size]


# ---------------------------------------------------------------------------------------------------------------------
# statistics


def clustered(per_date: list[float]) -> dict:
    """Mean of a per-date statistic and its t-value over dates."""
    n = len(per_date)
    if n < 2:
        return {"dates": n, "mean": per_date[0] if n else None, "t": None}
    mean = statistics.fmean(per_date)
    sd = statistics.stdev(per_date)
    return {"dates": n, "mean": mean, "t": None if sd == 0 else mean / (sd / math.sqrt(n))}


def side_of(direction: str) -> str | None:
    return "bull" if direction.startswith("BULLISH") else "bear" if direction.startswith("BEARISH") else None


def spread_stats(observations: list[dict], min_per_date: int) -> dict:
    """observations: {date, side ('bull'|'bear'|None), ret (percent)}. Spread per date, clustered; group sizes; base rate."""
    by_date: dict[str, dict[str, list[float]]] = {}
    for o in observations:
        cell = by_date.setdefault(o["date"], {"bull": [], "bear": [], "all": []})
        cell["all"].append(o["ret"])
        if o["side"]:
            cell[o["side"]].append(o["ret"])
    spreads, quarters = [], {}
    for date, cell in sorted(by_date.items()):
        if len(cell["bull"]) >= min_per_date and len(cell["bear"]) >= min_per_date:
            spreads.append(statistics.fmean(cell["bull"]) - statistics.fmean(cell["bear"]))
        quarter = f"{date[:4]}Q{(int(date[5:7]) - 1) // 3 + 1}"
        q = quarters.setdefault(quarter, {"bull": [], "bear": []})
        q["bull"] += cell["bull"]
        q["bear"] += cell["bear"]
    bull = [o["ret"] for o in observations if o["side"] == "bull"]
    bear = [o["ret"] for o in observations if o["side"] == "bear"]
    everything = [o["ret"] for o in observations]
    usable = {k: v for k, v in quarters.items() if len(v["bull"]) >= H1_MIN_QUARTER_GROUP and len(v["bear"]) >= H1_MIN_QUARTER_GROUP}
    positive = sum(1 for v in usable.values() if statistics.fmean(v["bull"]) > statistics.fmean(v["bear"]))
    return {"observations": len(everything), "bull": len(bull), "bear": len(bear),
            "baseMean": statistics.fmean(everything) if everything else None,
            "baseHitRate": sum(1 for r in everything if r > 0) / len(everything) if everything else None,
            "bullMean": statistics.fmean(bull) if bull else None, "bearMean": statistics.fmean(bear) if bear else None,
            "bullHitRate": sum(1 for r in bull if r > 0) / len(bull) if bull else None,
            "bearHitRate": sum(1 for r in bear if r > 0) / len(bear) if bear else None,
            "spread": clustered(spreads), "quarters": len(usable), "positiveQuarters": positive}


# ---------------------------------------------------------------------------------------------------------------------
# H1 daily analogs


def h1(api: Api, ev: dict) -> dict:
    dates = ev["h1Dates"]
    for n, batch in enumerate(chunks(dates, 5)):
        log(f"H1 computing analog summaries {n * 5 + 1}-{n * 5 + len(batch)} of {len(dates)} dates")
        api.post("/analogs/compute", {"dates": batch, "lookbacks": list(H1_LOOKBACKS)})
    tagged: dict[tuple, list[dict]] = {}
    symbols = set()
    for date in dates:
        for lookback in H1_LOOKBACKS:
            for forward in H1_FORWARDS:
                ranked = api.get("/analogs/rank", lookback=lookback, forward=forward, sort="count", minCount=0, date=date)
                if ranked["date"] != date:
                    continue  # no summaries for exactly this session
                for row in ranked["rows"]:
                    symbols.add(row["symbol"])
                    tagged.setdefault((lookback, forward), []).append({"date": date, "symbol": row["symbol"],
                        "direction": row["outcome"]["direction"], "reliability": row["outcome"]["reliability"], "count": row["outcome"]["count"]})
    log(f"H1 loading closes of {len(symbols)} symbols")
    closes: dict[str, tuple[list[str], list[float]]] = {}
    for symbol in sorted(symbols):
        rows = api.get(f"/ratings/{urllib.parse.quote(symbol)}/history", **{"from": ev["from"], "to": ev["to"]})
        closes[symbol] = ([r["sessionDate"] for r in rows], [float(r["close"]) for r in rows])
    cells = {}
    for (lookback, forward), rows in tagged.items():
        observations = []
        for row in rows:
            days, prices = closes[row["symbol"]]
            if row["date"] not in days:
                continue
            i = days.index(row["date"])
            if i + forward >= len(days):
                continue
            observations.append({**row, "ret": (prices[i + forward] / prices[i] - 1) * 100})
        buckets = {"MEDIUM_OR_HIGH": [o for o in observations if o["reliability"] in RELIABLE]}
        for reliability in ("HIGH", "MEDIUM", "LOW", "INSUFFICIENT"):
            buckets[reliability] = [o for o in observations if o["reliability"] == reliability]
        cells[f"{lookback}/{forward}"] = {bucket: spread_stats([{"date": o["date"], "side": side_of(o["direction"]), "ret": o["ret"]} for o in obs],
                                                                H1_MIN_PER_DATE) for bucket, obs in buckets.items()}
    return {"dates": len(dates), "symbols": len(symbols), "cells": cells}


def h1_verdict(result: dict) -> tuple[str, list[str]]:
    passed, measurable, notes = 0, 0, []
    for cell, buckets in sorted(result["cells"].items()):
        s = buckets["MEDIUM_OR_HIGH"]
        forward = int(cell.split("/")[1])
        enough = s["bull"] >= H1_MIN_GROUP and s["bear"] >= H1_MIN_GROUP
        measurable += enough
        spread, t = s["spread"]["mean"], s["spread"]["t"]
        quarters_ok = s["quarters"] > 0 and s["positiveQuarters"] / s["quarters"] >= H1_MIN_POSITIVE_QUARTERS
        ok = enough and spread is not None and spread >= H1_MIN_SPREAD_PCT[forward] and t is not None and t >= MIN_T and quarters_ok
        passed += ok
        notes.append(f"{cell}: {'pass' if ok else 'fail' if enough else 'too few observations'}")
    if measurable < H1_CELLS_TO_PASS:
        return "INCONCLUSIVE", notes
    return ("PASS" if passed >= H1_CELLS_TO_PASS else "FAIL"), notes


# ---------------------------------------------------------------------------------------------------------------------
# H2 session analogs


def realised_session_return(api: Api, instrument_id: str, date: str, checkpoint: str) -> float | None:
    day = dt.date.fromisoformat(date)
    start = dt.datetime.combine(day, dt.time(9, 15), IST)
    end = dt.datetime.combine(day, dt.time(15, 30), IST)
    candles = api.get("/market/candles", instrumentId=instrument_id, timeframe="M5", **{"from": start.isoformat(), "to": end.isoformat()})
    hh, mm = (int(x) for x in checkpoint.split(":"))
    at = dt.datetime.combine(day, dt.time(hh, mm), IST)
    xh, xm = (int(x) for x in H2_EXIT.split(":"))
    exit_at = dt.datetime.combine(day, dt.time(xh, xm), IST)
    before = after = None
    for c in candles:
        closes_at = dt.datetime.fromisoformat(c["openTime"].replace("Z", "+00:00")) + dt.timedelta(minutes=5)
        if closes_at <= at:
            before = float(c["close"])
        if closes_at <= exit_at:
            after = float(c["close"])
    return None if before is None or after is None or before <= 0 else (after / before - 1) * 100


def h2(api: Api, ev: dict) -> dict:
    symbols = nifty50_symbols()
    ids = {}
    for symbol in symbols:
        try:
            ids[symbol] = api.get("/instruments/resolve", symbol=symbol)["id"]
        except RuntimeError:
            log(f"H2 {symbol} does not resolve; skipped")
    observations = {cp: [] for cp in H2_CHECKPOINTS}
    for n, date in enumerate(ev["h2Dates"]):
        log(f"H2 session {n + 1} of {len(ev['h2Dates'])}: {date}")
        for checkpoint in H2_CHECKPOINTS:
            for batch in chunks(sorted(ids), 50):
                summaries = api.get("/analogs/session", symbols=",".join(batch), checkpoint=checkpoint, date=date)
                for symbol, summary in summaries.items():
                    if summary["sessionDate"] != date or not summary["outcomes"]:
                        continue
                    ret = realised_session_return(api, ids[symbol], date, checkpoint)
                    if ret is not None:
                        observations[checkpoint].append({"date": date, "side": side_of(summary["outcomes"][0]["direction"]), "ret": ret})
    pooled = [{**o, "date": o["date"]} for cp in H2_CHECKPOINTS for o in observations[cp]]
    return {"dates": len(ev["h2Dates"]), "symbols": len(ids), "pooled": spread_stats(pooled, H2_MIN_PER_DATE),
            "byCheckpoint": {cp: spread_stats(observations[cp], H2_MIN_PER_DATE) for cp in H2_CHECKPOINTS}}


def h2_verdict(result: dict) -> tuple[str, list[str]]:
    s = result["pooled"]
    if s["bull"] < H2_MIN_GROUP or s["bear"] < H2_MIN_GROUP:
        return "INCONCLUSIVE", [f"{s['bull']} bullish and {s['bear']} bearish observations, {H2_MIN_GROUP} each needed"]
    spread, t = s["spread"]["mean"], s["spread"]["t"]
    ok = spread is not None and spread >= H2_MIN_SPREAD_PCT and t is not None and t >= MIN_T
    return ("PASS" if ok else "FAIL"), [f"pooled spread {fmt(spread, '+.3f')} %, t {fmt(t, '.2f')}"]


# ---------------------------------------------------------------------------------------------------------------------
# H3 / H4 paired backtests with the research-only session filter


def strategy(api: Api, slug: str) -> dict:
    s = strategy_by_slug(api, slug)
    version = api.get(f"/strategies/{s['id']}/versions/{s['latestVersion']}")
    series = [series_for(api, item) for item in universe(version)]
    return {"slug": slug, "versionId": version["id"], "direction": str(version["definition"].get("direction", "")).lower(),
            "instruments": [x["symbol"] for x in series], "stocks": all(x["kind"] == "stock" for x in series)}


def previous_session(days: list[str]) -> dict[str, str]:
    return {days[i]: days[i - 1] for i in range(1, len(days))}


def rs_filter(api: Api, ev: dict, s: dict) -> dict:
    """Per session: the N strongest (long) or weakest (short) of the strategy's stocks by the previous session's RS raw value."""
    members = set(s["instruments"])
    days = {}
    for date, prev in previous_session(ev["sessions"]).items():
        rows = [r for r in api.get("/ratings", date=prev, limit=1000, sort="rs")["ratings"] if r["symbol"] in members and r.get("rsRaw") is not None]
        if not rows or rows[0]["sessionDate"] != prev:
            continue  # no snapshot: the session stays unlisted and is blocked
        rows.sort(key=lambda r: r["rsRaw"], reverse=s["direction"] == "long")
        days[date] = {"instruments": [r["symbol"] for r in rows[:H3_TOP_N]], "sides": ["BUY" if s["direction"] == "long" else "SELL"]}
    return {"unlistedDates": "BLOCK", "days": days}


def condition_filter(api: Api, ev: dict) -> tuple[dict, float]:
    labels = {r["date"]: r["marketCondition"] for r in api.get("/context/regime/history", **{"from": ev["from"], "to": ev["to"]})}
    days, blocked = {}, 0
    previous = previous_session(ev["sessions"])
    for date, prev in previous.items():
        allowed = labels.get(prev) in LONG_CONDITIONS
        blocked += not allowed
        days[date] = {"sides": ["BUY", "SELL"] if allowed else ["SELL"]}
    return {"unlistedDates": "BLOCK", "days": days}, blocked / max(1, len(previous))


def paired(api: Api, ev: dict, s: dict, session_filter: dict, poll: float) -> dict:
    body = {"versionId": s["versionId"], "instruments": s["instruments"], "fillModel": "NEXT_OPEN",
            "slippageBps": STOCK_SLIPPAGE_BPS if s["stocks"] else OTHER_SLIPPAGE_BPS, "from": ev["from"], "to": ev["to"], "splits": {"type": "NONE"}}
    plain = api.post("/backtests", body)["id"]
    filtered = api.post("/backtests", {**body, "sessionFilter": session_filter})["id"]
    done = wait(api, [plain, filtered], poll)
    out = {"slug": s["slug"]}
    for name, backtest_id in (("plain", plain), ("filtered", filtered)):
        b = done[backtest_id]
        m = b.get("metrics") or {}
        out[name] = {"id": backtest_id, "status": b["status"], "resultHash": b.get("resultHash"), "trades": m.get("totalTrades"),
                     "expectancyR": m.get("expectancyR"), "profitFactor": m.get("profitFactor")}
    return out


def paired_verdict(rows: list[dict]) -> tuple[str, list[str]]:
    measurable, passed, notes = 0, 0, []
    for r in rows:
        f, p = r["filtered"], r["plain"]
        if f["status"] != "DONE" or p["status"] != "DONE" or (f["trades"] or 0) < MIN_FILTERED_TRADES or f["expectancyR"] is None or p["expectancyR"] is None:
            notes.append(f"{r['slug']}: not measurable ({f['trades']} filtered trades)")
            continue
        measurable += 1
        ok = f["expectancyR"] - p["expectancyR"] >= MIN_DELTA_R and f["expectancyR"] > 0
        passed += ok
        notes.append(f"{r['slug']}: {'pass' if ok else 'fail'} ({fmt(p['expectancyR'], '+.3f')} R -> {fmt(f['expectancyR'], '+.3f')} R)")
    if measurable == 0:
        return "INCONCLUSIVE", notes or ["no eligible strategy"]
    return ("PASS" if passed * 2 > measurable else "FAIL"), notes


# ---------------------------------------------------------------------------------------------------------------------
# H5 bases


def h5(api: Api, ev: dict) -> dict:
    ledger = api.get("/ratings/setups/past", **{"from": ev["from"], "to": ev["to"]})
    labels = {r["date"]: r["marketCondition"] for r in api.get("/context/regime/history", **{"from": ev["from"], "to": ev["to"]})}
    groups: dict[str, dict[str, list[float]]] = {}
    statuses: dict[str, dict[str, int]] = {}
    for b in ledger["setups"]:
        statuses.setdefault(b["type"], {}).setdefault(b["status"], 0)
        statuses[b["type"]][b["status"]] += 1
        if b.get("outcomeR") is None:
            continue
        g = groups.setdefault(b["type"], {})
        g.setdefault("all", []).append(b["outcomeR"])
        g.setdefault("volume" if b.get("volumeConfirmed") else "noVolume", []).append(b["outcomeR"])
        g.setdefault("condition:" + labels.get(b["triggerDate"], "UNKNOWN"), []).append(b["outcomeR"])
    return {"statuses": statuses, "byType": {t: {k: {"n": len(v), "meanR": statistics.fmean(v), "medianR": statistics.median(v),
                                                     "winRate": sum(1 for x in v if x > 0) / len(v)} for k, v in g.items()} for t, g in groups.items()}}


def h5_verdict(result: dict) -> tuple[str, list[str]]:
    total = sum(g.get("all", {}).get("n", 0) for g in result["byType"].values())
    if total < H5_MIN_TRIGGERED:
        return "INCONCLUSIVE", [f"{total} triggered setups in the range, {H5_MIN_TRIGGERED} needed"]
    notes, ok = [], False
    for base_type, g in sorted(result["byType"].items()):
        a, vol, no_vol = g["all"], g.get("volume"), g.get("noVolume")
        good = a["n"] >= H5_MIN_TRIGGERED and a["meanR"] >= H5_MIN_MEAN_R and (vol is None or no_vol is None or vol["meanR"] >= no_vol["meanR"])
        ok = ok or good
        notes.append(f"{base_type}: {'pass' if good else 'fail'} (mean {a['meanR']:+.2f} R of {a['n']})")
    return ("PASS" if ok else "FAIL"), notes


# ---------------------------------------------------------------------------------------------------------------------
# commands


def cmd_plan(api: Api, args) -> None:
    ev = evaluation(api, args.latest, args.calendar_symbol)
    print(f"evaluation range {ev['from']} .. {ev['to']}: {len(ev['sessions'])} sessions (calendar: {args.calendar_symbol})")
    print(f"H1: {len(ev['h1Dates'])} sample dates ({ev['h1Dates'][0]} .. {ev['h1Dates'][-1]}), lookbacks {H1_LOOKBACKS}, forwards {H1_FORWARDS}; "
          f"{math.ceil(len(ev['h1Dates']) / 5)} compute calls, {len(ev['h1Dates']) * len(H1_LOOKBACKS) * len(H1_FORWARDS)} rank reads, one history read per symbol")
    print(f"H2: {len(ev['h2Dates'])} sample dates x {len(H2_CHECKPOINTS)} checkpoints x {len(nifty50_symbols())} symbols; the server reads its session "
          f"history once and extends it date by date (again after a write to the candle store or past midnight)")
    for slug in args.slugs:
        s = strategy(api, slug)
        h3 = "H3" if s["stocks"] and s["direction"] in ("long", "short") else "H3 skipped (not a one-sided stock strategy)"
        h4 = "H4" if s["direction"] == "long" else "H4 skipped (not a long strategy)"
        print(f"{slug}: direction {s['direction']}, {len(s['instruments'])} instruments; {h3}; {h4}")
    if not args.slugs:
        print("H3/H4: no strategies named; they will be INCONCLUSIVE")
    print("H5: one ledger read")


def cmd_run(api: Api, args) -> None:
    only = set(args.only.split(",")) if args.only else {"h1", "h2", "h3", "h4", "h5"}
    ev = evaluation(api, args.latest, args.calendar_symbol)
    results = json.loads(OUT.read_text()) if OUT.exists() else {}
    if results.get("latest") not in (None, args.latest.isoformat()):
        sys.exit(f"{OUT} was measured with --latest {results['latest']}: the range is pinned; use the same date or remove the file")
    results.update({"latest": args.latest.isoformat(), "from": ev["from"], "to": ev["to"], "sessions": len(ev["sessions"]),
                    "measuredAt": dt.datetime.now(dt.timezone.utc).isoformat(timespec="seconds")})

    def save():
        OUT.parent.mkdir(parents=True, exist_ok=True)
        OUT.write_text(json.dumps(results, indent=1, sort_keys=True))

    if "h1" in only:
        results["h1"] = h1(api, ev)
        save()
    if "h2" in only:
        results["h2"] = h2(api, ev)
        save()
    strategies = [strategy(api, slug) for slug in args.slugs] if only & {"h3", "h4"} else []
    if "h3" in only:
        results["h3"] = [paired(api, ev, s, rs_filter(api, ev, s), args.poll) for s in strategies if s["stocks"] and s["direction"] in ("long", "short")]
        save()
    if "h4" in only:
        gate, blocked = condition_filter(api, ev)
        results["h4"] = {"blockedShare": blocked, "rows": [paired(api, ev, s, gate, args.poll) for s in strategies if s["direction"] == "long"]}
        save()
    if "h5" in only:
        results["h5"] = h5(api, ev)
        save()
    log(f"saved {OUT}; now: context_validation.py report")


def replace_section(marker: str, content: str) -> None:
    text = REPORT.read_text()
    start, end = f"<!-- context-validation:{marker}:start -->", f"<!-- context-validation:{marker}:end -->"
    if start not in text or end not in text:
        raise RuntimeError(f"{REPORT} has no {marker} markers")
    head, rest = text.split(start, 1)
    _, tail = rest.split(end, 1)
    REPORT.write_text(head + start + "\n" + content.rstrip() + "\n" + end + tail)


def spread_row(label: str, s: dict) -> str:
    return (f"| {label} | {s['bull']} | {fmt(s['bullMean'], '+.2f')} % ({fmt(s['bullHitRate'], '.0%')}) | {s['bear']} | {fmt(s['bearMean'], '+.2f')} % "
            f"({fmt(s['bearHitRate'], '.0%')}) | {s['observations']} | {fmt(s['baseMean'], '+.2f')} % ({fmt(s['baseHitRate'], '.0%')}) | "
            f"{fmt(s['spread']['mean'], '+.3f')} % | {fmt(s['spread']['t'], '.2f')} ({s['spread']['dates']}) | {s['positiveQuarters']}/{s['quarters']} |")


SPREAD_HEADER = ("| Group | Bullish n | Bullish mean (hit rate) | Bearish n | Bearish mean (hit rate) | All n | Base mean (hit rate) | Spread per date | "
                 "t (dates) | Positive quarters |\n|---|---|---|---|---|---|---|---|---|---|")


def cmd_report(_api, _args) -> None:
    if not OUT.exists():
        sys.exit(f"{OUT} not found: run first")
    r = json.loads(OUT.read_text())
    lines = [f"Measured {r['measuredAt']} over **{r['from']} .. {r['to']}** ({r['sessions']} sessions, `--latest {r['latest']}`). Every rate stands next to "
             "its count. Daily-universe results (H1, H3, H5) carry the survivorship-bias caveat of the protocol.", ""]
    verdicts = []
    if "h1" in r:
        verdict, notes = h1_verdict(r["h1"])
        verdicts.append(("H1 daily analogs", verdict, notes))
        lines += [f"### H1 daily analogs: **{verdict}**", "", f"{r['h1']['dates']} sample dates, {r['h1']['symbols']} symbols.", "", SPREAD_HEADER]
        for cell, buckets in sorted(r["h1"]["cells"].items()):
            for bucket in ("MEDIUM_OR_HIGH", "HIGH", "MEDIUM", "LOW", "INSUFFICIENT"):
                lines.append(spread_row(f"lookback/forward {cell}, reliability {bucket}", buckets[bucket]))
        lines.append("")
    if "h2" in r:
        verdict, notes = h2_verdict(r["h2"])
        verdicts.append(("H2 session analogs", verdict, notes))
        lines += [f"### H2 session analogs: **{verdict}**", "", f"{r['h2']['dates']} sample dates, {r['h2']['symbols']} symbols.", "", SPREAD_HEADER,
                  spread_row("pooled (the pass mark)", r["h2"]["pooled"])]
        lines += [spread_row(f"checkpoint {cp} (informational)", s) for cp, s in r["h2"]["byCheckpoint"].items()]
        lines.append("")
    for key, title in (("h3", "H3 RS selection"), ("h4", "H4 market condition gate")):
        if key not in r:
            continue
        rows = r[key] if key == "h3" else r[key]["rows"]
        verdict, notes = paired_verdict(rows)
        verdicts.append((title, verdict, notes))
        lines += [f"### {title}: **{verdict}**", ""]
        if key == "h4":
            lines += [f"The gate blocked longs on {r[key]['blockedShare']:.0%} of the sessions.", ""]
        lines += ["| Strategy | Unrestricted trades | Expectancy | Profit factor | Restricted trades | Expectancy | Profit factor | Δ expectancy | Backtests |",
                  "|---|---|---|---|---|---|---|---|---|"]
        for row in rows:
            p, f = row["plain"], row["filtered"]
            delta = None if p["expectancyR"] is None or f["expectancyR"] is None else f["expectancyR"] - p["expectancyR"]
            lines.append(f"| `{row['slug']}` | {fmt(p['trades'], 'd')} | {fmt(p['expectancyR'], '+.3f')} R | {fmt(p['profitFactor'], '.2f')} | {fmt(f['trades'], 'd')} | "
                         f"{fmt(f['expectancyR'], '+.3f')} R | {fmt(f['profitFactor'], '.2f')} | {fmt(delta, '+.3f')} R | `{p['id']}` / `{f['id']}` |")
        lines.append("")
    if "h5" in r:
        verdict, notes = h5_verdict(r["h5"])
        verdicts.append(("H5 bases (informational)", verdict, notes))
        lines += [f"### H5 bases: **{verdict}**", "", "R is before costs with fills at the pivot: optimistic.", "",
                  "| Base type | Group | Triggered n | Mean R | Median R | Winners |", "|---|---|---|---|---|---|"]
        for base_type, groups in sorted(r["h5"]["byType"].items()):
            for group, s in sorted(groups.items()):
                lines.append(f"| {base_type} | {group} | {s['n']} | {s['meanR']:+.2f} | {s['medianR']:+.2f} | {s['winRate']:.0%} of {s['n']} |")
        lines += ["", "Closed setups by status: " + "; ".join(f"{t}: " + ", ".join(f"{k} {v}" for k, v in sorted(c.items()))
                                                                for t, c in sorted(r["h5"]["statuses"].items())), ""]
    summary = ["| Hypothesis | Verdict | Detail |", "|---|---|---|"] + [f"| {name} | **{v}** | {'; '.join(notes)} |" for name, v, notes in verdicts]
    replace_section("results", "\n".join(summary + [""] + lines))
    for name, verdict, notes in verdicts:
        print(f"{name}: {verdict}  ({'; '.join(notes)})")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    sub = parser.add_subparsers(dest="command", required=True)
    for name in ("plan", "run"):
        p = sub.add_parser(name)
        p.add_argument("--latest", required=True, type=dt.date.fromisoformat, help="the latest session of the evaluation range (pinned at the first run)")
        p.add_argument("--calendar-symbol", default="NSE:RELIANCE", help="the symbol whose rating rows define the session calendar")
        p.add_argument("slugs", nargs="*", help="bake-off survivors for H3 and H4")
        if name == "run":
            p.add_argument("--only", help="comma list of h1,h2,h3,h4,h5")
            p.add_argument("--poll", type=float, default=10.0)
    sub.add_parser("report")
    args = parser.parse_args()
    if args.command == "report":
        cmd_report(None, args)
        return
    base, key = os.environ.get("HEJJE_URL"), os.environ.get("HEJJE_API_KEY")
    if not base or not key:
        sys.exit("set HEJJE_URL and HEJJE_API_KEY")
    api = PoliteApi(base, key)
    started = time.time()
    (cmd_plan if args.command == "plan" else cmd_run)(api, args)
    log(f"{args.command} took {time.time() - started:.0f} s")


if __name__ == "__main__":
    main()
