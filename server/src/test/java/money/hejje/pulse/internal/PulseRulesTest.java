package money.hejje.pulse.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import money.hejje.pulse.MarketPulse;
import money.hejje.pulse.PulseComponent;
import money.hejje.pulse.PulseDirection;
import money.hejje.pulse.PulseProperties;
import money.hejje.pulse.PulseStrength;
import money.hejje.pulse.SectorStrength;
import money.hejje.pulse.TechnicalPulse;
import money.hejje.regime.Breadth;
import money.hejje.regime.EventEnvironment;
import money.hejje.regime.IntradayStructure;
import money.hejje.regime.Opening;
import money.hejje.regime.RegimeSnapshot;
import money.hejje.regime.Trend;
import money.hejje.regime.Volatility;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

class PulseRulesTest {

    static final PulseProperties PROPS = new Binder(new MapConfigurationPropertySource(Map.of())).bindOrCreate("hejje.pulse", PulseProperties.class);

    static RegimeSnapshot regime(Trend trend, Breadth breadth, Opening opening, double gapPct, Volatility vol) {
        return new RegimeSnapshot(LocalDate.of(2026, 9, 8), null, trend, vol, opening, breadth, IntradayStructure.TREND_DAY, EventEnvironment.NORMAL,
                Map.of("gapPct", gapPct, "advances", 41, "declines", 7), List.of(), "1", false);
    }

    static List<PulseInput.SectorObservation> sectors(Double bank, Double it, Double auto) {
        return List.of(new PulseInput.SectorObservation("Banking", "INDEX:NIFTY BANK", bank), new PulseInput.SectorObservation("IT", "INDEX:NIFTY IT", it),
                new PulseInput.SectorObservation("Auto", "INDEX:NIFTY AUTO", auto));
    }

    @Test
    void everythingBullishIsStrongBullishWithFullCoverage() {
        PulseInput in = new PulseInput(regime(Trend.STRONG_UP, Breadth.STRONG_POSITIVE, Opening.GAP_CONTINUATION, 0.5, Volatility.LOW),
                20_200.0, 20_000.0, 20_120.0, 0.5, 30, 13.0, 14.0, 2.0, 0.3, sectors(2.0, 1.6, 0.9));
        TechnicalPulse t = PulseRules.technical(in, PROPS);
        assertThat(t.direction()).isEqualTo(PulseDirection.BULLISH);
        assertThat(t.strength()).isEqualTo(PulseStrength.STRONG);
        assertThat(t.score()).isGreaterThanOrEqualTo(80);
        assertThat(t.coverage()).isEqualTo(1.0);
        assertThat(t.components()).hasSize(10).allMatch(PulseComponent::available);
        assertThat(t.label()).isEqualTo("BULLISH — STRONG");
        assertThat(t.evidence()).anySatisfy(e -> assertThat(e).contains("Relative volume 2.00x confirms the advance"));
        assertThat(t.evidence()).anySatisfy(e -> assertThat(e).contains("Breadth STRONG_POSITIVE (41 advances / 7 declines)"));
        MarketPulse m = PulseRules.market(in, PROPS);
        assertThat(m.regime()).isEqualTo("Trending ↑");
        assertThat(m.volatility()).isEqualTo("Low");
        assertThat(m.breadth()).isEqualTo("Strong positive");
        assertThat(m.globalContext()).isEqualTo("NEUTRAL");
        assertThat(m.sectors()).extracting(SectorStrength::label).containsExactly(SectorStrength.Label.STRONG, SectorStrength.Label.STRONG, SectorStrength.Label.NEUTRAL);
        assertThat(m.sectors().get(0).relativePct()).isEqualTo(1.0);
    }

    @Test
    void everythingBearishMirrors() {
        PulseInput in = new PulseInput(regime(Trend.STRONG_DOWN, Breadth.STRONG_NEGATIVE, Opening.GAP_REJECTION, 0.5, Volatility.EXTREME),
                19_800.0, 20_000.0, 19_880.0, -0.5, 30, 16.0, 14.0, 2.0, -0.2, sectors(-2.0, -1.6, -0.9));
        TechnicalPulse t = PulseRules.technical(in, PROPS);
        assertThat(t.direction()).isEqualTo(PulseDirection.BEARISH);
        assertThat(t.strength()).isEqualTo(PulseStrength.STRONG);
        assertThat(t.score()).isLessThanOrEqualTo(-80);
        assertThat(t.evidence()).anySatisfy(e -> assertThat(e).contains("extreme volatility regime"));
        assertThat(t.evidence()).anySatisfy(e -> assertThat(e).contains("Opening GAP_REJECTION (gap +0.50%)"));
    }

    @Test
    void flatInputsAreNeutralAndWeak() {
        PulseInput in = new PulseInput(regime(Trend.RANGE, Breadth.MIXED, Opening.FLAT, 0.05, Volatility.NORMAL),
                20_000.0, 20_000.0, 20_000.0, 0.0, 30, 14.0, 14.0, 1.0, 0.1, sectors(0.0, 0.1, -0.1));
        TechnicalPulse t = PulseRules.technical(in, PROPS);
        assertThat(t.score()).isZero();
        assertThat(t.direction()).isEqualTo(PulseDirection.NEUTRAL);
        assertThat(t.strength()).isEqualTo(PulseStrength.WEAK);
        assertThat(t.evidence()).anySatisfy(e -> assertThat(e).contains("Relative volume 1.00x (no confirmation)"));
    }

    @Test
    void missingInputsDropOutOfTheWeightingAndOnlyTheirRowsAreUnknown() {
        PulseInput in = new PulseInput(null, 20_200.0, 20_000.0, 20_120.0, 0.5, 30, null, null, null, null, sectors(2.0, null, 0.9));
        TechnicalPulse t = PulseRules.technical(in, PROPS);
        assertThat(t.components()).filteredOn(c -> !c.available()).extracting(PulseComponent::name)
                .containsExactlyInAnyOrder("index_trend", "breadth", "relative_volume", "vix", "futures_basis", "gap");
        double usedWeight = 15 + 10 + 10 + 10;
        assertThat(t.coverage()).isCloseTo(usedWeight / 105.0, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(t.direction()).isEqualTo(PulseDirection.BULLISH); // the available rules still decide
        MarketPulse m = PulseRules.market(in, PROPS);
        assertThat(m.regime()).isEqualTo("Unknown");
        assertThat(m.sectors()).extracting(SectorStrength::label).containsExactly(SectorStrength.Label.STRONG, SectorStrength.Label.UNKNOWN, SectorStrength.Label.NEUTRAL);
        assertThat(m.sectors().get(1).changePct()).isNull();
        assertThat(t.evidence()).anySatisfy(e -> assertThat(e).startsWith("n/a  Index trend unavailable"));
    }

    @Test
    void noInputsAtAllIsNeutralWithZeroCoverage() {
        PulseInput in = new PulseInput(null, null, null, null, null, 0, null, null, null, null, List.of());
        TechnicalPulse t = PulseRules.technical(in, PROPS);
        assertThat(t.score()).isZero();
        assertThat(t.coverage()).isZero();
        assertThat(t.components()).noneMatch(PulseComponent::available);
        assertThat(PulseRules.market(in, PROPS).sectors()).isEmpty();
    }
}
