/**
 * Bots (plan M7.3, docs/bots.md): programs that read market state and send decisions, never orders. A bot trades through
 * a backing strategy and its deployments; entries become signals of the deployment (sized by Hejje, through risk, the
 * kill switch and the approval policy), exits and stop moves act on the deployment's position. Decision points go out on
 * {@code /ws/bot} at every closed bar of the bot's timeframe; in SIM the replay waits for the answer (lockstep).
 */
@org.springframework.modulith.ApplicationModule
package money.hejje.bots;
