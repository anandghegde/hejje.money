package money.hejje.ratings.internal;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import money.hejje.ratings.Base;
import money.hejje.ratings.BaseStatus;
import money.hejje.ratings.DailyRating;
import money.hejje.ratings.RatingsProperties;
import money.hejje.ratings.SetupRow;
import org.springframework.stereotype.Component;

/** The lists (docs/ratings.md, "Lists"): queries over a session's ratings and the bases as of that session. */
@Component
public class RatingsLists {

    public static final List<String> NAMES = List.of("setups", "buyzone", "nearpivot", "leaders", "movers");

    private static final Comparator<SetupRow> BY_COMPOSITE = Comparator
            .comparing((SetupRow r) -> r.rating() == null || r.rating().techComposite() == null ? -1 : r.rating().techComposite()).reversed()
            .thenComparing(r -> r.base() != null ? r.base().symbol() : r.rating().symbol());

    private final RatingsProperties props;
    private final RatingsStore ratings;
    private final BaseStore bases;

    RatingsLists(RatingsProperties props, RatingsStore ratings, BaseStore bases) {
        this.props = props;
        this.ratings = ratings;
        this.bases = bases;
    }

    public List<Base> bases(LocalDate asOf) {
        return bases.asOf(asOf, props.engineVersion());
    }

    public List<Base> basesOf(String symbol, LocalDate asOf) {
        return bases.ofSymbol(symbol, asOf, props.engineVersion());
    }

    public List<Base> transitions(LocalDate date) {
        return bases.transitions(date, props.engineVersion());
    }

    public List<SetupRow> list(String name, LocalDate date) {
        List<DailyRating> session = ratings.forDate(date, props.engineVersion());
        RatingsProperties.Lists cfg = props.lists();
        return switch (name) {
            case "leaders" -> session.stream().filter(r -> leader(r, cfg)).map(r -> new SetupRow(r, null)).sorted(BY_COMPOSITE).toList();
            case "movers" -> session.stream()
                    .filter(r -> r.changePct() != null && Math.abs(r.changePct()) >= cfg.moverChangePct() && r.volVsAvg50Pct() != null
                            && r.volVsAvg50Pct() >= (cfg.moverVolume() - 1.0) * 100.0)
                    .map(r -> new SetupRow(r, null))
                    .sorted(Comparator.comparing((SetupRow r) -> Math.abs(r.rating().changePct())).reversed().thenComparing(r -> r.rating().symbol()))
                    .toList();
            case "buyzone" -> setups(session, date, List.of(List.of(BaseStatus.IN_BUY_ZONE)));
            case "nearpivot" -> setups(session, date, List.of(List.of(BaseStatus.NEAR_PIVOT)));
            // the combined ordering: in the buy zone, then triggered and away from it, then near the pivot; each by technical composite
            case "setups" -> setups(session, date, List.of(List.of(BaseStatus.IN_BUY_ZONE), List.of(BaseStatus.EXTENDED, BaseStatus.PULLBACK),
                    List.of(BaseStatus.NEAR_PIVOT)));
            default -> throw new IllegalArgumentException("list must be one of " + NAMES + " or groups");
        };
    }

    public static boolean leader(DailyRating r, RatingsProperties.Lists cfg) {
        return r.techComposite() != null && r.techComposite() >= cfg.leaderComposite() && r.rsRating() != null && r.rsRating() >= cfg.leaderRs()
                && r.avgTurnoverCr() != null && r.avgTurnoverCr() >= cfg.minTurnoverCr();
    }

    private List<SetupRow> setups(List<DailyRating> session, LocalDate date, List<List<BaseStatus>> tiers) {
        Map<java.util.UUID, DailyRating> byInstrument = session.stream().collect(Collectors.toMap(DailyRating::instrumentId, Function.identity()));
        List<Base> open = bases.asOf(date, props.engineVersion());
        List<SetupRow> out = new ArrayList<>();
        for (List<BaseStatus> tier : tiers) {
            out.addAll(open.stream().filter(b -> tier.contains(b.status())).map(b -> new SetupRow(byInstrument.get(b.instrumentId()), b))
                    .sorted(BY_COMPOSITE).toList());
        }
        return out;
    }
}
