# nifty_orb_call_buy (bundled, M5.4)

The `nifty_orb` breakout (close above the 15-minute opening-range high on the NIFTY front-month future, above VWAP, relative
volume above 1.2, 09:30–12:00) traded with **one lot of the ATM weekly call** instead of the future.

- Leg: buy, directional (a long signal buys a CE), ATM of the nearest weekly expiry still tradable today, 1 lot.
- Exits (options position monitor): 30 % premium stop, 60 % premium target, the underlying's opening-range-low stop,
  15:10 force exit — whichever comes first.
- Risk: the premium at risk is bounded by `hejje.options.max-premium-rupees`; buying an option is defined risk.
- PAPER-only until it has `hejje.options.min-paper-trades` closed paper positions: the historical store has no option
  candles, so it cannot be backtested (docs/options.md).
