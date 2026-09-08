package money.hejje.orders;

/** What produced an order event. */
public enum OrderEventSource { BROKER_WS, BROKER_POSTBACK, BROKER_POLL, USER, SYSTEM, RECONCILIATION, EXTERNAL }
