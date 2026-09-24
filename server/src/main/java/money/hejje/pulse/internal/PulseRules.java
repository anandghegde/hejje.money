package money.hejje.pulse.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import money.hejje.pulse.MarketPulse;
import money.hejje.pulse.PulseComponent;
import money.hejje.pulse.PulseDirection;
import money.hejje.pulse.PulseProperties;
import money.hejje.pulse.PulseStrength;
import money.hejje.pulse.SectorStrength;
import money.hejje.pulse.TechnicalPulse;
import money.hejje.regime.Breadth;
import money.hejje.regime.RegimeSnapshot;
import money.hejje.regime.Trend;
import money.hejje.regime.Volatility;

/** The PRD 16 rules (docs/pulse.md). Pure: inputs in, labelled composite out. */
final class PulseRules {

    private PulseRules() {
    }

    static TechnicalPulse technical(PulseInput in, PulseProperties p) {
        PulseProperties.Thresholds t = p.thresholds();
        List<PulseComponent> c = new ArrayList<>();
        RegimeSnapshot r = in.regime();

        // index trend from the regime engine
        Double trend = r == null ? null : switch (r.trend()) {
            case STRONG_UP -> 1.0;
            case UP -> 0.5;
            case RANGE -> 0.0;
            case DOWN -> -0.5;
            case STRONG_DOWN -> -1.0;
            case UNKNOWN -> null;
        };
        c.add(trend == null ? PulseComponent.unavailable("index_trend", p.weight("index_trend"), "Index trend unavailable (regime unknown)")
                : component("index_trend", p.weight("index_trend"), trend, "Index trend " + r.trend()));

        // index vs session average
        if (in.indexLast() != null && in.indexSessionAverage() != null && in.indexSessionAverage() > 0) {
            double pct = 100.0 * (in.indexLast() - in.indexSessionAverage()) / in.indexSessionAverage();
            c.add(component("index_vs_vwap", p.weight("index_vs_vwap"), clip(pct / t.vwapFullPct()),
                    String.format(Locale.ROOT, "Index %.2f is %+.2f%% from its session average %.2f", in.indexLast(), pct, in.indexSessionAverage())));
        } else {
            c.add(PulseComponent.unavailable("index_vs_vwap", p.weight("index_vs_vwap"), "No index bars yet today"));
        }

        // day change
        Double change = in.indexChangePct();
        if (change != null) {
            c.add(component("day_change", p.weight("day_change"), clip(change / t.dayChangeFullPct()),
                    String.format(Locale.ROOT, "Index %+.2f%% vs previous close %.2f", change, in.indexPrevClose())));
        } else {
            c.add(PulseComponent.unavailable("day_change", p.weight("day_change"), "Index previous close or last price unavailable"));
        }

        // momentum
        if (in.indexRocPct() != null) {
            c.add(component("momentum", p.weight("momentum"), clip(in.indexRocPct() / t.rocFullPct()),
                    String.format(Locale.ROOT, "Rate of change %+.2f%% over the last %d bars", in.indexRocPct(), Math.min(t.rocBars(), Math.max(0, in.indexBars() - 1)))));
        } else {
            c.add(PulseComponent.unavailable("momentum", p.weight("momentum"), "Fewer than 3 index bars today"));
        }

        // breadth
        Double breadth = r == null ? null : switch (r.breadth()) {
            case STRONG_POSITIVE -> 1.0;
            case POSITIVE -> 0.5;
            case MIXED -> 0.0;
            case NEGATIVE -> -0.5;
            case STRONG_NEGATIVE -> -1.0;
            case UNKNOWN -> null;
        };
        c.add(breadth == null ? PulseComponent.unavailable("breadth", p.weight("breadth"), "Breadth unavailable")
                : component("breadth", p.weight("breadth"), breadth, "Breadth " + r.breadth()
                        + (r.features().get("advances") == null ? "" : " (" + r.features().get("advances") + " advances / " + r.features().get("declines") + " declines)")));

        // relative volume confirms the day's direction
        if (in.relativeVolume() != null && change != null) {
            double confirm = Math.max(0, Math.min(1, (in.relativeVolume() - 1) / (t.relativeVolumeFull() - 1)));
            double value = Math.signum(change) * confirm;
            c.add(component("relative_volume", p.weight("relative_volume"), value,
                    String.format(Locale.ROOT, "Relative volume %.2fx %s", in.relativeVolume(), confirm == 0 ? "(no confirmation)" : "confirms the " + (change >= 0 ? "advance" : "decline"))));
        } else {
            c.add(PulseComponent.unavailable("relative_volume", p.weight("relative_volume"), "Futures volume history unavailable"));
        }

        // VIX: change against its previous close, plus an extreme-level penalty
        if (in.vixLast() != null && in.vixPrevClose() != null && in.vixPrevClose() > 0) {
            double vixChange = 100.0 * (in.vixLast() - in.vixPrevClose()) / in.vixPrevClose();
            double value = -clip(vixChange / t.vixFullChangePct());
            String extra = "";
            if (r != null && r.volatility() == Volatility.EXTREME) {
                value = clip(value - 0.5);
                extra = ", extreme volatility regime";
            }
            c.add(component("vix", p.weight("vix"), value, String.format(Locale.ROOT, "VIX %.2f, %+.1f%% on the day%s", in.vixLast(), vixChange, extra)));
        } else {
            c.add(PulseComponent.unavailable("vix", p.weight("vix"), "VIX unavailable"));
        }

        // sectors: strong minus weak among the sectors with data
        List<SectorStrength> sectors = sectors(in, t);
        long known = sectors.stream().filter(s -> s.label() != SectorStrength.Label.UNKNOWN).count();
        if (known > 0) {
            long strong = sectors.stream().filter(s -> s.label() == SectorStrength.Label.STRONG).count();
            long weak = sectors.stream().filter(s -> s.label() == SectorStrength.Label.WEAK).count();
            c.add(component("sectors", p.weight("sectors"), (double) (strong - weak) / known,
                    String.format(Locale.ROOT, "%d strong / %d weak of %d sectors with data", strong, weak, known)));
        } else {
            c.add(PulseComponent.unavailable("sectors", p.weight("sectors"), "No sector index data"));
        }

        // futures basis
        if (in.futuresBasisPct() != null) {
            c.add(component("futures_basis", p.weight("futures_basis"), clip((in.futuresBasisPct() - t.basisNeutralPct()) / t.basisFullPct()),
                    String.format(Locale.ROOT, "Futures basis %+.2f%% of the index (neutral %+.2f%%)", in.futuresBasisPct(), t.basisNeutralPct())));
        } else {
            c.add(PulseComponent.unavailable("futures_basis", p.weight("futures_basis"), "Nearest future or index price unavailable"));
        }

        // gap behaviour
        Double gapPct = r == null ? null : r.features().get("gapPct") instanceof Number n ? n.doubleValue() : null;
        Double gap = r == null || gapPct == null ? null : switch (r.opening()) {
            case GAP_CONTINUATION -> Math.signum(gapPct);
            case GAP_REJECTION -> -Math.signum(gapPct);
            case GAP_UP -> 0.5;
            case GAP_DOWN -> -0.5;
            case FLAT -> 0.0;
            case UNKNOWN -> null;
        };
        c.add(gap == null ? PulseComponent.unavailable("gap", p.weight("gap"), "Opening behaviour unavailable")
                : component("gap", p.weight("gap"), gap, String.format(Locale.ROOT, "Opening %s (gap %+.2f%%)", r.opening(), gapPct)));

        double totalWeight = c.stream().mapToDouble(PulseComponent::weight).sum();
        double usedWeight = c.stream().filter(PulseComponent::available).mapToDouble(PulseComponent::weight).sum();
        double sum = c.stream().filter(PulseComponent::available).mapToDouble(PulseComponent::contribution).sum();
        int score = usedWeight == 0 ? 0 : (int) Math.round(100.0 * sum / usedWeight);
        PulseDirection direction = score >= t.bullishScore() ? PulseDirection.BULLISH : score <= -t.bullishScore() ? PulseDirection.BEARISH : PulseDirection.NEUTRAL;
        int magnitude = Math.abs(score);
        PulseStrength strength = magnitude >= t.strongScore() ? PulseStrength.STRONG : magnitude >= t.moderateScore() ? PulseStrength.MODERATE : PulseStrength.WEAK;
        List<String> evidence = new ArrayList<>();
        for (PulseComponent x : c) {
            evidence.add((x.available() ? String.format(Locale.ROOT, "%+.1f ", x.contribution()) : "n/a  ") + x.evidence());
        }
        return new TechnicalPulse(direction, strength, score, totalWeight == 0 ? 0 : usedWeight / totalWeight, c, evidence);
    }

    static MarketPulse market(PulseInput in, PulseProperties p) {
        RegimeSnapshot r = in.regime();
        return new MarketPulse(r == null ? "Unknown" : regimeLabel(r.trend()), r == null ? "Unknown" : volatilityLabel(r.volatility()),
                r == null ? "Unknown" : breadthLabel(r.breadth()), sectors(in, p.thresholds()), "NEUTRAL",
                r == null ? "Unknown" : conditionLabel(r.marketCondition()),
                r == null ? null : r.evidence().stream().filter(e -> e.startsWith("Market condition")).findFirst().orElse(null));
    }

    static String conditionLabel(money.hejje.regime.MarketCondition condition) {
        return switch (condition) {
            case CONFIRMED_UPTREND -> "Confirmed uptrend";
            case UPTREND_UNDER_PRESSURE -> "Uptrend under pressure";
            case RALLY_ATTEMPT -> "Rally attempt";
            case DOWNTREND -> "Downtrend";
            case UNKNOWN -> "Unknown";
        };
    }

    static List<SectorStrength> sectors(PulseInput in, PulseProperties.Thresholds t) {
        List<SectorStrength> out = new ArrayList<>();
        Double index = in.indexChangePct();
        for (PulseInput.SectorObservation s : in.sectors()) {
            if (s.changePct() == null || index == null) {
                out.add(new SectorStrength(s.name(), s.symbol(), SectorStrength.Label.UNKNOWN, s.changePct(), null));
                continue;
            }
            double relative = s.changePct() - index;
            SectorStrength.Label label = relative >= t.sectorStrongPct() ? SectorStrength.Label.STRONG
                    : relative <= -t.sectorStrongPct() ? SectorStrength.Label.WEAK : SectorStrength.Label.NEUTRAL;
            out.add(new SectorStrength(s.name(), s.symbol(), label, round(s.changePct()), round(relative)));
        }
        return out;
    }

    static String regimeLabel(Trend trend) {
        return switch (trend) {
            case STRONG_UP, UP -> "Trending ↑";
            case STRONG_DOWN, DOWN -> "Trending ↓";
            case RANGE -> "Ranging";
            case UNKNOWN -> "Unknown";
        };
    }

    static String volatilityLabel(Volatility v) {
        return switch (v) {
            case VERY_LOW -> "Very low";
            case LOW -> "Low";
            case NORMAL -> "Moderate";
            case HIGH -> "High";
            case EXTREME -> "Extreme";
            case UNKNOWN -> "Unknown";
        };
    }

    static String breadthLabel(Breadth b) {
        return switch (b) {
            case STRONG_POSITIVE -> "Strong positive";
            case POSITIVE -> "Positive";
            case MIXED -> "Mixed";
            case NEGATIVE -> "Negative";
            case STRONG_NEGATIVE -> "Strong negative";
            case UNKNOWN -> "Unknown";
        };
    }

    private static PulseComponent component(String name, double weight, double value, String evidence) {
        return new PulseComponent(name, weight, value, weight * value, evidence);
    }

    static double clip(double v) {
        return Math.max(-1, Math.min(1, v));
    }

    private static Double round(double v) {
        return Math.round(v * 100) / 100.0;
    }
}
