package money.hejje.market;

import java.time.Instant;
import java.util.UUID;
import money.hejje.common.Timeframe;

/**
 * Order-book and trade-flow features of one bar (plan M9.4, docs/market-data.md), built from the ticks of the bar. Live
 * or recorded ticks only: historical candles carry no depth, so a bar replayed from candles has none, and the indicators
 * that read these values are not ready on it.
 *
 * @param imbalanceClose (bid5 − ask5) / (bid5 + ask5) at the bar's last tick with depth; null without depth
 * @param imbalanceMean  mean of that imbalance over the bar's ticks with depth (tick-weighted)
 * @param buySellRatio   total buy / total sell quantity at the bar's last tick carrying both; null otherwise
 * @param upVolumeShare  up volume / (up + down volume); volume deltas between consecutive ticks signed by the price
 *                       change, unchanged-price ticks ignored; null when nothing moved
 * @param upVolume       the signed-up volume behind {@code upVolumeShare} (kept for aggregation)
 * @param downVolume     the signed-down volume
 * @param ticks          ticks in the bar
 * @param depthTicks     ticks with both five-level quantities
 */
public record BarMicro(UUID instrumentId, Timeframe timeframe, Instant openTime, Double imbalanceClose, Double imbalanceMean, Double buySellRatio,
        Double upVolumeShare, long upVolume, long downVolume, int ticks, int depthTicks) {}
