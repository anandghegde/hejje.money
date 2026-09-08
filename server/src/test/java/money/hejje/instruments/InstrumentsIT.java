package money.hejje.instruments;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import money.hejje.AbstractIntegrationTest;
import money.hejje.audit.AuditEventType;
import money.hejje.audit.AuditQuery;
import money.hejje.audit.AuditService;
import money.hejje.common.Exchange;
import money.hejje.common.InstrumentType;
import money.hejje.common.time.MutableClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

class InstrumentsIT extends AbstractIntegrationTest {

    @Autowired
    InstrumentService instruments;

    @Autowired
    AuditService audit;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    MutableClock clock;

    private java.time.Instant realNow;

    @BeforeEach
    void sync() {
        realNow = clock.instant();
        instruments.sync();
    }

    @AfterEach
    void resetClock() {
        clock.set(realNow);
    }

    @Test
    void fixtureImportsAndSecondSyncIsIdempotent() {
        long instrumentsAfterFirst = jdbc.queryForObject("SELECT count(*) FROM instrument", Long.class);
        long mappingsAfterFirst = jdbc.queryForObject("SELECT count(*) FROM broker_instrument_mapping", Long.class);
        assertThat(instrumentsAfterFirst).isEqualTo(32);
        assertThat(mappingsAfterFirst).isEqualTo(32);

        InstrumentSyncResult second = instruments.sync();
        assertThat(second.received()).isEqualTo(32);
        assertThat(second.deactivated()).isZero();
        assertThat(second.activeAfter()).isEqualTo(32);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM instrument", Long.class)).isEqualTo(instrumentsAfterFirst);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM broker_instrument_mapping", Long.class)).isEqualTo(mappingsAfterFirst);
        assertThat(audit.query(new AuditQuery(null, null, AuditEventType.INSTRUMENTS_SYNCED, null, 0, 10)).total()).isGreaterThanOrEqualTo(2);
    }

    @Test
    void resolvesCanonicalSymbolsAndTradingSymbols() {
        assertThat(instruments.resolve("NSE:RELIANCE")).get().satisfies(i -> {
            assertThat(i.type()).isEqualTo(InstrumentType.EQ);
            assertThat(i.hejjeSymbol().format()).isEqualTo("NSE:RELIANCE");
        });
        assertThat(instruments.resolve("BSE:RELIANCE")).get().satisfies(i -> assertThat(i.exchange()).isEqualTo(Exchange.BSE));
        assertThat(instruments.resolve("INDEX:NIFTY 50")).get().satisfies(i -> assertThat(i.type()).isEqualTo(InstrumentType.INDEX));
        assertThat(instruments.resolve("NFO:NIFTY:FUT:2026-10-27")).get().satisfies(i -> assertThat(i.lotSize()).isEqualTo(75));
        assertThat(instruments.resolve("NFO:NIFTY:OPT:2026-09-15:25000:CE")).get()
                .satisfies(i -> assertThat(instruments.mapping(i.id(), "fake")).get()
                        .satisfies(m -> assertThat(m.tradingSymbol()).isEqualTo("NIFTY2691525000CE")));
        assertThat(instruments.resolve("NFO:NIFTY26SEPFUT")).get().satisfies(i -> assertThat(i.expiry()).isEqualTo(LocalDate.of(2026, 9, 29)));
        assertThat(instruments.resolve("NSE:NOSUCHTHING")).isEmpty();
        assertThat(instruments.findByBrokerToken("fake", "256265")).get().satisfies(i -> assertThat(i.symbol()).isEqualTo("NIFTY 50"));
    }

    @Test
    void nearestFutureFollowsTheClock() {
        clock.setIst("2026-09-08T10:00:00");
        assertThat(instruments.nearestFuture("NIFTY")).get().satisfies(i -> assertThat(i.expiry()).isEqualTo(LocalDate.of(2026, 9, 29)));
        clock.setIst("2026-09-29T15:00:00"); // expiry day: the September contract is still the nearest
        assertThat(instruments.nearestFuture("nifty")).get().satisfies(i -> assertThat(i.expiry()).isEqualTo(LocalDate.of(2026, 9, 29)));
        clock.setIst("2026-09-30T09:15:00");
        assertThat(instruments.nearestFuture("NIFTY")).get().satisfies(i -> assertThat(i.expiry()).isEqualTo(LocalDate.of(2026, 10, 27)));
        clock.setIst("2026-12-01T09:15:00");
        assertThat(instruments.nearestFuture("NIFTY")).isEmpty();
        assertThat(instruments.nearestFuture("BANKNIFTY", LocalDate.of(2026, 10, 1))).get()
                .satisfies(i -> assertThat(i.expiry()).isEqualTo(LocalDate.of(2026, 10, 27)));
    }

    @Test
    void optionChainAndExpiries() {
        clock.setIst("2026-09-08T10:00:00");
        List<Instrument> chain = instruments.optionChain("NIFTY", LocalDate.of(2026, 9, 15));
        assertThat(chain).hasSize(6);
        assertThat(chain.stream().map(i -> i.strike().toPlainString() + i.optionType()))
                .containsExactly("24900.00CE", "24900.00PE", "25000.00CE", "25000.00PE", "25100.00CE", "25100.00PE");
        assertThat(instruments.weeklyExpiries("NIFTY")).containsExactly(LocalDate.of(2026, 9, 15), LocalDate.of(2026, 9, 22), LocalDate.of(2026, 9, 29));
        clock.setIst("2026-09-16T10:00:00");
        assertThat(instruments.weeklyExpiries("NIFTY")).containsExactly(LocalDate.of(2026, 9, 22), LocalDate.of(2026, 9, 29));
    }

    @Test
    void searchAndEndpoints() {
        assertThat(instruments.search("reli", null, null, 10)).extracting(Instrument::symbol).contains("RELIANCE");
        assertThat(instruments.search("RELI", Exchange.NSE, InstrumentType.EQ, 10)).hasSize(1);
        assertThat(instruments.search("bank", null, InstrumentType.EQ, 10)).extracting(Instrument::symbol).contains("HDFCBANK", "ICICIBANK", "SBIN");

        String token = adminAccessToken();
        ResponseEntity<List> list = rest.exchange("/api/v1/instruments?q=NIFTY&type=FUT", HttpMethod.GET, new HttpEntity<>(bearer(token)), List.class);
        assertThat(list.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(list.getBody()).hasSize(5);

        ResponseEntity<Map> resolved = rest.exchange("/api/v1/instruments/resolve?symbol=NFO:NIFTY:FUT:2026-09-29", HttpMethod.GET,
                new HttpEntity<>(bearer(token)), Map.class);
        assertThat(resolved.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resolved.getBody()).containsEntry("hejjeSymbol", "NFO:NIFTY:FUT:2026-09-29").containsEntry("lotSize", 75);

        ResponseEntity<Map> byId = rest.exchange("/api/v1/instruments/" + resolved.getBody().get("id"), HttpMethod.GET,
                new HttpEntity<>(bearer(token)), Map.class);
        assertThat(byId.getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(rest.exchange("/api/v1/instruments/resolve?symbol=NSE:NOPE", HttpMethod.GET, new HttpEntity<>(bearer(token)), Map.class)
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(rest.exchange("/api/v1/instruments/resolve?symbol=garbage", HttpMethod.GET, new HttpEntity<>(bearer(token)), Map.class)
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(rest.getForEntity("/api/v1/instruments?q=x", String.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        ResponseEntity<Map> sync = rest.exchange("/api/v1/instruments/sync", HttpMethod.POST, new HttpEntity<>(bearer(token)), Map.class);
        assertThat(sync.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(sync.getBody()).containsEntry("received", 32).containsEntry("broker", "fake");
    }
}
