package money.hejje.ratings.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import money.hejje.ratings.Base;
import money.hejje.ratings.BaseStatus;
import money.hejje.ratings.BaseType;
import money.hejje.ratings.RatingsProperties;

/**
 * Walks one instrument's sessions forward: first every open base is advanced by the session, then the detectors look
 * at the session. An instrument has at most one open base and one open reversal setup at a time; a left high that
 * already produced a base never produces another, except that a cup which has not triggered gives way to the cup with
 * handle that forms from it.
 */
final class BaseWalker {

    /** Receives what the walk produces, in order. */
    interface Sink {

        void detected(Base base);

        void status(Base base);
    }

    private final BaseDetector detector;
    private final BaseLifecycle lifecycle;
    private final String version;

    BaseWalker(RatingsProperties.Bases cfg, String version) {
        this.detector = new BaseDetector(cfg);
        this.lifecycle = new BaseLifecycle(cfg);
        this.version = version;
    }

    /** @param existing every base of the instrument so far with its current status */
    void walk(RatingsEngine.Member m, int from, int to, List<Base> existing, Sink sink) {
        DailySeries s = m.series();
        List<Base> all = new ArrayList<>(existing);
        for (int i = from; i <= to; i++) {
            for (int k = 0; k < all.size(); k++) {
                Base b = all.get(k);
                if (!b.status().closed()) { // a base detected this session is added below, so it is first advanced by the next one
                    Base next = lifecycle.advance(b, s, i);
                    if (next != b) {
                        all.set(k, next);
                        sink.status(next);
                    }
                }
            }
            final int session = i;
            Optional<BaseDetector.Detected> pattern = detector.base(s, i);
            if (pattern.isPresent()) {
                BaseDetector.Detected d = pattern.get();
                Optional<Base> open = all.stream().filter(b -> !b.type().reversal() && !b.status().closed()).findFirst();
                boolean sameStart = all.stream().anyMatch(b -> !b.type().reversal() && b.startDate().equals(s.date(d.start())));
                boolean handleOfOpenCup = d.type() == BaseType.CUP_WITH_HANDLE && open.isPresent() && open.get().type() == BaseType.CUP
                        && open.get().triggerDate() == null && open.get().startDate().equals(s.date(d.start()));
                if (handleOfOpenCup) {
                    Base superseded = open.get().withStatus(BaseStatus.EXPIRED, s.date(session), null, null, null, null, null, null);
                    all.set(all.indexOf(open.get()), superseded);
                    sink.status(superseded);
                }
                if (handleOfOpenCup || (open.isEmpty() && !sameStart)) {
                    Base base = lifecycle.open(m, d, version);
                    all.add(base);
                    sink.detected(base);
                }
            }
            if (all.stream().noneMatch(b -> b.type().reversal() && !b.status().closed())) {
                detector.reversal(s, i).ifPresent(d -> {
                    Base base = lifecycle.open(m, d, version);
                    all.add(base);
                    sink.detected(base);
                });
            }
        }
    }
}
