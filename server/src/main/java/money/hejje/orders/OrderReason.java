package money.hejje.orders;

/** Why an order intent was created (PRD section 30). */
public enum OrderReason { MANUAL, STRATEGY_SIGNAL, STRATEGY_EXIT, STRATEGY_STOP, POSITION_CLOSE, KILL_SWITCH, AGENT_PROPOSAL, WEBHOOK, BASKET_ROLLBACK }
