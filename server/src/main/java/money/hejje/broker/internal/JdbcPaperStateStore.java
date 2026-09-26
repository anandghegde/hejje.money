package money.hejje.broker.internal;

import java.util.Optional;
import money.hejje.broker.paper.PaperStateStore;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@code paper_state}: the PAPER adapter's simulated delivery book, one JSON document per key (plan M11.1). */
@Repository
class JdbcPaperStateStore implements PaperStateStore {

    private final JdbcClient jdbc;

    JdbcPaperStateStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<String> load(String key) {
        return jdbc.sql("SELECT state::text FROM paper_state WHERE key = :key").param("key", key).query(String.class).optional();
    }

    @Override
    public void save(String key, String json) {
        jdbc.sql("""
                INSERT INTO paper_state (key, state, updated_at) VALUES (:key, CAST(:state AS jsonb), now())
                ON CONFLICT (key) DO UPDATE SET state = EXCLUDED.state, updated_at = EXCLUDED.updated_at
                """).param("key", key).param("state", json).update();
    }
}
