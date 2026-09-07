package money.hejje.system.internal;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Cheap liveness probe of the database connection pool. */
@Component
public class DatabaseCheck {

    private final JdbcClient jdbc;

    DatabaseCheck(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public boolean isHealthy() {
        try {
            return jdbc.sql("SELECT 1").query(Integer.class).single() == 1;
        } catch (RuntimeException e) {
            return false;
        }
    }
}
