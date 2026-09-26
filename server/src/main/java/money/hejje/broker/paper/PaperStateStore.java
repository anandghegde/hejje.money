package money.hejje.broker.paper;

import java.util.Optional;

/**
 * Where a simulated broker keeps the state that must outlive a restart (plan M11.1/M11.2: delivery holdings and GTTs
 * of the PAPER swing book). {@link #IN_MEMORY} keeps nothing (the fake broker, SIM sessions, unit tests).
 */
public interface PaperStateStore {

    Optional<String> load(String key);

    void save(String key, String json);

    PaperStateStore IN_MEMORY = new PaperStateStore() {
        @Override
        public Optional<String> load(String key) {
            return Optional.empty();
        }

        @Override
        public void save(String key, String json) {
        }
    };
}
