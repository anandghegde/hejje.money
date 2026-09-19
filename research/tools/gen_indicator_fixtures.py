"""Generate the golden indicator fixture for money.hejje.market.indicators (plan M2.2).

Independent reference implementation: TA-Lib for the classic indicators, pandas for the session-based ones.
Run once and commit the CSV:

    cd research && .venv/bin/python tools/gen_indicator_fixtures.py

Output: server/src/test/resources/indicators/golden.csv
"""
from pathlib import Path

import numpy as np
import pandas as pd
import talib

OUT = Path(__file__).resolve().parents[2] / "server/src/test/resources/indicators/golden.csv"
SESSIONS = ["2026-09-01", "2026-09-02", "2026-09-03", "2026-09-04", "2026-09-07", "2026-09-08",
            "2026-09-09", "2026-09-10", "2026-09-11", "2026-09-14", "2026-09-15", "2026-09-16"]
BARS_PER_SESSION = 75  # 09:15 .. 15:25 at 5 minutes
OPENING_RANGE_MINUTES = 15
RELVOL_SESSIONS = 20
OPENING_RETURN_MINUTES = 30


def synthetic_bars() -> pd.DataFrame:
    rng = np.random.default_rng(20260909)
    rows = []
    price = 24800.0
    for day in SESSIONS:
        # a session gap
        price *= 1 + rng.normal(0, 0.004)
        for i in range(BARS_PER_SESSION):
            ts = pd.Timestamp(day) + pd.Timedelta(hours=9, minutes=15) + pd.Timedelta(minutes=5 * i)
            open_ = price
            drift = rng.normal(0, 12)
            close = open_ + drift
            high = max(open_, close) + abs(rng.normal(0, 6))
            low = min(open_, close) - abs(rng.normal(0, 6))
            # a few flat bars to exercise zero ranges
            if i % 37 == 5:
                high = low = open_ = close = round(open_, 2)
            volume = int(abs(rng.normal(120000, 40000))) + 1000
            # U-shaped intraday volume
            volume = int(volume * (1.6 if i < 6 or i > 68 else 1.0))
            rows.append((ts, round(open_, 2), round(high, 2), round(low, 2), round(close, 2), volume))
            price = close
    df = pd.DataFrame(rows, columns=["ts", "open", "high", "low", "close", "volume"])
    df["high"] = df[["high", "open", "close"]].max(axis=1)
    df["low"] = df[["low", "open", "close"]].min(axis=1)
    return df


def session_indicators(df: pd.DataFrame) -> pd.DataFrame:
    df = df.copy()
    df["date"] = df["ts"].dt.date
    df["tod"] = df["ts"].dt.time
    tp = (df["high"] + df["low"] + df["close"]) / 3
    pv = (tp * df["volume"]).groupby(df["date"]).cumsum()
    cv = df["volume"].groupby(df["date"]).cumsum()
    df["vwap"] = pv / cv

    open_time = pd.Timestamp("2026-01-01 09:15").time()
    or_end = (pd.Timestamp("2026-01-01 09:15") + pd.Timedelta(minutes=OPENING_RANGE_MINUTES)).time()
    df["close_tod"] = (df["ts"] + pd.Timedelta(minutes=5)).dt.time
    in_range = df["tod"] < or_end
    or_high = df[in_range].groupby("date")["high"].max()
    or_low = df[in_range].groupby("date")["low"].min()
    ready = df["close_tod"] >= or_end
    df["or_high_15"] = np.where(ready, df["date"].map(or_high), np.nan)
    df["or_low_15"] = np.where(ready, df["date"].map(or_low), np.nan)

    # relative volume: bar volume / mean of the same slot over the previous N sessions (fewer when unavailable)
    relvol = []
    hist: dict = {}
    for _, r in df.iterrows():
        h = hist.setdefault(r["tod"], [])
        relvol.append(r["volume"] / np.mean(h) if h else np.nan)
        h.append(r["volume"])
        del h[:-RELVOL_SESSIONS]
    df["relvol_20"] = relvol

    daily = df.groupby("date").agg(day_high=("high", "max"), day_low=("low", "min"), day_close=("close", "last"), day_open=("open", "first"))
    prev = daily.shift(1)
    df["prev_day_high"] = df["date"].map(prev["day_high"])
    df["prev_day_low"] = df["date"].map(prev["day_low"])
    df["prev_day_close"] = df["date"].map(prev["day_close"])
    df["gap_pct"] = (df["date"].map(daily["day_open"]) - df["prev_day_close"]) / df["prev_day_close"] * 100
    df["session_open"] = df["date"].map(daily["day_open"])
    df["session_high"] = df.groupby("date")["high"].cummax()
    df["session_low"] = df.groupby("date")["low"].cummin()

    # central pivot range from the previous session
    p = (prev["day_high"] + prev["day_low"] + prev["day_close"]) / 3
    bc = (prev["day_high"] + prev["day_low"]) / 2
    tc = 2 * p - bc
    top, bottom = np.maximum(tc, bc), np.minimum(tc, bc)
    df["pivot"] = df["date"].map(p)
    df["cpr_top"] = df["date"].map(top)
    df["cpr_bottom"] = df["date"].map(bottom)
    df["cpr_width_pct"] = df["date"].map((top - bottom) / p * 100)

    # NR(n): previous session's range is the smallest of the last n completed sessions (ties count)
    rng = daily["day_high"] - daily["day_low"]
    for n in (4, 7):
        nr = (rng <= rng.rolling(n).min()).astype(float).where(rng.rolling(n).count() == n)
        df[f"prev_day_nr_{n}"] = df["date"].map(nr.shift(1))

    # opening return: close of the bar ending at 09:15 + d vs the previous close, constant after it
    end_ts = df["ts"].dt.normalize() + pd.Timedelta(hours=9, minutes=15 + OPENING_RETURN_MINUTES)
    anchor = df[(df["ts"] + pd.Timedelta(minutes=5)) == end_ts].set_index("date")["close"]
    ret = (df["date"].map(anchor) - df["prev_day_close"]) / df["prev_day_close"] * 100
    df[f"opening_return_{OPENING_RETURN_MINUTES}"] = np.where((df["ts"] + pd.Timedelta(minutes=5)) >= end_ts, ret, np.nan)

    df["session_minutes"] = ((df["ts"] + pd.Timedelta(minutes=5)) - df["ts"].dt.normalize() - pd.Timedelta(hours=9, minutes=15)).dt.total_seconds() / 60
    return df.drop(columns=["date", "tod", "close_tod"])


def supertrend(h: np.ndarray, l: np.ndarray, c: np.ndarray, n: int, k: float) -> np.ndarray:
    """Textbook Supertrend: final-band recursion on hl2 ± k·ATR(n) (Wilder, TA-Lib), starting in an uptrend."""
    atr = talib.ATR(h, l, c, n)
    hl2 = (h + l) / 2
    out = np.full(len(c), np.nan)
    upper = lower = np.nan
    trend_up = True
    for i in range(len(c)):
        if np.isnan(atr[i]):
            continue
        bu, bl = hl2[i] + k * atr[i], hl2[i] - k * atr[i]
        if np.isnan(upper):
            upper, lower, trend_up = bu, bl, True
        else:
            upper = bu if (bu < upper or c[i - 1] > upper) else upper
            lower = bl if (bl > lower or c[i - 1] < lower) else lower
            trend_up = c[i] >= lower if trend_up else c[i] > upper
        out[i] = lower if trend_up else upper
    return out


def main() -> None:
    df = synthetic_bars()
    h, l, c = df["high"].values, df["low"].values, df["close"].values
    df["sma_20"] = talib.SMA(c, 20)
    df["ema_20"] = talib.EMA(c, 20)
    df["rsi_14"] = talib.RSI(c, 14)
    df["atr_14"] = talib.ATR(h, l, c, 14)
    upper, _, lower = talib.BBANDS(c, 20, 2, 2, talib.MA_Type.SMA)
    df["bb_upper_20_2"] = upper
    df["bb_lower_20_2"] = lower
    df["adx_14"] = talib.ADX(h, l, c, 14)
    df["highest_20"] = talib.MAX(h, 20)
    df["lowest_20"] = talib.MIN(l, 20)
    df["supertrend_10_3"] = supertrend(h, l, c, 10, 3.0)
    df = session_indicators(df)
    df["ts"] = df["ts"].dt.strftime("%Y-%m-%dT%H:%M")
    OUT.parent.mkdir(parents=True, exist_ok=True)
    df.to_csv(OUT, index=False, float_format="%.10f")
    print(f"wrote {len(df)} rows to {OUT}")


if __name__ == "__main__":
    main()
