-- Plan M9.5: bots of kind JEV (in-process, Jev answers the decision points) name their question sets; every bot gets
-- exit-vote hysteresis (an EXIT / TAKE_PROFIT executes after exit_confirm_votes consecutive votes; 1 = at once).
ALTER TABLE bot ADD COLUMN exit_confirm_votes int NOT NULL DEFAULT 1;
ALTER TABLE bot ADD COLUMN question_set text;
