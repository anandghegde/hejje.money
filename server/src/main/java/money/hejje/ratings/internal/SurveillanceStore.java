package money.hejje.ratings.internal;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

/** {@code surveillance_snapshot} and {@code surveillance_flag}: one snapshot per session, replaced as a whole on a re-fetch. */
@Repository
class SurveillanceStore {

    private final JdbcClient jdbc;
    private final NamedParameterJdbcTemplate named;
    private final TransactionTemplate tx;

    SurveillanceStore(JdbcClient jdbc, NamedParameterJdbcTemplate named, TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.named = named;
        this.tx = tx;
    }

    void replace(LocalDate session, LocalDate asmDate, LocalDate gsmDate, Instant fetchedAt, Collection<SurveillanceParser.Flag> flags) {
        tx.executeWithoutResult(status -> {
            jdbc.sql("DELETE FROM surveillance_snapshot WHERE session_date = :d").param("d", session).update();
            jdbc.sql("INSERT INTO surveillance_snapshot (session_date, asm_date, gsm_date, fetched_at) VALUES (:d, :asm, :gsm, :at)")
                    .param("d", session).param("asm", asmDate).param("gsm", gsmDate).param("at", fetchedAt.atOffset(ZoneOffset.UTC)).update();
            named.batchUpdate("INSERT INTO surveillance_flag (session_date, symbol, flag, code) VALUES (:d, :symbol, :flag, :code)",
                    flags.stream().map(f -> new MapSqlParameterSource().addValue("d", session).addValue("symbol", f.symbol())
                            .addValue("flag", f.flag()).addValue("code", f.code())).toArray(MapSqlParameterSource[]::new));
        });
    }

    /** The newest snapshot on or before {@code date}. */
    Optional<LocalDate> latest(LocalDate date) {
        return jdbc.sql("SELECT max(session_date) FROM surveillance_snapshot WHERE session_date <= :d").param("d", date).query(LocalDate.class).optional();
    }

    Map<String, SurveillanceParser.Flag> flags(LocalDate session) {
        Map<String, SurveillanceParser.Flag> out = new LinkedHashMap<>();
        jdbc.sql("SELECT symbol, flag, code FROM surveillance_flag WHERE session_date = :d").param("d", session)
                .query((rs, i) -> new SurveillanceParser.Flag(rs.getString("symbol"), rs.getString("flag"), rs.getString("code")))
                .list().forEach(f -> out.put(f.symbol(), f));
        return out;
    }
}
