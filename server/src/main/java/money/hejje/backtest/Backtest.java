package money.hejje.backtest;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** A persisted backtest run (without its trades; see {@code GET /backtests/{id}/trades}). */
public record Backtest(UUID id, UUID versionId, BacktestSpec spec, BacktestStatus status, int progressPct, Instant createdAt,
        Instant startedAt, Instant finishedAt, BacktestMetrics metrics, Map<Split, BacktestMetrics> bySplit, List<WalkForwardWindow> windows,
        List<QualityWarning> warnings, int sessionsExpected, int sessionsWithData, int skippedSignals, String resultHash, Engine engine,
        String error, String createdBy) {
}
