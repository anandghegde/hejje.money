package money.hejje.orders;

/** Lifecycle of an order intent before it becomes an order. */
public enum IntentStatus { CREATED, VALIDATING, RISK_REJECTED, READY, SUBMITTED, FAILED }
