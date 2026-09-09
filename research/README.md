# research/

Python tooling that supports the Java core without being a source of truth.

- `tools/gen_indicator_fixtures.py`: regenerates `server/src/test/resources/indicators/golden.csv` (TA-Lib + pandas
  reference values for the indicator library). Needs a venv with `pandas`, `numpy`, `TA-Lib` (the C library via
  `brew install ta-lib`): `uv venv .venv --python 3.12 && uv pip install --python .venv/bin/python pandas numpy TA-Lib`.

The optional research worker (plan M2.9) lives here later.
