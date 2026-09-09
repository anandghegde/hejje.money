/**
 * Market regime engine (PRD section 13, plan M3.1): deterministic classification of today and every historical
 * session along the PRD's six dimensions, stored per session and classifier version, with the evidence behind each
 * label. Optional at runtime: when disabled or when inputs are missing every dimension is {@code UNKNOWN}, never an
 * error. Rules and thresholds: docs/regime.md, config/regime.yaml.
 */
@org.springframework.modulith.ApplicationModule
package money.hejje.regime;
