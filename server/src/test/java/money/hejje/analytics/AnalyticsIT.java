package money.hejje.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import money.hejje.AbstractIntegrationTest;
import money.hejje.broker.fake.FakeBrokerAdapter;
import money.hejje.common.ActorType;
import money.hejje.common.OrderType;
import money.hejje.common.Price;
import money.hejje.common.Product;
import money.hejje.common.Quantity;
import money.hejje.common.Side;
import money.hejje.common.time.MutableClock;
import money.hejje.execution.ExecutionEngine;
import money.hejje.execution.OrderIntentCommand;
import money.hejje.instruments.Instrument;
import money.hejje.instruments.InstrumentService;
import money.hejje.orders.OrderReason;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

class AnalyticsIT extends AbstractIntegrationTest {

    @org.springframework.beans.factory.annotation.Autowired ReviewService reviewService;

    @Autowired ExecutionEngine engine;
    @Autowired InstrumentService instruments;
    @Autowired FakeBrokerAdapter fake;
    @Autowired AnalyticsService analytics;
    @Autowired MutableClock clock;
    @Autowired JdbcTemplate jdbc;
    @Autowired money.hejje.market.internal.QuoteCache quoteCache;

    UUID infy;
    String token;

    @BeforeEach
    void setUp() {
        jdbc.execute("TRUNCATE trade_review, trade, order_event, hejje_order, risk_decision, order_intent, position, idempotency_record CASCADE");
        jdbc.update("UPDATE kill_switch SET stop_new_orders = FALSE, set_at = NULL, set_by = NULL, reason = NULL");
        instruments.sync();
        infy = instruments.resolve("NSE:INFY").map(Instrument::id).orElseThrow();
        fake.reset();
        quoteCache.clear();
        clock.setIst("2026-09-08T10:00:00");
        token = adminAccessToken();
    }

    @AfterEach
    void tearDown() {
        clock.set(java.time.Instant.now());
    }

    private OrderIntentCommand manual(Side side, int qty, Price stop) {
        return new OrderIntentCommand(UUID.randomUUID(), UUID.randomUUID().toString(), ActorType.USER, "test", null, null, infy, side, Quantity.of(qty), OrderType.MARKET,
                Product.MIS, null, null, stop, null, null, side == Side.BUY ? OrderReason.MANUAL : OrderReason.POSITION_CLOSE);
    }

    @Test
    void manualRoundTripIsAttributedReviewedAndSummed() throws Exception {
        fake.injectQuote(infy, "1500.00");
        engine.submit(manual(Side.BUY, 10, Price.of("1490.00")));
        fake.flush();
        clock.setIst("2026-09-08T10:30:00");
        fake.injectQuote(infy, "1510.00");
        engine.submit(manual(Side.SELL, 10, null));
        fake.flush();

        List<RoundTrip> trips = analytics.roundTrips(money.hejje.common.ExecutionMode.PAPER, clock.instant().minusSeconds(3600 * 24), clock.instant().plusSeconds(60));
        assertThat(trips).hasSize(1);
        RoundTrip trip = trips.get(0);
        assertThat(trip.strategyId()).isNull();
        assertThat(trip.grossPnl()).isEqualTo(money.hejje.common.Money.of("100.00"));
        assertThat(trip.fees().paise()).isGreaterThan(0);

        // the review is written when the position goes flat (async listener)
        TradeReview review = null;
        for (int i = 0; i < 100 && (review == null || review.cause() == null); i++) {
            review = analytics.reviewForEntryOrder(trip.entryOrderId()).orElse(null);
            if (review == null || review.cause() == null) {
                Thread.sleep(100);
            }
        }
        assertThat(review).as("review created").isNotNull();
        assertThat(review.strategyId()).isNull();
        assertThat(review.netPnl()).isEqualTo(trip.netPnl());
        assertThat(review.outcomeR()).isCloseTo(trip.netPnl().toRupees().doubleValue() / 100.0, org.assertj.core.data.Offset.offset(1e-6)); // risk 10 x 10
        assertThat(review.expectedSetupValid()).isNull();
        assertThat(review.ruleAdherencePct()).isNull();
        assertThat(review.entrySlippageBps()).isNull();
        assertThat(review.notes()).isEqualTo("manual trade");
        assertThat(review.context()).containsKeys("regime", "breadth", "news", "event");

        token = adminAccessToken(); // the clock moved 30 minutes: the earlier 15-minute JWT has expired
        ResponseEntity<Map> pnl = rest.exchange("/api/v1/analytics/pnl?groupBy=strategy&from=2026-09-08&to=2026-09-08", HttpMethod.GET, new HttpEntity<>(bearer(token)), Map.class);
        assertThat(pnl.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Map<?, ?>> buckets = (List<Map<?, ?>>) pnl.getBody().get("buckets");
        assertThat(buckets).hasSize(1);
        assertThat(buckets.get(0).get("key")).isEqualTo("MANUAL");
        assertThat(buckets.get(0).get("trades")).isEqualTo(1);
        assertThat(((Map<?, ?>) buckets.get(0).get("netPnl")).get("paise")).isEqualTo((int) trip.netPnl().paise());
        assertThat(((Number) buckets.get(0).get("averageR")).doubleValue()).isCloseTo(review.outcomeR(), org.assertj.core.data.Offset.offset(1e-6));
        Map<?, ?> summary = (Map<?, ?>) pnl.getBody().get("summary");
        assertThat(((Map<?, ?>) summary.get("netPnl")).get("paise")).isEqualTo((int) trip.netPnl().paise());
        for (String groupBy : List.of("version", "instrument", "weekday", "hour", "regime")) {
            ResponseEntity<Map> r = rest.exchange("/api/v1/analytics/pnl?groupBy=" + groupBy + "&from=2026-09-08&to=2026-09-08", HttpMethod.GET, new HttpEntity<>(bearer(token)), Map.class);
            List<Map<?, ?>> b = (List<Map<?, ?>>) r.getBody().get("buckets");
            assertThat(b).as(groupBy).hasSize(1);
            assertThat(((Map<?, ?>) b.get(0).get("netPnl")).get("paise")).as(groupBy).isEqualTo((int) trip.netPnl().paise());
        }
        ResponseEntity<List> reviews = rest.exchange("/api/v1/reviews", HttpMethod.GET, new HttpEntity<>(bearer(token)), List.class);
        assertThat(reviews.getBody()).hasSize(1);
        ResponseEntity<Map> one = rest.exchange("/api/v1/reviews/" + review.id(), HttpMethod.GET, new HttpEntity<>(bearer(token)), Map.class);
        assertThat(one.getBody().get("side")).isEqualTo("BUY");
        ResponseEntity<Map> byOrder = rest.exchange("/api/v1/reviews/by-order/" + trip.entryOrderId(), HttpMethod.GET, new HttpEntity<>(bearer(token)), Map.class);
        assertThat(byOrder.getBody().get("id")).isEqualTo(review.id().toString());

        // plan M9.6: a provisional cause at the close (a manual exit with no close reason: UNKNOWN), completed after the window
        assertThat(review.cause()).isNotNull();
        assertThat(review.cause().complete()).isFalse();
        assertThat(review.cause().evidence()).containsEntry("r", 10.0);
        clock.setIst("2026-09-08T11:10:00");
        reviewService.completeCauses();
        assertThat(analytics.reviewForEntryOrder(trip.entryOrderId()).orElseThrow().cause().complete()).isTrue();
        token = adminAccessToken();
        ResponseEntity<Map> agreement = rest.exchange("/api/v1/reviews/cause-agreement?from=2026-09-08&to=2026-09-08", HttpMethod.GET,
                new HttpEntity<>(bearer(token)), Map.class);
        assertThat(agreement.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(agreement.getBody()).containsEntry("trades", 0);
    }
}
