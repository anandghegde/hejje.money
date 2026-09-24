package money.hejje.bots.internal;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import money.hejje.bots.Bot;
import money.hejje.bots.BotDecision;
import money.hejje.common.ExecutionMode;
import money.hejje.common.Timeframe;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class BotStore {

    private final JdbcClient jdbc;
    private final ObjectMapper json;

    BotStore(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public void insert(Bot b) {
        jdbc.sql("""
                INSERT INTO bot (id, name, version, kind, knowledge_cutoff, allowed_modes, strategy_id, timeframe, decision_every_minutes, universe, enabled,
                    created_by, created_at, updated_at, exit_confirm_votes, question_set)
                VALUES (:id, :name, :version, :kind, :cutoff, CAST(:modes AS jsonb), :strategy, :tf, :every, CAST(:universe AS jsonb), :enabled, :by, :at, :at,
                    :votes, :qset)
                """).param("votes", b.exitConfirmVotes()).param("qset", b.questionSet()).param("id", b.id()).param("name", b.name()).param("version", b.version()).param("kind", b.kind().name())
                .param("cutoff", b.knowledgeCutoff()).param("modes", write(b.allowedModes().stream().map(Enum::name).sorted().toList()))
                .param("strategy", b.strategyId()).param("tf", b.timeframe().name()).param("every", b.decisionEveryMinutes())
                .param("universe", write(b.universe())).param("enabled", b.enabled()).param("by", b.createdBy()).param("at", ts(b.createdAt())).update();
    }

    public void setEnabled(UUID id, boolean enabled, Instant at) {
        jdbc.sql("UPDATE bot SET enabled = :e, updated_at = :at WHERE id = :id").param("e", enabled).param("at", ts(at)).param("id", id).update();
    }

    public Optional<Bot> find(UUID id) {
        return jdbc.sql("SELECT * FROM bot WHERE id = :id").param("id", id).query(this::bot).optional();
    }

    public Optional<Bot> findByName(String name) {
        return jdbc.sql("SELECT * FROM bot WHERE name = :n").param("n", name).query(this::bot).optional();
    }

    public List<Bot> all() {
        return jdbc.sql("SELECT * FROM bot ORDER BY created_at").query(this::bot).list();
    }

    /** Inserts the decision; returns false when the (bot, point, instrument) row already exists. */
    public boolean insert(BotDecision d) {
        return jdbc.sql("""
                INSERT INTO bot_decision (id, bot_id, point_id, instrument, action, stop, target, confidence, thesis, stage, scores, candidates, latency_ms,
                    outcome, detail, signal_id, order_id, mode, decided_at)
                VALUES (:id, :bot, :point, :instrument, :action, :stop, :target, :confidence, :thesis, :stage, CAST(:scores AS jsonb), CAST(:candidates AS jsonb),
                    :latency, :outcome, :detail, :signal, :order, :mode, :at)
                ON CONFLICT (bot_id, point_id, instrument) DO NOTHING
                """).params(params(d)).update() == 1;
    }

    public void update(BotDecision d) {
        jdbc.sql("UPDATE bot_decision SET outcome = :outcome, detail = :detail, signal_id = :signal, order_id = :order WHERE id = :id").params(params(d)).update();
    }

    public Optional<BotDecision> find(UUID botId, String pointId, String instrument) {
        return jdbc.sql("SELECT * FROM bot_decision WHERE bot_id = :b AND point_id = :p AND instrument = :i").param("b", botId).param("p", pointId)
                .param("i", instrument).query(this::decision).optional();
    }

    public List<BotDecision> decisions(UUID botId, int limit) {
        return jdbc.sql("SELECT * FROM bot_decision WHERE bot_id = :b ORDER BY decided_at DESC, instrument LIMIT :l").param("b", botId).param("l", limit)
                .query(this::decision).list();
    }

    /** The decision whose signal an order executed (trade attribution). */
    public Optional<BotDecision> bySignal(UUID signalId) {
        return jdbc.sql("SELECT * FROM bot_decision WHERE signal_id = :s").param("s", signalId).query(this::decision).optional();
    }

    private Map<String, Object> params(BotDecision d) {
        Map<String, Object> p = new HashMap<>();
        p.put("id", d.id());
        p.put("bot", d.botId());
        p.put("point", d.pointId());
        p.put("instrument", d.instrument());
        p.put("action", d.action().name());
        p.put("stop", d.stop());
        p.put("target", d.target());
        p.put("confidence", d.confidence());
        p.put("thesis", d.thesis());
        p.put("stage", d.stage());
        p.put("scores", d.scores() == null ? null : write(d.scores()));
        p.put("candidates", d.candidates() == null ? null : write(d.candidates()));
        p.put("latency", d.latencyMs());
        p.put("outcome", d.outcome().name());
        p.put("detail", d.detail());
        p.put("signal", d.signalId());
        p.put("order", d.orderId());
        p.put("mode", d.mode().name());
        p.put("at", ts(d.decidedAt()));
        return p;
    }

    private Bot bot(ResultSet rs, int i) throws SQLException {
        List<String> modes = read(rs.getString("allowed_modes"), new TypeReference<List<String>>() {});
        List<String> universe = read(rs.getString("universe"), new TypeReference<List<String>>() {});
        Object every = rs.getObject("decision_every_minutes");
        return new Bot(rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("version"), Bot.Kind.valueOf(rs.getString("kind")),
                rs.getObject("knowledge_cutoff", LocalDate.class), Set.copyOf(modes.stream().map(ExecutionMode::valueOf).toList()),
                rs.getObject("strategy_id", UUID.class), Timeframe.valueOf(rs.getString("timeframe")), every == null ? null : ((Number) every).intValue(),
                universe, rs.getBoolean("enabled"), rs.getString("created_by"), instant(rs, "created_at"), instant(rs, "updated_at"),
                rs.getInt("exit_confirm_votes"), rs.getString("question_set"));
    }

    private BotDecision decision(ResultSet rs, int i) throws SQLException {
        Object latency = rs.getObject("latency_ms");
        Object confidence = rs.getObject("confidence");
        String scores = rs.getString("scores");
        String candidates = rs.getString("candidates");
        return new BotDecision(rs.getObject("id", UUID.class), rs.getObject("bot_id", UUID.class), rs.getString("point_id"), rs.getString("instrument"),
                BotDecision.Action.valueOf(rs.getString("action")), rs.getBigDecimal("stop"), rs.getBigDecimal("target"),
                confidence == null ? null : ((Number) confidence).doubleValue(), rs.getString("thesis"), rs.getString("stage"),
                scores == null ? null : read(scores, new TypeReference<Map<String, Object>>() {}),
                candidates == null ? null : read(candidates, new TypeReference<List<Object>>() {}), latency == null ? null : ((Number) latency).longValue(),
                BotDecision.Outcome.valueOf(rs.getString("outcome")), rs.getString("detail"), rs.getObject("signal_id", UUID.class),
                rs.getObject("order_id", UUID.class), ExecutionMode.valueOf(rs.getString("mode")), instant(rs, "decided_at"));
    }

    private String write(Object v) {
        try {
            return json.writeValueAsString(v);
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }

    private <T> T read(String text, TypeReference<T> type) {
        try {
            return json.readValue(text, type);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static Instant instant(ResultSet rs, String c) throws SQLException {
        OffsetDateTime v = rs.getObject(c, OffsetDateTime.class);
        return v == null ? null : v.toInstant();
    }

    private static OffsetDateTime ts(Instant i) {
        return i.atOffset(ZoneOffset.UTC);
    }
}
