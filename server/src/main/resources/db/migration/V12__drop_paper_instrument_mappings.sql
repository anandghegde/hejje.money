-- Instrument masters are stored under the real broker's code (zerodha/fake); rows synced under the paper wrapper's
-- code before that fix are unreachable and are removed. The next sync repopulates the mappings.
DELETE FROM broker_instrument_mapping WHERE broker = 'paper';
