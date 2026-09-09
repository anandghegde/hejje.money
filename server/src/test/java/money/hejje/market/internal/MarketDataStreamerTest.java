package money.hejje.market.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import money.hejje.broker.BrokerAdapter;
import money.hejje.broker.BrokerSessionState;
import money.hejje.broker.MarketDataStream;
import money.hejje.common.Exchange;
import money.hejje.common.InstrumentType;
import money.hejje.common.event.MarketTick;
import money.hejje.common.time.HejjeClock;
import money.hejje.common.time.MutableClock;
import money.hejje.instruments.Instrument;
import money.hejje.instruments.InstrumentService;
import money.hejje.market.MarketProperties;
import org.junit.jupiter.api.Test;

class MarketDataStreamerTest {

    @Test
    void subscribesWatchlistOnceItResolvesAfterStartup() {
        BrokerAdapter broker = mock(BrokerAdapter.class);
        when(broker.sessionState()).thenReturn(BrokerSessionState.CONNECTED);
        MarketDataStream stream = mock(MarketDataStream.class);
        when(stream.isConnected()).thenReturn(true);
        when(broker.streamMarketData(any())).thenReturn(stream);
        InstrumentService instruments = mock(InstrumentService.class);
        when(instruments.resolve(anyString())).thenReturn(Optional.empty()); // catalog empty at startup
        when(instruments.nearestFuture(anyString())).thenReturn(Optional.empty());
        HejjeClock clock = new HejjeClock(MutableClock.atIst("2026-09-08T10:00:00"), MutableClock.IST, (d, e) -> false);
        MarketProperties properties = new MarketProperties(List.of("INDEX:NIFTY 50"), true, Duration.ofSeconds(10), Duration.ofSeconds(5),
                1000, false, 15, 3);
        MarketDataStreamer streamer = new MarketDataStreamer(broker, instruments, mock(MarketPipeline.class), clock, properties);

        streamer.onStartup();
        verify(stream, never()).subscribe(any(), any());
        assertThat(streamer.state().healthy()).isFalse();

        Instrument nifty = new Instrument(UUID.randomUUID(), "NIFTY 50", "NIFTY 50", Exchange.INDEX, InstrumentType.INDEX, null, null, null,
                null, 1, new BigDecimal("0.05"), null, true, Instant.now());
        when(instruments.resolve("INDEX:NIFTY 50")).thenReturn(Optional.of(nifty)); // instrument sync has run
        streamer.reconnectIfNeeded();

        verify(stream).subscribe(Set.of(nifty.id()), MarketTick.Mode.FULL);
    }
}
