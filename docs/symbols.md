# Hejje symbols

Every instrument has a broker-neutral canonical symbol. Clients, strategies, the TUI and the API use this form;
broker trading symbols and tokens live only in `broker_instrument_mapping`.

| Kind | Form | Example |
|---|---|---|
| Equity | `EXCHANGE:SYMBOL` | `NSE:RELIANCE`, `BSE:RELIANCE` |
| Index | `INDEX:NAME` | `INDEX:NIFTY 50`, `INDEX:NIFTY BANK`, `INDEX:INDIA VIX` |
| Future | `EXCHANGE:UNDERLYING:FUT:YYYY-MM-DD` | `NFO:NIFTY:FUT:2026-09-29` |
| Option | `EXCHANGE:UNDERLYING:OPT:YYYY-MM-DD:STRIKE:CE\|PE` | `NFO:NIFTY:OPT:2026-09-29:25000:CE`, `NFO:RELIANCE:OPT:2026-09-29:1402.5:PE` |

Rules:

- Exchanges: `NSE`, `BSE`, `NFO`, `BFO`, `MCX` and the pseudo-exchange `INDEX` (indices only; derivatives are
  never on `INDEX`).
- Parsing is case-insensitive; the canonical output is upper case. Index names keep their spaces.
- Strikes print without trailing zeros (`25000`, `1402.5`).
- The symbol part of a derivative is the underlying as the broker names it (`NIFTY`, `BANKNIFTY`, `RELIANCE`).
- The database identity is the natural key `(exchange, symbol, type, expiry, strike, option_type)`; the `id`
  (UUID v7) is stable across daily syncs.

Code: `money.hejje.instruments.HejjeSymbol` (`parse`, `format`), tests in `HejjeSymbolTest`.

`GET /api/v1/instruments/resolve?symbol=` also accepts `EXCHANGE:TRADINGSYMBOL` with a broker trading symbol
(for example `NFO:NIFTY26SEPFUT`) as a convenience for humans; canonical symbols are preferred everywhere else.

## Instrument master sync

`InstrumentSyncJob` pulls `BrokerAdapter.getInstruments()` daily at 08:00 IST on trading days
(`hejje.instruments.sync-cron`), on demand via `POST /api/v1/instruments/sync` (admin), and at startup when
`hejje.instruments.sync-on-startup=true` (dev default). It upserts `instrument` and `broker_instrument_mapping`,
marks instruments that vanished from the master as `active=false` (never deletes) and audits `INSTRUMENTS_SYNCED`.
The fake broker serves `server/src/main/resources/broker/fake/kite-instruments-fixture.csv` (Kite CSV format).
