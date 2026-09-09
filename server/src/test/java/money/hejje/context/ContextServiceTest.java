package money.hejje.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import money.hejje.common.time.HejjeClock;
import money.hejje.common.time.MutableClock;
import money.hejje.events.EventRisk;
import money.hejje.events.EventService;
import money.hejje.instruments.InstrumentService;
import money.hejje.news.NewsBias;
import money.hejje.news.NewsService;
import money.hejje.pulse.PulseService;
import money.hejje.regime.RegimeService;
import money.hejje.scoring.Adjustment;
import money.hejje.scoring.ScoringService;
import money.hejje.strategy.StrategyService;
import money.hejje.strategy.StrategyVersion;
import org.junit.jupiter.api.Test;

/** Degradation: every context service down or disabled → UNKNOWN rows, net impact 0, no exception (plan M3.5 task 5). */
class ContextServiceTest {

    static final HejjeClock CLOCK = new HejjeClock(MutableClock.atIst("2026-09-08T10:00:00"), MutableClock.IST, (d, e) -> false);

    @Test
    void everythingDownRendersUnknownRows() {
        UUID versionId = UUID.randomUUID();
        UUID instrumentId = UUID.randomUUID();
        ScoringService scoring = mock(ScoringService.class);
        StrategyService strategies = mock(StrategyService.class);
        InstrumentService instruments = mock(InstrumentService.class);
        RegimeService regime = mock(RegimeService.class);
        PulseService pulse = mock(PulseService.class);
        EventService events = mock(EventService.class);
        NewsService news = mock(NewsService.class);
        StrategyVersion version = mock(StrategyVersion.class);
        when(version.id()).thenReturn(versionId);
        when(strategies.versionById(versionId)).thenReturn(Optional.of(version));
        when(scoring.adjustments(any(), any())).thenThrow(new IllegalStateException("scoring down"));
        when(events.risk(any())).thenReturn(EventRisk.unavailable("event service unavailable"));
        when(events.nextEventLine(any())).thenReturn(null);
        when(news.bias(any())).thenReturn(NewsBias.unavailable(instrumentId, Instant.now(), "news unavailable (LLM disabled)"));
        when(instruments.findById(instrumentId)).thenReturn(Optional.empty());
        when(regime.current()).thenThrow(new IllegalStateException("regime down"));

        ContextService service = new ContextService(scoring, strategies, instruments, regime, pulse, events, news, CLOCK);
        StrategyContext card = service.strategyContext(versionId, instrumentId);
        assertThat(card.items()).hasSize(5).allMatch(i -> i.status() == ContextItem.Status.UNKNOWN);
        assertThat(card.items()).extracting(ContextItem::name).containsExactly("Technical fit", "Market regime", "News bias", "Event risk", "Sector");
        assertThat(card.eventRisk().evidence()).containsExactly("event service unavailable");
        assertThat(card.newsBias().evidence()).containsExactly("news unavailable (LLM disabled)");
        assertThat(card.technicalFit().evidence().get(0)).contains("not computed");
        assertThat(card.netImpact()).isZero();
        assertThat(card.nextEvent()).isNull();
        assertThat(service.currentRegime()).isEmpty();
    }

    @Test
    void adjusterDeltasMapOntoThirds() {
        List<Adjustment> adjustments = List.of(new Adjustment("Current regime", 7, -10, 10, List.of("Preferred regime 'trending' is current: +3")),
                new Adjustment("Technical compatibility", -10, -10, 8, List.of("0 of 3 entry conditions pass")),
                new Adjustment("Event risk", 0, -8, 0, List.of("event service unavailable")));
        assertThat(ContextService.fromAdjuster(adjustments, "Current regime", "Market regime", 10, -10, "Favorable", "Neutral", "Unfavorable").status()).isEqualTo(ContextItem.Status.GREEN);
        assertThat(ContextService.fromAdjuster(adjustments, "Technical compatibility", "Technical fit", 8, -10, "Strong", "Forming", "Weak").value()).isEqualTo("Weak");
        assertThat(ContextService.fromAdjuster(adjustments, "Event risk", "Event risk", 0, -8, "Low", "Medium", "High").status()).isEqualTo(ContextItem.Status.UNKNOWN);
        assertThat(ContextService.fromAdjuster(List.of(new Adjustment("Current regime", 0, -10, 10, List.of("No regime preferences declared"))), "Current regime", "Market regime",
                10, -10, "Favorable", "Neutral", "Unfavorable").status()).isEqualTo(ContextItem.Status.AMBER);
    }
}
