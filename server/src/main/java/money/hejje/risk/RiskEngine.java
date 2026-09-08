package money.hejje.risk;

import money.hejje.orders.OrderIntent;

/**
 * Authoritative, deterministic pre-trade control. Every order intent passes through here before it reaches the broker;
 * no client can bypass it. M1.4 ships an approve-all stub so the call site and persistence exist; M1.5 replaces the
 * implementation with the real controls and kill switch.
 */
public interface RiskEngine {

    RiskDecision evaluate(OrderIntent intent);
}
