#!/usr/bin/env python3
"""Writes the golden D1 series of the base detector tests (plan M8.4) to server/src/test/resources/bases/*.csv.

Standard library only. Each series is a piecewise-linear close path; a bar's open is the previous close, its high and
low are 0.5 % around the close/open extremes, volume is 100000 unless a segment says otherwise. Every series starts
with 30 flat sessions at 100 and, unless it is a "no uptrend" near miss, an 80-session advance to 150 (a 50 % prior
uptrend); the left high is session 110.
"""
import os

OUT = os.path.join(os.path.dirname(__file__), "..", "..", "server", "src", "test", "resources", "bases")
UPTREND = [(30, 100.0), (80, 150.0)]


def series(segments, start=100.0, overrides=None):
    """segments: (sessions, target close[, volume]); overrides: {index: (open, high, low, close, volume)}"""
    rows = []
    close = start
    for segment in segments:
        sessions, target = segment[0], segment[1]
        volume = segment[2] if len(segment) > 2 else 100000
        first = close
        for k in range(1, sessions + 1):
            previous = close
            close = first + (target - first) * k / sessions
            rows.append((previous, max(previous, close) * 1.005, min(previous, close) * 0.995, close, volume))
    for index, row in (overrides or {}).items():
        rows[index] = row
    return rows


def zigzag(sessions, low, high, step=5):
    """Alternating legs between low and high."""
    legs = []
    up = False
    for _ in range(sessions // step):
        legs.append((step, high if up else low))
        up = not up
    return legs


FIXTURES = {
    # flat base: 30 sessions between 138 and 146 under a 150 left high (depth about 9 %)
    "flat_base": UPTREND + zigzag(30, 138.0, 146.0),
    "flat_base_too_deep": UPTREND + zigzag(30, 125.0, 130.0),
    "flat_base_too_short": UPTREND + zigzag(20, 138.0, 146.0),
    "flat_base_no_uptrend": [(30, 100.0), (80, 118.0)] + zigzag(30, 110.0, 115.0),
    # cup with handle: 25 % deep, 65 sessions high to high, right side to 146, an 8-session handle around 140
    "cup_with_handle": UPTREND + [(30, 112.0), (35, 146.0), (4, 139.0), (2, 141.0), (2, 139.5)],
    "cup_too_deep": UPTREND + [(30, 88.0), (35, 146.0), (4, 139.0), (2, 141.0), (2, 139.5)],
    "cup_too_short": UPTREND + [(10, 125.0), (12, 146.0), (4, 139.0), (2, 141.0), (2, 139.5)],
    "cup_handle_too_deep": UPTREND + [(30, 112.0), (35, 146.0), (5, 124.0), (3, 126.0)],
    # double bottom: 120, a middle peak at 135, an undercut to 118.5, five sessions up
    "double_bottom": UPTREND + [(15, 120.0), (10, 135.0), (12, 118.5), (5, 128.0)],
    "double_bottom_lows_apart": UPTREND + [(15, 120.0), (10, 135.0), (12, 110.0), (5, 120.0)],
    "double_bottom_no_uptrend": [(30, 100.0), (80, 118.0), (15, 100.0), (10, 110.0), (12, 99.0), (5, 105.0)],
}

# moving-average reversal: a steady advance, an eight-session pullback to the 50-DMA, then a session that trades down
# to it and closes near its high
reversal = series([(30, 100.0), (120, 160.0), (8, 152.0)])
reversal.append((152.0, 154.6, 151.0, 154.2, 150000))
FIXTURES_ROWS = {"ma_reversal": reversal}
weak = list(reversal[:-1])
weak.append((152.0, 154.6, 151.0, 152.3, 150000))  # closes in the lower half of its range
FIXTURES_ROWS["ma_reversal_weak_close"] = weak

# lifecycle: the flat base, then a breakout on volume through the 150.75 pivot and a run to the goal ...
FIXTURES["lifecycle_goal"] = FIXTURES["flat_base"] + [(3, 149.0), (1, 153.0, 200000), (25, 185.0)]
# ... or a breakout without volume that falls back through the stop
FIXTURES["lifecycle_stopped"] = FIXTURES["flat_base"] + [(3, 149.0), (1, 153.0), (2, 151.0), (6, 138.0)]
# ... or a base that breaks down before it ever triggers
FIXTURES["lifecycle_failed"] = FIXTURES["flat_base"] + [(6, 130.0)]


def main():
    os.makedirs(OUT, exist_ok=True)
    everything = {name: series(segments) for name, segments in FIXTURES.items()}
    everything.update(FIXTURES_ROWS)
    for name, rows in everything.items():
        with open(os.path.join(OUT, name + ".csv"), "w", encoding="utf-8") as f:
            f.write("open,high,low,close,volume\n")
            for o, h, l, c, v in rows:
                f.write(f"{o:.2f},{h:.2f},{l:.2f},{c:.2f},{int(v)}\n")
        print(f"{name}: {len(rows)} sessions")


if __name__ == "__main__":
    main()
