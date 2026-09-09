package money.hejje.news.internal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import money.hejje.common.Ids;
import money.hejje.news.NewsAssessment;
import money.hejje.news.NewsBias;
import money.hejje.news.NewsBiasLabel;
import money.hejje.news.NewsItem;
import money.hejje.news.NewsSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class NewsStore {

    private static final TypeReference<List<String>> STRINGS = new TypeReference<>() {};
    private static final TypeReference<List<NewsBias.Contribution>> CONTRIBUTIONS = new TypeReference<>() {};

    private final JdbcClient jdbc;
    private final ObjectMapper json;

    NewsStore(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    // --- sources ---

    public void upsertSource(String name, String url, NewsSource.Kind kind, double reliability, boolean enabled) {
        jdbc.sql("""
                INSERT INTO news_source (id, name, url, kind, reliability, enabled) VALUES (:id, :name, :url, :kind, :reliability, :enabled)
                ON CONFLICT (url) DO UPDATE SET name = EXCLUDED.name, kind = EXCLUDED.kind
                """).param("id", Ids.newId()).param("name", name).param("url", url).param("kind", kind.name()).param("reliability", reliability)
                .param("enabled", enabled).update();
    }

    public List<NewsSource> sources() {
        return jdbc.sql("SELECT * FROM news_source ORDER BY name").query(this::mapSource).list();
    }

    public Optional<NewsSource> source(UUID id) {
        return jdbc.sql("SELECT * FROM news_source WHERE id = :id").param("id", id).query(this::mapSource).optional();
    }

    public void updateSource(UUID id, boolean enabled, double reliability) {
        jdbc.sql("UPDATE news_source SET enabled = :e, reliability = :r WHERE id = :id").param("e", enabled).param("r", reliability).param("id", id).update();
    }

    public void polled(UUID id, Instant at, String error) {
        jdbc.sql("UPDATE news_source SET last_polled_at = :at, last_error = :err WHERE id = :id").param("at", at.atOffset(ZoneOffset.UTC)).param("err", error)
                .param("id", id).update();
    }

    public Optional<Instant> lastSuccessfulPoll() {
        return jdbc.sql("SELECT max(last_polled_at) FROM news_source WHERE enabled AND last_error IS NULL").query(OffsetDateTime.class).optional()
                .map(OffsetDateTime::toInstant);
    }

    // --- items ---

    /** Inserts unless the URL or hash exists; returns the stored item, empty when it was a duplicate. */
    public Optional<NewsItem> insertItem(NewsItem item) {
        int n = jdbc.sql("""
                INSERT INTO news_item (id, source_id, url, title, summary, body, published_at, fetched_at, hash, norm_title)
                VALUES (:id, :sourceId, :url, :title, :summary, :body, :publishedAt, :fetchedAt, :hash, :normTitle)
                ON CONFLICT DO NOTHING
                """).param("id", item.id()).param("sourceId", item.sourceId()).param("url", item.url()).param("title", item.title()).param("summary", item.summary())
                .param("body", item.body()).param("publishedAt", item.publishedAt().atOffset(ZoneOffset.UTC)).param("fetchedAt", item.fetchedAt().atOffset(ZoneOffset.UTC))
                .param("hash", item.hash()).param("normTitle", item.normTitle()).update();
        return n == 1 ? Optional.of(item) : Optional.empty();
    }

    public List<String> recentNormTitles(Instant since) {
        return jdbc.sql("SELECT norm_title FROM news_item WHERE published_at >= :since").param("since", since.atOffset(ZoneOffset.UTC)).query(String.class).list();
    }

    public List<NewsItem> items(Instant since, UUID instrumentId, int limit) {
        if (instrumentId == null) {
            return jdbc.sql("SELECT * FROM news_item WHERE published_at >= :since ORDER BY published_at DESC LIMIT :n").param("since", since.atOffset(ZoneOffset.UTC))
                    .param("n", limit).query(this::mapItem).list();
        }
        return jdbc.sql("""
                SELECT DISTINCT i.* FROM news_item i JOIN news_assessment a ON a.item_id = i.id
                WHERE i.published_at >= :since AND a.instrument_id = :instrument ORDER BY i.published_at DESC LIMIT :n
                """).param("since", since.atOffset(ZoneOffset.UTC)).param("instrument", instrumentId).param("n", limit).query(this::mapItem).list();
    }

    public Optional<NewsItem> item(UUID id) {
        return jdbc.sql("SELECT * FROM news_item WHERE id = :id").param("id", id).query(this::mapItem).optional();
    }

    // --- assessments ---

    public void insertAssessment(NewsAssessment a) {
        jdbc.sql("""
                INSERT INTO news_assessment (id, item_id, instrument_id, sector, relevance, direction, materiality, novelty, confidence, event_type, summary, model,
                                             prompt_version, created_at)
                VALUES (:id, :itemId, :instrumentId, :sector, :relevance, :direction, :materiality, :novelty, :confidence, :eventType, :summary, :model, :promptVersion,
                        :createdAt)
                """).param("id", a.id()).param("itemId", a.itemId()).param("instrumentId", a.instrumentId(), java.sql.Types.OTHER).param("sector", a.sector())
                .param("relevance", a.relevance()).param("direction", a.direction()).param("materiality", a.materiality()).param("novelty", a.novelty())
                .param("confidence", a.confidence()).param("eventType", a.eventType()).param("summary", a.summary()).param("model", a.model())
                .param("promptVersion", a.promptVersion()).param("createdAt", a.createdAt().atOffset(ZoneOffset.UTC)).update();
    }

    /** Assessments of one instrument whose item was published since {@code since}, newest first. */
    public List<NewsAssessment> assessments(UUID instrumentId, Instant since) {
        return jdbc.sql("""
                SELECT a.* FROM news_assessment a JOIN news_item i ON i.id = a.item_id
                WHERE a.instrument_id = :instrument AND i.published_at >= :since ORDER BY i.published_at DESC
                """).param("instrument", instrumentId).param("since", since.atOffset(ZoneOffset.UTC)).query(this::mapAssessment).list();
    }

    public List<NewsAssessment> assessmentsOfItem(UUID itemId) {
        return jdbc.sql("SELECT * FROM news_assessment WHERE item_id = :id").param("id", itemId).query(this::mapAssessment).list();
    }

    // --- bias ---

    public void insertBias(NewsBias b) {
        try {
            jdbc.sql("""
                    INSERT INTO news_bias (id, instrument_id, computed_at, score, label, items, evidence)
                    VALUES (:id, :instrumentId, :computedAt, :score, :label, :items, CAST(:evidence AS jsonb))
                    """).param("id", Ids.newId()).param("instrumentId", b.instrumentId()).param("computedAt", b.computedAt().atOffset(ZoneOffset.UTC))
                    .param("score", b.score()).param("label", b.label().name()).param("items", b.items()).param("evidence", json.writeValueAsString(b.evidence())).update();
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(e);
        }
    }

    private NewsSource mapSource(ResultSet rs, int i) throws SQLException {
        OffsetDateTime polled = rs.getObject("last_polled_at", OffsetDateTime.class);
        return new NewsSource(rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("url"), NewsSource.Kind.valueOf(rs.getString("kind")),
                rs.getDouble("reliability"), rs.getBoolean("enabled"), polled == null ? null : polled.toInstant(), rs.getString("last_error"));
    }

    private NewsItem mapItem(ResultSet rs, int i) throws SQLException {
        return new NewsItem(rs.getObject("id", UUID.class), rs.getObject("source_id", UUID.class), rs.getString("url"), rs.getString("title"), rs.getString("summary"),
                rs.getString("body"), rs.getObject("published_at", OffsetDateTime.class).toInstant(), rs.getObject("fetched_at", OffsetDateTime.class).toInstant(),
                rs.getString("hash"), rs.getString("norm_title"));
    }

    private NewsAssessment mapAssessment(ResultSet rs, int i) throws SQLException {
        Object instrument = rs.getObject("instrument_id");
        return new NewsAssessment(rs.getObject("id", UUID.class), rs.getObject("item_id", UUID.class), instrument == null ? null : (UUID) instrument,
                rs.getString("sector"), rs.getDouble("relevance"), rs.getDouble("direction"), rs.getDouble("materiality"), rs.getDouble("novelty"),
                rs.getDouble("confidence"), rs.getString("event_type"), rs.getString("summary"), rs.getString("model"), rs.getString("prompt_version"),
                rs.getObject("created_at", OffsetDateTime.class).toInstant());
    }

    @SuppressWarnings("unused")
    private List<String> strings(String s) throws JsonProcessingException {
        return json.readValue(s, STRINGS);
    }

    @SuppressWarnings("unused")
    private List<NewsBias.Contribution> contributions(String s) throws JsonProcessingException {
        return json.readValue(s, CONTRIBUTIONS);
    }
}
