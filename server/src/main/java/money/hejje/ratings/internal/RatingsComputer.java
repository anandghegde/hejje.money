package money.hejje.ratings.internal;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import money.hejje.ratings.DailyRating;
import money.hejje.ratings.GroupRank;
import money.hejje.ratings.RatingsComputeResult;
import money.hejje.ratings.RatingsProperties;
import money.hejje.ratings.RatingsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Walks the sessions of a range forward, computing and storing the ones that have no rows under the engine version. */
@Component
public class RatingsComputer {

    private static final Logger log = LoggerFactory.getLogger(RatingsComputer.class);
    /** Calendar days of history loaded before the first session: four quarters of sessions with room for holidays. */
    private static final int WARMUP_DAYS = 420;

    private final RatingsProperties props;
    private final UniverseSeries universe;
    private final RatingsStore store;

    RatingsComputer(RatingsProperties props, UniverseSeries universe, RatingsStore store) {
        this.props = props;
        this.universe = universe;
        this.store = store;
    }

    public synchronized RatingsComputeResult compute(LocalDate from, LocalDate to) {
        String version = props.engineVersion();
        List<RatingsEngine.Member> members = universe.load(from.minusDays(WARMUP_DAYS), to);
        List<LocalDate> sessions = universe.sessions(members, from, to);
        RatingsEngine engine = new RatingsEngine(props.formula(), version);
        List<DailyRating> all = new ArrayList<>();
        int computed = 0;
        for (LocalDate date : sessions) {
            if (store.exists(date, version)) {
                all.addAll(store.forDate(date, version));
                continue;
            }
            RatingsEngine.Result result = engine.compute(date, members);
            store.insert(result.ratings(), result.groups());
            all.addAll(result.ratings());
            computed++;
        }
        log.info("Ratings v{} {}..{}: {} sessions, {} computed, {} rows", version, from, to, sessions.size(), computed, all.size());
        return new RatingsComputeResult(from, to, version, sessions.size(), computed, all.size(), RatingsService.hash(all));
    }

    public Optional<LocalDate> latest(LocalDate cap) {
        return store.latest(cap, props.engineVersion());
    }

    public Optional<DailyRating> find(String symbol, LocalDate date) {
        return store.find(symbol, date, props.engineVersion());
    }

    public List<DailyRating> forDate(LocalDate date) {
        return store.forDate(date, props.engineVersion());
    }

    public List<GroupRank> groups(LocalDate date) {
        return store.groups(date, props.engineVersion());
    }

    public List<DailyRating> history(String symbol, LocalDate from, LocalDate to) {
        return store.history(symbol, from, to, props.engineVersion());
    }
}
