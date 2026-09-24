package money.hejje.market.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import money.hejje.broker.BrokerAdapter;
import money.hejje.broker.BrokerCandle;
import money.hejje.common.Timeframe;
import money.hejje.instruments.HejjeSymbol;
import money.hejje.instruments.Instrument;
import money.hejje.market.HistoricalCandleStore;
import money.hejje.market.MarketProperties;
import money.hejje.market.UniverseBackfill;
import org.junit.jupiter.api.Test;

class UniverseBackfillTest {

    private static final Instant FROM = Instant.parse("2016-01-01T00:00:00Z");
    private static final Instant TO = Instant.parse("2026-09-18T12:00:00Z");

    private final BrokerAdapter broker = mock(BrokerAdapter.class);
    private final HistoricalCandleStore store = mock(HistoricalCandleStore.class);
    private final MarketProperties props = mock(MarketProperties.class);

    private static Instrument instrument(String symbol) {
        Instrument instrument = mock(Instrument.class);
        when(instrument.id()).thenReturn(UUID.nameUUIDFromBytes(symbol.getBytes()));
        when(instrument.hejjeSymbol()).thenReturn(HejjeSymbol.parse("NSE:" + symbol));
        return instrument;
    }

    private static BrokerCandle candle() {
        BigDecimal p = new BigDecimal("100.00");
        return new BrokerCandle(Instant.parse("2026-09-17T03:45:00Z"), p, p, p, p, 1000, 0);
    }

    @Test
    void oneParentJobRunsAChildPerInstrumentAndReportsFailuresAndUnresolvedSymbols() {
        when(props.historicalPerSecond()).thenReturn(1000);
        Instrument infy = instrument("INFY");
        Instrument tcs = instrument("TCS");
        Instrument bad = instrument("BAD");
        when(broker.getHistory(any(), eq(Timeframe.D1), any(), any())).thenReturn(List.of(candle()));
        when(broker.getHistory(eq(bad.id()), eq(Timeframe.D1), any(), any())).thenThrow(new IllegalStateException("token missing"));
        HistoricalBackfillJob job = new HistoricalBackfillJob(broker, store, props);

        UniverseBackfill result = job.runUniverseNow("nifty500", List.of(infy, bad, tcs), List.of("NSE:HEG"), Timeframe.D1, FROM, TO);

        assertThat(result.status()).isEqualTo("PARTIAL");
        assertThat(result.childrenTotal()).isEqualTo(3);
        assertThat(result.childrenDone()).isEqualTo(2);
        assertThat(result.childrenFailed()).isEqualTo(1);
        assertThat(result.failed()).containsExactly("NSE:BAD: token missing");
        assertThat(result.unresolved()).containsExactly("NSE:HEG");
        assertThat(result.candlesWritten()).isEqualTo(4); // two 2000-day chunks for each of the two good symbols
        verify(broker, times(2)).getHistory(eq(infy.id()), eq(Timeframe.D1), any(), any());
        assertThat(job.universeProgress(result.jobId())).isEqualTo(result);
    }

    @Test
    void aCleanRunIsDone() {
        when(props.historicalPerSecond()).thenReturn(1000);
        when(broker.getHistory(any(), any(), any(), any())).thenReturn(List.of(candle()));
        HistoricalBackfillJob job = new HistoricalBackfillJob(broker, store, props);
        UniverseBackfill result = job.runUniverseNow("nifty50", List.of(instrument("INFY")), List.of(), Timeframe.D1,
                Instant.parse("2026-09-10T00:00:00Z"), TO);
        assertThat(result.status()).isEqualTo("DONE");
        assertThat(result.failed()).isEmpty();
    }
}
