package money.hejje.calibration;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import money.hejje.AbstractIntegrationTest;
import money.hejje.common.time.MutableClock;
import money.hejje.common.Side;
import money.hejje.common.Timeframe;
import money.hejje.instruments.Instrument;
import money.hejje.instruments.InstrumentService;
import money.hejje.market.Candle;
import money.hejje.market.HistoricalCandleStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

/** Recording, labelling from stored M1 candles, immutability and the API (plan M9.2). Candles on 2026-12-02. */
class CalibrationIT extends AbstractIntegrationTest {

    static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    static final LocalDate DAY = LocalDate.of(2026, 12, 2);

    @Autowired CalibrationService calibration;
    @Autowired InstrumentService instruments;
    @Autowired HistoricalCandleStore history;
    @Autowired MutableClock clock;

    static Instant at(String hhmm) {
        return DAY.atTime(LocalTime.parse(hhmm)).atZone(IST).toInstant();
    }

    /** Flat at 100 from 10:00 with a 10:05 bar reaching 102.5 (a long from 100 with stop 98 hits +1R there), and 10:03 touching 97.5. */
    static List<Candle> day(UUID id, boolean dipAt1003) {
        List<Candle> out = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            Instant t = at("10:00").plusSeconds(60L * i);
            BigDecimal o = new BigDecimal("100.00");
            BigDecimal h = new BigDecimal("100.50");
            BigDecimal l = new BigDecimal("99.50");
            if (i == 5) {
                h = new BigDecimal("102.50");
            }
            if (i == 3 && dipAt1003) {
                l = new BigDecimal("97.50");
            }
            out.add(new Candle(id, Timeframe.M1, t, o, h, l, o, 1000, 0, false));
        }
        return out;
    }

    @Test
    @SuppressWarnings("unchecked")
    void aPredictionIsLabelledOnceItsWindowHasPassedAndNeverChanges() {
        instruments.sync();
        UUID id = instruments.resolve("NSE:INFY").map(Instrument::id).orElseThrow();
        history.write(id, Timeframe.M1, day(id, false));
        String purpose = "it-entry-" + System.nanoTime();
        // decided at 10:01: bars from 10:01 on count, the 10:00 bar does not
        Prediction p = new Prediction("jev", UUID.randomUUID().toString(), "setup", purpose, "1", 0.7, id, LabelRule.ENTRY_1R, Side.BUY,
                new BigDecimal("98.00"), at("10:01"));
        calibration.record(p);
        calibration.record(p); // idempotent

        clock.setIst(DAY + "T10:20:00"); // the 30-minute window ends at 10:31
        calibration.labelDue();
        assertThat(calibration.report(purpose, "1", null, null).pending()).isEqualTo(1);

        clock.setIst(DAY + "T10:31:00");
        calibration.labelDue();
        CalibrationReport r = calibration.report(purpose, null, DAY, DAY);
        assertThat(r.version()).isEqualTo("1");
        assertThat(r.n()).isEqualTo(1);
        assertThat(r.pending()).isZero();
        assertThat(r.buckets().get(7).hits()).isEqualTo(1);
        assertThat(r.brier()).isEqualTo(0.09);

        // later candles that would have stopped the trade out first change nothing: labels are final
        history.write(id, Timeframe.M1, day(id, true));
        clock.setIst(DAY + "T19:00:00");
        calibration.labelDue();
        assertThat(calibration.report(purpose, "1", null, null).buckets().get(7).hits()).isEqualTo(1);

        // the API, by purpose, and the purposes list
        String admin = adminAccessToken();
        ResponseEntity<Map> api = rest.exchange("/api/v1/calibration?purpose=" + purpose, HttpMethod.GET, new HttpEntity<>(bearer(admin)), Map.class);
        assertThat(api.getBody()).containsEntry("n", 1).containsEntry("passes", false);
        assertThat((List<?>) api.getBody().get("buckets")).hasSize(10);
        ResponseEntity<List> purposes = rest.exchange("/api/v1/calibration/purposes", HttpMethod.GET, new HttpEntity<>(bearer(admin)), List.class);
        assertThat((List<Map<String, Object>>) purposes.getBody()).anySatisfy(m -> assertThat(m).containsEntry("purpose", purpose).containsEntry("labelled", 1));
    }

    @Test
    void theEntryBarIsTheFirstOneOpeningAfterTheDecision() {
        instruments.sync();
        UUID id = instruments.resolve("NSE:TCS").map(Instrument::id).orElseThrow();
        history.write(id, Timeframe.M1, day(id, true));
        String purpose = "it-lookahead-" + System.nanoTime();
        // decided at 10:04: the 10:03 dip through the stop is before the decision and must not count
        calibration.record(new Prediction("bot", UUID.randomUUID().toString(), "confidence", purpose, "1", 0.55, id, LabelRule.ENTRY_1R, Side.BUY,
                new BigDecimal("98.00"), at("10:04")));
        // a direction prediction from 10:04 over 60 minutes, cut at the last stored bar: flat, so NONE
        calibration.record(new Prediction("jev", UUID.randomUUID().toString(), "long", purpose + "-dir", "1", 0.4, id, LabelRule.DIRECTION, Side.BUY, null,
                at("10:04")));
        clock.setIst(DAY + "T19:00:00");
        calibration.labelDue();
        assertThat(calibration.report(purpose, "1", null, null).n()).isEqualTo(1);
        assertThat(calibration.report(purpose, "1", null, null).buckets().get(5).hits()).isEqualTo(1);
        CalibrationReport dir = calibration.report(purpose + "-dir", "1", null, null);
        assertThat(dir.n()).isZero();
        assertThat(dir.none()).isEqualTo(1);
    }
}
