-- Plan M9.6: why each closed trade ended as it did (deterministic rules on M1 candles) and how its entry was timed, with
-- Jev's reading beside them when Jev is on. cause_complete turns true once the post-exit window has been seen.
ALTER TABLE trade_review ADD COLUMN cause           text;
ALTER TABLE trade_review ADD COLUMN entry_timing    text;
ALTER TABLE trade_review ADD COLUMN mfe_r           double precision;
ALTER TABLE trade_review ADD COLUMN mae_r           double precision;
ALTER TABLE trade_review ADD COLUMN cause_evidence  jsonb;
ALTER TABLE trade_review ADD COLUMN jev_cause       text;
ALTER TABLE trade_review ADD COLUMN jev_timing      text;
ALTER TABLE trade_review ADD COLUMN cause_complete  boolean NOT NULL DEFAULT false;
CREATE INDEX trade_review_cause_pending ON trade_review (closed_at) WHERE NOT cause_complete;
