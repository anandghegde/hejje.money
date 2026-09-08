/**
 * Execution module: the only path to the broker. The pipeline validates an intent, runs risk, creates and submits the
 * order, applies broker updates, and reconciles unknown outcomes. Rate limiting, full reconciliation, bootstrap and the
 * live-trading gate expand in M1.6.
 */
@org.springframework.modulith.ApplicationModule
package money.hejje.execution;
