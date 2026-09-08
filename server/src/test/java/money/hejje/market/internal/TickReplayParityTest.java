package money.hejje.market.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import money.hejje.common.event.MarketTick;
import money.hejje.market.Candle;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Recording a day's ticks to Parquet and replaying them yields exactly the same candles as the original run. */
class TickReplayParityTest {

    @Test
    void replayYieldsIdenticalCandles(@TempDir Path dir) throws Exception {
        List<MarketTick> ticks = CandleBuilderTest.fixtureTicks();
        List<Candle> live = CandleBuilderTest.buildAll(ticks);

        Path file = dir.resolve("2026-09-08").resolve("ticks.parquet");
        Files.createDirectories(file.getParent());
        TickRecorder.writeTicks(file, ticks);

        List<MarketTick> replayed = ReplayMarketDataSource.readFile(file);
        assertThat(replayed).usingRecursiveComparison().isEqualTo(ticks);

        List<Candle> fromReplay = CandleBuilderTest.buildAll(replayed);
        assertThat(fromReplay).usingRecursiveComparison().isEqualTo(live);
    }
}
