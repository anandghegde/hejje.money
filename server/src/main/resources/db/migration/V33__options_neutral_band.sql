-- M6.4 neutral options strategies: direction 'NEUTRAL' has no side on the underlying; its stop is a band whose lower edge is
-- underlying_stop and upper edge underlying_stop_high.
ALTER TABLE options_position ADD COLUMN underlying_stop_high NUMERIC(18, 2);
