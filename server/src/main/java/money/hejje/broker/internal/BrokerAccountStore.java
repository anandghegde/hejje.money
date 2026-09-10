package money.hejje.broker.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import money.hejje.broker.BrokerAccount;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class BrokerAccountStore {

    private final JdbcClient jdbc;

    BrokerAccountStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<BrokerAccount> list() {
        return jdbc.sql("SELECT * FROM broker_account ORDER BY created_at, id").query(BrokerAccountStore::map).list();
    }

    public Optional<BrokerAccount> find(UUID id) {
        return jdbc.sql("SELECT * FROM broker_account WHERE id = :id").param("id", id).query(BrokerAccountStore::map).optional();
    }

    public Optional<BrokerAccount> find(String broker, String accountId) {
        return jdbc.sql("SELECT * FROM broker_account WHERE broker = :broker AND account_id = :account").param("broker", broker)
                .param("account", accountId).query(BrokerAccountStore::map).optional();
    }

    public Optional<BrokerAccount> active() {
        return jdbc.sql("SELECT * FROM broker_account WHERE active").query(BrokerAccountStore::map).optional();
    }

    public void insert(UUID id, String broker, String accountId, Instant at) {
        jdbc.sql("""
                INSERT INTO broker_account (id, broker, account_id, active, created_at, updated_at) VALUES (:id, :broker, :account, FALSE, :at, :at)
                ON CONFLICT (broker, account_id) DO NOTHING
                """).param("id", id).param("broker", broker).param("account", accountId).param("at", ts(at)).update();
    }

    public void deactivateAll(Instant at) {
        jdbc.sql("UPDATE broker_account SET active = FALSE, updated_at = :at WHERE active").param("at", ts(at)).update();
    }

    public void activate(UUID id, String by, Instant at) {
        jdbc.sql("UPDATE broker_account SET active = TRUE, activated_at = :at, activated_by = :by, updated_at = :at WHERE id = :id")
                .param("at", ts(at)).param("by", by).param("id", id).update();
    }

    private static BrokerAccount map(ResultSet rs, int i) throws SQLException {
        OffsetDateTime activated = rs.getObject("activated_at", OffsetDateTime.class);
        return new BrokerAccount(rs.getObject("id", UUID.class), rs.getString("broker"), rs.getString("account_id"), rs.getString("label"),
                rs.getBoolean("active"), rs.getObject("created_at", OffsetDateTime.class).toInstant(), rs.getObject("updated_at", OffsetDateTime.class).toInstant(),
                activated == null ? null : activated.toInstant(), rs.getString("activated_by"));
    }

    private static OffsetDateTime ts(Instant i) {
        return i.atOffset(ZoneOffset.UTC);
    }
}
