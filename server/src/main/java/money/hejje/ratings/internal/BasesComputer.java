package money.hejje.ratings.internal;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.List;
import money.hejje.ratings.Base;
import money.hejje.ratings.BasesComputeResult;
import money.hejje.ratings.RatingsProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Runs the {@link BaseWalker} per instrument over a range and stores what it produces. {@code base_progress} remembers
 * the last processed session per instrument: a run always continues right after it (no session is skipped or processed
 * twice, so re-running a range is a no-op) and the range's {@code from} only matters for an instrument never processed.
 */
@Component
public class BasesComputer {

    private static final Logger log = LoggerFactory.getLogger(BasesComputer.class);
    /** Calendar days of history loaded before the range: the longest cup with its handle, peak check and prior uptrend. */
    private static final int WARMUP_DAYS = 760;

    private final RatingsProperties props;
    private final UniverseSeries universe;
    private final BaseStore store;
    private final TransactionTemplate tx;

    BasesComputer(RatingsProperties props, UniverseSeries universe, BaseStore store, TransactionTemplate tx) {
        this.props = props;
        this.universe = universe;
        this.store = store;
        this.tx = tx;
    }

    public synchronized BasesComputeResult compute(LocalDate from, LocalDate to) {
        String version = props.engineVersion();
        BaseWalker walker = new BaseWalker(props.bases(), version);
        List<RatingsEngine.Member> members = universe.load(from.minusDays(WARMUP_DAYS), to);
        int[] counts = new int[2];
        for (RatingsEngine.Member m : members) {
            tx.executeWithoutResult(status -> {
                DailySeries s = m.series();
                LocalDate start = store.progress(m.id(), version).map(d -> d.plusDays(1)).orElse(from);
                int first = s.indexAtOrBefore(start.minusDays(1)) + 1;
                int last = s.indexAtOrBefore(to);
                if (first > last) {
                    return;
                }
                walker.walk(m, first, last, store.ofInstrument(m.id(), version), new BaseWalker.Sink() {
                    @Override
                    public void detected(Base base) {
                        store.insert(base);
                        counts[0]++;
                    }

                    @Override
                    public void status(Base base) {
                        store.appendStatus(base);
                        counts[1]++;
                    }
                });
                store.progress(m.id(), version, s.date(last));
            });
        }
        log.info("Bases v{} {}..{}: {} instruments, {} detected, {} transitions", version, from, to, members.size(), counts[0], counts[1]);
        return new BasesComputeResult(from, to, version, members.size(), counts[0], counts[1], hash(store.asOf(to, version)));
    }

    static String hash(List<Base> bases) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (Base b : bases) {
                digest.update(String.join("|", b.id().toString(), b.symbol(), b.type().name(), b.startDate().toString(), b.detectedDate().toString(),
                        String.valueOf(b.depthPct()), b.baseLow().toPlainString(), b.pivot().toPlainString(), b.buyHigh().toPlainString(),
                        b.stop().toPlainString(), b.goal().toPlainString(), b.status().name(), b.statusDate().toString(), String.valueOf(b.triggerDate()),
                        String.valueOf(b.entry()), String.valueOf(b.volumeConfirmed()), String.valueOf(b.exit()), String.valueOf(b.outcomePct()),
                        String.valueOf(b.outcomeR()), "\n").getBytes(StandardCharsets.UTF_8));
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
