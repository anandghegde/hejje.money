package money.hejje.events;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import money.hejje.common.Exchange;
import money.hejje.common.InstrumentType;
import money.hejje.events.internal.Events;
import money.hejje.instruments.Instrument;
import money.hejje.strategy.StrategyDefinition;
import org.junit.jupiter.api.Test;

/** Proximity rules around each threshold (docs/events.md) and the strategy event rules on top. */
class EventRiskEvaluatorTest {

    static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    static final LocalDate DAY = LocalDate.of(2026, 9, 8);
    static final EventProperties.Risk RULES = new EventProperties.Risk(60, 30, 5);
    static final Instrument INFY = new Instrument(UUID.randomUUID(), "INFY", "Infosys", Exchange.NSE, InstrumentType.EQ, null, null, null, null, 1,
            new BigDecimal("0.05"), null, true, Instant.now());
    static final Instrument NIFTY_FUT = new Instrument(UUID.randomUUID(), "NIFTY26SEPFUT", "NIFTY", Exchange.NFO, InstrumentType.FUT, "NIFTY",
            LocalDate.of(2026, 9, 29), null, null, 75, new BigDecimal("0.05"), null, true, Instant.now());

    static Instant at(String time) {
        return DAY.atTime(LocalTime.parse(time)).atZone(IST).toInstant();
    }

    static MarketEvent macro(LocalDate date, String time) {
        return Events.on(EventType.RBI_POLICY, EventScope.MARKET, null, null, "RBI MPC decision", date, time == null ? null : LocalTime.parse(time), null, "curated",
                0.95, Map.of(), IST, Instant.EPOCH);
    }

    static MarketEvent instrumentEvent(EventType type, Instrument i, LocalDate date, String time) {
        return Events.on(type, EventScope.INSTRUMENT, i.id(), i.hejjeSymbol().format(), type == EventType.RESULTS ? "Q2 results" : type.name(), date,
                time == null ? null : LocalTime.parse(time), null, "manual", 1.0, Map.of(), IST, Instant.EPOCH);
    }

    @Test
    void macroProximityThresholds() {
        MarketEvent rbi = macro(DAY, "10:00");
        assertThat(EventRiskEvaluator.evaluate(List.of(rbi), INFY, at("09:01"), IST, RULES).level()).isEqualTo(EventRiskLevel.HIGH);   // in 59 min
        assertThat(EventRiskEvaluator.evaluate(List.of(rbi), INFY, at("09:00"), IST, RULES).level()).isEqualTo(EventRiskLevel.HIGH);   // exactly 60
        assertThat(EventRiskEvaluator.evaluate(List.of(rbi), INFY, at("08:59"), IST, RULES).level()).isEqualTo(EventRiskLevel.MEDIUM); // 61 min: macro today
        assertThat(EventRiskEvaluator.evaluate(List.of(rbi), INFY, at("10:20"), IST, RULES).level()).isEqualTo(EventRiskLevel.HIGH);   // in progress (30 min)
        assertThat(EventRiskEvaluator.evaluate(List.of(rbi), INFY, at("10:31"), IST, RULES).level()).isEqualTo(EventRiskLevel.MEDIUM); // over, still today
        EventRisk soon = EventRiskEvaluator.evaluate(List.of(rbi), INFY, at("09:30"), IST, RULES);
        assertThat(soon.minutesTo()).isEqualTo(30);
        assertThat(soon.nextEvent().title()).isEqualTo("RBI MPC decision");
        assertThat(soon.evidence().get(0)).isEqualTo("RBI MPC decision (2026-09-08 10:00, in 30 min) → HIGH");
        assertThat(soon.nextEventLine(IST, DAY)).isEqualTo("RBI MPC decision — Today 10:00");
        // tomorrow's macro event: LOW today, but it is the next event
        EventRisk tomorrow = EventRiskEvaluator.evaluate(List.of(macro(DAY.plusDays(1), "10:00")), INFY, at("09:30"), IST, RULES);
        assertThat(tomorrow.level()).isEqualTo(EventRiskLevel.LOW);
        assertThat(tomorrow.nextEventLine(IST, DAY)).isEqualTo("RBI MPC decision — Tomorrow 10:00");
        assertThat(tomorrow.evidence().get(0)).startsWith("No event today; next: RBI MPC decision");
        // yesterday's: nothing
        EventRisk none = EventRiskEvaluator.evaluate(List.of(macro(DAY.minusDays(1), "10:00")), INFY, at("09:30"), IST, RULES);
        assertThat(none.level()).isEqualTo(EventRiskLevel.LOW);
        assertThat(none.nextEvent()).isNull();
    }

    @Test
    void instrumentEventsResultsExDatesBoardMeetingsAndExpiry() {
        assertThat(EventRiskEvaluator.evaluate(List.of(instrumentEvent(EventType.RESULTS, INFY, DAY, "16:00")), INFY, at("09:30"), IST, RULES).level())
                .isEqualTo(EventRiskLevel.HIGH);
        assertThat(EventRiskEvaluator.evaluate(List.of(instrumentEvent(EventType.RESULTS, INFY, DAY, null)), INFY, at("09:30"), IST, RULES).minutesTo()).isZero();
        assertThat(EventRiskEvaluator.evaluate(List.of(instrumentEvent(EventType.EX_DIVIDEND, INFY, DAY, null)), INFY, at("09:30"), IST, RULES).level())
                .isEqualTo(EventRiskLevel.MEDIUM);
        assertThat(EventRiskEvaluator.evaluate(List.of(instrumentEvent(EventType.BOARD_MEETING, INFY, DAY, null)), INFY, at("09:30"), IST, RULES).level())
                .isEqualTo(EventRiskLevel.MEDIUM);
        // someone else's results do not touch INFY
        Instrument tcs = new Instrument(UUID.randomUUID(), "TCS", "TCS", Exchange.NSE, InstrumentType.EQ, null, null, null, null, 1, new BigDecimal("0.05"), null, true, Instant.now());
        assertThat(EventRiskEvaluator.evaluate(List.of(instrumentEvent(EventType.RESULTS, tcs, DAY, null)), INFY, at("09:30"), IST, RULES).level()).isEqualTo(EventRiskLevel.LOW);
        // expiry day: MEDIUM for the index derivative, LOW for an equity
        MarketEvent expiry = Events.on(EventType.FNO_EXPIRY, EventScope.MARKET, null, "NIFTY", "NIFTY weekly expiry", DAY, null, null, "computed", 1.0, Map.of(), IST, Instant.EPOCH);
        assertThat(EventRiskEvaluator.evaluate(List.of(expiry), NIFTY_FUT, at("09:30"), IST, RULES).level()).isEqualTo(EventRiskLevel.MEDIUM);
        assertThat(EventRiskEvaluator.evaluate(List.of(expiry), INFY, at("09:30"), IST, RULES).level()).isEqualTo(EventRiskLevel.LOW);
        // the highest rule wins: results (HIGH) with an ex-date (MEDIUM)
        assertThat(EventRiskEvaluator.evaluate(List.of(instrumentEvent(EventType.EX_DIVIDEND, INFY, DAY, null), instrumentEvent(EventType.RESULTS, INFY, DAY, "16:00")),
                INFY, at("09:30"), IST, RULES).level()).isEqualTo(EventRiskLevel.HIGH);
        // no instrument: market-only view
        assertThat(EventRiskEvaluator.evaluate(List.of(instrumentEvent(EventType.RESULTS, INFY, DAY, null)), null, at("09:30"), IST, RULES).level()).isEqualTo(EventRiskLevel.LOW);
        assertThat(EventRiskEvaluator.evaluate(List.of(), null, at("09:30"), IST, RULES).evidence().get(0)).isEqualTo("No scheduled events for the market in the horizon");
    }

    @Test
    void strategyEventRules() {
        EventRisk high = EventRiskEvaluator.evaluate(List.of(macro(DAY, "10:00")), INFY, at("09:30"), IST, RULES); // HIGH, 30 min away
        EventRuleOutcome block15 = EventRules.apply(new StrategyDefinition.EventRules(15, StrategyDefinition.EventAction.BLOCK), high);
        assertThat(block15.triggered()).isFalse();
        assertThat(block15.reason()).contains("outside the 15-minute rule window");
        EventRuleOutcome block45 = EventRules.apply(new StrategyDefinition.EventRules(45, StrategyDefinition.EventAction.BLOCK), high);
        assertThat(block45.blocks()).isTrue();
        assertThat(block45.reason()).isEqualTo("RBI MPC decision in 30 min (event risk HIGH, rule: block within 45 min)");
        EventRuleOutcome caution = EventRules.apply(new StrategyDefinition.EventRules(null, StrategyDefinition.EventAction.CAUTION), high);
        assertThat(caution.cautions()).isTrue();
        assertThat(caution.blocks()).isFalse();
        assertThat(EventRules.apply(StrategyDefinition.EventRules.DEFAULT, high).triggered()).isFalse();
        EventRisk medium = EventRiskEvaluator.evaluate(List.of(macro(DAY, "10:00")), INFY, at("08:00"), IST, RULES);
        assertThat(EventRules.apply(new StrategyDefinition.EventRules(null, StrategyDefinition.EventAction.BLOCK), medium).triggered()).isFalse();
        // the rule follows the event that made the risk HIGH, not the next one on the calendar
        EventRisk resultsThenRbi = EventRiskEvaluator.evaluate(List.of(instrumentEvent(EventType.RESULTS, INFY, DAY, "16:00"), macro(DAY.plusDays(1), "10:00")), INFY,
                at("17:00"), IST, RULES);
        assertThat(resultsThenRbi.level()).isEqualTo(EventRiskLevel.HIGH);
        assertThat(resultsThenRbi.trigger().title()).isEqualTo("Q2 results");
        assertThat(resultsThenRbi.triggerMinutesTo()).isZero();
        assertThat(resultsThenRbi.nextEvent().title()).isEqualTo("RBI MPC decision"); // the passed timed event is no longer "next"
        EventRuleOutcome afterResults = EventRules.apply(new StrategyDefinition.EventRules(15, StrategyDefinition.EventAction.CAUTION), resultsThenRbi);
        assertThat(afterResults.cautions()).isTrue();
        assertThat(afterResults.reason()).isEqualTo("Q2 results today (event risk HIGH, rule: caution within 15 min)");
        EventRuleOutcome unavailable = EventRules.apply(new StrategyDefinition.EventRules(null, StrategyDefinition.EventAction.BLOCK), EventRisk.unavailable("down"));
        assertThat(unavailable.triggered()).isFalse();
        assertThat(unavailable.reason()).contains("unavailable");
    }
}
