package money.hejje.analogs;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import money.hejje.common.Timeframe;
import money.hejje.common.time.HejjeClock;
import money.hejje.instruments.Instrument;
import money.hejje.instruments.InstrumentService;
import money.hejje.market.Candle;
import money.hejje.market.HistoricalCandleStore;
import money.hejje.sim.AbstractSimIT;
import money.hejje.sim.SimSession;
import money.hejje.sim.SimSessionService;
import money.hejje.sim.SimSessionSpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Session analogs inside a SIM replay (plan M8.6): the summary computed at simulated 09:46 equals the one computed from
 * the same day's stored bars after the session, and no session on or after the simulation date is a candidate. Shares
 * the one SIM context ({@link AbstractSimIT}); its day and its instrument are its own.
 */
class SessionAnalogSimIT extends AbstractSimIT {

    static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    static final LocalDate DAY = LocalDate.of(2026, 9, 11);

    @Autowired AnalogsService analogs;
    @Autowired SimSessionService sessions;
    @Autowired HistoricalCandleStore history;
    @Autowired InstrumentService instruments;
    @Autowired HejjeClock clock;
    @Autowired JdbcTemplate jdbc;

    @AfterEach
    void cancelActive() {
        sessions.active().ifPresent(a -> sessions.control(a.id(), SimSessionService.Action.CANCEL, null));
    }

    /** A seeded random-walk session as M1 candles (the replay's input) or M5 candles (history). */
    static List<Candle> session(UUID id, LocalDate date, Timeframe timeframe, long seed, double start) {
        Random random = new Random(seed);
        int minutes = timeframe == Timeframe.M1 ? 1 : 5;
        List<Candle> out = new ArrayList<>();
        double last = start;
        for (int i = 0; i < 375 / minutes; i++) {
            double open = last;
            double close = open * (1 + random.nextGaussian() * 0.0008);
            Instant t = date.atTime(LocalTime.of(9, 15).plusMinutes((long) i * minutes)).atZone(IST).toInstant();
            out.add(new Candle(id, timeframe, t, dec(open), dec(Math.max(open, close) + 0.2), dec(Math.min(open, close) - 0.2), dec(close), 5000, 0, false));
            last = close;
        }
        return out;
    }

    @Test
    void theReplayGivesWhatTheStoredDayGives() {
        jdbc.execute("TRUNCATE analog_summary, analog_match");
        UUID sbin = instruments.resolve("NSE:SBIN").map(Instrument::id).orElseThrow();
        // twenty earlier sessions of M5 history, the day itself as M1 for the replay, and a later session that must stay invisible
        List<LocalDate> earlier = new ArrayList<>();
        for (LocalDate d = DAY.minusDays(1); earlier.size() < 20; d = d.minusDays(1)) {
            if (clock.isTradingDay(d)) {
                earlier.add(0, d);
            }
        }
        List<Candle> m5 = new ArrayList<>();
        for (int k = 0; k < earlier.size(); k++) {
            m5.addAll(session(sbin, earlier.get(k), Timeframe.M5, 100 + k, 800 + k));
        }
        m5.addAll(session(sbin, DAY.plusDays(3), Timeframe.M5, 999, 830));
        history.write(sbin, Timeframe.M5, m5);
        history.write(sbin, Timeframe.M1, session(sbin, DAY, Timeframe.M1, 7, 821));

        SimSession s = sessions.create(new SimSessionSpec(List.of(DAY), null, null, List.of("NSE:SBIN"), null, 1_000_000L, 2000L, 5000L, 5, List.of()),
                "tester");
        for (int i = 0; i < 20; i++) {
            sessions.control(s.id(), SimSessionService.Action.STEP, null);
        }
        assertThat(analogs.session("NSE:SBIN", null, null)).isEmpty();        // 09:35: no checkpoint has passed
        for (int i = 0; i < 11; i++) {
            sessions.control(s.id(), SimSessionService.Action.STEP, null);
        }
        assertThat(clock.nowIst().toLocalTime()).isEqualTo(LocalTime.of(9, 46));
        AnalogSummary live = analogs.session("NSE:SBIN", null, null).orElseThrow();
        assertThat(live.checkpoint()).isEqualTo("09:45");
        assertThat(live.sessionDate()).isEqualTo(DAY);
        assertThat(live.kind()).isEqualTo(AnalogKind.SESSION);
        assertThat(live.candidates()).isEqualTo(5);                             // sessions 16..20 of the twenty; the first fifteen feed the ATR
        assertThat(analogs.sessionMatches("NSE:SBIN", "09:45", null).orElseThrow()).allSatisfy(m -> assertThat(m.endDate()).isBefore(DAY));
        assertThat(analogs.session("NSE:SBIN", "10:15", null)).isEmpty();      // not yet

        sessions.control(s.id(), SimSessionService.Action.PLAY, "MAX");
        for (int i = 0; i < 600 && !sessions.find(s.id()).orElseThrow().state().finished(); i++) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        assertThat(sessions.find(s.id()).orElseThrow().state().finished()).isTrue();

        jdbc.execute("TRUNCATE analog_summary, analog_match");
        assertThat(analogs.computeSession(DAY, "09:45", List.of(sbin))).containsExactly("NSE:SBIN");
        AnalogSummary afterwards = analogs.session("NSE:SBIN", "09:45", DAY).orElseThrow();
        assertThat(afterwards).isEqualTo(live);
        assertThat(analogs.session("NSE:SBIN", "13:00", DAY)).isPresent();      // every checkpoint of the finished day is there on demand
    }

    static BigDecimal dec(double v) {
        return BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP);
    }
}
