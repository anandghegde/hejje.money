package money.hejje.sim;

import java.util.UUID;

/** A SIM session ended (plan M7.5): DONE, CANCELLED or FAILED. Published synchronously before the ledger can change. */
public record SimSessionFinished(UUID sessionId, SimSession.State state) {}
