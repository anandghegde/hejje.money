package money.hejje.scoring.internal;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import money.hejje.backtest.BacktestFinished;
import money.hejje.common.config.HejjeProperties;
import money.hejje.common.time.HejjeClock;
import money.hejje.scoring.ScoringService;
import money.hejje.strategy.StrategyDeployment;
import money.hejje.strategy.StrategyService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Rescoring triggers: a finished backtest (its version) and every five minutes during the session (deployed versions). */
@Component
public class ScoreRefresh {

    private static final Logger log = LoggerFactory.getLogger(ScoreRefresh.class);

    private final ScoringService scoring;
    private final StrategyService strategies;
    private final HejjeProperties properties;
    private final HejjeClock clock;

    ScoreRefresh(ScoringService scoring, StrategyService strategies, HejjeProperties properties, HejjeClock clock) {
        this.scoring = scoring;
        this.strategies = strategies;
        this.properties = properties;
        this.clock = clock;
    }

    @ApplicationModuleListener
    public void onBacktestFinished(BacktestFinished event) {
        try {
            scoring.recomputeVersion(event.versionId());
        } catch (RuntimeException e) {
            log.warn("Rescoring version {} after backtest {} failed: {}", event.versionId(), event.backtestId(), e.getMessage());
        }
    }

    @Scheduled(fixedDelayString = "PT5M", initialDelayString = "PT1M")
    public void refreshDeployed() {
        if (!clock.isSessionOpen()) {
            return;
        }
        Set<UUID> versions = new LinkedHashSet<>();
        for (StrategyDeployment d : strategies.deployments(null, properties.mode(), true)) {
            versions.add(d.versionId());
        }
        for (UUID versionId : versions) {
            try {
                scoring.recomputeVersion(versionId);
            } catch (RuntimeException e) {
                log.warn("Rescoring deployed version {} failed: {}", versionId, e.getMessage());
            }
        }
    }
}
