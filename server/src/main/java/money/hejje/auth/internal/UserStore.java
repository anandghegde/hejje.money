package money.hejje.auth.internal;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class UserStore {

    public record User(UUID id, String username, String passwordHash, Instant createdAt) {}

    private final JdbcClient jdbc;

    UserStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public long count() {
        return jdbc.sql("SELECT count(*) FROM app_user").query(Long.class).single();
    }

    public Optional<User> findByUsername(String username) {
        return jdbc.sql("SELECT id, username, password_hash, created_at FROM app_user WHERE username = :u")
                .param("u", username)
                .query((rs, i) -> new User(rs.getObject("id", UUID.class), rs.getString("username"),
                        rs.getString("password_hash"), rs.getObject("created_at", OffsetDateTime.class).toInstant()))
                .optional();
    }

    public Optional<User> findById(UUID id) {
        return jdbc.sql("SELECT id, username, password_hash, created_at FROM app_user WHERE id = :id")
                .param("id", id)
                .query((rs, i) -> new User(rs.getObject("id", UUID.class), rs.getString("username"),
                        rs.getString("password_hash"), rs.getObject("created_at", OffsetDateTime.class).toInstant()))
                .optional();
    }

    public void insert(User user) {
        jdbc.sql("INSERT INTO app_user (id, username, password_hash, created_at) VALUES (:id, :u, :h, :c)")
                .param("id", user.id()).param("u", user.username()).param("h", user.passwordHash())
                .param("c", user.createdAt().atOffset(ZoneOffset.UTC))
                .update();
    }
}
