package money.hejje.ratings;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import money.hejje.common.time.HejjeClock;
import money.hejje.ratings.internal.BasesComputer;
import money.hejje.ratings.internal.RatingsComputer;
import money.hejje.ratings.internal.RatingsLists;
import org.springframework.stereotype.Service;

/**
 * Public API of the ratings module: stored ratings and group ranks per session, and the job that computes them. Reads
 * never see a session that has not closed on the Hejje clock, so a SIM replay cannot read its own future.
 */
@Service
public class RatingsService {

    private final RatingsProperties props;
    private final RatingsComputer computer;
    private final HejjeClock clock;
    private final BasesComputer basesComputer;
    private final RatingsLists lists;

    RatingsService(RatingsProperties props, RatingsComputer computer, HejjeClock clock, BasesComputer basesComputer, RatingsLists lists) {
        this.basesComputer = basesComputer;
        this.lists = lists;
        this.props = props;
        this.computer = computer;
        this.clock = clock;
    }

    public boolean enabled() {
        return props.enabled();
    }

    /** Computes every session of {@code [from, to]} that has no rows under the engine version; re-running is a no-op. */
    public RatingsComputeResult compute(LocalDate from, LocalDate to) {
        return computer.compute(from, to);
    }

    /** The newest closed session: today after the close, else the previous calendar day. */
    public LocalDate visibleCap() {
        LocalDate today = clock.today();
        boolean closed = !clock.isTradingDay(today) || !clock.now().isBefore(clock.sessionWindow(today).close().toInstant());
        return closed ? today : today.minusDays(1);
    }

    /** {@code requested} (or the latest computed session when null), never later than {@link #visibleCap()}. */
    public Optional<LocalDate> sessionFor(LocalDate requested) {
        LocalDate cap = visibleCap();
        return computer.latest(requested == null || requested.isAfter(cap) ? cap : requested);
    }

    public Optional<DailyRating> rating(String symbol, LocalDate date) {
        return sessionFor(date).flatMap(d -> computer.find(symbol.trim().toUpperCase(), d));
    }

    public List<DailyRating> ratings(LocalDate date) {
        return sessionFor(date).map(computer::forDate).orElse(List.of());
    }

    public List<GroupRank> groups(LocalDate date) {
        return sessionFor(date).map(computer::groups).orElse(List.of());
    }

    public List<DailyRating> history(String symbol, LocalDate from, LocalDate to) {
        LocalDate cap = visibleCap();
        return computer.history(symbol.trim().toUpperCase(), from, to.isAfter(cap) ? cap : to);
    }

    // --- bases and lists (plan M8.4) ---

    /** Advances and detects bases for every session of the range not processed yet; re-running is a no-op. */
    public BasesComputeResult computeBases(LocalDate from, LocalDate to) {
        return basesComputer.compute(from, to);
    }

    /** Bases with their status as of {@code date} (capped like every read), optionally of one status and type. */
    public List<Base> bases(BaseStatus status, BaseType type, LocalDate date) {
        return lists.bases(asOf(date)).stream().filter(b -> status == null || b.status() == status).filter(b -> type == null || b.type() == type)
                .toList();
    }

    public List<Base> basesOf(String symbol, LocalDate date) {
        return lists.basesOf(symbol.trim().toUpperCase(), asOf(date));
    }

    /** The past-setups ledger: bases that closed in {@code [from, to]}, newest first. */
    public List<Base> pastSetups(LocalDate from, LocalDate to) {
        return lists.bases(asOf(to)).stream().filter(b -> b.status().closed() && !b.statusDate().isBefore(from))
                .sorted(Comparator.comparing(Base::statusDate).reversed().thenComparing(Base::symbol)).toList();
    }

    /** One of {@link RatingsLists#NAMES} for the session of {@code date}. */
    public List<SetupRow> list(String name, LocalDate date) {
        return sessionFor(date).map(d -> lists.list(name, d)).orElse(List.of());
    }

    /** Status rows written for the session (the nightly alerts). */
    public List<Base> transitions(LocalDate date) {
        return lists.transitions(date);
    }

    public boolean leader(DailyRating rating) {
        return RatingsLists.leader(rating, props.lists());
    }

    private LocalDate asOf(LocalDate requested) {
        LocalDate cap = visibleCap();
        return requested == null || requested.isAfter(cap) ? cap : requested;
    }

    /** Sort keys of the list endpoint; nulls sort last. */
    public static Comparator<DailyRating> order(String sort) {
        return switch (sort == null ? "composite" : sort) {
            case "rs" -> desc(r -> r.rsRating() == null ? null : r.rsRating().doubleValue());
            case "ad" -> desc(DailyRating::adRaw);
            case "volume" -> desc(DailyRating::volVsAvg50Pct);
            case "change" -> desc(DailyRating::changePct);
            case "offHigh" -> Comparator.comparing(DailyRating::offHighPct, Comparator.nullsLast(Comparator.naturalOrder()));
            case "composite" -> desc(r -> r.techComposite() == null ? null : r.techComposite().doubleValue());
            default -> throw new IllegalArgumentException("sort must be one of composite, rs, ad, volume, change, offHigh");
        };
    }

    private static Comparator<DailyRating> desc(java.util.function.Function<DailyRating, Double> key) {
        return Comparator.comparing(key, Comparator.nullsLast(Comparator.reverseOrder())).thenComparing(DailyRating::symbol);
    }

    /** SHA-256 over the rows in instrument order (evidence left out: it restates the fields). */
    public static String hash(List<DailyRating> rows) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (DailyRating r : rows) {
                digest.update(String.join("|", r.sessionDate().toString(), r.instrumentId().toString(), r.engineVersion(), String.valueOf(r.rsRaw()),
                        String.valueOf(r.rsRating()), String.valueOf(r.adRaw()), String.valueOf(r.adGrade()), String.valueOf(r.offHighPct()),
                        String.valueOf(r.offLowPct()), String.valueOf(r.volVsAvg50Pct()), String.valueOf(r.upDownVolRatio()),
                        String.valueOf(r.avgTurnoverCr()), r.close().toPlainString(), String.valueOf(r.changePct()), String.valueOf(r.groupId()),
                        String.valueOf(r.groupRank()), String.valueOf(r.techComposite()), "\n").getBytes(StandardCharsets.UTF_8));
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
