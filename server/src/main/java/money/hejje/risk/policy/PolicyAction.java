package money.hejje.risk.policy;

/** What is being asked for: new exposure, or acting on an existing order or position. */
public enum PolicyAction { ORDER_NEW, ORDER_MODIFY, ORDER_CANCEL, POSITION_CLOSE }
