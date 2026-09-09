package money.hejje.strategy;

/** Lifecycle status of one strategy version (PRD section 25). Transitions are enforced by the lifecycle. */
public enum VersionStatus { DRAFT, BACKTESTED, VALIDATED, PAPER, LIVE, PAUSED, RETIRED }
