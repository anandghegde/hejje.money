package money.hejje.orders;

/** Lifecycle of an order intent before it becomes an order. */
/** PROPOSED: an agent proposal awaiting human approval (M4.4); DECLINED: that proposal was rejected, expired or failed. */
public enum IntentStatus { CREATED, VALIDATING, RISK_REJECTED, READY, SUBMITTED, FAILED, PROPOSED, DECLINED }
