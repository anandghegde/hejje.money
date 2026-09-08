package money.hejje.broker;

/** Lifecycle state of the broker session owned by the daemon (PRD section 40). */
public enum BrokerSessionState { CONNECTED, EXPIRED, DISCONNECTED, ERROR }
