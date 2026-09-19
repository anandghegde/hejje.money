# research/

Python tooling that supports the Java core without being a source of truth.

- `tools/gen_indicator_fixtures.py`: regenerates `server/src/test/resources/indicators/golden.csv` (TA-Lib + pandas
  reference values for the indicator library). Needs a venv with `pandas`, `numpy`, `TA-Lib` (the C library via
  `brew install ta-lib`): `uv venv .venv --python 3.12 && uv pip install --python .venv/bin/python pandas numpy TA-Lib`.

- `tools/bakeoff.py`: the Phase 6 strategy bake-off (plan M6.5, `docs/strategies/bakeoff.md`). Standard library only;
  needs `HEJJE_URL` and `HEJJE_API_KEY` (scopes `strategies:read`, `strategies:write`, `market:read`). `plan` prints each
  strategy's series and ranges, `run` starts the protocol backtests and writes the results table, `holdout` runs the
  passing strategies once on the 6-month holdout (pass `--latest` from the protocol run).

- `bots/example_bot.py`: the reference bot (plan M7.3, `docs/bots.md`): standard library only, a minimal WebSocket
  client and an opening-range rule; `HEJJE_URL`, `HEJJE_API_KEY` (preset `bot`), the bot id as argument.

The optional research worker (plan M2.9) lives here later.
