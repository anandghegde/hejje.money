/**
 * Pulse (PRD section 16, plan M3.2): "what kind of market are we trading today?" as a rule-based composite. The
 * Technical Pulse is a weighted score of index trend, VWAP relationship, breadth, relative volume, VIX, sector
 * strength, futures basis, gap behaviour and momentum; the Market Pulse is the PRD 16.2 label table. Weights and
 * thresholds: config/pulse.yaml; rules: docs/pulse.md. Missing inputs drop out of the weighting instead of failing.
 */
@org.springframework.modulith.ApplicationModule
package money.hejje.pulse;
